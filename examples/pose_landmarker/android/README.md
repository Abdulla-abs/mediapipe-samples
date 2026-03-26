# MediaPipe Tasks Pose Landmark Detection Android Demo

### Overview

This is a camera app that can detects landmarks on a person either from continuous camera frames seen by your device's back camera, an image, or a video from the device's gallery using a custom **task** file.

The task file is downloaded by a Gradle script when you build and run the app. You don't need to do any additional steps to download task files into the project explicitly unless you wish to use your own landmark detection task. If you do use your own task file, place it into the app's *assets* directory.

This application should be run on a physical Android device to take advantage of the camera.

## Rule-based action events (front camera, on-device)

This sample now includes a lightweight action recognition layer built on top of pose landmarks for single-player front-camera gameplay.

- Real-time rule evaluation runs fully on-device (no server upload).
- Stabilization includes EMA smoothing on `(x, y)` landmarks.
- Event triggering uses consecutive-frame confirmation (`holdFrames`) to reduce jitter retriggers.
- Thresholds are normalized by body scale (shoulder/torso distance) to reduce sensitivity to camera distance.

Recognized events exposed to game logic:

- Left/right arm raise start
- Left/right arm lower start
- Left/right arm wave start
- Left/right arm wave end

Event model and recognizer implementation:

- `app/src/main/java/com/google/mediapipe/examples/poselandmarker/recognition/PoseRecognitionModels.kt`
- `app/src/main/java/com/google/mediapipe/examples/poselandmarker/recognition/RulePoseRecognizer.kt`
- `app/src/main/java/com/google/mediapipe/examples/poselandmarker/recognition/GameEventDispatcher.kt`

The camera fragment enforces the front camera path and dispatches recognized `PoseActionEvent` objects through `GameEventDispatcher`.

![Pose Landmarker Demo](pose_landmarker.png?raw=true "Pose Landmarker Demo")
[Public domain video from Lance Foss](https://www.youtube.com/watch?v=KALIKOd1pbA)

## Build the demo using Android Studio

### Prerequisites

*   The **[Android Studio](https://developer.android.com/studio/index.html)** IDE. This sample has been tested on Android Studio Dolphin.

*   A physical Android device with a minimum OS version of SDK 24 (Android 7.0 -
    Nougat) with developer mode enabled. The process of enabling developer mode
    may vary by device.

### Building

*   Open Android Studio. From the Welcome screen, select Open an existing
    Android Studio project.

*   From the Open File or Project window that appears, navigate to and select
    the mediapipe/examples/pose_landmarker/android directory. Click OK. You may
    be asked if you trust the project. Select Trust.

*   If it asks you to do a Gradle Sync, click OK.

*   With your Android device connected to your computer and developer mode
    enabled, click on the green Run arrow in Android Studio.

### Models used

Downloading, extraction, and placing the models into the *assets* folder is
managed automatically by the **download.gradle** file.