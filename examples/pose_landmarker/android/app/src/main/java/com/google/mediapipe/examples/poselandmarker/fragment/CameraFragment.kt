/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.recognition.GameEventDispatcher
import com.google.mediapipe.examples.poselandmarker.recognition.PoseActionEvent
import com.google.mediapipe.examples.poselandmarker.recognition.PoseActionType
import com.google.mediapipe.examples.poselandmarker.recognition.PoseActionEventListener
import com.google.mediapipe.examples.poselandmarker.recognition.RulePoseRecognizer
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Pose Landmarker"
        private const val EVENT_DISPLAY_DURATION_MS = 1000L
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private lateinit var rulePoseRecognizer: RulePoseRecognizer
    private lateinit var eventAdapter: EventAdapter
    private val gameEventLogListener = PoseActionEventListener { event ->
        Log.i(TAG, "GameActionEvent: ${event.type} side=${event.side} ts=${event.timestampMs}")
        enqueueEventForDisplay(event)
    }

    /** 阻塞式机器学习操作通过此执行器执行 */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // 确保所有权限仍然存在，因为用户可能在应用暂停状态时移除了权限。
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // 当用户回到前台时重新启动 PoseLandmarkerHelper。
        backgroundExecutor.execute {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::eventAdapter.isInitialized) {
            eventAdapter.clearAll()
        }
        if (this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)
            viewModel.setCameraFacing(cameraFacing)

            // 关闭 PoseLandmarkerHelper 并释放资源
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        if (this::eventAdapter.isInitialized) {
            eventAdapter.clearAll()
        }
        GameEventDispatcher.removeListener(gameEventLogListener)
        _fragmentCameraBinding = null
        super.onDestroyView()

        // 关闭后台执行器
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraFacing = CameraSelector.LENS_FACING_FRONT
        viewModel.setCameraFacing(cameraFacing)
        rulePoseRecognizer = RulePoseRecognizer()
        GameEventDispatcher.addListener(gameEventLogListener)
        
        // 使用 EventAdapter 初始化 RecyclerView
        eventAdapter = EventAdapter(
            eventDisplayDurationMs = EVENT_DISPLAY_DURATION_MS,
            maxQueueSize = 10
        )
        fragmentCameraBinding.eventQueueContainer.adapter = eventAdapter
        
        applyEventBannerInsets()

        // 初始化后台执行器
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // 等待视图正确布局完成
        fragmentCameraBinding.viewFinder.post {
            // 设置相机及其用例
            setUpCamera()
        }

        // 创建处理推理的 PoseLandmarkerHelper
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = this
            )
        }

        // 为 UI 控制组件添加监听器
        initBottomSheetControls()
        initCameraControls()
    }

    private fun initCameraControls() {
        fragmentCameraBinding.cameraFlipButton.visibility = View.GONE
    }

    private fun applyEventBannerInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(fragmentCameraBinding.eventQueueContainer) { view, insets ->
            val statusTopInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val extraTopSpacing = resources.getDimensionPixelSize(R.dimen.event_queue_top_spacing)

            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusTopInset + extraTopSpacing
            }
            insets
        }
        ViewCompat.requestApplyInsets(fragmentCameraBinding.eventQueueContainer)
    }

    private fun initBottomSheetControls() {
        // 初始化底部设置页

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence
            )

        // 点击时降低姿态检测分数阈值下限
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseDetectionConfidence >= 0.2) {
                    poseLandmarkerHelper.minPoseDetectionConfidence -= 0.1f
                    updateControlsUi()
                }
            }
        }

        // 点击时提高姿态检测分数阈值下限
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseDetectionConfidence <= 0.8) {
                    poseLandmarkerHelper.minPoseDetectionConfidence += 0.1f
                    updateControlsUi()
                }
            }
        }

        // 点击时降低姿态跟踪分数阈值下限
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseTrackingConfidence >= 0.2) {
                    poseLandmarkerHelper.minPoseTrackingConfidence -= 0.1f
                    updateControlsUi()
                }
            }
        }

        // 点击时提高姿态跟踪分数阈值下限
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseTrackingConfidence <= 0.8) {
                    poseLandmarkerHelper.minPoseTrackingConfidence += 0.1f
                    updateControlsUi()
                }
            }
        }

        // 点击时降低姿态存在分数阈值下限
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPosePresenceConfidence >= 0.2) {
                    poseLandmarkerHelper.minPosePresenceConfidence -= 0.1f
                    updateControlsUi()
                }
            }
        }

        // 点击时提高姿态存在分数阈值下限
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPosePresenceConfidence <= 0.8) {
                    poseLandmarkerHelper.minPosePresenceConfidence += 0.1f
                    updateControlsUi()
                }
            }
        }

        // 点击时更改用于推理的底层硬件。
        // 当前选项为 CPU 和 GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    if (this@CameraFragment::poseLandmarkerHelper.isInitialized) {
                        poseLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* 无操作 */
                }
            }

        // 点击时更改用于姿态检测的底层模型
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(
            viewModel.currentModel,
            false
        )
                fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    if (this@CameraFragment::poseLandmarkerHelper.isInitialized) {
                        poseLandmarkerHelper.currentModel = p2
                        updateControlsUi()
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* 无操作 */
                }
            }
    }

    // 更新底部设置页中显示的值。重置 Poselandmarker
    // helper.
    private fun updateControlsUi() {
        if (this::poseLandmarkerHelper.isInitialized) {
            fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPoseDetectionConfidence
                )
            fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPoseTrackingConfidence
                )
            fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPosePresenceConfidence
                )

            // 需要清除而不是重新初始化，因为 GPU
            // 委托需要在适用时在使用它的线程上初始化
            backgroundExecutor.execute {
                poseLandmarkerHelper.clearPoseLandmarker()
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            fragmentCameraBinding.overlay.clear()
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // 声明并绑定预览、捕获和分析用例
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // 预览。仅使用 4:3 比例，因为这最接近我们的模型
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // 图像分析。使用 RGBA 8888 以匹配我们的模型工作方式
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // 然后将分析器分配给实例
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectPose(image)
                    }
                }

        // 在重新绑定用例之前必须解除绑定
        cameraProvider.unbindAll()

        try {
            // 可以在这里传递可变数量的用例 -
            // 相机提供对 CameraControl 和 CameraInfo 的访问
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // 将取景器的表面提供程序附加到预览用例
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if (this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        } else {
            imageProxy.close()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // 姿态检测后更新 UI。刷新分析结果
    override fun onResults(
        resultBundle: PoseLandmarkerHelper.ResultBundle
    ) {
        val poseResult = resultBundle.results.firstOrNull() ?: return
        val events = rulePoseRecognizer.process(
            result = poseResult,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
        events.forEach(::dispatchGameEvent)

        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)
                fragmentCameraBinding.overlay.setResults(
                    poseResult,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                // 强制重绘
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    PoseLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }

    private fun dispatchGameEvent(event: PoseActionEvent) {
        GameEventDispatcher.dispatch(event)
    }

    private fun enqueueEventForDisplay(event: PoseActionEvent) {
        val binding = _fragmentCameraBinding ?: return
        val eventMessage = formatEvent(event)
        
        activity?.runOnUiThread {
            eventAdapter.addEvent(eventMessage)
            binding.eventQueueContainer.visibility = View.VISIBLE
        }
    }

    private fun formatEvent(event: PoseActionEvent): String {
        val side = if (event.side.name == "LEFT") "左侧" else "右侧"
        val action = when (event.type) {
            PoseActionType.ARM_RAISE_START -> "抬手开始"
            PoseActionType.ARM_RAISE_END -> "放下开始"
            PoseActionType.ARM_WAVE_START -> "挥动开始"
            PoseActionType.ARM_WAVE_END -> "挥动结束"
        }
        return "$side $action"
    }

}
