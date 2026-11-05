/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.android.launcher3.settings.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.preference.ListPreference
import androidx.preference.TwoStatePreference
import com.android.launcher3.R
import com.android.launcher3.settings.CaddyCardSelectorPreference
import com.android.launcher3.settings.preferences.ColorPreference
import com.android.launcher3.settings.preferences.CustomSeekBarPreference
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPreference(
    row: SettingsRow,
    overview: Boolean,
    highlighted: Boolean,
    revision: Int,
    onClick: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pref = row.preference
    val enabled = remember(pref, revision) { pref.isEnabled }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by
        animateFloatAsState(
            if (pressed) 0.985f else 1f,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "preference_press",
        )
    val corner by
        animateDpAsState(
            if (pressed) 18.dp else 28.dp,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "preference_shape",
        )
    val shape =
        RoundedCornerShape(
            topStart = if (overview || row.first) corner else 5.dp,
            topEnd = if (overview || row.first) corner else 5.dp,
            bottomStart = if (overview || row.last) corner else 5.dp,
            bottomEnd = if (overview || row.last) corner else 5.dp,
        )
    val color by
        animateColorAsState(
            when {
                highlighted -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceBright
            },
            MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "preference_color",
        )
    val title = pref.title?.toString().orEmpty()
    val summary =
        when (pref) {
            is TwoStatePreference ->
                (if (pref.isChecked) pref.summaryOn else pref.summaryOff)?.toString()
                    ?: pref.summary?.toString()
            else -> pref.summary?.toString()
        }
    Surface(
        modifier =
            modifier.padding(bottom = if (overview) 9.dp else 0.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = shape,
        color = color,
    ) {
        when {
            pref is CustomSeekBarPreference -> SliderPreference(pref, revision, onChanged)
            pref is CaddyCardSelectorPreference -> {
                val selected = pref.sharedPreferences?.getBoolean(pref.key, true) ?: true
                StyleChoices(
                    title = stringResource(R.string.drawer_list_title),
                    labels =
                        listOf(
                            stringResource(R.string.feed_default),
                            stringResource(R.string.caddy_beta),
                        ),
                    selectedIndex = if (selected) 0 else 1,
                    enabled = enabled,
                    kind = "caddy",
                ) { index ->
                    if (pref.callChangeListener(index == 0)) {
                        pref.sharedPreferences?.edit()?.putBoolean(pref.key, index == 0)?.apply()
                        onChanged()
                    }
                }
            }
            pref is ListPreference && pref.key in visualPreferences -> {
                StyleChoices(
                    title,
                    pref.entries.map { it.toString() },
                    pref.findIndexOfValue(pref.value),
                    enabled,
                    pref.key,
                ) { index ->
                    val value = pref.entryValues[index].toString()
                    if (pref.callChangeListener(value)) pref.value = value
                    onChanged()
                }
            }
            else -> {
                val action =
                    if (pref is TwoStatePreference) {
                        Modifier.toggleable(
                            value = pref.isChecked,
                            enabled = enabled,
                            role = Role.Switch,
                            interactionSource = interaction,
                            indication = ripple(),
                            onValueChange = {
                                pref.performClick()
                                onChanged()
                            },
                        )
                    } else if (pref.isSelectable) {
                        Modifier.clickable(
                            enabled = enabled,
                            role = Role.Button,
                            interactionSource = interaction,
                            indication = ripple(),
                            onClick = onClick,
                        )
                    } else Modifier
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .then(action)
                        .heightIn(min = if (overview) 104.dp else 76.dp)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .alpha(if (enabled) 1f else 0.38f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (overview) {
                        val icon = settingsCategoryIcon(pref.key)
                        Box(
                            Modifier.size(52.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(18.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon,
                                null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (title.isNotEmpty())
                            Text(
                                title,
                                style =
                                    if (overview) MaterialTheme.typography.titleLargeEmphasized
                                    else MaterialTheme.typography.titleMediumEmphasized,
                            )
                        if (!summary.isNullOrEmpty())
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    when {
                        pref is TwoStatePreference ->
                            Switch(
                                checked = pref.isChecked,
                                onCheckedChange = null,
                                enabled = enabled,
                                thumbContent =
                                    if (pref.isChecked) {
                                        {
                                            Icon(
                                                Icons.Rounded.Check,
                                                null,
                                                Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        }
                                    } else null,
                            )
                        pref is ColorPreference ->
                            Box(Modifier.size(32.dp).background(Color(pref.value), CircleShape))
                        pref.isSelectable ->
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderPreference(pref: CustomSeekBarPreference, revision: Int, onChanged: () -> Unit) {
    val stored = remember(pref, revision) { pref.value }
    var value by remember(pref, stored) { mutableFloatStateOf(stored.toFloat()) }
    val enabled = pref.isEnabled
    val title = pref.title?.toString().orEmpty()
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
            if (pref.hasDefaultValue())
                IconButton(
                    shapes = IconButtonDefaults.shapes(),
                    enabled = enabled && value.roundToInt() != pref.defaultValue,
                    onClick = {
                        pref.commitValue(pref.defaultValue)
                        value = pref.value.toFloat()
                        onChanged()
                    },
                ) {
                    Icon(Icons.Rounded.Restore, stringResource(R.string.search_section_order_reset))
                }
        }
        pref.summary
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            IconButton(
                shapes = IconButtonDefaults.shapes(),
                enabled = enabled && value > pref.min,
                onClick = {
                    pref.commitValue(value.roundToInt() - pref.interval)
                    value = pref.value.toFloat()
                    onChanged()
                },
            ) {
                Icon(
                    Icons.Rounded.Remove,
                    stringResource(
                        R.string.custom_seekbar_value,
                        pref.formatValue(
                            (value.roundToInt() - pref.interval).coerceAtLeast(pref.min)
                        ),
                    ),
                )
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    pref.formatValue(value.roundToInt()),
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            IconButton(
                shapes = IconButtonDefaults.shapes(),
                enabled = enabled && value < pref.max,
                onClick = {
                    pref.commitValue(value.roundToInt() + pref.interval)
                    value = pref.value.toFloat()
                    onChanged()
                },
            ) {
                Icon(
                    Icons.Rounded.Add,
                    stringResource(
                        R.string.custom_seekbar_value,
                        pref.formatValue(
                            (value.roundToInt() + pref.interval).coerceAtMost(pref.max)
                        ),
                    ),
                )
            }
        }
        Slider(
            value = value.coerceIn(pref.min.toFloat(), pref.max.toFloat()),
            onValueChange = {
                value =
                    (pref.min + ((it - pref.min) / pref.interval).roundToInt() * pref.interval)
                        .coerceIn(pref.min, pref.max)
                        .toFloat()
            },
            onValueChangeFinished = {
                pref.commitValue(value.roundToInt())
                value = pref.value.toFloat()
                onChanged()
            },
            valueRange = pref.min.toFloat()..pref.max.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
        )
    }
}

private val visualPreferences =
    setOf("pref_app_drawer_style", "pref_quickspace_style", "pref_recents_style")

@Composable
private fun StyleChoices(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    kind: String,
    onSelected: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 20.dp).alpha(if (enabled) 1f else 0.38f)) {
        Text(
            title,
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        LazyRow(
            Modifier.padding(top = 16.dp).selectableGroup(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(labels) { index, label ->
                val selected = selectedIndex == index
                val shape by
                    animateDpAsState(
                        if (selected) 28.dp else 18.dp,
                        MaterialTheme.motionScheme.fastSpatialSpec(),
                        label = "choice_shape",
                    )
                val color by
                    animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "choice_color",
                    )
                Column(
                    Modifier.width(148.dp)
                        .clip(RoundedCornerShape(shape))
                        .background(color)
                        .selectable(
                            selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(index) },
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LayoutPreview(kind, index, Modifier.fillMaxWidth().height(126.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RadioButton(
                            selected,
                            onClick = null,
                            modifier = Modifier.size(24.dp),
                            enabled = enabled,
                        )
                        Text(
                            label,
                            style =
                                if (selected) MaterialTheme.typography.labelLargeEmphasized
                                else MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LayoutPreview(kind: String, variant: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val paper = MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.tertiaryContainer
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(paper, cornerRadius = CornerRadius(16.dp.toPx()))
        val margin = w * 0.13f
        fun pill(x: Float, y: Float, width: Float, height: Float, color: Color = ink) {
            drawRoundRect(color, Offset(x, y), Size(width, height), CornerRadius(height / 2))
        }
        when (kind) {
            "pref_recents_style" -> {
                val width = w * if (variant == 2) .52f else .64f
                val shift = w * .14f
                drawRoundRect(
                    accent,
                    Offset(margin + shift, h * .14f),
                    Size(width, h * .67f),
                    CornerRadius(14.dp.toPx()),
                )
                drawRoundRect(
                    ink.copy(alpha = .75f),
                    Offset(margin, h * .2f),
                    Size(width, h * .64f),
                    CornerRadius(14.dp.toPx()),
                )
                pill(w * .38f, h * .9f, w * .24f, h * .025f)
            }
            "pref_quickspace_style" -> {
                if (variant == 2) {
                    pill(margin, h * .18f, w * .28f, h * .22f, accent)
                    pill(margin, h * .46f, w * .28f, h * .22f)
                } else {
                    pill(margin, h * .22f, w * .5f, h * .09f)
                    pill(margin, h * .37f, w * .64f, h * .04f, ink.copy(alpha = .5f))
                    if (variant == 1) pill(margin, h * .52f, w * .4f, h * .04f, accent)
                }
            }
            else -> {
                val home = kind == "home" || kind == "pref_app_drawer_style" && variant == 4
                val sheet = kind == "pref_app_drawer_style" && variant == 0
                val folders = kind == "caddy" && variant == 1
                if (folders) {
                    drawRoundRect(
                        ink.copy(alpha = .16f),
                        Offset(margin, h * .08f),
                        Size(w - margin * 2, h * .18f),
                        CornerRadius(8.dp.toPx()),
                    )
                    repeat(4) { dot ->
                        drawCircle(ink, w * .045f, Offset(w * (.23f + dot * .18f), h * .17f))
                    }
                    pill(margin, h * .88f, w - margin * 2, h * .06f, accent)
                } else if (home) {
                    pill(margin, h * .1f, w * .4f, h * .04f)
                    pill(margin, h * .18f, w * .55f, h * .025f, accent)
                    pill(margin * .7f, h * .87f, w - margin * 1.4f, h * .08f, accent)
                } else {
                    pill(margin, h * .1f, w - margin * 2, h * .09f, accent)
                }
                if (sheet) pill(w * .4f, h * .035f, w * .2f, h * .02f, ink.copy(alpha = .4f))
                val list = kind == "pref_app_drawer_style" && variant == 2
                val columns = if (list) 1 else if (folders) 2 else 4
                val rowCount = if (folders) 2 else if (home) 3 else 4
                repeat(rowCount) { row ->
                    repeat(columns) { col ->
                        val x = margin + col * (w - margin * 2) / columns
                        val y = h * .3f + row * h * if (folders) .27f else .15f
                        if (folders) {
                            drawRoundRect(
                                ink.copy(alpha = .16f),
                                Offset(x, y),
                                Size(w * .3f, h * .23f),
                                CornerRadius(8.dp.toPx()),
                            )
                            pill(x + w * .04f, y + h * .025f, w * .16f, h * .014f)
                            repeat(3) { dot ->
                                drawCircle(
                                    ink,
                                    w * .035f,
                                    Offset(
                                        x + w * (.085f + dot % 2 * .13f),
                                        y + h * (.085f + dot / 2 * .09f),
                                    ),
                                )
                            }
                            repeat(4) { dot ->
                                drawCircle(
                                    ink,
                                    w * .012f,
                                    Offset(
                                        x + w * (.185f + dot % 2 * .055f),
                                        y + h * (.15f + dot / 2 * .045f),
                                    ),
                                )
                            }
                        } else {
                            drawCircle(
                                ink.copy(alpha = if (row == 0) 1f else .55f),
                                w * .055f,
                                Offset(x + w * .055f, y),
                            )
                            if (list) pill(x + w * .19f, y - h * .017f, w * .45f, h * .035f)
                        }
                    }
                }
                if (kind == "pref_app_drawer_style" && variant == 3) {
                    repeat(3) {
                        drawCircle(
                            ink.copy(alpha = if (it == 0) 1f else .3f),
                            w * .018f,
                            Offset(w * (.42f + it * .08f), h * .92f),
                        )
                    }
                }
            }
        }
    }
}

internal fun settingsCategoryIcon(key: String?): ImageVector =
    when (key) {
        "home_screen" -> Icons.Rounded.Home
        "app_drawer" -> Icons.Rounded.Apps
        "icons" -> Icons.Rounded.Palette
        "recents" -> Icons.Rounded.ViewCarousel
        else -> Icons.Rounded.Tune
    }

@Composable
internal fun HomeOverview(modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Home, null, Modifier.size(32.dp))
                Text(
                    stringResource(R.string.edit_home_screen),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                Text(
                    stringResource(R.string.derived_app_name),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            LayoutPreview("home", 0, Modifier.width(108.dp).height(148.dp))
        }
    }
}

@Composable
internal fun settingsEntryMotion(): Modifier {
    var entered by rememberSaveable { mutableStateOf(false) }
    val alpha = remember { Animatable(if (entered) 1f else 0f) }
    val offset = remember { Animatable(if (entered) 0f else 24f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val density = LocalDensity.current.density
    LaunchedEffect(Unit) {
        entered = true
        launch { alpha.animateTo(1f, effects) }
        offset.animateTo(0f, spatial)
    }
    return Modifier.graphicsLayer {
        this.alpha = alpha.value
        translationY = offset.value * density
    }
}
