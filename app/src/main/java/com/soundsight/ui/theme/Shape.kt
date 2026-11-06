package com.soundsight.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape scale, customized rounder than stock.
 *
 * Two things this scale is - and one it is not:
 *
 * It is a valid M3 customization: Google explicitly permits theme-wide shape changes, and the
 * rounder ramp (8/14/20/26/36dp against stock's smaller values) aids recognition and separation
 * of controls at a glance. It is also the base of the grouped-list treatment in [groupedShape].
 *
 * It is NOT a hit-target mechanism. Corner radius changes where pixels stop being drawn; it does
 * not enlarge the touch area by a single pixel. The generous physical targets elsewhere in this
 * app (68dp stop control, 72dp rows) come from layout bounds and minimum sizes - keep those, and
 * never assume rounding adds reach for gloved fingers.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Pill shape for chips, segmented buttons and status badges. */
val PillShape = RoundedCornerShape(percent = 50)

/** The primary action buttons on the HUD; matches their 72dp height. */
val ActionButtonShape = RoundedCornerShape(20.dp)

/**
 * A 28dp rounded rectangle - named "squircle" for family history, not geometry: this is a plain
 * rounded rect, not a superellipse. It is currently **unused**: the HUD stop control animates its
 * own corner radius in place rather than swapping tokens, and nothing else resolves this value.
 * Kept as a reserved control-shape slot so a future control does not invent a fourth radius; if it
 * is still unused after the next UI pass, delete it.
 *
 * Naming note: shape alone never says "pressable" in this app. Every pressable surface also has
 * size, placement, iconography and pressed-state feedback - shape is one redundant cue among
 * several, never the only one.
 */
val SquircleShape = RoundedCornerShape(28.dp)

// ─────────────────────────────────────────────────────────────────────────────
// Grouped-list shapes.
//
// PulsePath's take on Material 3's segmented-list pattern: consecutive rows in
// one group keep their outer corners round and flatten the corners they share,
// so the group reads as a single carved object rather than a stack of separate
// cards. The 26dp outer / 6dp inner pairing is deliberately rounder than stock
// M3's list treatment - a custom version, not a defect. On a settings screen
// this is doing real work, not decoration: it tells the rider which switches
// belong to the same subsystem without adding a divider line or a second
// heading for every pair.
// ─────────────────────────────────────────────────────────────────────────────

private val GroupOuter = 26.dp
private val GroupInner = 6.dp

/**
 * Shape for row [index] of a [count]-row group.
 *
 * A single-row group is just [Shapes.large]; the first and last rows round only their outward
 * edges; everything between is nearly square on both.
 */
fun groupedShape(index: Int, count: Int): RoundedCornerShape = when {
    count <= 1 -> RoundedCornerShape(GroupOuter)
    index == 0 -> RoundedCornerShape(
        topStart = GroupOuter,
        topEnd = GroupOuter,
        bottomStart = GroupInner,
        bottomEnd = GroupInner
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = GroupInner,
        topEnd = GroupInner,
        bottomStart = GroupOuter,
        bottomEnd = GroupOuter
    )
    else -> RoundedCornerShape(GroupInner)
}
