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
import java.util.Collections
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
            val clamped =
                value.coerceIn(0, if (widgets.isEmpty()) MAX_STACK_SIZE - 1 else widgets.lastIndex)
            if (field != clamped) {
                field = clamped
                listeners.forEach { it.onCurrentIndexChanged(clamped) }
            }
        }

    init {
        itemType = LauncherSettings.Favorites.ITEM_TYPE_WIDGET_STACK
    }

    constructor(initialWidgets: List<LauncherAppWidgetInfo>) : this() {
        initialWidgets.firstOrNull()?.let {
            spanX = it.spanX
            spanY = it.spanY
            minSpanX = spanX
            minSpanY = spanY
        }
        initialWidgets.forEach(this::add)
    }

    constructor(widgetStackInfo: WidgetStackInfo) : this() {
        copyFrom(widgetStackInfo)
        widgetStackInfo.widgets.forEach { widgets.add(it) }
        currentIndex = widgetStackInfo.currentIndex
    }

    fun canAdd(item: ItemInfo): Boolean =
        widgets.size < MAX_STACK_SIZE &&
            widgets.none { it === item } &&
            hasMatchingSize(item, spanX, spanY)

    override fun add(item: ItemInfo) {
        if (item !is LauncherAppWidgetInfo || !canAdd(item)) {
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
        val activeWidget = getActiveWidget()
        if (widgets.remove(item)) {
            widgets.forEachIndexed { rank, widget -> widget.rank = rank }
            currentIndex =
                activeWidget?.let(widgets::indexOf)?.takeIf { it >= 0 }
                    ?: currentIndex.coerceAtMost(max(0, widgets.lastIndex))
            listeners.forEach { it.onWidgetRemoved(item) }
        }
    }

    fun getWidgets(): List<LauncherAppWidgetInfo> = Collections.unmodifiableList(widgets)

    override fun getContents(): List<ItemInfo> = getWidgets()

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

        @JvmStatic
        fun hasMatchingSize(item: ItemInfo, spanX: Int, spanY: Int): Boolean =
            (item.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET ||
                item.itemType == LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET) &&
                spanX > 0 && spanY > 0 && item.spanX == spanX && item.spanY == spanY
    }
}
