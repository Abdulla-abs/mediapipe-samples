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
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.TextView
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
import java.util.LinkedHashMap
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeEventRemovalCallbacks = LinkedHashMap<View, Runnable>()
    private val gameEventLogListener = PoseActionEventListener { event ->
        Log.i(TAG, "GameActionEvent: ${event.type} side=${event.side} ts=${event.timestampMs}")
        enqueueEventForDisplay(event)
    }

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the PoseLandmarkerHelper again when users come back
        // to the foreground.
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
        clearEventDisplayState()
        if (this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)
            viewModel.setCameraFacing(cameraFacing)

            // Close the PoseLandmarkerHelper and release resources
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        clearEventDisplayState()
        GameEventDispatcher.removeListener(gameEventLogListener)
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
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
        applyEventBannerInsets()

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the PoseLandmarkerHelper that will handle the inference
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

        // Attach listeners to UI control widgets
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
        // init bottom sheet settings

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

        // When clicked, lower pose detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseDetectionConfidence >= 0.2) {
                    poseLandmarkerHelper.minPoseDetectionConfidence -= 0.1f
                    updateControlsUi()
                }
            }
        }

        // When clicked, raise pose detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseDetectionConfidence <= 0.8) {
                    poseLandmarkerHelper.minPoseDetectionConfidence += 0.1f
                    updateControlsUi()
                }
            }
        }

        // When clicked, lower pose tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseTrackingConfidence >= 0.2) {
                    poseLandmarkerHelper.minPoseTrackingConfidence -= 0.1f
                    updateControlsUi()
                }
            }
        }

        // When clicked, raise pose tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPoseTrackingConfidence <= 0.8) {
                    poseLandmarkerHelper.minPoseTrackingConfidence += 0.1f
                    updateControlsUi()
                }
            }
        }

        // When clicked, lower pose presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPosePresenceConfidence >= 0.2) {
                    poseLandmarkerHelper.minPosePresenceConfidence -= 0.1f
                    updateControlsUi()
                }
            }
        }

        // When clicked, raise pose presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.minPosePresenceConfidence <= 0.8) {
                    poseLandmarkerHelper.minPosePresenceConfidence += 0.1f
                    updateControlsUi()
                }
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
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
                    /* no op */
                }
            }

        // When clicked, change the underlying model used for object detection
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(
            viewModel.currentModel,
            false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    p2: Int,
                    p3: Long
                ) {
                    if (this@CameraFragment::poseLandmarkerHelper.isInitialized) {
                        poseLandmarkerHelper.currentModel = p2
                        updateControlsUi()
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset Poselandmarker
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

            // Needs to be cleared instead of reinitialized because the GPU
            // delegate needs to be initialized on the thread using it when applicable
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

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectPose(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
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

    // Update UI after pose have been detected. Refresh analysis result
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

                // Force a redraw
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
        mainHandler.post {
            val binding = _fragmentCameraBinding ?: return@post
            val eventView = createEventBannerView(formatEvent(event))
            binding.eventQueueContainer.addView(eventView)
            binding.eventQueueContainer.visibility = View.VISIBLE

            val removalRunnable = Runnable {
                removeEventBanner(eventView)
            }
            activeEventRemovalCallbacks[eventView] = removalRunnable
            mainHandler.postDelayed(removalRunnable, EVENT_DISPLAY_DURATION_MS)
        }
    }

    private fun clearEventDisplayState() {
        mainHandler.post {
            activeEventRemovalCallbacks.values.forEach(mainHandler::removeCallbacks)
            activeEventRemovalCallbacks.clear()
            _fragmentCameraBinding?.eventQueueContainer?.removeAllViews()
            _fragmentCameraBinding?.eventQueueContainer?.visibility = View.GONE
        }
    }

    private fun createEventBannerView(message: String): TextView {
        return TextView(requireContext()).apply {
            text = message
            visibility = View.VISIBLE
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setBackgroundColor(0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPaddingRelative(
                resources.getDimensionPixelSize(R.dimen.event_queue_item_horizontal_padding),
                resources.getDimensionPixelSize(R.dimen.event_queue_item_vertical_padding),
                resources.getDimensionPixelSize(R.dimen.event_queue_item_horizontal_padding),
                resources.getDimensionPixelSize(R.dimen.event_queue_item_vertical_padding)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.event_queue_item_spacing)
            }
        }
    }

    private fun removeEventBanner(eventView: View) {
        activeEventRemovalCallbacks.remove(eventView)?.let(mainHandler::removeCallbacks)
        val binding = _fragmentCameraBinding ?: return
        binding.eventQueueContainer.removeView(eventView)
        if (binding.eventQueueContainer.childCount == 0) {
            binding.eventQueueContainer.visibility = View.GONE
        }
    }

    private fun formatEvent(event: PoseActionEvent): String {
        val side = if (event.side.name == "LEFT") "左侧" else "右侧"
        val action = when (event.type) {
            PoseActionType.ARM_RAISE_START -> "抬手开始"
            PoseActionType.ARM_LOWER_START -> "放下开始"
            PoseActionType.ARM_WAVE_START -> "挥动开始"
            PoseActionType.ARM_WAVE_END -> "挥动结束"
        }
        return "$side $action"
    }

}
