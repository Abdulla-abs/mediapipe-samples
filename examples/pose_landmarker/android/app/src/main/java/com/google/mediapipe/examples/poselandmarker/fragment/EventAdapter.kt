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
import java.util.concurrent.ConcurrentLinkedQueue

class EventAdapter(
    private val eventDisplayDurationMs: Long = 2000L,
    private val maxQueueSize: Int = 6
) : RecyclerView.Adapter<EventAdapter.EventViewHolder>() {

    private data class EventItem(
        val message: String,
        val id: Long = System.nanoTime()  // 唯一ID，用于标识事件
    )

    private val events = mutableListOf<EventItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 使用线程安全的队列来管理移除回调
    private val pendingRemovals = ConcurrentLinkedQueue<Long>()
    
    // 用于同步访问events列表的锁对象
    private val eventsLock = Any()
    
    // 用于生成唯一ID的计数器
    private var eventIdCounter: Long = 0L

    inner class EventViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val textView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_banner, parent, false) as TextView
        return EventViewHolder(textView)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        synchronized(eventsLock) {
            if (position < events.size) {
                holder.textView.text = events[position].message
            }
        }
    }

    override fun getItemCount(): Int {
        synchronized(eventsLock) {
            return events.size
        }
    }

    fun addEvent(eventMessage: String) {
        // 确保在主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { addEvent(eventMessage) }
            return
        }

        synchronized(eventsLock) {
            // 如果队列已满，立即移除最旧的事件
            while (events.size >= maxQueueSize) {
                if (events.isNotEmpty()) {
                    val oldestEvent = events.removeAt(0)
                    pendingRemovals.add(oldestEvent.id)  // 标记为待移除，取消其回调
                    notifyItemRemoved(0)
                }
            }

            // 添加新事件
            val eventItem = EventItem(eventMessage, ++eventIdCounter)
            events.add(eventItem)
            val position = events.size - 1
            notifyItemInserted(position)

            // 调度延迟移除
            scheduleRemoval(eventItem)
        }
    }

    private fun scheduleRemoval(eventItem: EventItem) {
        val removal = Runnable {
            removeEventById(eventItem.id)
        }
        mainHandler.postDelayed(removal, eventDisplayDurationMs)
    }

    private fun removeEventById(eventId: Long) {
        // 确保在主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { removeEventById(eventId) }
            return
        }

        synchronized(eventsLock) {
            // 检查是否已经被标记为移除（被新事件挤出）
            if (pendingRemovals.contains(eventId)) {
                pendingRemovals.remove(eventId)
                return  // 已经被移除，跳过
            }

            // 查找并移除事件
            val position = events.indexOfFirst { it.id == eventId }
            if (position >= 0) {
                events.removeAt(position)
                notifyItemRemoved(position)
            }
        }
    }

    fun clearAll() {
        synchronized(eventsLock) {
            val size = events.size
            events.clear()
            pendingRemovals.clear()
            notifyItemRangeRemoved(0, size)
        }
    }
}
