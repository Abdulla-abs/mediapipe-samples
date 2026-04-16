package com.google.mediapipe.examples.poselandmarker.recognition

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.sqrt

/**
 * 基于规则的姿态识别器
 * 
 * 该类用于识别和跟踪人体姿态动作，包括：
 * - 抬手动作开始（ARM_RAISE_START）
 * - 抬手动作结束（ARM_RAISE_END）
 * 
 * 通过分析姿态关键点的位置，识别特定的手势动作。
 * 支持左右两侧独立识别，并使用指数移动平均（EMA）进行平滑处理。
 */
class RulePoseRecognizer(
    private val config: Config = Config(),
    private val eventListener: PoseActionEventListener? = null
) {

    /**
     * 配置参数
     * 
     * @param emaAlpha 指数移动平均平滑系数，用于平滑关键点位置，值越小平滑效果越强
     * @param enterHoldFrames 进入状态需要持续的帧数，防止误触发
     * @param exitHoldFrames 退出状态需要持续的帧数，防止误退出
     * @param minBodyScale 最小身体尺度，用于防止除零和过滤过远的检测结果
     * @param wristAboveShoulderThreshold 手腕高于肩膀的阈值（归一化）
     * @param wristBelowShoulderThreshold 手腕低于肩膀的阈值（归一化）
     * @param minArmLengthThreshold 手臂最小长度阈值（归一化），用于判断手臂是否伸展
     */
    data class Config(
        val emaAlpha: Float = 0.35f,
        val enterHoldFrames: Int = 4,
        val exitHoldFrames: Int = 4,
        val minBodyScale: Float = 0.08f,
        val wristAboveShoulderThreshold: Float = 0.50f,
        val wristBelowShoulderThreshold: Float = 0.30f,
        val minArmLengthThreshold: Float = 0.62f
    )

    // 平滑后的关键点位置数组，用于存储EMA平滑结果
    private val smoothed = Array<Point?>(LANDMARK_COUNT) { null }
    
    // 当前帧索引，用于跟踪时间序列
    private var frameIndex: Long = 0L

    // 左右两侧的状态映射，独立跟踪每一侧的识别状态
    private val sideStates = mapOf(
        BodySide.LEFT to SideState(),
        BodySide.RIGHT to SideState()
    )

    /**
     * 处理姿态检测结果，识别动作事件
     * 
     * @param result 姿态标记结果，包含关键点信息
     * @param isFrontCamera 是否使用前置相机，用于镜像处理
     * @return 识别出的动作事件列表
     */
    fun process(result: PoseLandmarkerResult, isFrontCamera: Boolean): List<PoseActionEvent> {
        // 递增帧索引
        frameIndex += 1
        
        // 获取第一个人的姿态关键点，如果没有则返回空列表
        val person = result.landmarks().firstOrNull() ?: return emptyList()
        
        // 确保关键点数量足够（至少需要右手腕关键点）
        if (person.size <= Landmark.RIGHT_WRIST) return emptyList()

        // 使用EMA平滑关键点位置，减少噪声影响
        val smoothedPose = smooth(person)

        // 获取左右肩和左右髋的关键点，考虑前置相机的镜像效果
        val leftShoulder = getSidePoint(smoothedPose, Landmark.LEFT_SHOULDER, Landmark.RIGHT_SHOULDER, isFrontCamera)
        val rightShoulder = getSidePoint(smoothedPose, Landmark.RIGHT_SHOULDER, Landmark.LEFT_SHOULDER, isFrontCamera)
        val leftHip = getSidePoint(smoothedPose, Landmark.LEFT_HIP, Landmark.RIGHT_HIP, isFrontCamera)
        val rightHip = getSidePoint(smoothedPose, Landmark.RIGHT_HIP, Landmark.LEFT_HIP, isFrontCamera)

        // 计算身体尺度，用于归一化距离测量
        val bodyScale = computeBodyScale(leftShoulder, rightShoulder, leftHip, rightHip)
        
        // 如果身体尺度无效，返回空列表
        if (bodyScale <= 0f) return emptyList()

        // 用于收集识别出的动作事件
        val events = mutableListOf<PoseActionEvent>()
        val timestampMs = result.timestampMs()

        // 评估左侧身体的动作状态
        evaluateSide(
            side = BodySide.LEFT,
            shoulder = leftShoulder,
            wrist = getSidePoint(smoothedPose, Landmark.LEFT_WRIST, Landmark.RIGHT_WRIST, isFrontCamera),
            hip = leftHip,
            bodyScale = bodyScale,
            timestampMs = timestampMs,
            events = events
        )

        // 评估右侧身体的动作状态
        evaluateSide(
            side = BodySide.RIGHT,
            shoulder = rightShoulder,
            wrist = getSidePoint(smoothedPose, Landmark.RIGHT_WRIST, Landmark.LEFT_WRIST, isFrontCamera),
            hip = rightHip,
            bodyScale = bodyScale,
            timestampMs = timestampMs,
            events = events
        )

        // 通知所有事件监听器
        events.forEach { eventListener?.onPoseActionEvent(it) }
        return events
    }

    /**
     * 评估单侧身体的动作状态
     * 
     * @param side 身体侧边（左或右）
     * @param shoulder 肩膀位置
     * @param wrist 手腕位置
     * @param hip 髋部位置
     * @param bodyScale 身体尺度，用于归一化
     * @param timestampMs 时间戳（毫秒）
     * @param events 事件列表，用于收集识别出的动作事件
     */
    private fun evaluateSide(
        side: BodySide,
        shoulder: Point,
        wrist: Point,
        hip: Point,
        bodyScale: Float,
        timestampMs: Long,
        events: MutableList<PoseActionEvent>
    ) {
        // 获取该侧的状态
        val state = sideStates.getValue(side)

        // 计算手腕相对于肩膀的归一化距离
        val wristAboveShoulder = (shoulder.y - wrist.y) / bodyScale  // 手腕在肩膀上方的距离
        val wristBelowShoulder = (wrist.y - shoulder.y) / bodyScale  // 手腕在肩膀下方的距离
        val armLength = distance(shoulder, wrist) / bodyScale  // 手臂长度

        // 判断是否可能抬手：手腕在肩膀上方且手臂伸展
        val armUpCandidate =
            wristAboveShoulder >= config.wristAboveShoulderThreshold &&
                armLength >= config.minArmLengthThreshold
        
        // 判断是否可能放下：手腕在肩膀下方或低于髋部
        val armDownCandidate =
            wristBelowShoulder >= config.wristBelowShoulderThreshold ||
                wrist.y > hip.y

        // 状态机：抬手状态转换
        if (!state.armUp) {
            // 当前手臂放下状态：检测抬手候选条件
            state.armUpEnterCount = if (armUpCandidate) state.armUpEnterCount + 1 else 0
            
            // 如果满足抬手条件持续足够帧数，则进入抬手状态
            if (state.armUpEnterCount >= config.enterHoldFrames) {
                state.armUp = true
                state.armUpEnterCount = 0
                state.armUpExitCount = 0
                events.add(createEvent(PoseActionType.ARM_RAISE_START, side, timestampMs))
            }
        } else {
            // 当前手臂抬起状态：检测放下候选条件
            state.armUpExitCount = if (armDownCandidate) state.armUpExitCount + 1 else 0
            
            // 如果满足放下条件持续足够帧数，则进入放下状态（抬手结束）
            if (state.armUpExitCount >= config.exitHoldFrames) {
                state.armUp = false
                state.armUpExitCount = 0
                state.armUpEnterCount = 0
                events.add(createEvent(PoseActionType.ARM_RAISE_END, side, timestampMs))
            }
        }

    }


    /**
     * 使用指数移动平均（EMA）平滑关键点位置
     * 
     * @param landmarks 原始关键点列表
     * @return 平滑后的关键点数组
     */
    private fun smooth(landmarks: List<NormalizedLandmark>): Array<Point> {
        val output = Array(LANDMARK_COUNT) { Point(0f, 0f) }
        
        // 对每个关键点应用EMA平滑
        for (i in 0 until LANDMARK_COUNT) {
            // 获取当前原始位置
            val raw = Point(landmarks[i].x(), landmarks[i].y())
            
            // 获取上一帧的平滑位置
            val prev = smoothed[i]
            
            // 计算EMA平滑位置：新值 = 旧值 + alpha * (原始值 - 旧值)
            val smoothedPoint =
                if (prev == null) {
                    raw  // 第一帧，直接使用原始值
                } else {
                    Point(
                        x = prev.x + config.emaAlpha * (raw.x - prev.x),
                        y = prev.y + config.emaAlpha * (raw.y - prev.y)
                    )
                }
            
            // 保存平滑结果
            smoothed[i] = smoothedPoint
            output[i] = smoothedPoint
        }
        return output
    }

    /**
     * 计算身体尺度，用于归一化距离测量
     * 
     * 身体尺度取肩宽和躯干长度的最大值
     * 
     * @param leftShoulder 左肩位置
     * @param rightShoulder 右肩位置
     * @param leftHip 左髋位置
     * @param rightHip 右髋位置
     * @return 身体尺度值
     */
    private fun computeBodyScale(
        leftShoulder: Point,
        rightShoulder: Point,
        leftHip: Point,
        rightHip: Point
    ): Float {
        // 计算肩宽
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        
        // 计算左右躯干长度
        val leftTorso = distance(leftShoulder, leftHip)
        val rightTorso = distance(rightShoulder, rightHip)
        
        // 取平均躯干长度
        val torso = (leftTorso + rightTorso) / 2f
        
        // 返回肩宽、躯干长度和最小尺度的最大值
        return maxOf(shoulderWidth, torso, config.minBodyScale)
    }

    /**
     * 根据相机类型获取正确侧边的关键点
     * 
     * 前置相机需要镜像处理，因此使用镜像索引
     * 
     * @param pose 姿态关键点数组
     * @param normalIndex 正常索引（后置相机）
     * @param mirroredIndex 镜像索引（前置相机）
     * @param isFrontCamera 是否使用前置相机
     * @return 对应的关键点位置
     */
    private fun getSidePoint(
        pose: Array<Point>,
        normalIndex: Int,
        mirroredIndex: Int,
        isFrontCamera: Boolean
    ): Point {
        // 根据相机类型选择正确的索引
        val index = if (isFrontCamera) mirroredIndex else normalIndex
        return pose[index]
    }

    /**
     * 创建动作事件
     * 
     * @param type 动作类型
     * @param side 身体侧边
     * @param timestampMs 时间戳（毫秒）
     * @return 动作事件对象
     */
    private fun createEvent(type: PoseActionType, side: BodySide, timestampMs: Long): PoseActionEvent {
        return PoseActionEvent(
            type = type,
            side = side,
            timestampMs = timestampMs,
            frameIndex = frameIndex
        )
    }


    /**
     * 单侧身体的状态数据
     * 
     * @param armUp 手臂是否抬起
     * @param armUpEnterCount 抬手状态进入计数器
     * @param armUpExitCount 抬手状态退出计数器
     */
    private data class SideState(
        var armUp: Boolean = false,
        var armUpEnterCount: Int = 0,
        var armUpExitCount: Int = 0
    )

    private data class Point(
        val x: Float,
        val y: Float
    )

    /**
     * MediaPipe姿态关键点索引
     * 这些索引对应MediaPipe姿态模型中的特定关键点
     */
    private object Landmark {
        const val LEFT_SHOULDER = 11   // 左肩
        const val RIGHT_SHOULDER = 12  // 右肩
        const val LEFT_HIP = 23        // 左髋
        const val RIGHT_HIP = 24       // 右髋
        const val LEFT_WRIST = 15      // 左手腕
        const val RIGHT_WRIST = 16     // 右手腕
    }

    companion object {
        // MediaPipe姿态模型的关键点总数
        private const val LANDMARK_COUNT = 33

        /**
         * 计算两点之间的欧氏距离
         * 
         * @param a 第一个点
         * @param b 第二个点
         * @return 两点间的距离
         */
        private fun distance(a: Point, b: Point): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}

