package com.soundsight.data.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.soundsight.audio.AudioClip
import com.soundsight.data.SoundClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Production-facing singleton. Owns exactly one [LiveSessionCoordinator] instance for the
 * process lifetime and delegates every call to it. All the actual connection/reconnect/flight
 * logic lives in [LiveSessionCoordinator], which is unit-testable in isolation with fakes; this
 * object only wires it up with real Android/network dependencies.
 */
object LiveSession {
    // FIX 6: Relaxed ping interval from 10s to 45s to avoid tear-down during transient mobile data stalls
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var appContext: Context? = null

    private fun isDeviceOnline(): Boolean {
        val ctx = appContext ?: return true // can't check yet; don't block reconnects on it
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private val coordinator: LiveSessionCoordinator by lazy {
        LiveSessionCoordinator(
            webSocketFactory = LiveWebSocketFactory { request, listener -> client.newWebSocket(request, listener) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            restFallback = { clip ->
                val ctx = appContext
                if (ctx == null) {
                    Result.failure(IllegalStateException("No context available for REST fallback"))
                } else {
                    SoundClassifier.classify(ctx, clip)
                }
            },
            apiKeyProvider = {
                val ctx = appContext
                if (ctx != null) {
                    UserKeyCredentialProvider.getInstance(ctx).getApiKey()
                } else {
                    BuildConfigCredentialProvider.getApiKey()
                }
            },
            connectivityChecker = ::isDeviceOnline
        )
    }

    val liveState get() = coordinator.liveState
    val lastCloseDiagnostic get() = coordinator.lastCloseDiagnostic
    val events get() = coordinator.events
    val openSocketCount get() = coordinator.openSocketCount
    val promptTokensTotal get() = coordinator.promptTokensTotal
    val responseTokensTotal get() = coordinator.responseTokensTotal
    val totalTokensRunning get() = coordinator.totalTokensRunning
    val handoverInProgress get() = coordinator.handoverInProgressState
    val sessionRecycleCount get() = coordinator.sessionRecycleCount
    val activeConnectionId get() = coordinator.activeConnectionId
    val reconnectAttempt get() = coordinator.reconnectAttempt
    val reconnectDelayMs get() = coordinator.reconnectDelayMs
    val lastFailureKind get() = coordinator.lastFailureKind
    val goAwayTimeLeftMs get() = coordinator.goAwayTimeLeftMs
    val resumable get() = coordinator.resumable
    val resumptionHandleSetAtMs get() = coordinator.resumptionHandleSetAtMs
    val audioBytesSentTotal get() = coordinator.audioBytesSentTotal
    val audioMsSentTotal get() = coordinator.audioMsSentTotal
    val flightTimeoutCount get() = coordinator.flightTimeoutCount
    val disconnectFallbackCount get() = coordinator.disconnectFallbackCount
    val lateResponseCount get() = coordinator.lateResponseCount
    val standaloneTestBlocked get() = coordinator.standaloneTestBlocked

    // Per-turn quota forensics - see LiveSessionCoordinator's "Per-turn token forensics" block.
    val lastTurnUsage get() = coordinator.lastTurnUsage
    val turnUsageHistory get() = coordinator.turnUsageHistory
    val turnsThisConnection get() = coordinator.turnsThisConnection
    val promptTokensThisConnection get() = coordinator.promptTokensThisConnection
    val promptSlopePerTurn get() = coordinator.promptSlopePerTurn
    val lastCloseUsage get() = coordinator.lastCloseUsage
    val closeKindCounts get() = coordinator.closeKindCounts
    val lastCloseKind get() = coordinator.lastCloseKind
    val emptyUsageReports get() = coordinator.emptyUsageReports

    fun start(context: Context) {
        appContext = context.applicationContext
        coordinator.start()
    }

    fun stop() {
        coordinator.stop()
    }

    /**
     * Reconnects the Live WebSocket session, e.g. after the API key is updated or cleared.
     * Stops the current session (dropping any stale resumption handle) and restarts if
     * the background monitoring service is currently active.
     */
    fun reconnect(context: Context) {
        appContext = context.applicationContext
        stop()
        if (com.soundsight.audio.ListenerService.isRunning.value) {
            start(context)
        }
    }

    /**
     * Forwarded from the app's ConnectivityManager callback. See
     * [LiveSessionCoordinator.onNetworkAvailable] for why an explicit nudge is needed rather than
     * letting the backoff ladder discover the network on its own.
     */
    fun onNetworkAvailable() {
        coordinator.onNetworkAvailable()
    }

    fun sendClip(clip: AudioClip): Boolean = coordinator.sendClip(clip)

    /**
     * Executes a single standalone test Live call for diagnostics. Refuses to open a second
     * socket while the persistent monitoring session owned by [start]/[stop] is active.
     */
    suspend fun testLiveCall(context: Context, clip: AudioClip): Result<LiveClassificationEvent> {
        if (appContext == null) appContext = context.applicationContext
        return coordinator.testLiveCall(clip)
    }
}
