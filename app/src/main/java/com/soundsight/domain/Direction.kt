package com.soundsight.domain

enum class Side { LEFT, RIGHT }

enum class DirectionConfidence { HIGH, MEDIUM, UNKNOWN }

/**
 * side is null whenever confidence is UNKNOWN, and never null otherwise.
 * The alert itself (colour, text, haptics) fires exactly the same either way -
 * UNKNOWN only removes the side indicator.
 */
data class DirectionEstimate(
    val side: Side?,
    val confidence: DirectionConfidence
) {
    init {
        require((side == null) == (confidence == DirectionConfidence.UNKNOWN)) {
            "side and confidence disagree: side=$side confidence=$confidence"
        }
    }

    companion object {
        val UNKNOWN = DirectionEstimate(null, DirectionConfidence.UNKNOWN)
    }
}
