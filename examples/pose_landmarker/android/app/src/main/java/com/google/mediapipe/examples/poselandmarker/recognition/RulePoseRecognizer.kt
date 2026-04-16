package com.google.mediapipe.examples.poselandmarker.recognition

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.sqrt

/**
 * 基于规则的姿态识别器（多人版）
 * 
 * 该类用于识别和跟踪人体姿态动作，包括：
 * - 抬手动作开始（ARM_RAISE_START）
 * - 抬手动作结束（ARM_RAISE_END）
 * - 身体向左移动（BODY_MOVE_LEFT）
 * - 身体向右移动（BODY_MOVE_RIGHT）
 * 
 * 通过分析姿态关键点的位置，识别特定的手势动作。
 * 支持左右两侧独立识别，并使用指数移动平均（EMA）进行平滑处理。
 * 支持多人同时识别，通过personId区分不同的人。
 * 
 * 左右移动检测原理：
 * - 通过跟踪身体中心点（双肩中点）的水平位置变化来检测移动方向
 * - 使用归一化位移量（相对于肩宽）来判断有效移动，适应不同距离的拍摄场景
 * - 采用状态机管理检测过程，包含持续帧数验证和冷却机制，确保检测稳定可靠
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
     * @param bodyMoveThreshold 身体中心水平移动距离阈值（归一化），用于判断是否发生有效位移
     * @param bodyMoveHoldFrames 身体移动需要持续的帧数，确保是稳定移动而非抖动
     * @param bodyMoveCooldownFrames 移动检测后的冷却帧数，避免重复检测同一动作
     */
    data class Config(
        val emaAlpha: Float = 0.35f,
        val enterHoldFrames: Int = 4,
        val exitHoldFrames: Int = 4,
        val minBodyScale: Float = 0.08f,
        val wristAboveShoulderThreshold: Float = 0.50f,
        val wristBelowShoulderThreshold: Float = 0.30f,
        val minArmLengthThreshold: Float = 0.62f,
        // 左右移动识别相关参数
        val bodyMoveThreshold: Float = 0.1f,           // 身体中心水平移动距离阈值（相对于肩宽）
        val bodyMoveHoldFrames: Int = 3,                 // 移动持续帧数要求
        val bodyMoveCooldownFrames: Int = 10             // 冷却帧数
    )

    // 当前帧索引，用于跟踪时间序列
    private var frameIndex: Long = 0L

    // 多人跟踪：每个人的状态管理器
    // Key是personId，Value是该人的完整状态（包括关键点平滑、左右侧状态、身体移动状态）
    private val personStates = mutableMapOf<Int, PersonState>()

    // 用于分配人员ID的计数器
    private var nextPersonId: Int = 0

    /**
     * 单个人的完整状态
     * 包含该人的所有跟踪状态和平滑后的关键点
     */
    private data class PersonState(
        val smoothed: Array<Point?> = Array(LANDMARK_COUNT) { null },  // 平滑后的关键点
        val sideStates: Map<BodySide, SideState> = mapOf(              // 左右两侧状态
            BodySide.LEFT to SideState(),
            BodySide.RIGHT to SideState()
        ),
        val bodyMoveState: BodyMoveState = BodyMoveState()             // 身体移动状态
    )

    /**
     * 处理姿态检测结果，识别动作事件（多人版）
     * 
     * @param result 姿态标记结果，包含关键点信息
     * @param isFrontCamera 是否使用前置相机，用于镜像处理
     * @return 识别出的动作事件列表
     */
    fun process(result: PoseLandmarkerResult, isFrontCamera: Boolean): List<PoseActionEvent> {
        // 递增帧索引
        frameIndex += 1
        
        // 获取所有检测到的人
        val persons = result.landmarks()
        if (persons.isEmpty()) return emptyList()
        
        // 用于收集所有人员的动作事件
        val allEvents = mutableListOf<PoseActionEvent>()
         val timestampMs = result.timestampMs()
        
        // 处理每一个检测到的人
        for ((index, person) in persons.withIndex()) {
            // 确保关键点数量足够（至少需要右手腕关键点）
            if (person.size <= Landmark.RIGHT_WRIST) continue
            
            // 为这个人分配或获取ID
            val personId = assignPersonId(index, person)
            
            // 获取或创建该人的状态
            val personState = personStates.getOrPut(personId) { PersonState() }
            
            // 使用EMA平滑关键点位置，减少噪声影响
            val smoothedPose = smooth(person, personState.smoothed)
            
            // 获取左右肩和左右髋的关键点，考虑前置相机的镜像效果
            val leftShoulder = getSidePoint(smoothedPose, Landmark.LEFT_SHOULDER, Landmark.RIGHT_SHOULDER, isFrontCamera)
            val rightShoulder = getSidePoint(smoothedPose, Landmark.RIGHT_SHOULDER, Landmark.LEFT_SHOULDER, isFrontCamera)
            val leftHip = getSidePoint(smoothedPose, Landmark.LEFT_HIP, Landmark.RIGHT_HIP, isFrontCamera)
            val rightHip = getSidePoint(smoothedPose, Landmark.RIGHT_HIP, Landmark.LEFT_HIP, isFrontCamera)

            // 计算身体尺度，用于归一化距离测量
            val bodyScale = computeBodyScale(leftShoulder, rightShoulder, leftHip, rightHip)
            
            // 如果身体尺度无效，跳过这个人
            if (bodyScale <= 0f) continue

            // 评估左侧身体的动作状态
            evaluateSide(
                personId = personId,
                side = BodySide.LEFT,
                shoulder = leftShoulder,
                wrist = getSidePoint(smoothedPose, Landmark.LEFT_WRIST, Landmark.RIGHT_WRIST, isFrontCamera),
                hip = leftHip,
                bodyScale = bodyScale,
                timestampMs = timestampMs,
                sideState = personState.sideStates.getValue(BodySide.LEFT),
                events = allEvents
            )

            // 评估右侧身体的动作状态
            evaluateSide(
                personId = personId,
                side = BodySide.RIGHT,
                shoulder = rightShoulder,
                wrist = getSidePoint(smoothedPose, Landmark.RIGHT_WRIST, Landmark.LEFT_WRIST, isFrontCamera),
                hip = rightHip,
                bodyScale = bodyScale,
                timestampMs = timestampMs,
                sideState = personState.sideStates.getValue(BodySide.RIGHT),
                events = allEvents
            )

            // 检测身体左右移动
            evaluateBodyMovement(
                personId = personId,
                leftShoulder = leftShoulder,
                rightShoulder = rightShoulder,
                bodyScale = bodyScale,
                timestampMs = timestampMs,
                bodyMoveState = personState.bodyMoveState,
                events = allEvents
            )
        }

        // 通知所有事件监听器
        allEvents.forEach { eventListener?.onPoseActionEvent(it) }
        return allEvents
    }

    /**
     * 为检测到的人分配ID
     * 简单实现：按照检测顺序分配ID（0, 1, 2...）
     * 
     * TODO: 未来可以实现更复杂的跟踪算法，基于位置相似度匹配同一人的ID
     * 
     * @param index 在检测结果列表中的索引
     * @param person 该人的关键点列表
     * @return 分配的人员ID
     */
    private fun assignPersonId(index: Int, person: List<NormalizedLandmark>): Int {
        // 简单实现：直接使用索引作为ID
        // 对于最多2人的场景，ID就是0或1
        return index
    }

    /**
     * 评估单侧身体的动作状态
     * 
     * @param personId 人员ID
     * @param side 身体侧边（左或右）
     * @param shoulder 肩膀位置
     * @param wrist 手腕位置
     * @param hip 髋部位置
     * @param bodyScale 身体尺度，用于归一化
     * @param timestampMs 时间戳（毫秒）
     * @param sideState 该侧的状态
     * @param events 事件列表，用于收集识别出的动作事件
     */
    private fun evaluateSide(
        personId: Int,
        side: BodySide,
        shoulder: Point,
        wrist: Point,
        hip: Point,
        bodyScale: Float,
        timestampMs: Long,
        sideState: SideState,
        events: MutableList<PoseActionEvent>
    ) {
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
        if (!sideState.armUp) {
            // 当前手臂放下状态：检测抬手候选条件
            sideState.armUpEnterCount = if (armUpCandidate) sideState.armUpEnterCount + 1 else 0
            
            // 如果满足抬手条件持续足够帧数，则进入抬手状态
            if (sideState.armUpEnterCount >= config.enterHoldFrames) {
                sideState.armUp = true
                sideState.armUpEnterCount = 0
                sideState.armUpExitCount = 0
                events.add(createEvent(PoseActionType.ARM_RAISE_START, side, timestampMs, personId))
            }
        } else {
            // 当前手臂抬起状态：检测放下候选条件
            sideState.armUpExitCount = if (armDownCandidate) sideState.armUpExitCount + 1 else 0
            
            // 如果满足放下条件持续足够帧数，则进入放下状态（抬手结束）
            if (sideState.armUpExitCount >= config.exitHoldFrames) {
                sideState.armUp = false
                sideState.armUpExitCount = 0
                sideState.armUpEnterCount = 0
                events.add(createEvent(PoseActionType.ARM_RAISE_END, side, timestampMs, personId))
            }
        }

    }

    /**
     * 评估身体的左右移动状态
     *
     * 通过跟踪身体中心点（双肩中点）的水平位置变化来检测左右移动。
     * 使用状态机管理移动检测，包含冷却机制避免重复检测。
     *
     * 检测原理：
     * 1. 记录一个基准位置（baselineCenterX），表示用户相对静止时的身体中心
     * 2. 计算当前位置相对于基准位置的累计位移
     * 3. 当累计位移超过阈值且持续一定帧数时，触发移动事件
     * 4. 移动后进入冷却期，避免重复检测
     *
     * @param personId 人员ID
     * @param leftShoulder 左肩位置
     * @param rightShoulder 右肩位置
     * @param bodyScale 身体尺度，用于归一化距离测量
     * @param timestampMs 时间戳（毫秒）
     * @param bodyMoveState 身体移动状态
     * @param events 事件列表，用于收集识别出的动作事件
     */
    private fun evaluateBodyMovement(
        personId: Int,
        leftShoulder: Point,
        rightShoulder: Point,
        bodyScale: Float,
        timestampMs: Long,
        bodyMoveState: BodyMoveState,
        events: MutableList<PoseActionEvent>
    ) {
        // 计算当前身体中心点的X坐标（双肩中点）
        val currentCenterX = (leftShoulder.x + rightShoulder.x) / 2f

        // 获取基准位置，如果是第一帧则初始化并返回
        val baselineCenterX = bodyMoveState.baselineCenterX
        if (baselineCenterX == null) {
            bodyMoveState.baselineCenterX = currentCenterX
            return
        }

        // 处理冷却期：在冷却期间不进行新的移动检测
        if (bodyMoveState.cooldownCounter > 0) {
            bodyMoveState.cooldownCounter--
            // 冷却期结束后，重置基准位置为当前位置，准备下次检测
            if (bodyMoveState.cooldownCounter == 0) {
                bodyMoveState.baselineCenterX = currentCenterX
            }
            return
        }

        // 计算相对于基准位置的归一化累计位移（相对于身体尺度）
        val totalDeltaX = (currentCenterX - baselineCenterX) / bodyScale

        // 判断移动方向并更新计数器
        when {
            // 向左移动：累计位移为负且绝对值超过阈值
            totalDeltaX < -config.bodyMoveThreshold -> {
                bodyMoveState.leftMoveCount++
                bodyMoveState.rightMoveCount = 0  // 重置反向计数

                // 如果向左移动持续足够帧数，触发左移事件
                if (bodyMoveState.leftMoveCount >= config.bodyMoveHoldFrames) {
                    events.add(createEvent(PoseActionType.BODY_MOVE_LEFT, BodySide.LEFT, timestampMs, personId))
                    resetBodyMovement("left", bodyMoveState)
                }
            }
            // 向右移动：累计位移为正且超过阈值
            totalDeltaX > config.bodyMoveThreshold -> {
                bodyMoveState.rightMoveCount++
                bodyMoveState.leftMoveCount = 0   // 重置反向计数

                // 如果向右移动持续足够帧数，触发右移事件
                if (bodyMoveState.rightMoveCount >= config.bodyMoveHoldFrames) {
                    events.add(createEvent(PoseActionType.BODY_MOVE_RIGHT, BodySide.RIGHT, timestampMs, personId))
                    resetBodyMovement("right", bodyMoveState)
                }
            }
            // 移动量不足：表示回到了基准位置附近或停止移动
            else -> {
                // 重置两个方向的计数器
                bodyMoveState.leftMoveCount = 0
                bodyMoveState.rightMoveCount = 0
                // 更新基准位置为当前位置，以便检测下一次移动
                bodyMoveState.baselineCenterX = currentCenterX
            }
        }
    }

    /**
     * 重置身体移动检测状态
     *
     * 在检测到有效移动后调用，进入冷却期以避免重复检测同一动作。
     *
     * @param direction 检测到的移动方向（"left" 或 "right"）
     * @param bodyMoveState 身体移动状态
     */
    private fun resetBodyMovement(direction: String, bodyMoveState: BodyMoveState) {
        bodyMoveState.leftMoveCount = 0
        bodyMoveState.rightMoveCount = 0
        bodyMoveState.cooldownCounter = config.bodyMoveCooldownFrames
        bodyMoveState.lastMoveDirection = direction
        // 注意：不在这里重置baselineCenterX，而是在冷却期结束后再更新
    }


    /**
     * 使用指数移动平均（EMA）平滑关键点位置
     * 
     * @param landmarks 原始关键点列表
     * @param smoothedArray 该人的平滑数组（用于存储历史平滑结果）
     * @return 平滑后的关键点数组
     */
    private fun smooth(landmarks: List<NormalizedLandmark>, smoothedArray: Array<Point?>): Array<Point> {
        val output = Array(LANDMARK_COUNT) { Point(0f, 0f) }
        
        // 对每个关键点应用EMA平滑
        for (i in 0 until LANDMARK_COUNT) {
            // 获取当前原始位置
            val raw = Point(landmarks[i].x(), landmarks[i].y())
            
            // 获取上一帧的平滑位置
            val prev = smoothedArray[i]
            
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
            smoothedArray[i] = smoothedPoint
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
     * @param personId 人员ID
     * @return 动作事件对象
     */
    private fun createEvent(type: PoseActionType, side: BodySide, timestampMs: Long, personId: Int): PoseActionEvent {
        return PoseActionEvent(
            type = type,
            side = side,
            timestampMs = timestampMs,
            frameIndex = frameIndex,
            personId = personId
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

    // 身体移动检测的全局状态（左右移动是整体动作，不区分左右侧）
    private data class BodyMoveState(
        var baselineCenterX: Float? = null,          // 基准位置的身体中心X坐标（移动前的稳定位置）
        var leftMoveCount: Int = 0,                   // 向左移动累计帧数
        var rightMoveCount: Int = 0,                  // 向右移动累计帧数
        var cooldownCounter: Int = 0,                 // 冷却倒计时计数器
        var lastMoveDirection: String? = null         // 上次检测到的移动方向，用于冷却期判断
    )
    
    // 身体移动状态实例
    private val bodyMoveState = BodyMoveState()

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

