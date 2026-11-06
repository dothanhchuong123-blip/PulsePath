package com.soundsight.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundsight.ui.theme.PillShape
import kotlin.math.roundToInt

/**
 * A smooth Material 3 slider over named microphone-sensitivity stops.
 *
 * The handle follows the finger continuously. Crossing a stop updates the live detector
 * immediately, but the visual handle only snaps to the nearest named stop after release. This
 * avoids both failure modes of the old controls: a handle frozen between discrete callbacks and a
 * delayed detector change that made the setting feel unresponsive.
 *
 * When [revealLastStopAfterIndex] is supplied, the final stop is a deliberate second stage. The
 * user first reaches High on the normal three-stop slider; only after that gesture finishes (or
 * High is selected directly) does Max appear. The range never changes underneath an active
 * finger, so revealing Max cannot make the handle jump mid-drag.
 *
 * The labels are also 48dp-tall radio-button targets. They make the relative scale explicit and
 * provide a precise alternative to dragging for gloves, switch access, and screen readers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialSensitivitySlider(
    label: String,
    optionLabels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    increaseActionLabel: String,
    decreaseActionLabel: String,
    modifier: Modifier = Modifier,
    revealLastStopAfterIndex: Int? = null,
    allowMaxStop: Boolean = true
) {
    require(optionLabels.size >= 2) { "A sensitivity slider needs at least two stops" }
    require(selectedIndex in optionLabels.indices) { "selectedIndex out of range" }

    val lastIndex = optionLabels.lastIndex
    val gated = allowMaxStop && revealLastStopAfterIndex != null && lastIndex == revealLastStopAfterIndex + 1
    val revealIndex = revealLastStopAfterIndex ?: lastIndex
    val maxAllowedIndex = if (allowMaxStop) lastIndex else revealIndex
    var maxRevealed by remember(optionLabels.size, allowMaxStop) {
        mutableStateOf(allowMaxStop && (!gated || selectedIndex == lastIndex))
    }
    val displayLastIndex = if (maxRevealed) maxAllowedIndex else revealIndex
    var visualValue by remember(optionLabels.size, allowMaxStop) {
        mutableFloatStateOf(selectedIndex.coerceAtMost(displayLastIndex).toFloat())
    }
    var committedIndex by remember(optionLabels.size, allowMaxStop) {
        mutableIntStateOf(selectedIndex.coerceAtMost(displayLastIndex))
    }
    var isAdjusting by remember { mutableStateOf(false) }
    var hitRevealStop by remember { mutableStateOf(false) }
    var revealRequested by remember { mutableStateOf(false) }

    // Finish the pointer frame before inserting Max. Otherwise a direct High tap can be
    // retargeted onto the newly-created rightmost label on some devices.
    LaunchedEffect(revealRequested) {
        if (revealRequested && allowMaxStop) {
            withFrameNanos { }
            maxRevealed = true
            revealRequested = false
        }
    }

    LaunchedEffect(selectedIndex, lastIndex, allowMaxStop) {
        val clampedSelected = selectedIndex.coerceAtMost(displayLastIndex)
        committedIndex = clampedSelected
        if (gated && selectedIndex == lastIndex) maxRevealed = true
        if (!isAdjusting) {
            visualValue = clampedSelected.toFloat()
        }
    }

    fun select(index: Int, revealFromDirectSelection: Boolean = false): Boolean {
        val next = index.coerceIn(0, displayLastIndex)
        if (gated && revealFromDirectSelection && next >= revealIndex) revealRequested = true
        if (next == committedIndex) return false
        committedIndex = next
        onSelect(next)
        return true
    }

    Column(modifier = modifier) {
        Slider(
            value = visualValue.coerceIn(0f, displayLastIndex.toFloat()),
            onValueChange = { raw ->
                isAdjusting = true
                visualValue = raw
                if (gated && !maxRevealed && raw >= revealIndex - RevealTolerance) {
                    hitRevealStop = true
                }
                select(raw.roundToInt().coerceAtMost(displayLastIndex))
            },
            onValueChangeFinished = {
                isAdjusting = false
                if (hitRevealStop) revealRequested = true
                hitRevealStop = false
                visualValue = committedIndex.toFloat()
            },
            valueRange = 0f..displayLastIndex.toFloat(),
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                activeTickColor = MaterialTheme.colorScheme.onPrimary,
                inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                inactiveTickColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("slider_sensitivity")
                .semantics {
                    contentDescription = label
                    stateDescription = optionLabels[committedIndex]
                    customActions = listOf(
                        CustomAccessibilityAction(increaseActionLabel) {
                            val next = (committedIndex + 1).coerceAtMost(displayLastIndex)
                            val changed = select(next, revealFromDirectSelection = true)
                            visualValue = committedIndex.toFloat()
                            changed
                        },
                        CustomAccessibilityAction(decreaseActionLabel) {
                            val changed = select(committedIndex - 1)
                            visualValue = committedIndex.toFloat()
                            changed
                        }
                    )
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
        ) {
            optionLabels.take(displayLastIndex + 1).forEachIndexed { index, text ->
                val selected = index == selectedIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = {
                                isAdjusting = false
                                hitRevealStop = false
                                select(index, revealFromDirectSelection = true)
                                visualValue = index.toFloat()
                            }
                        )
                        .testTag("sensitivity_stop_$index")
                ) {
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                        contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        shape = PillShape
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Small tolerance for a drag that lands on the end stop within pointer/float rounding. */
internal const val RevealTolerance = 0.02f
