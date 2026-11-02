/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.android.launcher3.settings.compose

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.notification.NotificationListener
import com.android.launcher3.qsb.DockSearchWidgetHelper
import com.android.launcher3.search.universal.UniversalSearchResults
import com.android.launcher3.settings.NotificationDotsPreference
import com.android.launcher3.settings.SearchProviderPreference
import com.android.launcher3.settings.SearchSectionOrderPreference
import com.android.launcher3.settings.preferences.ColorPreference
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsDialog(pref: Preference, onDismiss: () -> Unit) {
    when (pref) {
        is ColorPreference -> ColorSheet(pref, onDismiss)
        is SearchProviderPreference -> SearchWidgetSheet(pref, onDismiss)
        is SearchSectionOrderPreference -> SearchOrderSheet(pref, onDismiss)
        is ListPreference ->
            ChoiceSheet(
                title = pref.dialogTitle?.toString() ?: pref.title.toString(),
                options =
                    pref.entries.mapIndexed { i, label ->
                        pref.entryValues[i].toString() to label.toString()
                    },
                selected = pref.value,
                onDismiss = onDismiss,
            ) { value ->
                if (pref.callChangeListener(value)) pref.value = value
            }
        else -> {
            val context = LocalContext.current
            val message =
                when {
                    pref is NotificationDotsPreference ->
                        context.getString(
                            R.string.msg_missing_notification_access,
                            context.getString(R.string.derived_app_name),
                        )
                    pref.key == "pref_clear_home_screen" ->
                        stringResource(R.string.remove_all_views_from_home_screen_desc)
                    else -> pref.summary?.toString().orEmpty()
                }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        pref.title.toString(),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                },
                text = { Text(message) },
                confirmButton = {
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = {
                            when {
                                pref is NotificationDotsPreference ->
                                    context.startActivity(
                                        Intent(
                                                Settings
                                                    .ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS
                                            )
                                            .putExtra(
                                                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                                ComponentName(
                                                        context,
                                                        NotificationListener::class.java,
                                                    )
                                                    .flattenToString(),
                                            )
                                    )
                                pref.key == "pref_clear_home_screen" ->
                                    LauncherAppState.getInstance(context)
                                        .clearAllViewsFromHomeScreen()
                                else -> pref.performClick()
                            }
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contentMotionScheme = MaterialTheme.motionScheme
    val sheetMotionScheme =
        remember(contentMotionScheme) {
            object : MotionScheme by contentMotionScheme {
                override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
                    tween(durationMillis = 300)
            }
        }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                state.hide()
                onDismiss()
            }
        }
    }
    MaterialTheme(motionScheme = sheetMotionScheme) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier =
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            sheetState = state,
        ) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                MaterialTheme(motionScheme = contentMotionScheme) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            modifier = Modifier.padding(bottom = 20.dp),
                        )
                        content(dismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Sheet(title, onDismiss) { dismiss ->
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(options, key = { it.first }) { (value, label) ->
                val checked = selected == value
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color =
                        if (checked) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                checked,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(value)
                                    dismiss()
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(checked, onClick = null)
                        Text(
                            label,
                            style =
                                if (checked) MaterialTheme.typography.titleMediumEmphasized
                                else MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = dismiss,
            modifier = Modifier.align(Alignment.End).padding(top = 12.dp),
        ) {
            Text(stringResource(android.R.string.cancel))
        }
    }
}

private data class SearchWidgetChoice(
    val value: String,
    val label: String,
    val info: AppWidgetProviderInfo? = null,
)

@Composable
private fun SearchWidgetSheet(pref: SearchProviderPreference, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options by
        produceState<List<SearchWidgetChoice>?>(null, context) {
            value =
                withContext(Dispatchers.IO) {
                    listOf(
                        SearchWidgetChoice(
                            "",
                            context.getString(R.string.dock_search_widget_default),
                        )
                    ) +
                        DockSearchWidgetHelper.getEligibleSearchWidgets(context).map {
                            SearchWidgetChoice(
                                it.provider.flattenToString(),
                                DockSearchWidgetHelper.getWidgetLabel(context, it),
                                it,
                            )
                        }
                }
        }
    val choices = options
    val selected = DockSearchWidgetHelper.getSelectedProviderFlattened(context)
    Sheet(stringResource(R.string.dock_search_widget_picker_title), onDismiss) { dismiss ->
        if (choices == null) {
            LoadingIndicator(Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(choices, key = { it.value }) { choice ->
                    val checked = selected == choice.value
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color =
                            if (checked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    checked,
                                    role = Role.RadioButton,
                                    onClick = {
                                        val provider = choice.info?.provider
                                        DockSearchWidgetHelper.setSelectedProvider(
                                            context,
                                            provider,
                                        )
                                        if (
                                            DockSearchWidgetHelper.supportsConfiguration(
                                                choice.info
                                            )
                                        ) {
                                            DockSearchWidgetHelper.setPendingConfiguration(
                                                context,
                                                true,
                                            )
                                        }
                                        pref.summary =
                                            DockSearchWidgetHelper.getSelectedWidgetLabel(context)
                                        dismiss()
                                    },
                                )
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DockSearchWidgetPreview(choice.info)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                RadioButton(checked, onClick = null)
                                Text(
                                    choice.label,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = dismiss,
            modifier = Modifier.align(Alignment.End).padding(top = 12.dp),
        ) {
            Text(stringResource(android.R.string.cancel))
        }
    }
}

@Composable
private fun ColorSheet(pref: ColorPreference, onDismiss: () -> Unit) {
    var color by rememberSaveable(pref.key) { mutableIntStateOf(pref.value) }
    var hex by rememberSaveable(pref.key) { mutableStateOf("%08X".format(pref.value)) }
    var hsb by rememberSaveable { mutableStateOf(false) }
    val initialHsv =
        remember(pref.key) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(pref.value, it) }
        }
    var hue by rememberSaveable(pref.key) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(pref.key) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable(pref.key) { mutableFloatStateOf(initialHsv[2]) }
    val valid = hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
    fun syncHsv(value: Int) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(value, it) }
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }
    fun update(value: Int, sync: Boolean = true) {
        color = value
        hex = "%08X".format(value)
        if (sync) syncHsv(value)
    }
    Sheet(pref.title.toString(), onDismiss) { dismiss ->
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .height(80.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(Color(color))
            )
            OutlinedTextField(
                value = hex,
                onValueChange = {
                    hex = it.removePrefix("#").take(8)
                    if (hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) {
                        color = android.graphics.Color.parseColor("#$hex")
                        syncHsv(color)
                    }
                },
                label = { Text(stringResource(R.string.color_picker_hex)) },
                prefix = { Text("#") },
                isError = !valid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement =
                    Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                listOf(R.string.color_picker_rgb, R.string.color_picker_hsb).forEachIndexed {
                    index,
                    label ->
                    ToggleButton(
                        checked = hsb == (index == 1),
                        onCheckedChange = { hsb = index == 1 },
                        shapes =
                            if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes()
                            else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                }
            }
            if (hsb) {
                val hsv = floatArrayOf(hue, saturation, brightness)
                listOf(
                        R.string.color_picker_hue,
                        R.string.color_picker_saturation,
                        R.string.color_picker_brightness,
                    )
                    .forEachIndexed { index, label ->
                        ColorChannel(
                            stringResource(label),
                            hsv[index] * if (index == 0) 1f else 100f,
                            if (index == 0) 360f else 100f,
                        ) { value ->
                            hsv[index] = value / if (index == 0) 1f else 100f
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                            update(
                                android.graphics.Color.HSVToColor(
                                    android.graphics.Color.alpha(color),
                                    hsv,
                                ),
                                false,
                            )
                        }
                    }
            } else {
                listOf(
                        R.string.color_picker_red,
                        R.string.color_picker_green,
                        R.string.color_picker_blue,
                    )
                    .forEachIndexed { index, label ->
                        val shift = (2 - index) * 8
                        ColorChannel(
                            stringResource(label),
                            ((color ushr shift) and 255).toFloat(),
                            255f,
                        ) { value ->
                            update(
                                (color and (255 shl shift).inv()) or (value.roundToInt() shl shift)
                            )
                        }
                    }
            }
            ColorChannel(
                stringResource(R.string.background_opacity_title),
                android.graphics.Color.alpha(color) / 255f * 100f,
                100f,
            ) {
                update((color and 0x00FFFFFF) or ((it / 100f * 255f).roundToInt() shl 24), false)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
            Button(
                shapes = ButtonDefaults.shapes(),
                enabled = valid,
                onClick = {
                    pref.commitValue(color)
                    dismiss()
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Composable
private fun ColorChannel(label: String, value: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value.roundToInt().toString(),
                style = MaterialTheme.typography.labelLargeEmphasized,
            )
        }
        Slider(
            value = value.coerceIn(0f, max),
            onValueChange = onChange,
            valueRange = 0f..max,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

@Composable
private fun SearchOrderSheet(pref: SearchSectionOrderPreference, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var order by rememberSaveable {
        mutableStateOf(ArrayList(UniversalSearchResults.sectionOrder(context)))
    }
    val state = rememberLazyListState()
    var dragged by remember { mutableStateOf<Int?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    fun move(from: Int, to: Int) {
        if (from in order.indices && to in order.indices && from != to) {
            order = ArrayList(order).apply { add(to, removeAt(from)) }
        }
    }
    LaunchedEffect(dragged) {
        while (dragged != null) {
            withFrameNanos {}
            val item = state.layoutInfo.visibleItemsInfo.find { it.key == dragged }
            val center = dragTop + dragDelta + (item?.size ?: 0) / 2
            val scroll =
                when {
                    center < state.layoutInfo.viewportStartOffset + edge -> -edge / 5
                    center > state.layoutInfo.viewportEndOffset - edge -> edge / 5
                    else -> 0f
                }
            if (scroll != 0f) {
                state.scrollBy(scroll)
                val target =
                    state.layoutInfo.visibleItemsInfo.find {
                        center >= it.offset && center < it.offset + it.size
                    }
                if (target != null) move(order.indexOf(dragged), order.indexOf(target.key))
            }
        }
    }
    Sheet(pref.title.toString(), onDismiss) { dismiss ->
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(order, key = { it }) { section ->
                val index = order.indexOf(section)
                val label = UniversalSearchResults.getSectionTitle(context, section).toString()
                val dragging = dragged == section
                val spatial =
                    MaterialTheme.motionScheme.defaultSpatialSpec<
                        androidx.compose.ui.unit.IntOffset
                    >()
                Surface(
                    Modifier.fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .animateItem(placementSpec = if (dragging) null else spatial)
                        .graphicsLayer {
                            if (dragging)
                                translationY =
                                    dragTop + dragDelta -
                                        (state.layoutInfo.visibleItemsInfo
                                            .find { it.key == section }
                                            ?.offset ?: 0)
                        },
                    shape = MaterialTheme.shapes.medium,
                    color =
                        if (dragging) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp).heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(
                            shapes = IconButtonDefaults.shapes(),
                            enabled = index > 0,
                            onClick = { move(index, index - 1) },
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                "$label: ${stringResource(R.string.search_section_order_drag)} ↑",
                            )
                        }
                        IconButton(
                            shapes = IconButtonDefaults.shapes(),
                            enabled = index < order.lastIndex,
                            onClick = { move(index, index + 1) },
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                "$label: ${stringResource(R.string.search_section_order_drag)} ↓",
                            )
                        }
                        Box(
                            Modifier.size(48.dp).pointerInput(section) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragged = section
                                        dragTop =
                                            state.layoutInfo.visibleItemsInfo
                                                .find { it.key == section }
                                                ?.offset
                                                ?.toFloat() ?: 0f
                                        dragDelta = 0f
                                    },
                                    onDragEnd = { dragged = null },
                                    onDragCancel = { dragged = null },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragDelta += amount.y
                                        val item =
                                            state.layoutInfo.visibleItemsInfo.find {
                                                it.key == section
                                            }
                                        val center = dragTop + dragDelta + (item?.size ?: 0) / 2
                                        val target =
                                            state.layoutInfo.visibleItemsInfo.find {
                                                center >= it.offset && center < it.offset + it.size
                                            }
                                        if (target != null)
                                            move(order.indexOf(section), order.indexOf(target.key))
                                    },
                                )
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.DragHandle,
                                stringResource(R.string.search_section_order_drag),
                            )
                        }
                    }
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(
                onClick = {
                    LauncherPrefs.get(context).put(LauncherPrefs.SEARCH_SECTION_ORDER, "")
                    dismiss()
                }
            ) {
                Text(stringResource(R.string.search_section_order_reset))
            }
            TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) }
            Button(
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    LauncherPrefs.get(context)
                        .put(LauncherPrefs.SEARCH_SECTION_ORDER, order.joinToString(","))
                    dismiss()
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}
