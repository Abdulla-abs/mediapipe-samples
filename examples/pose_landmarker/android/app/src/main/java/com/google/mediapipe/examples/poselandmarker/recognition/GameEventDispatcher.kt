package com.google.mediapipe.examples.poselandmarker.recognition

import java.util.concurrent.CopyOnWriteArrayList

object GameEventDispatcher {
    private val listeners = CopyOnWriteArrayList<PoseActionEventListener>()

    fun addListener(listener: PoseActionEventListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: PoseActionEventListener) {
        listeners.remove(listener)
    }

    fun dispatch(event: PoseActionEvent) {
        listeners.forEach { it.onPoseActionEvent(event) }
    }
}

