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

package com.android.launcher3.popup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.AppWidgetResizeFrame
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherConstants
import com.android.launcher3.R
import com.android.launcher3.dragndrop.LauncherDragController
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.model.data.WidgetStackInfo
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.launcher3.util.PendingRequestArgs
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.widget.LauncherAppWidgetHostView
import com.android.launcher3.widget.WidgetManagerHelper
import com.android.launcher3.widget.WidgetStackView

/**
 * Controller for home screen items: folders, app pairs, and widgets. This controller does not
 * handle apps or app shortcuts. This controller handles actions for the popups such as showing and
 * dismissing them.
 */
class PopupControllerForExtraHomeScreenItems<T>(
    private val popupDataRepository: PopupDataRepository,
    private val dragController: LauncherDragController,
) : PopupController<T> where T : Context, T : ActivityContext {
    override fun show(view: View): Popup {
        val itemInfo = view.tag as ItemInfo
        val activityContext: ActivityContext = ActivityContext.lookupContext<T>(view.context)
        val container =
            PopupContainer.create<T>(
                context = view.context,
                originalView = view,
                itemInfo = itemInfo,
            )
        dragController.addDragListener(container)
        if (view is LauncherAppWidgetHostView || view is WidgetStackView) {
            addWidgetPopupContent(container, itemInfo, view, activityContext)
        } else {
            addSystemShortcuts(container, itemInfo, itemView = view, activityContext)
        }
        container.show()

        val launcher = Launcher.getLauncher(view.context)
        val cellLayout = launcher.workspace.getParentCellLayoutForView(view) ?: return container

        val resizeStrategy = DefaultPopupResizeStrategy()
        if (resizeStrategy.shouldShowResizeFrame(itemInfo, view, cellLayout)) {
            when (view) {
                is FolderIcon -> AppWidgetResizeFrame.showForFolder(view, cellLayout)

                is LauncherAppWidgetHostView -> AppWidgetResizeFrame.showForWidget(view, cellLayout)
            }
        }
        return container
    }

    private fun addWidgetPopupContent(
        popup: PopupContainer<T>,
        itemInfo: ItemInfo,
        itemView: View,
        activityContext: ActivityContext,
    ) {
        val launcher = if (activityContext is Launcher) activityContext else null
        popup.systemShortcutContainer =
            popup.inflateAndAdd(R.layout.system_shortcut_rows_container, popup)

        val activeWidgetInfo: LauncherAppWidgetInfo? = when {
            itemView is WidgetStackView -> itemView.getActiveWidgetInfo()
            itemInfo is LauncherAppWidgetInfo -> itemInfo
            else -> null
        }

        val targetPackage = activeWidgetInfo?.targetComponent?.packageName

        // 1. "Create stack" or "Add widget to stack"
        val canAddMore = if (itemView is WidgetStackView) {
            itemView.widgetCount < WidgetStackInfo.MAX_STACK_SIZE
        } else {
            true
        }

        if (canAddMore) {
            val isStack = itemView is WidgetStackView
            addShortcutRow(
                popup = popup,
                iconResId = R.drawable.ic_plus,
                labelResId = if (isStack) R.string.add_widget_to_stack else R.string.create_widget_stack,
            ) {
                AbstractFloatingView.closeAllOpenViews(activityContext)
                if (launcher != null) {
                    if (itemView is WidgetStackView) {
                        launcher.startWidgetStackPicker(null, itemView.tag as? WidgetStackInfo)
                    } else if (itemInfo is LauncherAppWidgetInfo) {
                        launcher.startWidgetStackPicker(itemInfo, null)
                    }
                }
            }
        }

        // 2. "Widget settings" (if reconfigurable)
        var isReconfigurable = false
        var appWidgetId = -1
        if (activeWidgetInfo != null) {
            appWidgetId = activeWidgetInfo.appWidgetId
            val widgetHelper = WidgetManagerHelper(itemView.context)
            val provider = widgetHelper.getLauncherAppWidgetInfo(appWidgetId, activeWidgetInfo.targetComponent)
            isReconfigurable = provider?.isReconfigurable == true
        }

        if (isReconfigurable && activeWidgetInfo != null && appWidgetId > 0) {
            addShortcutRow(
                popup = popup,
                iconResId = R.drawable.ic_setting,
                labelResId = R.string.widget_settings,
            ) {
                AbstractFloatingView.closeAllOpenViews(activityContext)
                activityContext.setWaitingForResult(
                    PendingRequestArgs.forWidgetInfo(
                        appWidgetId,
                        null,
                        activeWidgetInfo,
                    )
                )
                activityContext.appWidgetHolder?.startConfigActivity(
                    ActivityContext.lookupContext(itemView.context),
                    appWidgetId,
                    LauncherConstants.ActivityCodes.REQUEST_RECONFIGURE_APPWIDGET,
                )
            }
        }

        // 3. "App info"
        if (!targetPackage.isNullOrEmpty()) {
            addShortcutRow(
                popup = popup,
                iconResId = R.drawable.ic_info_no_shadow,
                labelResId = R.string.app_info_drop_target_label,
            ) {
                AbstractFloatingView.closeAllOpenViews(activityContext)
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", targetPackage, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                itemView.context.startActivity(intent)
            }
        }

        // 4. "Remove"
        addShortcutRow(
            popup = popup,
            iconResId = R.drawable.ic_remove_no_shadow,
            labelResId = R.string.remove_drop_target_label,
        ) {
            AbstractFloatingView.closeAllOpenViews(activityContext)
            if (itemView is WidgetStackView) {
                itemView.removeCurrentWidget(true)
            } else {
                val dropTargetHandler = activityContext.dropTargetHandler
                dropTargetHandler.prepareToUndoDelete()
                dropTargetHandler.onDeleteComplete(itemInfo, itemView)
            }
        }
    }

    private fun addShortcutRow(
        popup: PopupContainer<T>,
        iconResId: Int,
        labelResId: Int,
        action: () -> Unit,
    ) {
        val view: DeepShortcutView =
            popup.inflateAndAdd(R.layout.system_shortcut, popup.systemShortcutContainer)
        view.iconView.setBackgroundResource(iconResId)
        view.bubbleText.setText(labelResId)
        view.setOnClickListener { action() }
    }

    private fun addSystemShortcuts(
        popup: PopupContainer<T>,
        itemInfo: ItemInfo,
        itemView: View,
        activityContext: ActivityContext,
    ) {
        popup.systemShortcutContainer =
            popup.inflateAndAdd(R.layout.system_shortcut_rows_container, popup)
        if (
            itemView is FolderIcon &&
                itemInfo is FolderInfo &&
                itemInfo.spanX == 2 &&
                itemInfo.spanY == 2
        ) {
            addBigFolderStyleSelector(popup, itemInfo, itemView)
        }
        val popupData = popupDataRepository.getPopupDataByItemInfo(itemInfo)?.toList()
        popupData?.forEach { systemShortcut ->
            val view: DeepShortcutView =
                popup.inflateAndAdd(R.layout.system_shortcut, popup.systemShortcutContainer)

            view.iconView.setBackgroundResource(systemShortcut.iconResId)
            view.bubbleText.setText(systemShortcut.labelResId)

            view.tag = systemShortcut
            view.setOnClickListener {
                systemShortcut.popupAction.invoke(activityContext, itemInfo, itemView)
            }
        }
    }

    private fun addBigFolderStyleSelector(
        popup: PopupContainer<T>,
        folderInfo: FolderInfo,
        folderIcon: FolderIcon,
    ) {
        val selectorRow: View =
            popup.inflateAndAdd(R.layout.folder_style_selector_row, popup.systemShortcutContainer)
        val btn3x3 = selectorRow.findViewById<View>(R.id.folder_style_3x3)
        val btn2x2 = selectorRow.findViewById<View>(R.id.folder_style_2x2)
        val btnFeatured = selectorRow.findViewById<View>(R.id.folder_style_featured)
        val icon3x3 = selectorRow.findViewById<View>(R.id.folder_style_3x3_icon)
        val icon2x2 = selectorRow.findViewById<View>(R.id.folder_style_2x2_icon)
        val iconFeatured = selectorRow.findViewById<View>(R.id.folder_style_featured_icon)

        fun updateSelectionUi(activeStyle: Int) {
            val is3x3 = activeStyle == FolderInfo.FOLDER_STYLE_3X3
            val is2x2 = activeStyle == FolderInfo.FOLDER_STYLE_2X2
            val isFeatured = activeStyle == FolderInfo.FOLDER_STYLE_FEATURED

            btn3x3.isSelected = is3x3
            btn2x2.isSelected = is2x2
            btnFeatured.isSelected = isFeatured

            btn3x3.setBackgroundResource(
                if (is3x3) R.drawable.bg_folder_style_option_selected else 0
            )
            btn2x2.setBackgroundResource(
                if (is2x2) R.drawable.bg_folder_style_option_selected else 0
            )
            btnFeatured.setBackgroundResource(
                if (isFeatured) R.drawable.bg_folder_style_option_selected else 0
            )
            icon3x3.alpha = if (is3x3) 1.0f else 0.55f
            icon2x2.alpha = if (is2x2) 1.0f else 0.55f
            iconFeatured.alpha = if (isFeatured) 1.0f else 0.55f
        }

        updateSelectionUi(folderInfo.getBigFolderStyle())

        btn3x3.setOnClickListener {
            folderIcon.setBigFolderStyle(FolderInfo.FOLDER_STYLE_3X3)
            updateSelectionUi(FolderInfo.FOLDER_STYLE_3X3)
        }
        btn2x2.setOnClickListener {
            folderIcon.setBigFolderStyle(FolderInfo.FOLDER_STYLE_2X2)
            updateSelectionUi(FolderInfo.FOLDER_STYLE_2X2)
        }
        btnFeatured.setOnClickListener {
            folderIcon.setBigFolderStyle(FolderInfo.FOLDER_STYLE_FEATURED)
            updateSelectionUi(FolderInfo.FOLDER_STYLE_FEATURED)
        }
    }

    override fun dismiss() {
        TODO("Not yet implemented")
    }
}
