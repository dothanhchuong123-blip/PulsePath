package com.soundsight.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundsight.core.AppLanguage
import com.soundsight.ui.theme.Motion
import com.soundsight.ui.theme.PillShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The language control: a globe that spins between the two languages' own halves of the world.
 *
 * Replaces the settings list's language card. The card spent a full row explaining a binary the
 * app can show in the corner of the bar, and - more to the point - a rider who has just put the
 * app into a language they cannot read needs the way back to be *visible*, not something to be
 * found by scrolling a list written in that language. In the app bar it is on screen the moment
 * Settings opens, in both directions.
 *
 * ## Why a real projection rather than a swap
 *
 * The obvious cheap build is two flat icons cross-faded behind a horizontal squeeze. This draws
 * the continents as latitude/longitude polygons through an actual orthographic projection instead,
 * for one reason worth the ~40 lines: the squeeze version has to *decide* when to swap, and every
 * choice of moment looks like a cut. Projected, the landmasses leave and arrive round the limb on
 * their own, so the same press reads as one object turning rather than two pictures alternating.
 * The cost is four `Landmass` polygons of pre-resolved sines and four multiplies per vertex per
 * frame - cheaper than the two vector assets it replaces.
 *
 * The globe carries a `VI`/`EN` badge and is never shipped bare. A globe on its own is the icon
 * for the *category* "language"; it says a language can be chosen, not which one is in force. The
 * badge names the language that is currently active - not the one a press would switch to, which
 * is the ambiguity that makes unlabelled toggles guesswork.
 *
 * Not a hazard element, so unlike an alert glyph this one does follow the dynamic colour scheme -
 * the same rule that lets the idle HUD chip take `primary`.
 */

// ── Geometry ─────────────────────────────────────────────────────────────────

private const val DegToRad = (PI / 180.0).toFloat()

/**
 * How far the north pole leans towards the viewer.
 *
 * Zero tilt projects the equator to a flat line and the whole thing reads as a disc with a stripe.
 * A small lean is what makes it read as a sphere at 26dp.
 */
private const val TiltDeg = 18f

/** Longitude sitting at the centre of the disc for each language. */
private const val FacingAmericas = -90f   // the middle of the Americas
private const val FacingVietnam = 106f    // Vietnam's own meridian, not a generic "Asia"

/**
 * A landmass as (latitude, longitude) degrees, with the trigonometry resolved once at class load.
 *
 * Per frame a vertex then costs four multiplies and two adds (the angle-addition identities in
 * [Projector]) rather than two `sin`/`cos` calls, which matters because this redraws on every
 * frame of the spin.
 */
@Immutable
private class Landmass(latLonDeg: FloatArray) {
    val size = latLonDeg.size / 2
    val cosLat = FloatArray(size)
    val sinLat = FloatArray(size)
    val cosLon = FloatArray(size)
    val sinLon = FloatArray(size)

    init {
        for (i in 0 until size) {
            val lat = latLonDeg[i * 2] * DegToRad
            val lon = latLonDeg[i * 2 + 1] * DegToRad
            cosLat[i] = cos(lat); sinLat[i] = sin(lat)
            cosLon[i] = cos(lon); sinLon[i] = sin(lon)
        }
    }
}

/**
 * Continents at icon resolution: a dozen points each, drawn as smooth closed curves.
 *
 * These are silhouettes, not maps. Every vertex is a corner the shape is *recognised* by - the
 * Alaskan shoulder, the taper to Cape Horn, the Indian and Arabian wedges, Cape York - and
 * everything between them is a curve. The earlier version of this file traced real coastlines at
 * three times the vertex count and it was the wrong instinct twice over: at 26dp the extra detail
 * resolved to a serrated edge rather than to geography, and a serrated edge is not what a Material
 * icon looks like. Panama, the Malay peninsula and every island are gone for the same reason -
 * below about 6dp of drawn width a feature stops being a feature and becomes a burr.
 *
 * North and South America are two shapes rather than one. The isthmus joining them is thinner than
 * the stroke that would draw it, so at this size two blobs is not a simplification of the Americas;
 * it *is* the Americas.
 *
 * Four landmasses, not seven. Europe and Africa were drawn and then cut: on the English face they
 * only ever grazed the right-hand limb, and on the Vietnamese face they fused with Asia across the
 * Middle East into one shape with no silhouette of its own. Without them each face is the same
 * composition - a large mass and a small one, in opposite corners - which is what makes the two
 * states tell apart at a glance rather than on inspection.
 */
private val NorthAmerica = Landmass(
    floatArrayOf(
        64f, -162f, 71f, -130f, 69f, -98f, 60f, -72f, 47f, -56f, 38f, -73f,
        29f, -81f, 22f, -97f, 16f, -95f, 21f, -106f, 31f, -115f, 42f, -124f, 55f, -136f
    )
)

private val SouthAmerica = Landmass(
    floatArrayOf(
        11f, -72f, 8f, -58f, 0f, -48f, -7f, -35f, -23f, -42f, -35f, -56f,
        -52f, -69f, -40f, -73f, -20f, -71f, -4f, -81f, 5f, -78f
    )
)

private val Asia = Landmass(
    floatArrayOf(
        38f, 30f, 58f, 40f, 72f, 70f, 73f, 105f, 66f, 140f, 58f, 162f,
        48f, 138f, 38f, 124f, 22f, 113f, 10f, 106f, 20f, 92f, 9f, 78f,
        24f, 64f, 13f, 50f, 24f, 36f
    )
)

private val Australia = Landmass(
    floatArrayOf(
        -12f, 131f, -14f, 143f, -25f, 153f, -37f, 150f, -35f, 137f, -33f, 120f,
        -22f, 114f, -15f, 124f
    )
)

private val Continents = arrayOf(NorthAmerica, SouthAmerica, Asia, Australia)

/**
 * Orthographic projection of the unit sphere, spun by a longitude and tilted towards the viewer.
 *
 * Writes into [x]/[y]/[z] rather than returning, so a whole coastline projects without allocating.
 * [z] is depth: positive is the near face. Callers need it for two things - deciding whether a
 * vertex is on the visible half at all, and fading a landmass out as its last vertex goes round
 * the back.
 */
private class Projector(facingLonDeg: Float) {
    // A point at longitude `facingLonDeg` must land dead centre, which puts it at rotation -facing.
    private val cosR = cos(-facingLonDeg * DegToRad)
    private val sinR = sin(-facingLonDeg * DegToRad)
    private val cosT = cos(TiltDeg * DegToRad)
    private val sinT = sin(TiltDeg * DegToRad)

    var x = 0f
    var y = 0f
    var z = 0f

    fun project(cosLat: Float, sinLat: Float, cosLon: Float, sinLon: Float) {
        val sinL = sinLon * cosR + cosLon * sinR
        val cosL = cosLon * cosR - sinLon * sinR
        val depth = cosLat * cosL
        x = cosLat * sinL
        // Screen y is negated here already: latitude grows upwards, pixels grow downwards.
        y = -(sinLat * cosT - depth * sinT)
        z = sinLat * sinT + depth * cosT
    }
}

/**
 * Draws the globe with [facingLonDeg] at the centre of the disc.
 *
 * Split out of the composable so a test can render it at fixed angles: the spin is an
 * [Animatable] and the Compose test clock will only ever hand a capture one frame of it.
 *
 * Two shapes and two colours, and no meridian wireframe. A drawn graticule was tried and cut: over
 * the ocean it was clutter at icon size, and over a continent it read as a cut through the shape
 * rather than a line on it - on the Vietnamese face it delivered Eurasia in three pieces. What
 * says "sphere" here is the tilt and the way land arrives and leaves round the limb, which is
 * motion the wireframe was not adding to.
 *
 * Hidden vertices are pushed radially out to the silhouette rather than dropped. That is the
 * correct clip - the silhouette *is* the disc's outline under orthographic projection - and it is
 * continuous, because a vertex reaching the terminator has already arrived at the rim by the time
 * it turns invisible. Dropping vertices instead makes a coastline visibly shed corners as it goes
 * round the back.
 */
internal fun DrawScope.drawGlobe(
    facingLonDeg: Float,
    center: Offset,
    radius: Float,
    ocean: Color,
    land: Color
) {
    val p = Projector(facingLonDeg)

    drawCircle(color = ocean, radius = radius, center = center)

    val xs = FloatArray(Continents.maxOf { it.size })
    val ys = FloatArray(xs.size)

    for (continent in Continents) {
        val n = continent.size
        // Fade a landmass out as it turns away, and well before it is actually gone. Without this
        // it collapses onto the rim as a hard-edged crescent - every vertex clamped to the same
        // arc - and then vanishes in one frame. The threshold is deliberately generous: a shape
        // still technically on the near face but seen almost edge-on has no silhouette left to
        // recognise, so it should be a ghost arriving rather than a bright sliver stuck to the
        // edge.
        var nearest = -1f
        for (i in 0 until n) {
            p.project(continent.cosLat[i], continent.sinLat[i], continent.cosLon[i], continent.sinLon[i])
            if (p.z > nearest) nearest = p.z
            var px = p.x
            var py = p.y
            if (p.z <= 0f) {
                val len = hypot(px, py)
                if (len > 1e-4f) { px /= len; py /= len } else { px = 0f; py = 1f }
            }
            xs[i] = center.x + px * radius
            ys[i] = center.y + py * radius
        }
        // The ramp starts above zero, not at it: a shape seen edge-on is still technically on the
        // near face, and drawing it puts a crisp hairline crescent on the rim that reads as a
        // rendering seam. Reaching zero early keeps the arrival smooth and the limb clean.
        val alpha = ((nearest - 0.08f) / 0.28f).coerceIn(0f, 1f)
        if (alpha <= 0f) continue

        // Each vertex becomes the control point of a quadratic through the midpoints either side
        // of it, which is what turns a dozen coordinates into a coastline instead of a polygon.
        val path = Path()
        path.moveTo((xs[n - 1] + xs[0]) / 2f, (ys[n - 1] + ys[0]) / 2f)
        for (i in 0 until n) {
            val next = (i + 1) % n
            path.quadraticTo(xs[i], ys[i], (xs[i] + xs[next]) / 2f, (ys[i] + ys[next]) / 2f)
        }
        path.close()
        drawPath(path, color = land, alpha = alpha)
    }
}

// ── Composables ──────────────────────────────────────────────────────────────

/** The globe on its own, at a fixed angle. The toggle animates [facingLonDeg]; tests pin it. */
@Composable
internal fun GlobeGlyph(
    facingLonDeg: Float,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        drawGlobe(
            facingLonDeg = facingLonDeg,
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.minDimension / 2f,
            ocean = primary.copy(alpha = 0.20f),
            land = primary
        )
    }
}

private fun facingFor(language: AppLanguage): Float =
    if (language == AppLanguage.EN) FacingAmericas else FacingVietnam

/**
 * The app bar's language action: spin the globe, swap the badge.
 *
 * [onToggle] is handed the language being switched *to* rather than a boolean, so the call site
 * reads as the change it is about to persist.
 */
@Composable
internal fun GlobeLanguageToggle(
    language: AppLanguage,
    contentDescription: String,
    onToggle: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    // One value, two targets, so the return trip is automatically the outward trip run backwards -
    // a press always unwinds the spin the press before it wound up.
    val facing = remember { Animatable(facingFor(language)) }
    LaunchedEffect(language) {
        facing.animateTo(facingFor(language), Motion.defaultSpatial())
    }

    Row(
        modifier = modifier
            // Invisible bounds, not a bigger drawing: the globe illustration stays at its authored
            // 26dp, but the *interactive* area meets the 48dp minimum so the control is usable in
            // gloves and by the accessibility contract every pressable surface here follows.
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button) {
                onToggle(if (language == AppLanguage.EN) AppLanguage.VI else AppLanguage.EN)
            }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .testTag("settings_language_globe"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GlobeGlyph(
            facingLonDeg = facing.value,
            modifier = Modifier.size(26.dp)
        )
        AnimatedContent(
            targetState = language,
            transitionSpec = {
                // Scale, not crossfade - the same swap the wave-style and clock-style switches use.
                (scaleIn(Motion.defaultSpatial()) + fadeIn(Motion.fastEffects()))
                    .togetherWith(scaleOut(Motion.defaultSpatial()) + fadeOut(Motion.fastEffects()))
            },
            label = "lang_badge"
        ) { current ->
            Text(
                text = if (current == AppLanguage.EN) "EN" else "VI",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("settings_language_code")
            )
        }
    }
}
