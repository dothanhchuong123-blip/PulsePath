package com.soundsight.ui.settings.illustration

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import com.soundsight.ui.theme.LocalHazardColors
import kotlin.math.sin

/**
 * Looping spot illustration for the Collision Detection setting: the phone rides in its bar mount,
 * a vehicle clips the bike, the phone hits the road, and the camera pushes in as the collision
 * alert builds on its screen.
 *
 * Ported from the "Bike crash phone animation" Claude Design project
 * (`Collision Detected Animation copy.dc.html` / `crash-scene.jsx`). The design file's 1200x900
 * stage coordinates and per-property easing are kept verbatim - see [IllustrationSupport] - so the
 * two stay diffable.
 *
 * The alert red and the impact accent are pulled from [LocalHazardColors] rather than the design
 * file's literals, because this drawing is depicting *this app's own alert*: if the takeover's red
 * ever changes, the illustration of it must change with it.
 */

private const val W = 1200f

/**
 * The camera's viewport, matching the design file exactly: a 1200x900 frame opening at 1.85x.
 *
 * An earlier port widened this to 1600x900 at 1.55x so that nothing would be cropped by a short,
 * wide settings card. That was the wrong trade - it pulled the lens back until the bike was a small
 * object in a lot of empty road. The framing is part of the illustration, so it stays as authored
 * and the *card* is sized to suit it instead (see IllustrationPanel): tall enough that the crop
 * only trims a sliver off each side, leaving the front half of the bike filling the frame.
 */
private const val VIEW_W = 1200f
private const val VIEW_H = 900f
private const val BASE_ZOOM = 1.85f

private const val GROUND = 690f
private const val HUB_X = 430f
private const val HUB_Y = 572f
private const val WHEEL_R = 118f
private const val MOUNT_X = 640f
private const val MOUNT_Y = 296f
private const val LAND_X = 780f
private const val LAND_Y = 663f
private const val HIT_X = 492f
private const val HIT_Y = 548f

// Scene cues in seconds, accumulated from the design file's OM_SCENES durations:
// Ride 1.0, Impact 0.9, Zoom 0.8, Alert 1.6, Reset 0.5.
private const val CUE_IMPACT = 1.0f
private const val CUE_ZOOM = 1.9f
private const val CUE_RESET = 4.3f
internal const val COLLISION_LOOP_SECONDS = 4.8f

private class CrashPalette(
    val bg: Color,
    val ground: Color,
    val body: Color,
    val bodyDeep: Color,
    val phone: Color,
    val screen: Color,
    val streak: Color,
    val accent: Color,
    val far: Color,
    val farDeep: Color,
    val glass: Color,
    val red: Color,
    val onRed: Color
)

@Composable
fun CollisionIllustration(modifier: Modifier = Modifier) {
    val dark = illustrationIsDark()
    val hazard = LocalHazardColors.current
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh

    val palette = remember(dark, hazard, surface) {
        if (dark) {
            CrashPalette(
                bg = surface,
                // Lighter than the design file's #2B2930: that value was picked against its own
                // near-black stage, and on an M3 surfaceContainerHigh card the road disappeared
                // into the background, leaving the bike floating.
                ground = Color(0xFF433F4B),
                body = Color(0xFF8E8797),
                bodyDeep = Color(0xFF6F6779),
                phone = Color(0xFFE6E0E9),
                screen = Color(0xFF1B1920),
                streak = Color(0xFF3B3841),
                accent = hazard.Warning,
                far = Color(0xFF332F3A),
                farDeep = Color(0xFF413C49),
                glass = Color(0xFF221F27),
                red = hazard.Critical,
                onRed = hazard.OnCritical
            )
        } else {
            CrashPalette(
                bg = surface,
                ground = Color(0xFFE7E0EC),
                body = Color(0xFFB9B4C0),
                bodyDeep = Color(0xFF9A94A4),
                phone = Color(0xFFFFFFFF),
                screen = Color(0xFFF1ECF4),
                streak = Color(0xFFDED8E6),
                accent = hazard.Warning,
                far = Color(0xFFD3CBDE),
                farDeep = Color(0xFFC1B8CF),
                glass = Color(0xFFEAE4F0),
                red = hazard.Critical,
                onRed = hazard.OnCritical
            )
        }
    }

    val t by rememberLoopClock(COLLISION_LOOP_SECONDS)

    Canvas(modifier = modifier.testTag("illustration_collision")) {
        drawCollisionFrame(t, palette)
    }
}

private fun DrawScope.drawCollisionFrame(t: Float, c: CrashPalette) {
    val tOff = CUE_IMPACT + 0.14f
    val tLand = CUE_IMPACT + 0.6f

    val bob = sin(t * 9f) * 4f * (1f - clamp01((t - CUE_IMPACT) / 0.3f))
    var bikeRot = anim(t, 0f, 26f, CUE_IMPACT + 0.06f, CUE_IMPACT + 0.68f, Ease.InQuad)
    var sceneOp = 1f - anim(t, 0f, 1f, CUE_ZOOM, CUE_ZOOM + 0.45f, Ease.OutCubic)
    var streakOp = 1f - clamp01((t - (CUE_IMPACT - 0.15f)) / 0.25f)

    // Phone flight: thrown out of the mount, then one damped bounce off the tarmac.
    val e = clamp01((t - tLand) / 0.32f)
    val bounce = if (t > tLand) -26f * sin(e * PI_F) * (1f - e) else 0f
    var px = anim(t, MOUNT_X, LAND_X, tOff, tLand, Ease.OutSine)
    var py = anim(t, MOUNT_Y, LAND_Y, tOff, tLand, Ease.InQuad) + bounce
    var prot = anim(t, 78f, 270f, tOff, tLand + 0.04f, Ease.OutCubic) +
        anim(t, 0f, 90f, CUE_ZOOM + 0.05f, CUE_ZOOM + 0.62f, Ease.InOutCubic)
    val squash = if (t > tLand) 1f - 0.16f * sin(e * PI_F) * (1f - e * 0.4f) else 1f
    var pscale = anim(t, 0.5f, 1f, CUE_ZOOM, CUE_ZOOM + 0.7f, Ease.InOutCubic)

    // Oncoming vehicle, back layer: closes in until the impact, then carries on out of frame.
    var carX = anim(t, 1300f, 470f, 0f, CUE_IMPACT) -
        anim(t, 0f, 1160f, CUE_IMPACT, CUE_ZOOM + 0.45f)
    val carDip = if (t > CUE_IMPACT && t < CUE_IMPACT + 0.5f) {
        sin(clamp01((t - CUE_IMPACT) / 0.5f) * PI_F) * 9f
    } else {
        0f
    }

    val hitK = clamp01((t - CUE_IMPACT) / 0.42f)
    val hitOn = if (t > CUE_IMPACT && hitK < 1f) 1f else 0f
    val burst = clamp01((t - tLand) / 0.4f)

    // Camera: pushes in on the fallen phone once the bike is out of the way.
    var s = anim(t, BASE_ZOOM, 4.2f, CUE_ZOOM, CUE_ZOOM + 0.8f, Ease.InOutCubic)
    var fx = anim(t, 624f, LAND_X, CUE_ZOOM, CUE_ZOOM + 0.55f, Ease.OutCubic)
    var fy = anim(t, 474f, LAND_Y - 6f, CUE_ZOOM, CUE_ZOOM + 0.5f, Ease.OutCubic)
    val shakeK = clamp01((t - tLand) / 0.24f)
    val shake = if (t > tLand && shakeK < 1f) sin(shakeK * 26f) * 7f * (1f - shakeK) else 0f

    var alertK = clamp01((t - (CUE_ZOOM + 0.45f)) / 1.05f)

    // Final scene eases every value back to the opening frame so the loop has no visible seam.
    val rk = anim(t, 0f, 1f, CUE_RESET, COLLISION_LOOP_SECONDS, Ease.InOutCubic)
    if (rk > 0f) {
        bikeRot = lerp(bikeRot, 0f, rk)
        sceneOp = lerp(sceneOp, 1f, rk)
        streakOp = lerp(streakOp, 1f, rk)
        px = lerp(px, MOUNT_X, rk)
        py = lerp(py, MOUNT_Y, rk)
        prot = lerp(prot, 438f, rk)
        pscale = lerp(pscale, 0.5f, rk)
        carX = lerp(carX, 1300f, rk)
        s = lerp(s, BASE_ZOOM, rk)
        fx = lerp(fx, 624f, rk)
        fy = lerp(fy, 474f, rk)
        alertK = lerp(alertK, 0f, clamp01(rk * 2.4f))
    }

    val shove = anim(t, 0f, -34f, CUE_IMPACT, CUE_IMPACT + 0.45f, Ease.OutCubic) * (1f - rk)

    drawRect(c.bg)

    stage(srcX = 0f, srcY = 0f, srcW = VIEW_W, srcH = VIEW_H) {
        withTransform({
            translate(VIEW_W / 2f - fx * s, VIEW_H / 2f - fy * s + shake)
            scale(s, s, Offset.Zero)
        }) {
            slab(-200f, GROUND, W + 400f, 26f, c.ground, radius = 13f, alpha = sceneOp)

            // Speed streaks, drifting right to left. They fade out just before the impact so the
            // scene reads as "everything stopped" rather than "the phone fell off a moving bike".
            listOf(300f to 150f, 420f to 210f, 520f to 120f)
                .forEachIndexed { i, (y, w) ->
                    val drift = wrap(t * 520f + i * 190f, 640f)
                    slab(
                        x = 1080f - drift,
                        y = y,
                        w = w,
                        h = 12f,
                        color = c.streak,
                        radius = 6f,
                        alpha = sceneOp * streakOp * 0.9f * sin(clamp01(drift / 640f) * PI_F)
                    )
                }

            drawCar(carX, GROUND - 252f + carDip, c, sceneOp * 0.62f)

            // Wake behind the vehicle.
            for (i in 0..2) {
                slab(
                    x = carX + 500f + wrap(t * 620f + i * 150f, 300f),
                    y = GROUND - 200f + i * 56f,
                    w = 90f + i * 40f,
                    h = 10f,
                    color = c.far,
                    radius = 5f,
                    alpha = sceneOp * streakOp * 0.45f
                )
            }

            withTransform({
                translate(80f + shove, bob)
                rotate(bikeRot, Offset(HUB_X, GROUND))
            }) {
                drawBike(c, sceneOp)
            }

            drawHitBurst(hitK, hitOn * (1f - hitK) * sceneOp, c)

            // Impact ring and shards where the phone struck the road.
            if (burst > 0f) {
                val ringScale = 0.3f + burst * 1.5f
                ringD(
                    cx = LAND_X,
                    cy = GROUND + 10f,
                    diameter = 140f * ringScale,
                    border = 6f * ringScale,
                    color = c.accent,
                    alpha = (1f - burst) * sceneOp
                )
            }
            if (t > tLand) {
                listOf(-40f, -12f, 16f, 44f).forEachIndexed { i, a ->
                    bar(
                        cx = LAND_X - 10f + sin((a / 60f) * 1.5f) * (60f + burst * 130f),
                        cy = GROUND - 40f - burst * (70f + i * 14f),
                        w = 26f,
                        h = 8f,
                        color = c.accent,
                        rotation = a,
                        alpha = (1f - burst) * sceneOp
                    )
                }
            }

            drawPhone(px, py, prot, pscale, squash, alertK, t, c)
        }
    }
}

/** Wheel pair, frame, handlebar and the mount cradle the phone sits in. */
private fun DrawScope.drawBike(c: CrashPalette, alpha: Float) {
    if (alpha <= 0.004f) return
    // 18px border, box-sizing border-box: the stroke centreline is 9 in from the rim.
    ringD(HUB_X, HUB_Y, WHEEL_R * 2f, 18f, c.body, alpha)
    disc(HUB_X, HUB_Y, 14f, c.bodyDeep, alpha)
    ringD(190f, HUB_Y, WHEEL_R * 2f, 18f, c.body, alpha)
    disc(190f, HUB_Y, 14f, c.bodyDeep, alpha)

    bar(310f, 572f, 250f, 16f, c.bodyDeep, alpha = alpha)
    bar(235f, 491f, 190f, 20f, c.bodyDeep, rotation = -60.9f, alpha = alpha)
    bar(381f, 447f, 216f, 18f, c.bodyDeep, rotation = 20.1f, alpha = alpha)
    bar(268f, 398f, 78f, 18f, c.body, alpha = alpha)
    bar(495f, 466f, 250f, 22f, c.bodyDeep, rotation = -58.5f, alpha = alpha)
    bar(566f, 352f, 212f, 20f, c.body, alpha = alpha)
    bar(646f, 352f, 60f, 28f, c.accent, alpha = alpha)
    bar(612f, 374f, 46f, 10f, c.bodyDeep, rotation = 20f, alpha = alpha)
    bar(566f, 342f, 16f, 30f, c.bodyDeep, radius = 8f, alpha = alpha)
    bar(578f, 330f, 96f, 15f, c.bodyDeep, radius = 8f, rotation = -12f, alpha = alpha)
    bar(620f, 320f, 11f, 24f, c.bodyDeep, radius = 6f, rotation = -12f, alpha = alpha)
}

private fun DrawScope.drawCar(x: Float, y: Float, c: CrashPalette, alpha: Float) {
    if (alpha <= 0.004f) return
    slabCorners(x + 104f, y, 262f, 118f, c.farDeep, 46f, 34f, 6f, 6f, alpha)
    slabCorners(x + 130f, y + 24f, 100f, 66f, c.glass, 28f, 8f, 4f, 4f, alpha)
    slabCorners(x + 244f, y + 24f, 98f, 66f, c.glass, 8f, 20f, 4f, 4f, alpha)
    slab(x, y + 96f, 470f, 116f, c.far, radius = 34f, alpha = alpha)
    slabCorners(x + 12f, y + 124f, 40f, 26f, c.accent, 11f, 6f, 6f, 11f, alpha * 0.9f)
    listOf(78f, 296f).forEach { wx ->
        // Background paints under the border box, so the hub reads as a cut-out of the backdrop.
        disc(x + wx + 50f, y + 206f, 50f, c.bg, alpha)
        ringD(x + wx + 50f, y + 206f, 100f, 20f, c.farDeep, alpha)
    }
}

/** Flash, expanding ring and eight spokes at the point the vehicle clips the bike. */
private fun DrawScope.drawHitBurst(hitK: Float, alpha: Float, c: CrashPalette) {
    if (alpha <= 0.004f) return
    disc(
        cx = HIT_X,
        cy = HIT_Y,
        r = 78f * (0.5f + hitK * 1.1f),
        color = c.accent,
        alpha = alpha * clamp01(1.6f - hitK * 4f)
    )
    val ringScale = 0.4f + hitK * 2.1f
    ringD(HIT_X, HIT_Y, 120f * ringScale, 10f * ringScale, c.accent, alpha)
    for (i in 0..7) {
        val angle = i * 45f + hitK * 12f
        val length = (60f + (i % 2) * 34f) * (1f - hitK * 0.55f)
        // transform-origin is 50% 0%, i.e. the top-centre of the spoke, which sits 7 above HIT.
        withTransform({ rotate(angle, Offset(HIT_X, HIT_Y - 7f)) }) {
            slab(
                x = HIT_X - 7f,
                y = HIT_Y - 7f + 34f + hitK * 108f,
                w = 14f,
                h = length,
                color = c.accent,
                radius = 7f,
                alpha = alpha
            )
        }
    }
}

private fun DrawScope.drawPhone(
    px: Float,
    py: Float,
    rotation: Float,
    scale: Float,
    squash: Float,
    alertK: Float,
    t: Float,
    c: CrashPalette
) {
    withTransform({
        translate(px, py)
        rotate(rotation, Offset.Zero)
        scale(scale / squash, scale * squash, Offset.Zero)
        translate(-54f, -98f)
    }) {
        slab(0f, 0f, 108f, 196f, c.phone, radius = 20f)
        slab(10f, 10f, 88f, 176f, c.screen, radius = 12f)
        if (alertK > 0f) {
            clipRect(10f, 10f, 98f, 186f) {
                withTransform({ translate(10f, 10f) }) {
                    drawAlertScreen(alertK, t, c)
                }
            }
        }
    }
}

/**
 * The collision alert building on the phone's own screen, in the screen's local 88x176 space.
 *
 * [k] is the reveal, not the clock: each element has its own window inside 0..1, so the mark, the
 * two text lines and the two buttons arrive in the order the real takeover shows them.
 */
private fun DrawScope.drawAlertScreen(k: Float, t: Float, c: CrashPalette) {
    drawRect(
        color = c.red,
        topLeft = Offset.Zero,
        size = Size(88f, 176f),
        alpha = clamp01(k * 6f)
    )

    val tri = anim(k, 0f, 1f, 0.02f, 0.42f, Ease.OutBack)
    val p1 = anim(k, 0f, 1f, 0.22f, 0.5f, Ease.OutCubic)
    val p2 = anim(k, 0f, 1f, 0.3f, 0.58f, Ease.OutCubic)
    val b1 = anim(k, 0f, 1f, 0.42f, 0.7f, Ease.OutCubic)
    val b2 = anim(k, 0f, 1f, 0.5f, 0.78f, Ease.OutCubic)
    val pulse = wrap(t * 0.85f, 1f)

    val ringScale = 0.7f + pulse * 0.9f
    ringD(44f, 44f, 54f * ringScale, 2f * ringScale, c.onRed, (1f - pulse) * 0.35f * tri)

    val markScale = tri.coerceIn(0f, 1.15f)
    withTransform({ scale(markScale, markScale, Offset(44f, 49.9f)) }) {
        val triangle = Path().apply {
            moveTo(44f, 25.48f)
            lineTo(65f, 61f)
            lineTo(23f, 61f)
            close()
        }
        drawPath(triangle, c.onRed, alpha = clamp01(tri))
        slab(42.5f, 39f, 3f, 10f, c.red, radius = 1.5f, alpha = clamp01(tri))
        disc(44f, 53f, 1.5f, c.red, clamp01(tri))
    }

    slab(14f, 74f, 60f, 6f, c.onRed, radius = 3f, alpha = 0.85f * p1)
    slab(24f, 86f, 40f, 6f, c.onRed, radius = 3f, alpha = 0.5f * p2)

    listOf(b1, b2).forEachIndexed { i, bk ->
        val top = 108f + i * 22f + (1f - bk) * 10f
        // The second button is the tonal one: a translucent white over the red, not a solid fill.
        val fill = if (i == 0) c.onRed else c.onRed.copy(alpha = 0.28f * clamp01(bk))
        slab(11f, top, 66f, 16f, fill, radius = 8f, alpha = if (i == 0) bk else 1f)
        disc(23f, top + 8f, 3f, if (i == 0) c.red else c.onRed, bk)
    }
}
