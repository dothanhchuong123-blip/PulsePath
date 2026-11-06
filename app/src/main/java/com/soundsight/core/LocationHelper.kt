package com.soundsight.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Last-known-location lookup for the post-collision message.
 *
 * Deliberately does **not** request a fresh fix. After an impact the phone may have seconds of
 * usable attention and no clear sky; waiting on a GPS lock would put a spinner in front of the
 * rider at the worst possible moment, and a lock that never comes would put it there forever. A
 * cached fix a minute old is close enough to send someone to, and the message says how old it is
 * so whoever reads it can judge.
 *
 * Uses the framework [LocationManager] rather than the Play Services fused provider so the feature
 * adds no Gradle dependency - `play-services-location` is deliberately absent from this project.
 *
 * Location is read only at the moment the rider taps "send my location", never in the background
 * and never on a schedule.
 */
object LocationHelper {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best cached fix across all enabled providers, or null. Never throws.
     *
     * "Best" prefers a newer fix, but not one that is dramatically less accurate - a fresh
     * network fix good to 2km is worse for finding a crashed rider than a GPS fix from two
     * minutes ago good to 10m.
     */
    fun lastKnownLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        var best: Location? = null
        val providers = try {
            lm.getProviders(true)
        } catch (_: Exception) {
            emptyList<String>()
        }
        for (provider in providers) {
            val candidate = try {
                lm.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            } ?: continue
            best = pickBetter(best, candidate)
        }
        return best
    }

    /** Extracted so the newer-versus-more-accurate trade-off can be unit tested without a device. */
    fun pickBetter(current: Location?, candidate: Location): Location {
        if (current == null) return candidate
        val newer = candidate.time > current.time
        // Accept a newer fix unless it is far less accurate than what we already have. An
        // accuracy of 0 means "unknown" on some providers, so treat it as no better.
        val notMuchWorse = candidate.accuracy > 0f &&
            (current.accuracy <= 0f || candidate.accuracy <= current.accuracy * 2f)
        return if (newer && notMuchWorse) candidate else current
    }

    /** A plain https maps link, readable as text and openable in any messaging app. */
    fun mapsLink(location: Location): String = String.format(
        Locale.US,
        "https://maps.google.com/?q=%.6f,%.6f",
        location.latitude,
        location.longitude
    )

    /** Minutes since the fix was taken, for "recorded N minutes ago" in the message. */
    fun ageMinutes(location: Location): Long =
        ((System.currentTimeMillis() - location.time).coerceAtLeast(0L)) / 60000L

    /**
     * One fresh fix with a short timeout, for the post-collision message when the rider has
     * opted into a location mode that allows it (COLLISION_ONLY / ALWAYS). Falls back to
     * [lastKnownLocation] when the fix does not arrive in time - a spinner must never stand
     * between a crashed rider and the send button, so the timeout is short and the fallback is
     * the same cached fix today's behaviour uses. Never throws; returns null without permission.
     */
    suspend fun freshFix(context: Context, timeoutMs: Long = 3_000L): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = LocationListener { location ->
                    if (cont.isActive) cont.resume(location)
                }
                try {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    try {
                        lm.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return fresh ?: lastKnownLocation(context)
    }
}
