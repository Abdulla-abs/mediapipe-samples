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

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.mediapipe.examples.poselandmarker.R

class EventAdapter(
    private val eventDisplayDurationMs: Long = 1000L,
    private val maxQueueSize: Int = 10
) : RecyclerView.Adapter<EventAdapter.EventViewHolder>() {

    private data class EventItem(val message: String)

    private val events = mutableListOf<EventItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val removalCallbacks = mutableMapOf<EventItem, Runnable>()

    inner class EventViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val textView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_banner, parent, false) as TextView
        return EventViewHolder(textView)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.textView.text = events[position].message
    }

    override fun getItemCount(): Int = events.size

    fun addEvent(eventMessage: String) {
        // If queue is full, remove the oldest item
        if (events.size >= maxQueueSize) {
            removeEventAt(0)
        }

        val eventItem = EventItem(eventMessage)
        events.add(eventItem)
        val position = events.size - 1
        notifyItemInserted(position)

        // Schedule removal after display duration
        scheduleRemoval(eventItem)
    }

    private fun scheduleRemoval(eventItem: EventItem) {
        val removal = Runnable {
            removeEvent(eventItem)
        }
        removalCallbacks[eventItem] = removal
        mainHandler.postDelayed(removal, eventDisplayDurationMs)
    }

    private fun removeEvent(eventItem: EventItem) {
        // Cancel any existing removal callback
        removalCallbacks.remove(eventItem)?.let { mainHandler.removeCallbacks(it) }

        val position = events.indexOf(eventItem)
        if (position >= 0) {
            events.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    private fun removeEventAt(position: Int) {
        if (position < 0 || position >= events.size) return
        
        val eventItem = events[position]
        removeEvent(eventItem)
    }

    fun clearAll() {
        removalCallbacks.values.forEach { mainHandler.removeCallbacks(it) }
        removalCallbacks.clear()
        val size = events.size
        events.clear()
        notifyItemRangeRemoved(0, size)
    }
}
