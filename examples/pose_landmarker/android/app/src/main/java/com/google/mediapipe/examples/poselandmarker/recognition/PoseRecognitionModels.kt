package com.google.mediapipe.examples.poselandmarker.recognition

enum class BodySide {
    LEFT,
    RIGHT
}

enum class PoseActionType {
    ARM_RAISE_START,
    ARM_RAISE_END,
    BODY_MOVE_LEFT,      // 身体向左移动
    BODY_MOVE_RIGHT,     // 身体向右移动
    ARM_WAVE_START,
    ARM_WAVE_END
}

data class PoseActionEvent(
    val type: PoseActionType,
    val side: BodySide,
    val timestampMs: Long,
    val frameIndex: Long,
    val personId: Int = 0  // 人员ID，用于区分不同的人
)

fun interface PoseActionEventListener {
    fun onPoseActionEvent(event: PoseActionEvent)
}

