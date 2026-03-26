package com.google.mediapipe.examples.poselandmarker.recognition

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.abs
import kotlin.math.sqrt

class RulePoseRecognizer(
    private val config: Config = Config(),
    private val eventListener: PoseActionEventListener? = null
) {

    data class Config(
        val emaAlpha: Float = 0.35f,
        val enterHoldFrames: Int = 4,
        val exitHoldFrames: Int = 4,
        val waveVelocityThreshold: Float = 1.1f,
        val waveQuietVelocityThreshold: Float = 0.45f,
        val waveDirectionChangesToStart: Int = 2,
        val maxFramesBetweenDirectionChanges: Int = 8,
        val minBodyScale: Float = 0.08f,
        val wristAboveShoulderThreshold: Float = 0.50f,
        val wristBelowShoulderThreshold: Float = 0.30f,
        val minArmLengthThreshold: Float = 0.62f
    )

    private val smoothed = Array<Point?>(LANDMARK_COUNT) { null }
    private var frameIndex: Long = 0L

    private val sideStates = mapOf(
        BodySide.LEFT to SideState(),
        BodySide.RIGHT to SideState()
    )

    fun process(result: PoseLandmarkerResult, isFrontCamera: Boolean): List<PoseActionEvent> {
        frameIndex += 1
        val person = result.landmarks().firstOrNull() ?: return emptyList()
        if (person.size <= Landmark.RIGHT_WRIST) return emptyList()

        val smoothedPose = smooth(person)

        val leftShoulder = getSidePoint(smoothedPose, Landmark.LEFT_SHOULDER, Landmark.RIGHT_SHOULDER, isFrontCamera)
        val rightShoulder = getSidePoint(smoothedPose, Landmark.RIGHT_SHOULDER, Landmark.LEFT_SHOULDER, isFrontCamera)
        val leftHip = getSidePoint(smoothedPose, Landmark.LEFT_HIP, Landmark.RIGHT_HIP, isFrontCamera)
        val rightHip = getSidePoint(smoothedPose, Landmark.RIGHT_HIP, Landmark.LEFT_HIP, isFrontCamera)

        val bodyScale = computeBodyScale(leftShoulder, rightShoulder, leftHip, rightHip)
        if (bodyScale <= 0f) return emptyList()

        val events = mutableListOf<PoseActionEvent>()
        val timestampMs = result.timestampMs()

        evaluateSide(
            side = BodySide.LEFT,
            shoulder = leftShoulder,
            wrist = getSidePoint(smoothedPose, Landmark.LEFT_WRIST, Landmark.RIGHT_WRIST, isFrontCamera),
            hip = leftHip,
            bodyScale = bodyScale,
            timestampMs = timestampMs,
            events = events
        )

        evaluateSide(
            side = BodySide.RIGHT,
            shoulder = rightShoulder,
            wrist = getSidePoint(smoothedPose, Landmark.RIGHT_WRIST, Landmark.LEFT_WRIST, isFrontCamera),
            hip = rightHip,
            bodyScale = bodyScale,
            timestampMs = timestampMs,
            events = events
        )

        events.forEach { eventListener?.onPoseActionEvent(it) }
        return events
    }

    private fun evaluateSide(
        side: BodySide,
        shoulder: Point,
        wrist: Point,
        hip: Point,
        bodyScale: Float,
        timestampMs: Long,
        events: MutableList<PoseActionEvent>
    ) {
        val state = sideStates.getValue(side)

        val wristAboveShoulder = (shoulder.y - wrist.y) / bodyScale
        val wristBelowShoulder = (wrist.y - shoulder.y) / bodyScale
        val armLength = distance(shoulder, wrist) / bodyScale

        val armUpCandidate =
            wristAboveShoulder >= config.wristAboveShoulderThreshold &&
                armLength >= config.minArmLengthThreshold
        val armDownCandidate =
            wristBelowShoulder >= config.wristBelowShoulderThreshold ||
                wrist.y > hip.y

        if (!state.armUp) {
            state.armUpEnterCount = if (armUpCandidate) state.armUpEnterCount + 1 else 0
            if (state.armUpEnterCount >= config.enterHoldFrames) {
                state.armUp = true
                state.armUpEnterCount = 0
                state.armUpExitCount = 0
                events.add(createEvent(PoseActionType.ARM_RAISE_START, side, timestampMs))
            }
        } else {
            state.armUpExitCount = if (armDownCandidate) state.armUpExitCount + 1 else 0
            if (state.armUpExitCount >= config.exitHoldFrames) {
                state.armUp = false
                state.armUpExitCount = 0
                state.armUpEnterCount = 0
                if (state.waveActive) {
                    state.waveActive = false
                    events.add(createEvent(PoseActionType.ARM_WAVE_END, side, timestampMs))
                }
                resetWaveTracking(state)
                events.add(createEvent(PoseActionType.ARM_LOWER_START, side, timestampMs))
            }
        }

        val vxNorm = computeNormalizedVelocityX(state, wrist, timestampMs, bodyScale)
        val waveCandidate = state.armUp && updateWaveDirection(state, vxNorm)

        if (!state.waveActive) {
            state.waveEnterCount = if (waveCandidate) state.waveEnterCount + 1 else 0
            if (state.waveEnterCount >= config.enterHoldFrames) {
                state.waveActive = true
                state.waveEnterCount = 0
                state.waveExitCount = 0
                events.add(createEvent(PoseActionType.ARM_WAVE_START, side, timestampMs))
            }
        } else {
            val shouldExitWave = !state.armUp || abs(vxNorm) < config.waveQuietVelocityThreshold || !waveCandidate
            state.waveExitCount = if (shouldExitWave) state.waveExitCount + 1 else 0
            if (state.waveExitCount >= config.exitHoldFrames) {
                state.waveActive = false
                state.waveExitCount = 0
                state.waveEnterCount = 0
                resetWaveTracking(state)
                events.add(createEvent(PoseActionType.ARM_WAVE_END, side, timestampMs))
            }
        }
    }

    private fun computeNormalizedVelocityX(
        state: SideState,
        wrist: Point,
        timestampMs: Long,
        bodyScale: Float
    ): Float {
        val prevWristX = state.prevWristX
        val prevTimestampMs = state.prevTimestampMs

        state.prevWristX = wrist.x
        state.prevTimestampMs = timestampMs

        if (prevWristX == null || prevTimestampMs == null) return 0f

        val dtMs = (timestampMs - prevTimestampMs).coerceAtLeast(1L)
        val vx = (wrist.x - prevWristX) / dtMs.toFloat() * 1000f
        return if (bodyScale > 0f) vx / bodyScale else 0f
    }

    private fun updateWaveDirection(state: SideState, vxNorm: Float): Boolean {
        val absV = abs(vxNorm)
        if (absV < config.waveVelocityThreshold) {
            if (frameIndex - state.lastDirectionFrame > config.maxFramesBetweenDirectionChanges) {
                state.directionChanges = 0
                state.lastDirectionSign = 0
            }
            return state.directionChanges >= config.waveDirectionChangesToStart
        }

        val sign = if (vxNorm > 0f) 1 else -1
        if (state.lastDirectionSign == 0) {
            state.lastDirectionSign = sign
            state.lastDirectionFrame = frameIndex
            return state.directionChanges >= config.waveDirectionChangesToStart
        }

        val gap = frameIndex - state.lastDirectionFrame
        if (sign != state.lastDirectionSign) {
            state.directionChanges =
                if (gap <= config.maxFramesBetweenDirectionChanges) state.directionChanges + 1 else 1
            state.lastDirectionSign = sign
            state.lastDirectionFrame = frameIndex
        } else if (gap > config.maxFramesBetweenDirectionChanges) {
            state.directionChanges = 0
            state.lastDirectionSign = sign
            state.lastDirectionFrame = frameIndex
        }

        return state.directionChanges >= config.waveDirectionChangesToStart
    }

    private fun smooth(landmarks: List<NormalizedLandmark>): Array<Point> {
        val output = Array(LANDMARK_COUNT) { Point(0f, 0f) }
        for (i in 0 until LANDMARK_COUNT) {
            val raw = Point(landmarks[i].x(), landmarks[i].y())
            val prev = smoothed[i]
            val smoothedPoint =
                if (prev == null) {
                    raw
                } else {
                    Point(
                        x = prev.x + config.emaAlpha * (raw.x - prev.x),
                        y = prev.y + config.emaAlpha * (raw.y - prev.y)
                    )
                }
            smoothed[i] = smoothedPoint
            output[i] = smoothedPoint
        }
        return output
    }

    private fun computeBodyScale(
        leftShoulder: Point,
        rightShoulder: Point,
        leftHip: Point,
        rightHip: Point
    ): Float {
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        val leftTorso = distance(leftShoulder, leftHip)
        val rightTorso = distance(rightShoulder, rightHip)
        val torso = (leftTorso + rightTorso) / 2f
        return maxOf(shoulderWidth, torso, config.minBodyScale)
    }

    private fun getSidePoint(
        pose: Array<Point>,
        normalIndex: Int,
        mirroredIndex: Int,
        isFrontCamera: Boolean
    ): Point {
        val index = if (isFrontCamera) mirroredIndex else normalIndex
        return pose[index]
    }

    private fun createEvent(type: PoseActionType, side: BodySide, timestampMs: Long): PoseActionEvent {
        return PoseActionEvent(
            type = type,
            side = side,
            timestampMs = timestampMs,
            frameIndex = frameIndex
        )
    }

    private fun resetWaveTracking(state: SideState) {
        state.waveEnterCount = 0
        state.waveExitCount = 0
        state.directionChanges = 0
        state.lastDirectionSign = 0
        state.lastDirectionFrame = frameIndex
    }

    private data class SideState(
        var armUp: Boolean = false,
        var waveActive: Boolean = false,
        var armUpEnterCount: Int = 0,
        var armUpExitCount: Int = 0,
        var waveEnterCount: Int = 0,
        var waveExitCount: Int = 0,
        var prevWristX: Float? = null,
        var prevTimestampMs: Long? = null,
        var directionChanges: Int = 0,
        var lastDirectionSign: Int = 0,
        var lastDirectionFrame: Long = 0L
    )

    private data class Point(
        val x: Float,
        val y: Float
    )

    private object Landmark {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
    }

    companion object {
        private const val LANDMARK_COUNT = 33

        private fun distance(a: Point, b: Point): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}

