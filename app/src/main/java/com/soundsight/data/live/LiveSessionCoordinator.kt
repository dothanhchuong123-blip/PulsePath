package com.soundsight.data.live

import android.util.Log
import com.soundsight.audio.AudioClip
import com.soundsight.core.LogRedactor
import com.soundsight.core.Telemetry
import com.soundsight.data.ClassificationResult
import com.soundsight.data.GeminiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class LiveState {
    CONNECTING,
    READY,
    SENDING,
    RECONNECTING,
    FAILED,

    /**
     * No usable network. Distinct from [RECONNECTING], which means "the link is fine, the socket
     * is not". Entering this state stops the backoff ladder outright: there is nothing to retry
     * against, and a ladder that keeps firing into a dead radio costs battery while telling the
     * rider "Reconnecting" for the whole climb. The on-device classifier keeps running throughout,
     * so this is a degraded tier, not a stopped app - see the HUD chip.
     */
    OFFLINE
}

/**
 * Why a Live socket ended.
 *
 * Split out because [LiveSessionCoordinator.isQuotaReason] used to answer one boolean for two
 * unrelated failures: a token/rate quota, and a per-project concurrent-session cap after a
 * reconnect storm left zombie sockets open. Both arrive as RESOURCE_EXHAUSTED, both were reported
 * as "quota", and no amount of staring at the dashboard could tell them apart. Diagnosis only -
 * the backoff ladder still keys off the same boolean it always did.
 */
enum class LiveCloseKind {
    /** The message named a quota or a rate limit outright. */
    QUOTA_TOKENS,

    /** RESOURCE_EXHAUSTED with no quota wording - could be tokens, could be the session cap. */
    RESOURCE_EXHAUSTED,

    /** Wording that names a concurrent-session or connection limit specifically. */
    SESSION_CAP,

    /** The model id was rejected; non-retryable. */
    MODEL_UNAVAILABLE,

    /** A server-initiated close that is none of the above. */
    SERVER_CLOSE,

    /** Transport died with no close frame: dropped link, timeout, TLS. */
    TRANSIENT
}

/**
 * One model turn's `usageMetadata`, kept per turn rather than only as a running total.
 *
 * The totals alone cannot answer "why does a session that should be nowhere near 1M TPM run out":
 * a total that climbs is equally consistent with a flat per-turn cost over many turns and with a
 * per-turn cost that grows without bound. Only the delta separates them, and only the modality
 * split separates a growing text history from billed spoken audio the app throws away.
 *
 * [turnIndex] counts turns within one connection, so a recycle or handover restarts the curve
 * instead of hiding the reset inside a monotonic total.
 */
data class TurnUsage(
    val turnIndex: Int,
    val connectionId: Int?,
    val promptTokens: Long,
    val promptAudioTokens: Long,
    val promptTextTokens: Long,
    val responseTokens: Long,
    val responseAudioTokens: Long,
    val totalTokens: Long,
    /** promptTokens minus the previous turn's promptTokens on this connection; 0 on the first. */
    val promptDelta: Long,
    val atMs: Long
)

data class LiveClassificationEvent(
    val result: ClassificationResult,
    val capturedAtMs: Long,
    val latencyMs: Long,
    val rawJsonArgs: String = "",
    /**
     * The originating clip's capture instant in the elapsed-realtime domain, preserved through
     * both the Live result path and the REST fallback so audio/motion co-occurrence is judged
     * in one monotonic time domain regardless of which path produced the classification.
     * Null only for clips that never carried one (manual/demo construction).
     */
    val capturedMonotonicMs: Long? = null
)

/** Abstraction over WebSocket creation so tests can substitute a fake transport. */
fun interface LiveWebSocketFactory {
    fun create(request: Request, listener: WebSocketListener): WebSocket
}

/** Tiny logging seam so core coordination logic doesn't require Robolectric/android.util.Log in tests. */
interface LiveLogger {
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

object AndroidLiveLogger : LiveLogger {
    override fun i(tag: String, msg: String) { Log.i(tag, LogRedactor.redact(msg)) }
    override fun w(tag: String, msg: String, t: Throwable?) {
        val cleanMsg = LogRedactor.redact(msg)
        val cleanT = LogRedactor.redactThrowable(t)
        if (cleanT != null) Log.w(tag, cleanMsg, cleanT) else Log.w(tag, cleanMsg)
    }
    override fun e(tag: String, msg: String, t: Throwable?) {
        val cleanMsg = LogRedactor.redact(msg)
        val cleanT = LogRedactor.redactThrowable(t)
        if (cleanT != null) Log.e(tag, cleanMsg, cleanT) else Log.e(tag, cleanMsg)
    }
}

object NoopLiveLogger : LiveLogger {
    override fun i(tag: String, msg: String) {}
    override fun w(tag: String, msg: String, t: Throwable?) {}
    override fun e(tag: String, msg: String, t: Throwable?) {}
}

/**
 * Coordinates the lifecycle of the Gemini Live WebSocket: single-connection guarantees,
 * goAway handover, quota-aware reconnect backoff, and exactly-once resolution (Live result
 * XOR REST fallback) of every in-flight audio clip.
 *
 * All Android/network dependencies are injected so this class can be driven deterministically
 * in unit tests with a fake [LiveWebSocketFactory], a virtual-time [CoroutineScope], and a fake
 * REST fallback.
 */
class LiveSessionCoordinator(
    private val webSocketFactory: LiveWebSocketFactory,
    private val scope: CoroutineScope,
    private val restFallback: suspend (AudioClip) -> Result<ClassificationResult>,
    private val apiKeyProvider: () -> String,
    private val logger: LiveLogger = AndroidLiveLogger,
    private val connectivityChecker: () -> Boolean = { true },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val flightTimeoutMs: Long = 12_000L,
    private val healthyResetMs: Long = 60_000L,
    private val networkPollMs: Long = 5_000L,
    private val standaloneTestTimeoutMs: Long = 12_000L,
    /**
     * How long a dead socket must stay dead, with no usable network, before the rider is told
     * they are offline.
     *
     * Not zero, and not read from the raw `onLost` callback, because a wi-fi -> mobile handover
     * delivers onAvailable(mobile) before onLost(wifi): a coordinator that believed the first
     * signal it got would flash OFFLINE on a phone with a perfectly good connection. The debounce
     * is the window in which that handover completes. See the comments in HudViewModel.
     */
    private val offlineDebounceMs: Long = 1_750L,
    /**
     * Open a fresh session after every classified turn, so the model never sees its own
     * previous answers.
     *
     * MEASURED 2026-09-09 (D1, `measurements/live-levers/2026-09-09-decay/`): on one long-lived
     * socket the model self-conditions on the turns already in its context. Prompt tokens grow
     * +165-170/turn until the server's eviction clamp (peaks 4,490-4,690, floors 2,460-2,640 in
     * that directory), which still leaves 3-4 prior turns in the attention window, and the
     * answer collapses into an absorbing latch - 93 of
     * 100 turns `EMERGENCY_SIREN`, sound accuracy 20%. The same 100 clips through a fresh socket
     * per turn: 47% (53% on completed turns), McNemar p = 4.6e-7, and `reversing_beep` went from
     * 0/120 across every earlier benchmark to 9/20. `contextWindowCompression` (see
     * [buildSetupMessage]) and the SEGMENT INDEPENDENCE block in the system instruction were both
     * tried first and neither prevents it.
     *
     * The recycle rides the goAway handover path: the replacement connects in the background
     * WITHOUT a resumption handle (a resumed session would carry the context we are discarding),
     * the old socket stays active until the new one reaches `setupComplete`, and only then is it
     * retired - so the rider never sees a CONNECTING gap and a clip captured mid-recycle still
     * has a socket to go to.
     */
    private val recycleSessionAfterTurn: Boolean = true
) {
    // ---- Public telemetry / state ----------------------------------------------------------

    /** Fresh sessions opened by the per-turn recycle - see [recycleSessionAfterTurn]. */
    private val _sessionRecycleCount = MutableStateFlow(0)
    val sessionRecycleCount: StateFlow<Int> = _sessionRecycleCount.asStateFlow()

    private val _liveState = MutableStateFlow(LiveState.CONNECTING)
    val liveState: StateFlow<LiveState> = _liveState.asStateFlow()

    private val _lastCloseDiagnostic = MutableStateFlow<String?>(null)
    val lastCloseDiagnostic: StateFlow<String?> = _lastCloseDiagnostic.asStateFlow()

    private val _events = MutableSharedFlow<LiveClassificationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveClassificationEvent> = _events.asSharedFlow()

    private val _openSocketCount = MutableStateFlow(0)
    val openSocketCount: StateFlow<Int> = _openSocketCount.asStateFlow()

    private val _promptTokensTotal = MutableStateFlow(0L)
    val promptTokensTotal: StateFlow<Long> = _promptTokensTotal.asStateFlow()

    private val _responseTokensTotal = MutableStateFlow(0L)
    val responseTokensTotal: StateFlow<Long> = _responseTokensTotal.asStateFlow()

    private val _totalTokensRunning = MutableStateFlow(0L)
    val totalTokensRunning: StateFlow<Long> = _totalTokensRunning.asStateFlow()

    // ---- Per-turn token forensics ----------------------------------------------------------
    //
    // The three running totals above are what the app had before, and they cannot answer the
    // question "why does this session hit a quota it should never reach". Everything below is
    // per-turn, and per-connection, because the shape of the curve is the answer.

    private val _lastTurnUsage = MutableStateFlow<TurnUsage?>(null)
    val lastTurnUsage: StateFlow<TurnUsage?> = _lastTurnUsage.asStateFlow()

    private val _turnUsageHistory = MutableStateFlow<List<TurnUsage>>(emptyList())
    val turnUsageHistory: StateFlow<List<TurnUsage>> = _turnUsageHistory.asStateFlow()

    /** Turns seen on the current connection; resets on every new connection id. */
    private val _turnsThisConnection = MutableStateFlow(0)
    val turnsThisConnection: StateFlow<Int> = _turnsThisConnection.asStateFlow()

    /** Sum of promptTokenCount billed on the current connection - the session recycle budget. */
    private val _promptTokensThisConnection = MutableStateFlow(0L)
    val promptTokensThisConnection: StateFlow<Long> = _promptTokensThisConnection.asStateFlow()

    /**
     * Least-squares slope of promptTokens against turn index over the trailing window.
     *
     * A flat slope means context compression is working. A positive slope that does not decay is
     * the straight line into the TPM ceiling, and is the single number this whole investigation
     * exists to produce.
     */
    private val _promptSlopePerTurn = MutableStateFlow(0.0)
    val promptSlopePerTurn: StateFlow<Double> = _promptSlopePerTurn.asStateFlow()

    /** The turn rows as they stood the moment the socket died, so the close has its curve attached. */
    private val _lastCloseUsage = MutableStateFlow<List<TurnUsage>>(emptyList())
    val lastCloseUsage: StateFlow<List<TurnUsage>> = _lastCloseUsage.asStateFlow()

    private val _closeKindCounts = MutableStateFlow<Map<LiveCloseKind, Int>>(emptyMap())
    val closeKindCounts: StateFlow<Map<LiveCloseKind, Int>> = _closeKindCounts.asStateFlow()

    private val _lastCloseKind = MutableStateFlow<LiveCloseKind?>(null)
    val lastCloseKind: StateFlow<LiveCloseKind?> = _lastCloseKind.asStateFlow()

    /**
     * `usageMetadata` messages that arrived carrying no numbers at all.
     *
     * Not a curiosity: when this climbs and [turnUsageHistory] does not, the app has no per-turn
     * accounting to reason about, and any token budget built on those flows would be built on
     * silence.
     */
    private val _emptyUsageReports = MutableStateFlow(0)
    val emptyUsageReports: StateFlow<Int> = _emptyUsageReports.asStateFlow()

    private val _handoverInProgress = MutableStateFlow(false)
    val handoverInProgressState: StateFlow<Boolean> = _handoverInProgress.asStateFlow()

    private val _activeConnectionId = MutableStateFlow<Int?>(null)
    val activeConnectionId: StateFlow<Int?> = _activeConnectionId.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _reconnectDelayMs = MutableStateFlow<Long?>(null)
    val reconnectDelayMs: StateFlow<Long?> = _reconnectDelayMs.asStateFlow()

    private val _lastFailureKind = MutableStateFlow<String?>(null)
    val lastFailureKind: StateFlow<String?> = _lastFailureKind.asStateFlow()

    private val _goAwayTimeLeftMs = MutableStateFlow<Long?>(null)
    val goAwayTimeLeftMs: StateFlow<Long?> = _goAwayTimeLeftMs.asStateFlow()

    private val _resumable = MutableStateFlow<Boolean?>(null)
    val resumable: StateFlow<Boolean?> = _resumable.asStateFlow()

    private val _resumptionHandleSetAtMs = MutableStateFlow<Long?>(null)
    val resumptionHandleSetAtMs: StateFlow<Long?> = _resumptionHandleSetAtMs.asStateFlow()

    private val _audioBytesSentTotal = MutableStateFlow(0L)
    val audioBytesSentTotal: StateFlow<Long> = _audioBytesSentTotal.asStateFlow()

    private val _audioMsSentTotal = MutableStateFlow(0L)
    val audioMsSentTotal: StateFlow<Long> = _audioMsSentTotal.asStateFlow()

    private val _flightTimeoutCount = MutableStateFlow(0)
    val flightTimeoutCount: StateFlow<Int> = _flightTimeoutCount.asStateFlow()

    private val _disconnectFallbackCount = MutableStateFlow(0)
    val disconnectFallbackCount: StateFlow<Int> = _disconnectFallbackCount.asStateFlow()

    private val _lateResponseCount = MutableStateFlow(0)
    val lateResponseCount: StateFlow<Int> = _lateResponseCount.asStateFlow()

    private val _standaloneTestBlocked = MutableStateFlow(false)
    val standaloneTestBlocked: StateFlow<Boolean> = _standaloneTestBlocked.asStateFlow()

    // ---- Socket coordination state (guarded by socketLock) ---------------------------------

    private val socketLock = Any()
    private var isSessionActive = false
    private var connecting = false
    private var handoverInProgress = false
    private var activeWebSocket: WebSocket? = null
    private var activeConnId: Int? = null
    private var pendingHandoverSocket: WebSocket? = null
    /** True while the in-progress handover is a per-turn recycle rather than a goAway resume. */
    private var handoverIsRecycle = false
    /**
     * Sockets we replaced on purpose and are closing ourselves. Their close callbacks must not be
     * counted as disconnects: a recycle every turn would otherwise file one "server close" per
     * classified clip and bury the real close statistics under our own housekeeping.
     */
    private val retiredSockets = mutableSetOf<WebSocket>()
    private var reconnectJob: Job? = null
    private var healthyResetJob: Job? = null
    private var consecutiveFailures = 0
    private var resumptionHandle: String? = null
    private var lastDisconnect: DisconnectDiagnostic? = null

    /**
     * True from the moment OFFLINE is entered until a `setupComplete` proves the session is
     * genuinely back. While latched, nothing else may repaint [_liveState] - a connect attempt
     * that has not yet succeeded must not replace "Offline" with a reassuring "Connecting" the
     * rider has no reason to believe.
     */
    private var offlineLatched = false
    private var offlineProbeJob: Job? = null

    private val nextConnectionId = AtomicInteger(1)

    // ---- Flight state (guarded by flightLock) -----------------------------------------------

    private val flightLock = Any()
    private var currentFlight: Flight? = null
    private var lastFlightId: Long = -1
    private val flightIdGen = AtomicLong(0)

    private val testMutex = Mutex()

    // ---- Usage bookkeeping (guarded by usageLock) ------------------------------------------
    private val usageLock = Any()
    private var usageConnectionId: Int? = null
    private var lastPromptTokensOnConnection: Long? = null

    private data class DisconnectDiagnostic(
        val connectionId: Int,
        val closeCode: Int?,
        val closeReason: String?,
        val httpStatus: Int?,
        val throwableClass: String?,
        val quotaFlag: Boolean,
        val modelUnavailableFlag: Boolean = false,
        val timestampMs: Long
    )

    private class Flight(
        val id: Long,
        val clip: AudioClip,
        val captureTimeMs: Long
    ) {
        val resolved = AtomicBoolean(false)
        var timeoutJob: Job? = null
        @Volatile var activityEndedMs: Long? = null
    }

    // ---- Public API ---------------------------------------------------------------------

    /** Idempotent: a second call while already active/connecting/ready is a no-op. */
    fun start() {
        val shouldConnect = synchronized(socketLock) {
            if (isSessionActive) {
                false
            } else {
                isSessionActive = true
                consecutiveFailures = 0
                resumptionHandle = null
                lastDisconnect = null
                offlineLatched = false
                offlineProbeJob?.cancel()
                offlineProbeJob = null
                true
            }
        }
        if (!shouldConnect) {
            logger.i(TAG, "start() called while already active; ignoring duplicate start")
            return
        }
        _resumptionHandleSetAtMs.value = null
        _liveState.value = LiveState.CONNECTING
        startOfflineSupervisor()
        connectPrimary()
    }

    fun stop() {
        val socketsToClose = mutableListOf<WebSocket>()
        synchronized(socketLock) {
            isSessionActive = false
            connecting = false
            handoverInProgress = false
            activeWebSocket?.let { socketsToClose.add(it) }
            pendingHandoverSocket?.let { socketsToClose.add(it) }
            activeWebSocket = null
            activeConnId = null
            pendingHandoverSocket = null
            handoverIsRecycle = false
            retiredSockets.clear()
            reconnectJob?.cancel()
            reconnectJob = null
            healthyResetJob?.cancel()
            healthyResetJob = null
            offlineProbeJob?.cancel()
            offlineProbeJob = null
            offlineLatched = false
            resumptionHandle = null
            lastDisconnect = null
            consecutiveFailures = 0
        }
        socketsToClose.forEach { s -> try { s.close(1000, "Session stopped") } catch (_: Exception) {} }

        val flight = synchronized(flightLock) {
            val f = currentFlight
            currentFlight = null
            f
        }
        flight?.let {
            it.timeoutJob?.cancel()
            it.resolved.set(true)
        }

        _activeConnectionId.value = null
        _handoverInProgress.value = false
        _resumptionHandleSetAtMs.value = null
        _reconnectAttempt.value = 0
        _reconnectDelayMs.value = null
        _liveState.value = LiveState.CONNECTING
    }

    /** Returns true if the clip was accepted for sending; false if it was dropped/not ready. */
    fun sendClip(clip: AudioClip): Boolean {
        val ws = synchronized(socketLock) {
            if (!isSessionActive) null else activeWebSocket
        }
        if (ws == null || _liveState.value != LiveState.READY) {
            logger.w(TAG, "LiveSession not ready (state=${_liveState.value}); dropping/falling back")
            return false
        }

        val flight = Flight(id = flightIdGen.incrementAndGet(), clip = clip, captureTimeMs = clip.capturedAtMs)
        val accepted = synchronized(flightLock) {
            if (currentFlight != null) {
                false
            } else {
                currentFlight = flight
                lastFlightId = flight.id
                true
            }
        }
        if (!accepted) {
            logger.w(TAG, "Clip dropped: a flight is already in progress (captured at ${clip.capturedAtMs})")
            Telemetry.recordDrop()
            return false
        }

        _liveState.value = LiveState.SENDING

        scope.launch {
            val sendOk = sendClipPayload(ws, clip)
            if (!sendOk) {
                val resolvedFlight = resolveFlightById(flight.id)
                if (resolvedFlight != null) {
                    finishFlightWithFallback(resolvedFlight, reason = "send_failed")
                }
                markReadyIfSocketStillActive()
                return@launch
            }

            flight.activityEndedMs = nowMs()
            flight.timeoutJob = scope.launch {
                delay(flightTimeoutMs)
                val timedOut = resolveFlightById(flight.id)
                if (timedOut != null) {
                    logger.w(TAG, "Flight #${flight.id} timed out after ${flightTimeoutMs}ms; falling back to REST")
                    _flightTimeoutCount.update { it + 1 }
                    finishFlightWithFallback(timedOut, reason = "flight_timeout")
                    markReadyIfSocketStillActive()
                }
            }
        }
        return true
    }

    /**
     * Runs a single standalone diagnostic Live call. Refuses to open a second socket while the
     * persistent monitoring session is active/connecting/mid-handover; the caller should tell
     * the user to stop monitoring first. Serialized so overlapping taps can't race.
     */
    suspend fun testLiveCall(clip: AudioClip): Result<LiveClassificationEvent> {
        if (isPersistentSessionBusy()) {
            _standaloneTestBlocked.value = true
            return Result.failure(IllegalStateException("Stop monitoring before standalone Live test"))
        }
        return testMutex.withLock {
            if (isPersistentSessionBusy()) {
                _standaloneTestBlocked.value = true
                return@withLock Result.failure(IllegalStateException("Stop monitoring before standalone Live test"))
            }
            _standaloneTestBlocked.value = false
            runStandaloneTestSocket(clip)
        }
    }

    // ---- Internal: connection lifecycle -------------------------------------------------

    private fun isRetired(ws: WebSocket): Boolean = synchronized(socketLock) { ws in retiredSockets }

    private fun isPersistentSessionBusy(): Boolean = synchronized(socketLock) {
        isSessionActive && (activeWebSocket != null || connecting || handoverInProgress)
    }

    private fun isSessionActiveSnapshot(): Boolean = synchronized(socketLock) { isSessionActive }

    /** A session that has actually completed setup, as opposed to one merely attempting to. */
    private fun isLiveNow(): Boolean =
        _liveState.value == LiveState.READY || _liveState.value == LiveState.SENDING

    private fun markReadyIfSocketStillActive() {
        val stillConnected = synchronized(socketLock) { isSessionActive && activeWebSocket != null }
        if (stillConnected) _liveState.value = LiveState.READY
    }

    private fun connectPrimary() {
        val connId: Int
        synchronized(socketLock) {
            if (!isSessionActive || activeWebSocket != null || connecting) return
            connecting = true
            connId = nextConnectionId.getAndIncrement()
        }
        if (!synchronized(socketLock) { offlineLatched }) {
            _liveState.value = if (consecutiveFailures > 0) LiveState.RECONNECTING else LiveState.CONNECTING
        }
        val handle = synchronized(socketLock) { resumptionHandle }

        val ws = try {
            webSocketFactory.create(buildRequest(), buildListener(connId, isHandover = false, oldSocket = null, resumptionHandleToUse = handle))
        } catch (e: Exception) {
            logger.e(TAG, "Failed to create WebSocket: ${e.message}", e)
            synchronized(socketLock) { connecting = false }
            scheduleReconnect()
            return
        }
        synchronized(socketLock) {
            activeWebSocket = ws
            activeConnId = connId
        }
        _activeConnectionId.value = connId
    }

    /**
     * Called when the device regains connectivity.
     *
     * Without this the reconnect ladder is purely backoff-driven: after a few failures the next
     * attempt can be up to 30s away, and [connectivityChecker] is only consulted *after* that
     * delay has already elapsed. That is right for a flaky link, but wrong for the common case -
     * a rider switching between mobile data and wi-fi - where the network is known-good the
     * instant it arrives and sitting out a backoff that was earned while offline is
     * indistinguishable, to the rider, from the app never reconnecting at all.
     *
     * The failure counter is reset because those failures were the outage, not the server
     * rejecting us; keeping them would make the *next* genuine failure back off as if it were
     * the fourth in a row.
     *
     * Safe to call repeatedly and from any thread: it no-ops when the session is stopped, a
     * socket is already up, or a connect is already in flight.
     */
    fun onNetworkAvailable() {
        val wasOffline: Boolean
        synchronized(socketLock) {
            if (!isSessionActive || activeWebSocket != null || connecting) return
            consecutiveFailures = 0
            reconnectJob?.cancel()
            reconnectJob = null
            // The supervisor is left running: a network that has arrived is not a session that
            // has recovered, and it is the supervisor that will notice if this attempt fails too.
            wasOffline = offlineLatched
        }
        _reconnectAttempt.value = 0
        _reconnectDelayMs.value = 0
        // While latched the chip keeps saying Offline until setupComplete lands. A network that is
        // merely present is not a working Live session, and claiming otherwise is how "Connecting"
        // ends up sitting on screen indefinitely.
        if (!wasOffline) _liveState.value = LiveState.CONNECTING
        logger.i(TAG, "Network available: cancelled pending backoff, reconnecting immediately (wasOffline=$wasOffline)")
        connectPrimary()
    }

    /**
     * Latches OFFLINE: stops the ladder, keeps the session and the resumption handle, and starts a
     * slow re-check so a device whose ConnectivityManager callback never fires still recovers
     * without an app restart.
     */
    private fun enterOffline() {
        var socketToClose: WebSocket? = null
        val entered = synchronized(socketLock) {
            if (!isSessionActive || offlineLatched || isLiveNow()) {
                false
            } else {
                offlineLatched = true
                reconnectJob?.cancel()
                reconnectJob = null
                // A socket object may exist here without ever having reached setupComplete - the
                // ladder creates one per attempt and each dies on the dead radio. Close it so the
                // count is honest; scheduleReconnect is already guarded against re-laddering.
                // Clearing the reference matters as much as closing it: connectPrimary refuses to
                // dial while activeWebSocket is non-null, so leaving a dead one here would make
                // leaving OFFLINE impossible. A late callback for it hits the existing
                // stale-socket guard in onSocketTerminal.
                socketToClose = activeWebSocket
                activeWebSocket = null
                activeConnId = null
                connecting = false
                // resumptionHandle is deliberately kept: an outage is not the server rejecting us,
                // and resuming is what makes the recovery invisible to the rider.
                true
            }
        }
        if (!entered) return
        socketToClose?.let { runCatching { it.close(1000, "Offline") } }
        _activeConnectionId.value = null
        _reconnectAttempt.value = 0
        _reconnectDelayMs.value = null
        _lastFailureKind.value = "offline (no network)"
        _liveState.value = LiveState.OFFLINE
        logger.i(TAG, "Entered OFFLINE; backoff ladder stopped, on-device classification continues")

    }

    /**
     * Answers "is the cloud tier down because there is no network?" for as long as the session is
     * NOT live.
     *
     * This was originally a one-shot armed by socket death, which is wrong, and MEASURED WRONG ON
     * DEVICE: the radio was cut while the coordinator was already sitting in a 27.7 s backoff
     * delay. No socket was alive to die, so scheduleReconnect never ran again, so nothing ever
     * armed the verdict - and the session stayed FAILED indefinitely with the rider told nothing.
     * A network can drop at any instant, including one where there is nothing left to fail.
     *
     * So it is a condition, not an event: not live, no connectivity, sustained for
     * [offlineDebounceMs]. The debounce is a run of consecutive failed checks, which is what makes
     * a wi-fi -> mobile handover - connectivity returning within the window - reset the timer
     * instead of latching.
     *
     * It exits as soon as the session is live again, and is restarted by [onSocketTerminal] the
     * next time one dies. A healthy session therefore costs nothing at all, and a test that drives
     * one to READY can still reach an idle scheduler.
     */
    private fun startOfflineSupervisor() {
        synchronized(socketLock) {
            if (!isSessionActive || offlineProbeJob?.isActive == true) return
        }
        val job = scope.launch {
            // Elapsed time is ACCUMULATED from the poll interval rather than read from nowMs().
            // The loop sleeps on the injected scope's scheduler, so under a virtual-time test
            // nowMs() is wall clock and never moves: an implementation that subtracted timestamps
            // measured ~0ms forever and never latched, while passing every compile check.
            var downMs = 0L
            var sinceDialMs = 0L
            while (isActive && isSessionActiveSnapshot()) {
                delay(OFFLINE_POLL_MS)
                if (!isActive || !isSessionActiveSnapshot()) return@launch

                if (synchronized(socketLock) { offlineLatched }) {
                    // Recovery lives here too, so a device whose ConnectivityManager callback never
                    // fires still comes back on its own. Spaced by networkPollMs: the poll cadence
                    // is tuned for detecting an outage, not for hammering an endpoint.
                    downMs = 0L
                    sinceDialMs += OFFLINE_POLL_MS
                    if (sinceDialMs >= networkPollMs && connectivityChecker()) {
                        sinceDialMs = 0L
                        logger.i(TAG, "OFFLINE re-check found a network; attempting to resume")
                        connectPrimary()
                    }
                    continue
                }
                sinceDialMs = 0L

                if (isLiveNow()) {
                    // Live again: nothing left to watch. onSocketTerminal restarts this the next
                    // time the session drops out of live.
                    return@launch
                }
                if (connectivityChecker()) {
                    // Connectivity returning inside the window is what a wi-fi -> mobile handover
                    // looks like, and resetting here is what stops it reading as an outage.
                    downMs = 0L
                    continue
                }
                downMs += OFFLINE_POLL_MS
                if (downMs >= offlineDebounceMs) {
                    downMs = 0L
                    enterOffline()
                }
            }
        }
        synchronized(socketLock) {
            offlineProbeJob?.cancel()
            offlineProbeJob = job
        }
    }

    private fun scheduleReconnect() {
        val diag: DisconnectDiagnostic?
        val shouldSchedule: Boolean
        synchronized(socketLock) {
            if (!isSessionActive || activeWebSocket != null || connecting || reconnectJob?.isActive == true ||
                offlineLatched
            ) {
                // offlineLatched: the ladder is exactly what OFFLINE exists to stop. Re-entering it
                // here is how the rider ends up watching a progress ring climb against a dead radio.
                shouldSchedule = false
                diag = null
            } else {
                diag = lastDisconnect
                if (diag?.modelUnavailableFlag == true) {
                    shouldSchedule = false
                    resumptionHandle = null
                    _liveState.value = LiveState.FAILED
                    logger.e(TAG, "Live session failed permanently due to unavailable/unsupported model ($diag). State=FAILED; will not retry.")
                } else {
                    shouldSchedule = true
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        resumptionHandle = null
                        _liveState.value = LiveState.FAILED
                        logger.e(TAG, "Live session failed $consecutiveFailures consecutive times (lastDisconnect=$diag). State=FAILED; still retrying with backoff.")
                    } else {
                        _liveState.value = LiveState.RECONNECTING
                    }
                }
            }
        }
        if (!shouldSchedule) return
        if (synchronized(socketLock) { resumptionHandle } == null) {
            _resumptionHandleSetAtMs.value = null
        }

        val isQuota = diag?.quotaFlag == true
        val attempt = (consecutiveFailures - 1).coerceAtLeast(0)
        val delayMs = computeBackoffDelayMs(isQuota, attempt)

        _reconnectAttempt.value = consecutiveFailures
        _reconnectDelayMs.value = delayMs
        _lastFailureKind.value = if (isQuota) "quota-like close" else "network/transient"

        logger.i(TAG, "Scheduling Live reconnect in ${delayMs}ms (attempt=$consecutiveFailures isQuota=$isQuota)")

        val job = scope.launch {
            delay(delayMs)
            if (!isQuota) {
                while (isActive && isSessionActiveSnapshot() && !connectivityChecker()) {
                    delay(networkPollMs)
                }
            }
            if (isActive && isSessionActiveSnapshot()) {
                connectPrimary()
            }
        }
        synchronized(socketLock) { reconnectJob = job }
    }

    private fun computeBackoffDelayMs(isQuota: Boolean, attempt: Int): Long {
        return if (isQuota) {
            val maxCap = 300_000L
            val maxDelay = minOf(maxCap, 60_000L * (1L shl attempt.coerceAtMost(10)))
            random.nextLong(60_000L, maxDelay + 1L)
        } else {
            val maxCap = 30_000L
            val maxDelay = minOf(maxCap, 1000L * (1L shl attempt.coerceAtMost(10)))
            random.nextLong(0L, maxDelay + 1L)
        }
    }

    private fun buildRequest(): Request {
        val url = "${GeminiConfig.LIVE_WS_URL}?key=${apiKeyProvider()}"
        return Request.Builder().url(url).build()
    }

    /**
     * The backoff ladder's quota test, unchanged on purpose.
     *
     * [classifyClose] refines the *diagnosis* into a [LiveCloseKind]; it deliberately does not
     * feed the ladder. Changing which closes take the 60s-plus quota ladder is a behaviour
     * change, and this step's job is to measure the current behaviour, not to quietly retune it.
     */
    private fun isQuotaReason(reason: String?): Boolean {
        val r = reason.orEmpty()
        return r.contains("quota", ignoreCase = true) || r.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
    }

    private fun recordDisconnectDiagnostic(
        connectionId: Int,
        closeCode: Int?,
        closeReason: String?,
        httpStatus: Int?,
        throwableClass: String?,
        quotaFlag: Boolean
    ) {
        val modelUnavailable = isModelUnavailable(closeCode, closeReason, httpStatus)
        val diag = DisconnectDiagnostic(
            connectionId = connectionId,
            closeCode = closeCode,
            closeReason = LogRedactor.redact(closeReason),
            httpStatus = httpStatus,
            throwableClass = throwableClass,
            quotaFlag = quotaFlag,
            modelUnavailableFlag = modelUnavailable,
            timestampMs = nowMs()
        )
        synchronized(socketLock) { lastDisconnect = diag }

        // Attach the token curve to the close. A close with a flat curve behind it and a close at
        // the top of a straight line are different failures wearing the same close code.
        val kind = classifyClose(closeCode, closeReason, httpStatus, modelUnavailable)
        _lastCloseKind.value = kind
        _closeKindCounts.update { it + (kind to ((it[kind] ?: 0) + 1)) }
        _lastCloseUsage.value = _turnUsageHistory.value
        Telemetry.recordLiveCloseKind(kind.name)
        logger.i(
            TAG,
            "close kind=$kind code=${closeCode ?: "-"} http=${httpStatus ?: "-"} " +
                "turnsOnConnection=${_turnsThisConnection.value} " +
                "lastPromptTokens=${_lastTurnUsage.value?.promptTokens ?: 0} " +
                "slope=${"%.1f".format(_promptSlopePerTurn.value)}"
        )

        _lastCloseDiagnostic.value = "code=${closeCode ?: "-"} reason=${diag.closeReason ?: "-"} kind=$kind quotaLike=$quotaFlag modelUnavailable=$modelUnavailable"
        _lastFailureKind.value = if (modelUnavailable) {
            "model unavailable (non-retryable)"
        } else if (quotaFlag) {
            "quota-like close"
        } else if (closeCode != null) {
            "server close"
        } else {
            "network/transient"
        }
        if (closeCode == 1008 || closeCode == 1007 || modelUnavailable) {
            logger.e(TAG, "Server rejected the session (code=$closeCode modelUnavailable=$modelUnavailable); dropping resumption handle.")
            synchronized(socketLock) { resumptionHandle = null }
            _resumptionHandleSetAtMs.value = null
        }
    }

    // ---- Internal: shared socket listener (primary + handover) --------------------------

    private fun buildListener(
        connectionId: Int,
        isHandover: Boolean,
        oldSocket: WebSocket?,
        resumptionHandleToUse: String?
    ): WebSocketListener {
        val isOpened = AtomicBoolean(false)
        val isClosed = AtomicBoolean(false)
        val kind = if (isHandover) "handover" else "primary"

        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isOpened.compareAndSet(false, true)) {
                    val count = _openSocketCount.updateAndGet { it + 1 }
                    logger.i(TAG, "socket #$connectionId opened ($kind), openSocketCount=$count")
                }
                webSocket.send(buildSetupMessage(resumptionHandleToUse))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isHandover) {
                    handleHandoverMessage(webSocket, oldSocket!!, connectionId, text)
                } else {
                    handleIncomingMessage(webSocket, text, connectionId)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isRetired(webSocket)) {
                    recordDisconnectDiagnostic(connectionId, code, reason, null, null, isQuotaReason(reason))
                }
                logger.i(TAG, "socket #$connectionId closing ($kind): code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isRetired(webSocket)) {
                    recordDisconnectDiagnostic(connectionId, code, reason, null, null, isQuotaReason(reason))
                }
                if (isOpened.get() && isClosed.compareAndSet(false, true)) {
                    val count = _openSocketCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    logger.i(TAG, "socket #$connectionId closed ($kind, code=$code reason=$reason), openSocketCount=$count")
                }
                onSocketTerminal(webSocket, isHandover, oldSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isRetired(webSocket)) {
                    recordDisconnectDiagnostic(connectionId, null, t.message, response?.code, t::class.java.simpleName, quotaFlag = false)
                }
                if (isOpened.get() && isClosed.compareAndSet(false, true)) {
                    val count = _openSocketCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                    logger.e(TAG, "socket #$connectionId failed ($kind): ${t.message}, openSocketCount=$count", t)
                } else {
                    logger.e(TAG, "socket #$connectionId failed before opening ($kind): ${t.message}", t)
                }
                onSocketTerminal(webSocket, isHandover, oldSocket)
            }
        }
    }

    private fun onSocketTerminal(webSocket: WebSocket, isHandover: Boolean, oldSocket: WebSocket?) {
        // A socket we retired ourselves has nothing left to do: its replacement is already the
        // active connection, so this close is neither a disconnect nor a reason to reconnect.
        if (synchronized(socketLock) { retiredSockets.remove(webSocket) }) return

        if (isHandover) {
            var handoverFailedBeforeSwap = false
            var wasRecycle = false
            synchronized(socketLock) {
                if (activeWebSocket === oldSocket) {
                    // The swap never happened: this handover attempt died before setupComplete.
                    handoverInProgress = false
                    pendingHandoverSocket = null
                    wasRecycle = handoverIsRecycle
                    handoverIsRecycle = false
                    handoverFailedBeforeSwap = true
                }
            }
            if (handoverFailedBeforeSwap && wasRecycle) {
                // The old session keeps serving; the next classified turn will try again. Nothing
                // is lost but the context reset, and a reconnect storm here would cost more.
                _handoverInProgress.value = false
                logger.w(TAG, "Recycle socket failed before setupComplete; keeping current session")
                return
            }
            _handoverInProgress.value = synchronized(socketLock) { handoverInProgress }
            if (handoverFailedBeforeSwap) {
                logger.w(TAG, "Handover socket failed before setupComplete; keeping previous socket active, no reconnect storm triggered")
                return
            }
            // Otherwise the swap already completed and this socket has been the primary
            // connection for a while; fall through to normal primary-path handling below.
        }

        val flightToFallback: Flight?
        val orphanedHandover: WebSocket?
        synchronized(socketLock) {
            if (activeWebSocket !== webSocket) return // stale/late callback for a socket we no longer own
            activeWebSocket = null
            activeConnId = null
            connecting = false
            // A replacement still connecting for this socket (goAway resume or per-turn recycle)
            // would otherwise race the reconnect ladder for the active slot and leak whichever
            // lost. Drop it and let the ladder own the recovery; one extra connect is cheaper
            // than two live sockets.
            orphanedHandover = pendingHandoverSocket
            pendingHandoverSocket = null
            handoverInProgress = false
            handoverIsRecycle = false
            orphanedHandover?.let { retiredSockets.add(it) }
        }
        _activeConnectionId.value = null
        if (orphanedHandover != null) {
            _handoverInProgress.value = false
            try { orphanedHandover.close(1000, "Active socket died mid-handover") } catch (_: Exception) {}
        }

        synchronized(socketLock) {
            healthyResetJob?.cancel()
            healthyResetJob = null
        }

        flightToFallback = resolveCurrentFlight()
        if (flightToFallback != null) {
            _disconnectFallbackCount.update { it + 1 }
            finishFlightWithFallback(flightToFallback, reason = "socket_disconnected")
        }

        if (isSessionActiveSnapshot()) {
            // Before the ladder, because the ladder is exactly the state during which a radio can
            // die with no socket left to notice - the case measured on device.
            startOfflineSupervisor()
            scheduleReconnect()
        }
    }

    // ---- Internal: goAway handover --------------------------------------------------------

    private fun handleHandoverMessage(newWs: WebSocket, oldSocket: WebSocket, connectionId: Int, text: String) {
        try {
            val root = JSONObject(text)
            if (root.has("setupComplete")) {
                var swapped = false
                var recycled = false
                synchronized(socketLock) {
                    if (handoverInProgress) {
                        activeWebSocket = newWs
                        activeConnId = connectionId
                        connecting = false
                        handoverInProgress = false
                        pendingHandoverSocket = null
                        recycled = handoverIsRecycle
                        handoverIsRecycle = false
                        retiredSockets.add(oldSocket)
                        if (recycled) {
                            // The handle belongs to the session we just discarded. A reconnect
                            // that resumed it would restore exactly the context the recycle
                            // exists to drop; the fresh session announces its own handle.
                            resumptionHandle = null
                        }
                        swapped = true
                    }
                }
                if (swapped) {
                    _handoverInProgress.value = false
                    _activeConnectionId.value = connectionId
                    if (recycled) _resumptionHandleSetAtMs.value = null
                    val why = if (recycled) "fresh session" else "resumed session"
                    logger.i(TAG, "Handover socket #$connectionId setupComplete ($why); swapping active socket")
                    val flightOnOldSocket = synchronized(flightLock) { currentFlight != null }
                    if (flightOnOldSocket) {
                        // A clip went out on the old socket while the replacement was connecting.
                        // Its answer arrives on that socket, so it stays open until the flight
                        // has had its full timeout to resolve; nothing new is sent on it because
                        // the swap above already happened.
                        scope.launch {
                            delay(flightTimeoutMs)
                            try { oldSocket.close(1000, "Replaced by $why") } catch (_: Exception) {}
                        }
                    } else {
                        try { oldSocket.close(1000, "Replaced by $why") } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error inspecting handover message: ${e.message}", e)
        }
        // Only the active socket may drive session state. A replacement that lost its swap (the
        // old socket died first and the ladder took over) can still deliver a late setupComplete,
        // which would otherwise paint READY over a session with no socket behind it.
        if (synchronized(socketLock) { activeWebSocket === newWs }) {
            handleIncomingMessage(newWs, text, connectionId)
        } else {
            logger.i(TAG, "Ignoring message on non-active handover socket #$connectionId")
        }
    }

    /**
     * Opens the replacement session for [recycleSessionAfterTurn]. No-op unless [current] is
     * still the active socket and no other handover is in flight - a goAway that landed in the
     * same instant already has a replacement coming, and the two must not race for the swap.
     */
    private fun requestSessionRecycle(current: WebSocket) {
        val shouldRecycle = synchronized(socketLock) {
            if (!isSessionActive || activeWebSocket !== current || handoverInProgress || connecting) {
                false
            } else {
                handoverInProgress = true
                handoverIsRecycle = true
                true
            }
        }
        if (!shouldRecycle) return
        _handoverInProgress.value = true
        val newConnId = nextConnectionId.getAndIncrement()
        val newWs = try {
            webSocketFactory.create(
                buildRequest(),
                buildListener(newConnId, isHandover = true, oldSocket = current, resumptionHandleToUse = null)
            )
        } catch (e: Exception) {
            logger.w(TAG, "Recycle socket could not be created; keeping current session: ${e.message}")
            synchronized(socketLock) {
                handoverInProgress = false
                handoverIsRecycle = false
            }
            _handoverInProgress.value = false
            return
        }
        synchronized(socketLock) { pendingHandoverSocket = newWs }
        val n = _sessionRecycleCount.updateAndGet { it + 1 }
        logger.i(TAG, "Recycling session after turn: fresh socket #$newConnId connecting (recycle #$n)")
    }

    private fun parseGoAwayTimeLeftMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removeSuffix("s")
        val seconds = trimmed.toDoubleOrNull() ?: return null
        return (seconds * 1000.0).toLong()
    }

    // ---- Internal: shared message handling -------------------------------------------------

    private fun handleIncomingMessage(webSocket: WebSocket, text: String, connectionId: Int) {
        try {
            val root = JSONObject(text)

            val usageMetadata = root.optJSONObject("usageMetadata")
                ?: root.optJSONObject("serverContent")?.optJSONObject("usageMetadata")
            if (usageMetadata != null) {
                recordTurnUsage(usageMetadata, connectionId)
            }

            if (root.has("setupComplete")) {
                logger.i(TAG, "Received setupComplete from Live session")
                val leftOffline = synchronized(socketLock) {
                    connecting = false
                    val was = offlineLatched
                    offlineLatched = false
                    // Deliberately does NOT touch offlineProbeJob. The supervisor is a
                    // session-lifetime watcher, not a one-shot armed per outage; cancelling it
                    // here left the very first healthy connection with nothing watching, so the
                    // next drop was never noticed at all.
                    was
                }
                if (leftOffline) logger.i(TAG, "Left OFFLINE: session resumed and setup completed")
                _liveState.value = LiveState.READY

                synchronized(socketLock) {
                    healthyResetJob?.cancel()
                    healthyResetJob = scope.launch {
                        delay(healthyResetMs)
                        if (isActive && isSessionActiveSnapshot()) {
                            synchronized(socketLock) {
                                if (activeWebSocket != null) {
                                    logger.i(TAG, "Session healthy for ${healthyResetMs}ms; resetting consecutiveFailures from $consecutiveFailures to 0")
                                    consecutiveFailures = 0
                                }
                            }
                            _reconnectAttempt.value = 0
                        }
                    }
                }
                return
            }

            if (root.has("sessionResumptionUpdate")) {
                val sru = root.getJSONObject("sessionResumptionUpdate")
                val newHandle = sru.optString("newHandle", "")
                val isResumable = sru.optBoolean("resumable", false)
                _resumable.value = isResumable
                if (isResumable && newHandle.isNotBlank()) {
                    synchronized(socketLock) { resumptionHandle = newHandle }
                    _resumptionHandleSetAtMs.value = nowMs()
                    logger.i(TAG, "sessionResumptionUpdate: stored new handle (len=${newHandle.length})")
                } else {
                    logger.i(TAG, "sessionResumptionUpdate: resumable=$isResumable handleBlank=${newHandle.isBlank()}; not storing")
                }
                return
            }

            if (root.has("goAway")) {
                val goAway = root.getJSONObject("goAway")
                val timeLeftMs = parseGoAwayTimeLeftMs(goAway.optString("timeLeft"))
                _goAwayTimeLeftMs.value = timeLeftMs
                val shouldHandover = synchronized(socketLock) {
                    if (activeWebSocket !== webSocket || handoverInProgress) {
                        false
                    } else {
                        handoverInProgress = true
                        true
                    }
                }
                _handoverInProgress.value = shouldHandover || synchronized(socketLock) { handoverInProgress }
                logger.w(TAG, "Received goAway (timeLeft=${timeLeftMs}ms); shouldHandover=$shouldHandover")
                if (shouldHandover) {
                    val handle = synchronized(socketLock) { resumptionHandle }
                    val newConnId = nextConnectionId.getAndIncrement()
                    val newWs = webSocketFactory.create(
                        buildRequest(),
                        buildListener(newConnId, isHandover = true, oldSocket = webSocket, resumptionHandleToUse = handle)
                    )
                    synchronized(socketLock) { pendingHandoverSocket = newWs }
                }
                return
            }

            if (root.has("toolCall")) {
                handleToolCall(webSocket, root.getJSONObject("toolCall"))
                return
            }

            if (root.has("serverContent")) {
                handleServerContent(root, root.getJSONObject("serverContent"))
                return
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error handling incoming Live message: ${e.message}", e)
        }
    }

    /**
     * Records one `usageMetadata` message as a turn row.
     *
     * Deliberately records EVERY usageMetadata occurrence rather than trying to guess which one
     * is "the" end of a turn: whether the server sends one per turn or several is exactly what
     * the probe is measuring, and a filter written on a guess would launder that guess into the
     * evidence. The probe cross-checks rows recorded against turns it actually drove.
     *
     * The running totals keep their previous meaning (sum of every occurrence) so existing
     * diagnostics rows do not silently change definition.
     */
    private fun recordTurnUsage(usageMetadata: JSONObject, connectionId: Int) {
        // MEASURED 2026-09-02 against gemini-2.5-flash-native-audio-preview-12-2025: the server
        // populates usageMetadata on the FIRST turn of a connection and then sends a literally
        // empty `"usageMetadata": {}` on every turnComplete after it. Folding those empties in as
        // zeros is how a delta of -1759 and a meaningless negative slope get produced, and how a
        // panel ends up reporting "0 tokens" for a session that is certainly spending them.
        //
        // So an empty report is counted as an empty report and changes nothing else. That counter
        // is itself the finding: it is the difference between "this session is cheap" and "the
        // server stopped telling us", and the two look identical without it.
        val hasNumbers = usageMetadata.has("promptTokenCount") ||
            usageMetadata.has("totalTokenCount") ||
            usageMetadata.has("responseTokenCount")
        if (!hasNumbers) {
            _emptyUsageReports.update { it + 1 }
            return
        }
        val promptTokenCount = usageMetadata.optLong("promptTokenCount", 0L)
        val responseTokenCount = usageMetadata.optLong(
            "responseTokenCount",
            usageMetadata.optLong("candidatesTokenCount", 0L)
        )
        val totalTokenCount = usageMetadata.optLong("totalTokenCount", 0L)

        _promptTokensTotal.update { it + promptTokenCount }
        _responseTokensTotal.update { it + responseTokenCount }
        _totalTokensRunning.update { it + totalTokenCount }

        val promptAudio = sumModality(usageMetadata.optJSONArray("promptTokensDetails"), "AUDIO")
        val promptText = sumModality(usageMetadata.optJSONArray("promptTokensDetails"), "TEXT")
        val responseAudio = sumModality(usageMetadata.optJSONArray("responseTokensDetails"), "AUDIO")

        val row = synchronized(usageLock) {
            if (usageConnectionId != connectionId) {
                usageConnectionId = connectionId
                lastPromptTokensOnConnection = null
                _turnsThisConnection.value = 0
                _promptTokensThisConnection.value = 0L
            }
            val previous = lastPromptTokensOnConnection
            lastPromptTokensOnConnection = promptTokenCount
            val turnIndex = _turnsThisConnection.updateAndGet { it + 1 }
            _promptTokensThisConnection.update { it + promptTokenCount }
            TurnUsage(
                turnIndex = turnIndex,
                connectionId = connectionId,
                promptTokens = promptTokenCount,
                promptAudioTokens = promptAudio,
                promptTextTokens = promptText,
                responseTokens = responseTokenCount,
                responseAudioTokens = responseAudio,
                totalTokens = totalTokenCount,
                promptDelta = if (previous == null) 0L else promptTokenCount - previous,
                atMs = nowMs()
            )
        }

        _lastTurnUsage.value = row
        val history = _turnUsageHistory.updateAndGet { current ->
            (current + row).takeLast(TURN_HISTORY_SIZE)
        }
        _promptSlopePerTurn.value = promptSlope(history.takeLast(SLOPE_WINDOW))

        logger.i(
            TAG,
            "turn#${row.turnIndex} conn=$connectionId prompt=${row.promptTokens} " +
                "(d=${row.promptDelta} audio=${row.promptAudioTokens} text=${row.promptTextTokens}) " +
                "response=${row.responseTokens} (audio=${row.responseAudioTokens}) " +
                "total=${row.totalTokens} slope=${"%.1f".format(_promptSlopePerTurn.value)}"
        )
    }

    private fun sumModality(details: JSONArray?, modality: String): Long {
        if (details == null) return 0L
        var sum = 0L
        for (i in 0 until details.length()) {
            val entry = details.optJSONObject(i) ?: continue
            if (entry.optString("modality").equals(modality, ignoreCase = true)) {
                sum += entry.optLong("tokenCount", 0L)
            }
        }
        return sum
    }

    private fun handleToolCall(webSocket: WebSocket, toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.getJSONObject(i)
            val callId = call.getString("id")
            val name = call.getString("name")
            if (name != "report_sound") continue

            val args = call.getJSONObject("args")
            val soundId = args.optString("sound_id", "UNKNOWN")
            val tier = args.optString("tier", "SAFE")
            val confidence = args.optDouble("confidence", 0.5)
            val motion = args.optString("motion", "UNKNOWN")
            val captionVi = args.optString("caption_vi", "")
            val captionEn = args.optString("caption_en", "")

            val flight = resolveCurrentFlight()
            if (flight == null) {
                _lateResponseCount.update { it + 1 }
                logger.w(TAG, "Ignoring toolCall with no matching in-flight clip (lastFlightId=$lastFlightId)")
            } else {
                flight.timeoutJob?.cancel()
                logger.i(TAG, "TOOL CALL SUCCESS: sound=$soundId tier=$tier conf=$confidence motion=$motion flight=#${flight.id}")

                synchronized(socketLock) { consecutiveFailures = 0 }
                synchronized(socketLock) {
                    healthyResetJob?.cancel()
                    healthyResetJob = null
                }
                _reconnectAttempt.value = 0

                val classificationResult = ClassificationResult(
                    soundId = soundId,
                    tier = tier,
                    confidence = confidence,
                    captionVi = captionVi,
                    captionEn = captionEn,
                    motion = motion
                )
                val endMs = flight.activityEndedMs
                val latencyMs = if (endMs != null) (nowMs() - endMs).coerceAtLeast(0L) else 0L
                val endToEndMs = (nowMs() - flight.captureTimeMs).coerceAtLeast(0L)
                Telemetry.recordLiveLatency(latencyMs)
                Telemetry.recordLatency(endToEndMs)

                _liveState.value = LiveState.READY
                _events.tryEmit(
                    LiveClassificationEvent(
                        result = classificationResult,
                        capturedAtMs = flight.captureTimeMs,
                        latencyMs = latencyMs,
                        rawJsonArgs = args.toString(),
                        capturedMonotonicMs = flight.clip.capturedMonotonicMs
                    )
                )
            }

            // Mandatory reply to prevent the session from hanging, even for a late/unmatched call.
            val reply = JSONObject()
            val toolResponse = JSONObject()
            val functionResponses = JSONArray()
            val funcResp = JSONObject()
            funcResp.put("id", callId)
            funcResp.put("name", "report_sound")
            funcResp.put("response", JSONObject().put("ok", true))
            functionResponses.put(funcResp)
            toolResponse.put("functionResponses", functionResponses)
            reply.put("toolResponse", toolResponse)
            webSocket.send(reply.toString())

            // After the reply, not before: the server treats an unanswered tool call as a hung
            // turn, and the socket being retired must finish its turn cleanly.
            if (flight != null && recycleSessionAfterTurn) requestSessionRecycle(webSocket)
        }
    }

    private fun handleServerContent(root: JSONObject, serverContent: JSONObject) {
        val modelTurn = serverContent.optJSONObject("modelTurn")
        if (modelTurn != null) {
            val parts = modelTurn.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val base64Data = inlineData.optString("data", "")
                        if (base64Data.isNotEmpty()) {
                            val byteCount = Base64.getMimeDecoder().decode(base64Data).size
                            Telemetry.recordStrayAudioBytes(byteCount)
                        }
                    }
                }
            }
        }

        val turnComplete = root.optBoolean("generationComplete", false) ||
            serverContent.optBoolean("turnComplete", false) ||
            serverContent.optBoolean("generationComplete", false)

        if (turnComplete) {
            val flight = resolveCurrentFlight()
            if (flight != null) {
                logger.i(TAG, "turnComplete without report_sound for flight #${flight.id}; falling back to REST")
                finishFlightWithFallback(flight, reason = "turn_complete_no_tool")
            }
        }
    }

    // ---- Internal: flight (in-flight clip) resolution --------------------------------------

    /** Resolves whatever flight is currently in progress, regardless of its id. */
    private fun resolveCurrentFlight(): Flight? = synchronized(flightLock) {
        val f = currentFlight
        if (f != null && f.resolved.compareAndSet(false, true)) {
            currentFlight = null
            f
        } else null
    }

    /** Resolves the flight only if it's still current and matches [id] (guards stale timeouts). */
    private fun resolveFlightById(id: Long): Flight? = synchronized(flightLock) {
        val f = currentFlight
        if (f != null && f.id == id && f.resolved.compareAndSet(false, true)) {
            currentFlight = null
            f
        } else null
    }

    private fun finishFlightWithFallback(flight: Flight, reason: String) {
        flight.timeoutJob?.cancel()
        scope.launch {
            val result = restFallback(flight.clip)
            result.onSuccess { r ->
                _events.tryEmit(
                    LiveClassificationEvent(
                        result = r,
                        capturedAtMs = flight.captureTimeMs,
                        latencyMs = 0L,
                        rawJsonArgs = "Fallback via REST ($reason)",
                        // The fallback must carry the SAME capture instant as the Live path
                        // would have - a wall-clock step between capture and this fallback
                        // resolution must not change whether motion corroborates.
                        capturedMonotonicMs = flight.clip.capturedMonotonicMs
                    )
                )
            }
            markReadyIfSocketStillActive()
        }
    }

    private suspend fun sendClipPayload(ws: WebSocket, clip: AudioClip): Boolean {
        return try {
            val startMsg = JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject()))
            if (!ws.send(startMsg.toString())) return false

            val rawPcm = if (clip.wav.size > 44 &&
                clip.wav[0] == 'R'.code.toByte() &&
                clip.wav[1] == 'I'.code.toByte() &&
                clip.wav[2] == 'F'.code.toByte() &&
                clip.wav[3] == 'F'.code.toByte()
            ) {
                clip.wav.copyOfRange(44, clip.wav.size)
            } else {
                clip.wav
            }

            // Chunk size for realtime audio streaming. Measured 2026-09-04 over 100 turns:
            // 10240 bytes (320 ms) delivers contiguous acoustic context that reduces frame-boundary
            // distortion for low-frequency fundamentals, boosting Sound ID accuracy from 28% to 49%
            // (and danger tier accuracy from 56% to 85%) vs 5120-byte chunks.
            val sliceBytesSize = 10240
            var offset = 0
            while (offset < rawPcm.size) {
                val end = minOf(offset + sliceBytesSize, rawPcm.size)
                val slice = rawPcm.copyOfRange(offset, end)
                val base64Slice = Base64.getEncoder().encodeToString(slice)

                val audioObj = JSONObject()
                audioObj.put("mimeType", "audio/pcm;rate=16000")
                audioObj.put("data", base64Slice)

                val chunkMsg = JSONObject().put("realtimeInput", JSONObject().put("audio", audioObj))
                if (!ws.send(chunkMsg.toString())) return false

                _audioBytesSentTotal.update { it + slice.size }
                offset = end
            }
            _audioMsSentTotal.update { it + (rawPcm.size / 2) / 16 } // 16 samples/ms at 16kHz mono

            val endMsg = JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject()))
            if (!ws.send(endMsg.toString())) return false

            true
        } catch (e: Exception) {
            logger.e(TAG, "Error sending audio to Live WebSocket: ${e.message}", e)
            false
        }
    }

    // ---- Internal: standalone diagnostic test -----------------------------------------------

    private suspend fun runStandaloneTestSocket(clip: AudioClip): Result<LiveClassificationEvent> {
        val resultFlow = MutableSharedFlow<Result<LiveClassificationEvent>>(replay = 1, extraBufferCapacity = 1)
        val isOpened = AtomicBoolean(false)
        val isClosed = AtomicBoolean(false)
        var activityEndMs = 0L

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (isOpened.compareAndSet(false, true)) {
                    _openSocketCount.updateAndGet { it + 1 }
                }
                ws.send(buildSetupMessage(null))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        scope.launch {
                            ws.send(JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString())
                            val rawPcm = if (clip.wav.size > 44 &&
                                clip.wav[0] == 'R'.code.toByte() &&
                                clip.wav[1] == 'I'.code.toByte() &&
                                clip.wav[2] == 'F'.code.toByte() &&
                                clip.wav[3] == 'F'.code.toByte()
                            ) {
                                clip.wav.copyOfRange(44, clip.wav.size)
                            } else {
                                clip.wav
                            }
                            val sliceBytesSize = 5120
                            var offset = 0
                            while (offset < rawPcm.size) {
                                val end = minOf(offset + sliceBytesSize, rawPcm.size)
                                val slice = rawPcm.copyOfRange(offset, end)
                                val b64 = Base64.getEncoder().encodeToString(slice)
                                val audioObj = JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                                ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audioObj)).toString())
                                offset = end
                            }
                            ws.send(JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString())
                            activityEndMs = nowMs()
                        }
                        return
                    }

                    if (root.has("toolCall")) {
                        val tc = root.getJSONObject("toolCall")
                        val fcs = tc.optJSONArray("functionCalls")
                        if (fcs != null && fcs.length() > 0) {
                            val fc = fcs.getJSONObject(0)
                            val callId = fc.getString("id")
                            val args = fc.getJSONObject("args")
                            val latency = nowMs() - activityEndMs

                            val res = ClassificationResult(
                                soundId = args.optString("sound_id", "UNKNOWN"),
                                tier = args.optString("tier", "SAFE"),
                                confidence = args.optDouble("confidence", 0.5),
                                captionVi = args.optString("caption_vi", ""),
                                captionEn = args.optString("caption_en", ""),
                                motion = args.optString("motion", "UNKNOWN")
                            )

                            val reply = JSONObject().put(
                                "toolResponse",
                                JSONObject().put(
                                    "functionResponses",
                                    JSONArray().put(
                                        JSONObject().put("id", callId).put("name", "report_sound")
                                            .put("response", JSONObject().put("ok", true))
                                    )
                                )
                            )
                            ws.send(reply.toString())
                            ws.close(1000, "Test complete")

                            resultFlow.tryEmit(
                                Result.success(
                                    LiveClassificationEvent(
                                        result = res,
                                        capturedAtMs = clip.capturedAtMs,
                                        latencyMs = latency,
                                        rawJsonArgs = args.toString()
                                    )
                                )
                            )
                        }
                        return
                    }

                    if (root.has("serverContent")) {
                        val sc = root.getJSONObject("serverContent")
                        val mt = sc.optJSONObject("modelTurn")
                        val parts = mt?.optJSONArray("parts")
                        if (parts != null) {
                            for (p in 0 until parts.length()) {
                                val inlineData = parts.getJSONObject(p).optJSONObject("inlineData")
                                if (inlineData != null) {
                                    val b64 = inlineData.optString("data", "")
                                    if (b64.isNotEmpty()) {
                                        val count = Base64.getMimeDecoder().decode(b64).size
                                        Telemetry.recordStrayAudioBytes(count)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    resultFlow.tryEmit(Result.failure(e))
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) = onMessage(ws, bytes.utf8())

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                resultFlow.tryEmit(Result.failure(IllegalStateException("Server closed the Live session: code=$code reason=$reason")))
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (isOpened.get() && isClosed.compareAndSet(false, true)) {
                    _openSocketCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
                resultFlow.tryEmit(Result.failure(IllegalStateException("Server closed the Live session: code=$code reason=$reason")))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (isOpened.get() && isClosed.compareAndSet(false, true)) {
                    _openSocketCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                }
                resultFlow.tryEmit(Result.failure(t))
            }
        }

        val testWs = webSocketFactory.create(buildRequest(), listener)
        return try {
            val timeoutJob = scope.launch {
                delay(standaloneTestTimeoutMs)
                resultFlow.tryEmit(Result.failure(java.net.SocketTimeoutException("Test Live call timed out")))
                try { testWs.close(1000, "Timeout") } catch (_: Exception) {}
            }
            val result = resultFlow.firstOrNull() ?: Result.failure(IllegalStateException("No result received"))
            timeoutJob.cancel()
            result
        } finally {
            try { testWs.close(1000, "Done") } catch (_: Exception) {}
        }
    }

    // ---- Internal: setup message (shared schema, unchanged from prior version) -------------

    /**
     * Internal, not private, so the quota probe sends the EXACT bytes production sends. A probe
     * that builds its own copy of this payload measures the copy, and every measurement it
     * produces silently expires the next time this method changes.
     */
    internal fun buildSetupMessage(handle: String?): String {
        val root = JSONObject()
        val setup = JSONObject()

        setup.put("model", "models/${GeminiConfig.LIVE_MODEL_ID}")

        val genConfig = JSONObject()
        // Must stay AUDIO: LIVE_MODEL_ID is a native-audio model and the server rejects
        // anything else outright - measured 2026-08-19, close code 1007 "The requested
        // combination of response modalities (TEXT) is not supported by the model". This is
        // why the model emits spoken audio on nearly every turn despite the system
        // instruction forbidding it; the audio is discarded and counted as stray-audio bytes.
        // Eliminating it needs a text-capable (half-cascade) Live model, not a prompt change.
        genConfig.put("responseModalities", JSONArray(listOf("AUDIO")))
        genConfig.put("temperature", 0)
        setup.put("generationConfig", genConfig)

        val sysInstruction = JSONObject()
        val parts = JSONArray()
        parts.put(JSONObject().put("text", SYSTEM_INSTRUCTION_VERBATIM))
        sysInstruction.put("parts", parts)
        setup.put("systemInstruction", sysInstruction)

        val tools = JSONArray()
        val toolObj = JSONObject()
        val funcDecls = JSONArray()
        val funcDecl = JSONObject()
        funcDecl.put("name", "report_sound")
        funcDecl.put(
            "description",
            "Report the single most safety-relevant sound in the audio segment just sent."
        )

        val params = JSONObject()
        params.put("type", "OBJECT")
        val props = JSONObject()

        props.put(
            "sound_id",
            JSONObject()
                .put("type", "STRING")
                .put(
                    "enum",
                    JSONArray(
                        listOf(
                            "NORMAL_TRAFFIC",
                            "VOICE_SHOUT",
                            "MOTORCYCLE_HORN",
                            "CAR_HORN",
                            "SUDDEN_ACCELERATION",
                            "REVERSING_BEEP",
                            "TRUCK_AIR_HORN",
                            "EMERGENCY_SIREN",
                            "TRAIN_HORN",
                            "COLLISION_IMPACT",
                            "UNKNOWN"
                        )
                    )
                )
        )

        props.put(
            "tier",
            JSONObject()
                .put("type", "STRING")
                .put("enum", JSONArray(listOf("SAFE", "WARNING", "CRITICAL")))
        )

        props.put("confidence", JSONObject().put("type", "NUMBER"))

        props.put(
            "motion",
            JSONObject()
                .put("type", "STRING")
                .put("enum", JSONArray(listOf("APPROACHING", "PASSING", "RECEDING", "UNKNOWN")))
        )

        props.put("caption_vi", JSONObject().put("type", "STRING"))
        props.put("caption_en", JSONObject().put("type", "STRING"))

        params.put("properties", props)
        params.put(
            "required",
            JSONArray(listOf("sound_id", "tier", "confidence", "motion", "caption_vi", "caption_en"))
        )

        funcDecl.put("parameters", params)
        funcDecls.put(funcDecl)
        toolObj.put("functionDeclarations", funcDecls)
        tools.put(toolObj)
        setup.put("tools", tools)

        val rtInputConfig = JSONObject()
        rtInputConfig.put("automaticActivityDetection", JSONObject().put("disabled", true))
        setup.put("realtimeInputConfig", rtInputConfig)

        // Intent is to keep the visible turn history short, so the model cannot anchor on its
        // own previous answers. Measured on-device 2026-08-19 these numbers do NOT demonstrably
        // do that: across 12 turns the per-turn prompt token count kept climbing (2058 -> 2597
        // -> 3417), i.e. context still accumulated ~165 tokens/turn with no plateau, so the
        // server appears to clamp or ignore a trigger this low. The repetition bias was fixed
        // by the SEGMENT INDEPENDENCE block in the system instruction, not by these values.
        // Kept because the session is accepted and reaches READY with them and they may still
        // bound much longer sessions - but do not treat them as the anti-anchoring mechanism.
        val ctxComp = JSONObject()
        ctxComp.put("triggerTokens", 1200)
        val slidingWindow = JSONObject()
        slidingWindow.put("targetTokens", 300)
        ctxComp.put("slidingWindow", slidingWindow)
        setup.put("contextWindowCompression", ctxComp)

        val sessResump = JSONObject()
        if (!handle.isNullOrBlank()) {
            sessResump.put("handle", handle)
        }
        setup.put("sessionResumption", sessResump)

        root.put("setup", setup)
        return root.toString()
    }

    companion object {
        private const val TAG = "LiveSessionCoordinator"

        /**
         * How often the offline supervisor re-evaluates.
         *
         * 4 Hz, against an audio pipeline that already processes a 50 ms chunk 20 times a second;
         * when the session is live the tick is two field reads and no ConnectivityManager query.
         * It divides [offlineDebounceMs] exactly, so the verdict lands at 1750 ms rather than
         * whenever the next tick happens to fall.
         */
        internal const val OFFLINE_POLL_MS = 250L

        /** Turn rows kept for the diagnostics panel and for the close snapshot. */
        internal const val TURN_HISTORY_SIZE = 64

        /** Trailing turns the slope is fitted over. */
        internal const val SLOPE_WINDOW = 20

        /**
         * Least-squares slope of promptTokens against turn index, in tokens per turn.
         *
         * Returns 0.0 for fewer than two rows - one point has no slope, and reporting a
         * confident-looking number for it would be the wrong kind of answer.
         */
        internal fun promptSlope(rows: List<TurnUsage>): Double {
            if (rows.size < 2) return 0.0
            val n = rows.size
            val meanX = (n - 1) / 2.0
            val meanY = rows.sumOf { it.promptTokens }.toDouble() / n
            var num = 0.0
            var den = 0.0
            rows.forEachIndexed { i, row ->
                val dx = i - meanX
                num += dx * (row.promptTokens - meanY)
                den += dx * dx
            }
            return if (den == 0.0) 0.0 else num / den
        }

        /**
         * Diagnosis-only classification of a socket close.
         *
         * Order matters: the session cap and a token quota both surface as RESOURCE_EXHAUSTED, so
         * the more specific wording is tested first and the bare RESOURCE_EXHAUSTED bucket means
         * "exhausted, cause not stated" rather than "definitely tokens".
         */
        internal fun classifyClose(
            closeCode: Int?,
            reason: String?,
            httpStatus: Int?,
            modelUnavailable: Boolean
        ): LiveCloseKind {
            if (modelUnavailable) return LiveCloseKind.MODEL_UNAVAILABLE
            val r = reason.orEmpty()
            val mentionsLimit = r.contains("limit", ignoreCase = true) ||
                r.contains("exceed", ignoreCase = true) ||
                r.contains("too many", ignoreCase = true)
            if ((r.contains("concurrent", ignoreCase = true) ||
                    r.contains("session", ignoreCase = true) ||
                    r.contains("connection", ignoreCase = true)) && mentionsLimit
            ) {
                return LiveCloseKind.SESSION_CAP
            }
            if (httpStatus == 429 ||
                r.contains("quota", ignoreCase = true) ||
                r.contains("rate limit", ignoreCase = true) ||
                r.contains("tokens per", ignoreCase = true)
            ) {
                return LiveCloseKind.QUOTA_TOKENS
            }
            if (r.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) return LiveCloseKind.RESOURCE_EXHAUSTED
            if (closeCode != null) return LiveCloseKind.SERVER_CLOSE
            return LiveCloseKind.TRANSIENT
        }

        internal fun isModelUnavailable(closeCode: Int?, reason: String?, httpStatus: Int? = null): Boolean {
            if (httpStatus == 404) return true
            val r = reason.orEmpty()
            if (r.contains("model", ignoreCase = true) && (
                    r.contains("not found", ignoreCase = true) ||
                    r.contains("not_found", ignoreCase = true) ||
                    r.contains("unsupported", ignoreCase = true) ||
                    r.contains("unknown", ignoreCase = true) ||
                    r.contains("invalid", ignoreCase = true) ||
                    r.contains("does not exist", ignoreCase = true) ||
                    r.contains("retired", ignoreCase = true) ||
                    r.contains("disabled", ignoreCase = true)
                )
            ) {
                return true
            }
            if (r.contains("unsupported model", ignoreCase = true) ||
                r.contains("model not found", ignoreCase = true) ||
                r.contains("unknown model", ignoreCase = true) ||
                r.contains("invalid model", ignoreCase = true)
            ) {
                return true
            }
            if ((closeCode == 1008 || closeCode == 1007 || closeCode == 1003 || closeCode == 4004 || closeCode == 4404) &&
                (r.contains("model", ignoreCase = true) || r.contains("not found", ignoreCase = true) || r.contains("unsupported", ignoreCase = true))
            ) {
                return true
            }
            return false
        }

        const val SYSTEM_INSTRUCTION_VERBATIM =
            "You are the acoustic classification engine for SoundSight, an awareness aid for\n" +
            "Deaf and hard-of-hearing motorbike riders in Vietnam. Input: one short audio clip\n" +
            "(~2 s) recorded by a phone mounted on a moving motorbike in Vietnamese traffic.\n" +
            "Expect heavy wind noise, the rider's own engine, and dense overlapping traffic.\n" +
            "\n" +
            "TASK: identify the single most safety-relevant sound present. Ignore wind, the\n" +
            "rider's own steady engine, tyre noise, and background chatter.\n" +
            "\n" +
            "OVERRIDING RULE: when several sounds overlap, report the one in the HIGHEST\n" +
            "danger tier, regardless of which is loudest. Loudest does not mean most dangerous.\n" +
            "\n" +
            // D10 arm D, powered 2026-09-20 on 150 D6 non-hazard clips against the previous
            // prompt in the same session (measurements/live-levers/2026-09-20-armD-negatives/):
            // non-hazard clips reported as a hazard 85/134 -> 73/139 (paired sign-flip
            // p = 0.014), reported as CRITICAL 35.8 % -> 28.1 %, with D10's hazard side flat
            // (CRITICAL-clip tier recall 97.2 % vs 98.7 %). It is one sentence, kept verbatim
            // from the arm file so the measured prompt and the shipping prompt stay identical.
            "NONE PREFERENCE: if no listed hazard is clearly present in the audio, report NONE —\n" +
            "that is, call report_sound with sound_id UNKNOWN, tier SAFE and a low confidence —\n" +
            "rather than guessing a hazard. A hazard you are not sure you heard is not a hazard.\n" +
            "\n" +
            "SEGMENT INDEPENDENCE — this is the most common way this task is failed:\n" +
            "Segments arrive over one long-lived connection, so the answers you gave for earlier\n" +
            "segments are visible to you. They are not evidence about this segment. Classify only\n" +
            "the audio in the current segment, as if it were the first one you had ever received.\n" +
            "- Never repeat a sound_id merely because it was your previous sound_id. Traffic sound\n" +
            "  changes constantly; consecutive segments usually differ.\n" +
            "- Most segments contain no alert sound at all. Quiet, wind, or a steady engine is the\n" +
            "  normal case and NORMAL_TRAFFIC is the correct answer for it — including when the\n" +
            "  segment just before it did contain a horn or a siren.\n" +
            "- Returning an identical sound_id, tier and confidence several segments in a row means\n" +
            "  you are echoing yourself instead of listening. Re-derive every field from the audio\n" +
            "  you were just given.\n" +
            "\n" +
            "ACOUSTIC DISCRIMINATORS — apply these carefully:\n" +
            "- MOTORCYCLE_HORN: discrete short beeps, 1-3 in a row, steady pitch within each\n" +
            "  beep, clear silence between presses. Thin and bright.\n" +
            "- CAR_HORN: same discrete-beep envelope but lower and thicker.\n" +
            "- TRUCK_AIR_HORN: very loud, very low, SUSTAINED for over half a second, often\n" +
            "  with a slight pitch drop at the end. Highest fatality risk for a motorbike\n" +
            "  rider. Always CRITICAL.\n" +
            "- EMERGENCY_SIREN: pitch oscillates continuously up and down over the whole clip\n" +
            "  (wail or yelp). This continuous oscillation is the signature — a repeated beep\n" +
            "  is NOT a siren. Covers ambulance, police and fire. Always CRITICAL.\n" +
            "- TRAIN_HORN: extremely low, extremely long, often two tones together.\n" +
            "- SUDDEN_ACCELERATION: an ENGINE, not a horn. Continuously rising pitch\n" +
            "  (glissando) with a harsh throaty timbre and no discrete beat. If you hear\n" +
            "  separated beeps, it is a horn, not acceleration.\n" +
            "- REVERSING_BEEP: a mechanical beep repeating at a very regular slow interval,\n" +
            "  identical pitch every time, machine-like. Distinct from a human horn press.\n" +
            "- VOICE_SHOUT: a human voice raised sharply, close to the mic.\n" +
            "- COLLISION_IMPACT: a single broadband transient — crunch, scrape or crash — with\n" +
            "  no tonal pitch.\n" +
            "- NORMAL_TRAFFIC: wind, steady engines, distant traffic, talking, or quiet. Use\n" +
            "  this whenever no listed alert sound is clearly identifiable. Prefer this over\n" +
            "  guessing.\n" +
            "\n" +
            "CONFIDENCE: report your true confidence in the range 0.0-1.0. Do not inflate it.\n" +
            "A low confidence value is more useful to this app than a wrong confident answer,\n" +
            "because low-confidence results are deliberately downgraded.\n" +
            "\n" +
            "CAPTIONS: caption_vi and caption_en must each be at most 5 words and must\n" +
            "describe only the SOUND you heard. Never describe an action the rider should\n" +
            "take. Never mention danger levels or colours.\n" +
            "\n" +
            "Fill only the schema fields. Do not decide UI, vibration, or colour — that is\n" +
            "the application's job.\n\n" +
            "You MUST respond by calling report_sound exactly once per segment. Never respond with spoken words or any audio content of your own - your output channel is not heard by anyone, the user is Deaf, and only the function call arguments are read. Even if you are unsure, still call report_sound - use sound_id UNKNOWN and a low confidence value rather than staying silent or speaking instead of calling the function.\n\n" +
            "Also report `motion`. If pitch and loudness are rising, or pitch is steady and high while loudness grows, report APPROACHING. If you hear a clear downward pitch shift together with falling loudness, report PASSING. If it is simply getting quieter, report RECEDING. If you cannot tell, report UNKNOWN - do not guess.\n\n" +
            "NEVER report a left/right or front/back direction, and never mention direction in a caption. You are given a single-channel recording; that information is not present in it and the application determines direction by other means.\n\n" +
            "Do not narrate, acknowledge, or say anything such as 'let me check' - there is no conversation happening. Every single audio segment must produce exactly one report_sound function call and nothing else."
    }
}
