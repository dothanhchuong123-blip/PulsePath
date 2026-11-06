@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.soundsight.data.live

import com.soundsight.audio.AudioClip
import com.soundsight.data.ClassificationResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Minimal fake of [okhttp3.WebSocket] that records sent frames and can be told to reject sends. */
private class FakeWebSocket(private val req: Request) : WebSocket {
    val sentMessages = mutableListOf<String>()
    val closeCalls = mutableListOf<Pair<Int, String?>>()
    var sendResult: Boolean = true
    var closed = false

    override fun request(): Request = req
    override fun queueSize(): Long = 0L
    override fun send(text: String): Boolean {
        if (closed || !sendResult) return false
        sentMessages.add(text)
        return true
    }
    override fun send(bytes: okio.ByteString): Boolean = send(bytes.utf8())
    override fun close(code: Int, reason: String?): Boolean {
        closed = true
        closeCalls.add(code to reason)
        return true
    }
    override fun cancel() { closed = true }

    fun lastToolResponseId(): String? {
        val msg = sentMessages.lastOrNull { it.contains("toolResponse") } ?: return null
        return JSONObject(msg).getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0).getString("id")
    }
}

/** Records every socket the coordinator asks to create, and exposes its listener for driving fake server events. */
private class FakeWebSocketFactory : LiveWebSocketFactory {
    class Created(val socket: FakeWebSocket, val listener: WebSocketListener, val request: Request) {
        fun open() {
            val response = Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()
            listener.onOpen(socket, response)
        }
        fun deliver(json: String) = listener.onMessage(socket, json)
        fun closedByServer(code: Int, reason: String) = listener.onClosed(socket, code, reason)
        fun failed(t: Throwable) = listener.onFailure(socket, t, null)
    }

    val created = mutableListOf<Created>()

    override fun create(request: Request, listener: WebSocketListener): WebSocket {
        val ws = FakeWebSocket(request)
        created.add(Created(ws, listener, request))
        return ws
    }
}

private class FakeFallback {
    val calls = mutableListOf<AudioClip>()
    var result: Result<ClassificationResult> = Result.success(
        ClassificationResult(
            soundId = "MOTORCYCLE_HORN",
            tier = "WARNING",
            confidence = 0.8,
            captionVi = "Còi xe máy",
            captionEn = "Motorbike horn"
        )
    )

    suspend fun classify(clip: AudioClip): Result<ClassificationResult> {
        calls.add(clip)
        return result
    }
}

private fun clip(capturedAtMs: Long = 1_000L) = AudioClip(wav = ByteArray(3200), capturedAtMs = capturedAtMs)

private const val SETUP_COMPLETE = """{"setupComplete":{}}"""

private fun toolCallJson(callId: String = "call-1", soundId: String = "TRUCK_AIR_HORN"): String {
    val root = JSONObject()
    val toolCall = JSONObject()
    val fc = JSONObject()
    fc.put("id", callId)
    fc.put("name", "report_sound")
    val args = JSONObject()
    args.put("sound_id", soundId)
    args.put("tier", "CRITICAL")
    args.put("confidence", 0.95)
    args.put("motion", "APPROACHING")
    args.put("caption_vi", "Còi hơi")
    args.put("caption_en", "Air horn")
    fc.put("args", args)
    toolCall.put("functionCalls", org.json.JSONArray().put(fc))
    root.put("toolCall", toolCall)
    return root.toString()
}

private fun goAwayJson(timeLeftSeconds: String = "9.5s"): String =
    JSONObject().put("goAway", JSONObject().put("timeLeft", timeLeftSeconds)).toString()

private fun sessionResumptionUpdateJson(handle: String, resumable: Boolean): String =
    JSONObject().put(
        "sessionResumptionUpdate",
        JSONObject().put("newHandle", handle).put("resumable", resumable)
    ).toString()

private fun turnCompleteNoToolJson(): String =
    JSONObject().put("serverContent", JSONObject().put("turnComplete", true)).toString()

@RunWith(RobolectricTestRunner::class)
class LiveSessionCoordinatorTest {

    private fun newCoordinator(
        scope: TestScope,
        factory: FakeWebSocketFactory,
        fallback: FakeFallback,
        connectivity: () -> Boolean = { true }
    ) = LiveSessionCoordinator(
        webSocketFactory = factory,
        scope = scope,
        restFallback = fallback::classify,
        apiKeyProvider = { "test-key" },
        logger = NoopLiveLogger,
        connectivityChecker = connectivity
    )

    // 1. start() twice -> exactly one production socket.
    @Test
    fun startTwice_opensExactlyOneSocket() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        coordinator.start()

        assertEquals(1, factory.created.size)
    }

    // 2. Two duplicate "start intents" against the same coordinator behave as a single session:
    // only one socket ever exists and only one clip can be in flight at a time.
    @Test
    fun duplicateStartIntents_singleSessionSingleFlight() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        coordinator.start() // simulates a second start intent racing the first
        factory.created.single().open()
        factory.created.single().deliver(SETUP_COMPLETE)

        assertEquals(1, factory.created.size)
        assertTrue(coordinator.sendClip(clip(1000L)))
        // A second clip while one is in flight must be dropped, not create a second flight/socket.
        assertFalse(coordinator.sendClip(clip(2000L)))
        assertEquals(1, factory.created.size)
    }

    // 3. Tier-2 OFF -> zero Live socket; ON -> one socket; OFF again -> closes cleanly.
    // (Modeled at the coordinator level: start()/stop() are exactly what the Tier-2 toggle now calls.)
    @Test
    fun tier2ToggleOffOnOff_opensAndClosesExactlyOnce() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        // OFF: never call start() -> zero sockets.
        assertEquals(0, factory.created.size)

        // ON:
        coordinator.start()
        assertEquals(1, factory.created.size)
        factory.created.single().open()

        // OFF again:
        coordinator.stop()
        assertTrue(factory.created.single().socket.closed)
        assertEquals(LiveState.CONNECTING, coordinator.liveState.value)
    }

    // 4. goAway twice -> only one handover; socket count never exceeds 2.
    @Test
    fun repeatedGoAway_triggersOnlyOneHandover() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        primary.deliver(goAwayJson())
        assertEquals(2, factory.created.size) // primary + one handover socket
        assertTrue(coordinator.handoverInProgressState.value)

        primary.deliver(goAwayJson()) // duplicate goAway on the same (still-active) primary socket
        assertEquals(2, factory.created.size) // must not open a third socket
        assertTrue(coordinator.openSocketCount.value <= 2)
    }

    // 5. resumable=false or blank handle -> not stored/used for resumption.
    @Test
    fun nonResumableOrBlankHandle_isNotStored() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        primary.deliver(sessionResumptionUpdateJson("handle-A", resumable = false))
        assertNull(coordinator.resumptionHandleSetAtMs.value)

        primary.deliver(sessionResumptionUpdateJson("", resumable = true))
        assertNull(coordinator.resumptionHandleSetAtMs.value)

        primary.deliver(sessionResumptionUpdateJson("handle-B", resumable = true))
        assertTrue(coordinator.resumptionHandleSetAtMs.value != null)
    }

    // 6. New handover socket reaches setupComplete -> atomic swap, then old socket is closed.
    @Test
    fun handoverSetupComplete_swapsThenClosesOldSocket() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)
        primary.deliver(goAwayJson())

        val handover = factory.created[1]
        handover.open()
        assertTrue(primary.socket.closeCalls.isEmpty()) // not closed yet, still active

        handover.deliver(SETUP_COMPLETE)

        assertEquals(1, primary.socket.closeCalls.size)
        assertEquals(1000, primary.socket.closeCalls[0].first)
        assertEquals(LiveState.READY, coordinator.liveState.value)
        assertFalse(coordinator.handoverInProgressState.value)
    }

    // 7. Handover socket fails before setupComplete -> old socket stays active, no reconnect storm.
    @Test
    fun handoverFailsBeforeSetup_keepsOldSocketActiveNoStorm() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)
        primary.deliver(goAwayJson())

        val handover = factory.created[1]
        handover.open()
        handover.failed(RuntimeException("handover socket died"))

        assertFalse(coordinator.handoverInProgressState.value)
        assertEquals(LiveState.READY, coordinator.liveState.value) // old socket untouched
        assertTrue(primary.socket.closeCalls.isEmpty())

        advanceTimeBy(65_000L)
        runCurrent()
        // No reconnect storm: still only the original 2 sockets (primary + the one failed handover attempt).
        assertEquals(2, factory.created.size)
    }

    // 8. Standalone diagnostic test while persistent session is active -> refused, no second socket.
    @Test
    fun standaloneTestWhilePersistentActive_isRefused() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        factory.created[0].open()
        factory.created[0].deliver(SETUP_COMPLETE)

        val result = coordinator.testLiveCall(clip())

        assertTrue(result.isFailure)
        assertEquals(1, factory.created.size) // no second socket opened
        assertTrue(coordinator.standaloneTestBlocked.value)
    }

    // 9. Close 1011 with a quota reason -> no reconnect before 60s, and only one reconnect job.
    @Test
    fun quotaClose1011_waitsAtLeast60sAndSchedulesOnlyOneJob() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        primary.closedByServer(1011, "RESOURCE_EXHAUSTED: quota exceeded")

        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(1, factory.created.size) // must not have reconnected yet

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(2, factory.created.size) // reconnected once, right at/after the 60s floor

        // Advancing further must not spawn extra concurrent reconnect attempts while this one is pending.
        advanceUntilIdle()
        assertTrue(factory.created.size <= 3)
    }

    // 10. A network failure right after a quota failure must use fresh classification, not the stale quota reason.
    @Test
    fun networkFailureAfterQuotaFailure_usesFreshClassification() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)
        primary.closedByServer(1011, "RESOURCE_EXHAUSTED: quota exceeded")

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, factory.created.size)

        val second = factory.created[1]
        second.open()
        second.failed(java.io.IOException("network unreachable"))

        // If the stale quota reason leaked into this failure's classification, this would need
        // 60s again. A plain network failure must back off in, at most, a few seconds.
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(3, factory.created.size)
    }

    // 11. WebSocket.send() returning false -> REST fallback exactly once.
    @Test
    fun sendReturnsFalse_fallsBackToRestExactlyOnce() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)
        primary.socket.sendResult = false

        assertTrue(coordinator.sendClip(clip()))
        advanceUntilIdle()

        assertEquals(1, fallback.calls.size)
    }

    // 12. Socket disconnects while a clip is in flight -> REST fallback exactly once, state leaves SENDING.
    @Test
    fun disconnectWhileInFlight_fallsBackOnceAndLeavesSending() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        assertTrue(coordinator.sendClip(clip()))
        // Disconnect races the queued send coroutine (which hasn't run yet under StandardTestDispatcher).
        primary.closedByServer(1006, "abnormal closure")
        assertTrue(coordinator.liveState.value != LiveState.SENDING)

        advanceUntilIdle()

        assertEquals(1, fallback.calls.size)
        assertTrue(coordinator.liveState.value != LiveState.SENDING)
    }

    // 13. turnComplete with no tool call -> REST fallback exactly once.
    @Test
    fun turnCompleteWithoutToolCall_fallsBackOnce() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        assertTrue(coordinator.sendClip(clip()))
        runCurrent() // let the audio finish "sending" without also fast-forwarding the flight timeout

        primary.deliver(turnCompleteNoToolJson())
        advanceUntilIdle()

        assertEquals(1, fallback.calls.size)
    }

    // 14. 12s flight timeout -> REST fallback exactly once; a late tool call afterward is ignored.
    @Test
    fun flightTimeout_fallsBackOnceThenIgnoresLateToolCall() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        assertTrue(coordinator.sendClip(clip()))
        runCurrent() // audio send completes, timeout job scheduled from activityEnd

        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(1, fallback.calls.size)
        assertEquals(1, coordinator.flightTimeoutCount.value)

        // Late tool call for the already-timed-out flight must be ignored, not double-processed.
        primary.deliver(toolCallJson())
        advanceUntilIdle()

        assertEquals(1, fallback.calls.size)
        assertEquals(1, coordinator.lateResponseCount.value)
        // The mandatory protocol ack must still be sent so the session doesn't hang.
        assertTrue(primary.socket.sentMessages.any { it.contains("toolResponse") })
    }

    // 15. Tool call before the timeout -> timeout is cancelled, no fallback.
    @Test
    fun toolCallBeforeTimeout_cancelsTimeoutNoFallback() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)

        assertTrue(coordinator.sendClip(clip()))
        runCurrent() // let the audio finish "sending" without also fast-forwarding the flight timeout

        primary.deliver(toolCallJson())
        advanceUntilIdle()

        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(0, fallback.calls.size)
        assertEquals(0, coordinator.flightTimeoutCount.value)
        assertEquals(LiveState.READY, coordinator.liveState.value)
    }

    // 16. stop() during reconnect/handover/in-flight -> everything is cancelled cleanly;
    // late callbacks afterward must not reopen a socket.
    @Test
    fun stopDuringReconnectHandoverAndInFlight_cancelsCleanly() = runTest {
        val factory = FakeWebSocketFactory()
        val fallback = FakeFallback()
        val coordinator = newCoordinator(this, factory, fallback)

        coordinator.start()
        val primary = factory.created[0]
        primary.open()
        primary.deliver(SETUP_COMPLETE)
        primary.deliver(goAwayJson()) // start a handover
        val handover = factory.created[1]
        handover.open()

        assertTrue(coordinator.sendClip(clip())) // put a clip in flight too
        runCurrent() // let the audio finish "sending" without also fast-forwarding the flight timeout

        coordinator.stop()

        assertTrue(primary.socket.closed)
        assertTrue(handover.socket.closed)

        // Late callbacks from the sockets stop() already tore down must not resurrect anything.
        handover.failed(RuntimeException("late failure after stop"))
        primary.closedByServer(1000, "late close after stop")
        advanceTimeBy(120_000L)
        advanceUntilIdle()

        assertEquals(2, factory.created.size) // no new sockets were opened
        assertEquals(0, fallback.calls.size) // explicit stop does not synthesize a fallback result
        assertEquals(LiveState.CONNECTING, coordinator.liveState.value)
    }

    // 17. The setup frame asks for a short turn history. This pins the values we send; note
    // that on-device measurement showed the server does not visibly honour a trigger this low
    // (see the comment in buildSetupMessage), so this guards the request, not the outcome.
    @Test
    fun setupFrame_keepsTurnHistoryShort() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        factory.created.single().open()

        val setup = JSONObject(factory.created.single().socket.sentMessages.first())
            .getJSONObject("setup")
        val ctxComp = setup.getJSONObject("contextWindowCompression")
        val triggerTokens = ctxComp.getInt("triggerTokens")
        val targetTokens = ctxComp.getJSONObject("slidingWindow").getInt("targetTokens")

        assertTrue(
            "triggerTokens=$triggerTokens leaves too many prior turns visible",
            triggerTokens <= 1500
        )
        assertTrue("targetTokens must be below triggerTokens", targetTokens < triggerTokens)
    }

    // 18. Segment independence is stated in the system instruction the socket actually receives,
    // not merely in the source constant.
    @Test
    fun setupFrame_systemInstructionDemandsSegmentIndependence() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        factory.created.single().open()

        val text = JSONObject(factory.created.single().socket.sentMessages.first())
            .getJSONObject("setup")
            .getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")

        assertTrue(text.contains("SEGMENT INDEPENDENCE"))
        assertTrue(text.contains("NORMAL_TRAFFIC"))
    }

    // 20. Network restored -> reconnects immediately instead of waiting out the backoff it earned
    // while offline. Guards the fix for "switching wi-fi/mobile data never reconnects".
    @Test
    fun networkAvailable_cancelsBackoffAndReconnectsImmediately() = runTest {
        val factory = FakeWebSocketFactory()
        var online = true
        val coordinator = newCoordinator(this, factory, FakeFallback(), connectivity = { online })

        coordinator.start()
        factory.created[0].open()
        factory.created[0].deliver(SETUP_COMPLETE)
        assertEquals(1, factory.created.size)

        // Go offline and fail repeatedly, so a long backoff is earned.
        online = false
        repeat(3) {
            factory.created.last().failed(RuntimeException("network down"))
            advanceTimeBy(31_000L)
            runCurrent()
        }
        val socketsWhileOffline = factory.created.size

        // Network returns. Without the explicit nudge the coordinator would sit in delay()/poll
        // for up to 35s; with it, a fresh connect happens on the spot with no time advanced.
        online = true
        coordinator.onNetworkAvailable()
        runCurrent()

        assertTrue(
            "expected an immediate reconnect attempt after network returned",
            factory.created.size > socketsWhileOffline
        )
        assertEquals(0, coordinator.reconnectAttempt.value)
    }

    // 21. onNetworkAvailable must not open a second socket when one is already healthy.
    @Test
    fun networkAvailable_isNoOpWhenSocketAlreadyOpen() = runTest {
        val factory = FakeWebSocketFactory()
        val coordinator = newCoordinator(this, factory, FakeFallback())

        coordinator.start()
        factory.created[0].open()
        factory.created[0].deliver(SETUP_COMPLETE)

        coordinator.onNetworkAvailable()
        coordinator.onNetworkAvailable()
        runCurrent()

        assertEquals(1, factory.created.size)
    }
}
