package com.google.mediapipe.examples.poselandmarker.recognition

enum class BodySide {
    LEFT,
    RIGHT
}

enum class PoseActionType {
    ARM_RAISE_START,
    ARM_RAISE_END,
    ARM_WAVE_START,
    ARM_WAVE_END
}

data class PoseActionEvent(
    val type: PoseActionType,
    val side: BodySide,
    val timestampMs: Long,
    val frameIndex: Long
)

fun interface PoseActionEventListener {
    fun onPoseActionEvent(event: PoseActionEvent)
}

