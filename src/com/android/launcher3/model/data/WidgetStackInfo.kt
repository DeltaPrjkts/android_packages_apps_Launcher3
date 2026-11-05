/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.model.data

import com.android.launcher3.LauncherSettings
import com.android.launcher3.util.ContentWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * A type of collection that stacks up to [MAX_STACK_SIZE] widgets of the same size together,
 * allowing horizontal swiping between them.
 */
class WidgetStackInfo() : CollectionInfo() {

    private val widgets = mutableListOf<LauncherAppWidgetInfo>()
    private val listeners = CopyOnWriteArrayList<OnStackChangeListener>()

    var currentIndex: Int = 0
        set(value) {
            val clamped = if (widgets.isEmpty()) 0 else max(0, min(value, widgets.size - 1))
            if (field != clamped) {
                field = clamped
                listeners.forEach { it.onCurrentIndexChanged(clamped) }
            }
        }

    init {
        itemType = LauncherSettings.Favorites.ITEM_TYPE_WIDGET_STACK
    }

    constructor(initialWidgets: List<LauncherAppWidgetInfo>) : this() {
        initialWidgets.forEach(this::add)
    }

    constructor(widgetStackInfo: WidgetStackInfo) : this() {
        copyFrom(widgetStackInfo)
        currentIndex = widgetStackInfo.currentIndex
        widgetStackInfo.widgets.forEach { widgets.add(it) }
    }

    override fun add(item: ItemInfo) {
        if (item !is LauncherAppWidgetInfo) {
            return
        }
        if (widgets.size >= MAX_STACK_SIZE) {
            return
        }
        if (!widgets.contains(item)) {
            item.container = id
            item.rank = widgets.size
            widgets.add(item)
            listeners.forEach { it.onWidgetAdded(item) }
        }
    }

    fun removeWidget(item: LauncherAppWidgetInfo) {
        if (widgets.remove(item)) {
            listeners.forEach { it.onWidgetRemoved(item) }
            if (currentIndex >= widgets.size) {
                currentIndex = max(0, widgets.size - 1)
            }
        }
    }

    fun getWidgets(): List<LauncherAppWidgetInfo> = widgets

    override fun getContents(): List<ItemInfo> = widgets

    override fun getAppContents(): List<WorkspaceItemInfo> = emptyList()

    fun getActiveWidget(): LauncherAppWidgetInfo? {
        if (widgets.isEmpty()) return null
        val idx = max(0, min(currentIndex, widgets.size - 1))
        return widgets[idx]
    }

    fun addListener(listener: OnStackChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: OnStackChangeListener) {
        listeners.remove(listener)
    }

    override fun onAddToDatabase(writer: ContentWriter) {
        super.onAddToDatabase(writer)
        writer.put(LauncherSettings.Favorites.OPTIONS, currentIndex)
    }

    interface OnStackChangeListener {
        fun onWidgetAdded(info: LauncherAppWidgetInfo)
        fun onWidgetRemoved(info: LauncherAppWidgetInfo)
        fun onCurrentIndexChanged(index: Int)
    }

    companion object {
        const val MAX_STACK_SIZE = 4
    }
}
