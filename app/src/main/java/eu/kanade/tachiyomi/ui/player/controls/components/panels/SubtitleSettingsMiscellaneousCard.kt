/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
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

package eu.kanade.tachiyomi.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlignVerticalCenter
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WidthNormal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.player.components.ExpandableCard
import eu.kanade.presentation.player.components.SliderItem
import eu.kanade.tachiyomi.ui.player.controls.CARDS_MAX_WIDTH
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.toFixed
import eu.kanade.tachiyomi.ui.player.controls.panelCardsColors
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.utils.SubtitleBlurConfig
import eu.kanade.tachiyomi.ui.player.utils.SubtitleBlurShader
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.delay
import tachiyomi.core.common.preference.deleteAndGet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun SubtitlesMiscellaneousCard(modifier: Modifier = Modifier) {
    val preferences = remember { Injekt.get<SubtitlePreferences>() }
    var isExpanded by remember { mutableStateOf(true) }
    ExpandableCard(
        isExpanded,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                Icon(Icons.Default.Tune, null)
                Text(stringResource(MR.strings.player_sheets_sub_misc_title))
            }
        },
        onExpand = { isExpanded = !isExpanded },
        modifier.widthIn(max = CARDS_MAX_WIDTH),
        colors = panelCardsColors(),
    ) {
        Column {
            var subScale by remember {
                mutableStateOf(MPVLib.getPropertyDouble("sub-scale").toFloat())
            }
            var subPos by remember {
                mutableStateOf(
                    preferences.subtitlePos().get().coerceIn(
                        SubtitlePreferences.POSITION_MIN,
                        SubtitlePreferences.POSITION_MAX,
                    ),
                )
            }
            SliderItem(
                label = stringResource(MR.strings.player_sheets_sub_scale),
                value = subScale,
                valueText = subScale.toFixed(2).toString(),
                onChange = {
                    subScale = it
                    preferences.subtitleFontScale().set(it)
                    MPVLib.setPropertyDouble("sub-scale", it.toDouble())
                },
                max = 5f,
                icon = {
                    Icon(
                        Icons.Default.FormatSize,
                        null,
                    )
                },
            )
            SliderItem(
                label = stringResource(MR.strings.player_sheets_sub_position),
                value = subPos,
                valueText = subPos.toString(),
                onChange = {
                    val position = it.coerceIn(
                        SubtitlePreferences.POSITION_MIN,
                        SubtitlePreferences.POSITION_MAX,
                    )
                    subPos = position
                    preferences.subtitlePos().set(position)
                    MPVLib.setPropertyInt("sub-pos", position)
                },
                max = SubtitlePreferences.POSITION_MAX,
                min = SubtitlePreferences.POSITION_MIN,
                icon = {
                    Icon(
                        Icons.Default.AlignVerticalCenter,
                        null,
                    )
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall))
            val context = LocalContext.current
            var blurEnabled by remember { mutableStateOf(preferences.subtitleBlurEnabled().get()) }
            var blurStrength by remember { mutableIntStateOf(preferences.subtitleBlurStrength().get()) }
            var blurDim by remember { mutableIntStateOf(preferences.subtitleBlurDimOpacity().get()) }
            var blurHeight by remember { mutableIntStateOf(preferences.subtitleBlurHeight().get()) }
            var blurPosition by remember { mutableIntStateOf(preferences.subtitleBlurPosition().get()) }
            var blurWidth by remember { mutableIntStateOf(preferences.subtitleBlurWidth().get()) }
            var blurInitialized by remember { mutableStateOf(false) }

            LaunchedEffect(blurEnabled, blurStrength, blurDim, blurHeight, blurPosition, blurWidth) {
                if (!blurInitialized) {
                    blurInitialized = true
                    return@LaunchedEffect
                }
                if (blurEnabled) {
                    delay(40L)
                }
                SubtitleBlurShader.apply(
                    context = context,
                    config = SubtitleBlurConfig(
                        enabled = blurEnabled,
                        heightPercent = blurHeight,
                        positionPercent = blurPosition,
                        widthPercent = blurWidth,
                        blurStrength = blurStrength,
                        dimOpacity = blurDim,
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
                ) {
                    Icon(Icons.Default.BlurOn, null)
                    Text(
                        text = "Subtitle blur",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = blurEnabled,
                    onCheckedChange = {
                        blurEnabled = it
                        preferences.subtitleBlurEnabled().set(it)
                    },
                )
            }

            if (blurEnabled) {
                SliderItem(
                    label = "Blur strength",
                    value = blurStrength,
                    valueText = "$blurStrength%",
                    onChange = {
                        val value = it.coerceIn(
                            SubtitlePreferences.BLUR_STRENGTH_MIN,
                            SubtitlePreferences.BLUR_STRENGTH_MAX,
                        )
                        blurStrength = value
                        preferences.subtitleBlurStrength().set(value)
                    },
                    min = SubtitlePreferences.BLUR_STRENGTH_MIN,
                    max = SubtitlePreferences.BLUR_STRENGTH_MAX,
                    icon = { Icon(Icons.Default.BlurOn, null) },
                )
                SliderItem(
                    label = "Background opacity",
                    value = blurDim,
                    valueText = "$blurDim%",
                    onChange = {
                        val value = it.coerceIn(
                            SubtitlePreferences.BLUR_DIM_MIN,
                            SubtitlePreferences.BLUR_DIM_MAX,
                        )
                        blurDim = value
                        preferences.subtitleBlurDimOpacity().set(value)
                    },
                    min = SubtitlePreferences.BLUR_DIM_MIN,
                    max = SubtitlePreferences.BLUR_DIM_MAX,
                    icon = { Icon(Icons.Default.Opacity, null) },
                )
                SliderItem(
                    label = "Blur height",
                    value = blurHeight,
                    valueText = "$blurHeight%",
                    onChange = {
                        val value = it.coerceIn(
                            SubtitlePreferences.BLUR_HEIGHT_MIN,
                            SubtitlePreferences.BLUR_HEIGHT_MAX,
                        )
                        blurHeight = value
                        preferences.subtitleBlurHeight().set(value)
                    },
                    min = SubtitlePreferences.BLUR_HEIGHT_MIN,
                    max = SubtitlePreferences.BLUR_HEIGHT_MAX,
                    icon = { Icon(Icons.Default.Height, null) },
                )
                SliderItem(
                    label = "Blur position",
                    value = blurPosition,
                    valueText = "$blurPosition%",
                    onChange = {
                        val value = it.coerceIn(
                            SubtitlePreferences.BLUR_POSITION_MIN,
                            SubtitlePreferences.BLUR_POSITION_MAX,
                        )
                        blurPosition = value
                        preferences.subtitleBlurPosition().set(value)
                    },
                    min = SubtitlePreferences.BLUR_POSITION_MIN,
                    max = SubtitlePreferences.BLUR_POSITION_MAX,
                    icon = { Icon(Icons.Default.AlignVerticalCenter, null) },
                )
                SliderItem(
                    label = "Blur width",
                    value = blurWidth,
                    valueText = "$blurWidth%",
                    onChange = {
                        val value = it.coerceIn(
                            SubtitlePreferences.BLUR_WIDTH_MIN,
                            SubtitlePreferences.BLUR_WIDTH_MAX,
                        )
                        blurWidth = value
                        preferences.subtitleBlurWidth().set(value)
                    },
                    min = SubtitlePreferences.BLUR_WIDTH_MIN,
                    max = SubtitlePreferences.BLUR_WIDTH_MAX,
                    icon = { Icon(Icons.Default.WidthNormal, null) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = MaterialTheme.padding.medium, bottom = MaterialTheme.padding.medium),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        preferences.subtitlePos().deleteAndGet().let {
                            subPos = it
                            MPVLib.setPropertyInt("sub-pos", it)
                        }
                        preferences.subtitleFontScale().deleteAndGet().let {
                            subScale = it
                            MPVLib.setPropertyDouble("sub-scale", it.toDouble())
                        }
                        blurEnabled = preferences.subtitleBlurEnabled().deleteAndGet()
                        blurStrength = preferences.subtitleBlurStrength().deleteAndGet()
                        blurDim = preferences.subtitleBlurDimOpacity().deleteAndGet()
                        blurHeight = preferences.subtitleBlurHeight().deleteAndGet()
                        blurPosition = preferences.subtitleBlurPosition().deleteAndGet()
                        blurWidth = preferences.subtitleBlurWidth().deleteAndGet()
                    },
                ) {
                    Row {
                        Icon(Icons.Default.EditOff, null)
                        Text(stringResource(MR.strings.action_reset))
                    }
                }
            }
        }
    }
}
