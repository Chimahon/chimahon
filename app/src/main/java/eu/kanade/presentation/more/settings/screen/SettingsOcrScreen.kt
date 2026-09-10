package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chimahon.ocr.OcrCacheManager
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.ocr.ModelDownloader
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

internal enum class OcrScaleAxis(val label: String) {
    X("X"),
    Y("Y"),
}

object SettingsOcrScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes(): StringResource = MR.strings.pref_category_ocr

    @Composable
    override fun getPreferences(): List<Preference> {
        val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val cacheManager = remember { Injekt.get<OcrCacheManager>() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        val ocrEnginePref = dictionaryPreferences.ocrEngine()
        val ocrEngine by ocrEnginePref.collectAsState()

        val parallelOcrLimitPref = dictionaryPreferences.parallelOcrLimit()
        val parallelOcrLimit by parallelOcrLimitPref.collectAsState()

        val ocrBoxScaleXPref = dictionaryPreferences.ocrBoxScaleX()
        val ocrBoxScaleX by ocrBoxScaleXPref.collectAsState()
        val ocrBoxScaleYPref = dictionaryPreferences.ocrBoxScaleY()
        val ocrBoxScaleY by ocrBoxScaleYPref.collectAsState()

        val ocrBoxOpacityPref = dictionaryPreferences.ocrBoxOpacity()
        val ocrBoxOpacity by ocrBoxOpacityPref.collectAsState()

        val activeOcrTextOpacityPref = dictionaryPreferences.activeOcrTextOpacity()
        val activeOcrTextOpacity by activeOcrTextOpacityPref.collectAsState()

        val activeOcrBgOpacityPref = dictionaryPreferences.activeOcrBgOpacity()
        val activeOcrBgOpacity by activeOcrBgOpacityPref.collectAsState()

        val ocrOutlineVisiblePref = readerPreferences.ocrOutlineVisible()

        val ocrButtonSizePref = dictionaryPreferences.ocrButtonSize()
        val ocrButtonSize by ocrButtonSizePref.collectAsState()

        val ocrButtonAlphaPref = dictionaryPreferences.ocrButtonAlpha()
        val ocrButtonAlpha by ocrButtonAlphaPref.collectAsState()

        val ocrButtonColorPref = dictionaryPreferences.ocrButtonColor()
        val ocrButtonColor by ocrButtonColorPref.collectAsState()

        val videoOcrAudioPaddingPref = dictionaryPreferences.videoOcrSentenceAudioPaddingSeconds()
        val videoOcrAudioPadding by videoOcrAudioPaddingPref.collectAsState()

        val parallelOcrSubtitle = when {
            parallelOcrLimit == 1 -> "1 chapter (Recommended - safe and stable)"
            ocrEngine == "local" || ocrEngine == "paddle" -> "$parallelOcrLimit chapters (Running multiple OCR tasks on-device simultaneously will increase battery drain and cause the device to heat up)"
            else -> "$parallelOcrLimit chapters (Running multiple OCR tasks online simultaneously may cause temporary rate limits or IP blocks)"
        }

        var cacheSizeText by remember { mutableStateOf<String?>(null) }
        var isClearingCache by remember { mutableStateOf(false) }

        return listOf(
            // 1. Engine
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_engine),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = ocrEnginePref,
                        entries = persistentListOf(
                            "cloud" to "Cloud (Google Lens)",
                            *if (eu.kanade.tachiyomi.BuildConfig.HAS_LOCAL_OCR) {
                                arrayOf(
                                    "local" to "Local (On-Device)",
                                    "paddle" to "Paddle OCR (On-Device)",
                                )
                            } else {
                                emptyArray()
                            },
                        ).associate { it.first to it.second }.toPersistentMap(),
                        title = "OCR Engine",
                        onValueChanged = { value ->
                            if (value == "local") {
                                Injekt.get<ModelDownloader>().triggerDownload()
                            }
                            if (value == "paddle") {
                                Injekt.get<ModelDownloader>().triggerPaddleDownload()
                            }
                            true
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = parallelOcrLimit,
                        title = "Concurrent OCR tasks",
                        subtitle = parallelOcrSubtitle,
                        valueRange = 1..5,
                        steps = 3,
                        onValueChanged = { parallelOcrLimitPref.set(it) },
                    ),
                ),
            ),

            // 2. OCR Boxes
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_ocr_boxes),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_dict_ocr_box_scale),
                        content = {
                            var selectedAxis by remember { mutableStateOf(OcrScaleAxis.X) }
                            val selectedValue = when (selectedAxis) {
                                OcrScaleAxis.X -> ocrBoxScaleX
                                OcrScaleAxis.Y -> ocrBoxScaleY
                            }
                            val setSelectedValue: (Float) -> Unit = { value ->
                                val rounded = ((value * 10f).roundToInt() / 10f).coerceIn(0.5f, 3.0f)
                                when (selectedAxis) {
                                    OcrScaleAxis.X -> ocrBoxScaleXPref.set(rounded)
                                    OcrScaleAxis.Y -> ocrBoxScaleYPref.set(rounded)
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.pref_dict_ocr_box_scale),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            ocrBoxScaleXPref.set(1.0f)
                                            ocrBoxScaleYPref.set(1.0f)
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = "Reset OCR box scale",
                                        )
                                    }
                                }
                                Text(
                                    text = "X ${String.format("%.1fx", ocrBoxScaleX)}  Y ${String.format("%.1fx", ocrBoxScaleY)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    OcrScaleAxis.entries.forEachIndexed { index, axis ->
                                        SegmentedButton(
                                            selected = selectedAxis == axis,
                                            onClick = { selectedAxis = axis },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index,
                                                OcrScaleAxis.entries.size,
                                            ),
                                        ) {
                                            Text(axis.label)
                                        }
                                    }
                                }
                                Slider(
                                    value = selectedValue.coerceIn(0.5f, 3.0f),
                                    onValueChange = setSelectedValue,
                                    valueRange = 0.5f..3.0f,
                                    steps = 24,
                                )
                            }
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (ocrBoxOpacity * 100).toInt(),
                        title = stringResource(MR.strings.pref_dict_ocr_box_opacity),
                        subtitle = "${(ocrBoxOpacity * 100).toInt()}%",
                        valueRange = 0..100 step 5,
                        steps = 19,
                        onValueChanged = { ocrBoxOpacityPref.set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (activeOcrTextOpacity * 100).toInt(),
                        title = stringResource(MR.strings.pref_dict_active_ocr_text_opacity),
                        subtitle = "${(activeOcrTextOpacity * 100).toInt()}%",
                        valueRange = 0..100 step 5,
                        steps = 19,
                        onValueChanged = { activeOcrTextOpacityPref.set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (activeOcrBgOpacity * 100).toInt(),
                        title = stringResource(MR.strings.pref_dict_active_ocr_bg_opacity),
                        subtitle = "${(activeOcrBgOpacity * 100).toInt()}%",
                        valueRange = 0..100 step 5,
                        steps = 19,
                        onValueChanged = { activeOcrBgOpacityPref.set(it / 100f) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = ocrOutlineVisiblePref,
                        title = "Show outlines",
                        subtitle = "Draw bounding borders around detected text regions",
                    ),
                ),
            ),

            // 3. Screen Overlay
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_screen_overlay),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = ocrButtonSize,
                        title = stringResource(MR.strings.pref_dict_ocr_button_size),
                        subtitle = "${ocrButtonSize}dp",
                        valueRange = 40..96 step 2,
                        steps = 27,
                        onValueChanged = { ocrButtonSizePref.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = (ocrButtonAlpha * 100).toInt(),
                        title = stringResource(MR.strings.pref_dict_ocr_button_alpha),
                        subtitle = "${(ocrButtonAlpha * 100).toInt()}%",
                        valueRange = 10..100 step 5,
                        steps = 17,
                        onValueChanged = { ocrButtonAlphaPref.set(it / 100f) },
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(MR.strings.pref_dict_ocr_button_color),
                        content = {
                            val btnCtx = LocalContext.current
                            val defaultColor = ContextCompat.getColor(btnCtx, eu.kanade.tachiyomi.R.color.tachiyomi_primary)
                            val presets = listOf(
                                "Default" to defaultColor,
                                "White" to Color.White.toArgb(),
                                "Black" to Color.Black.toArgb(),
                                "Red" to Color(0xFFD32F2F).toArgb(),
                                "Orange" to Color(0xFFF57C00).toArgb(),
                                "Amber" to Color(0xFFFFA000).toArgb(),
                                "Green" to Color(0xFF388E3C).toArgb(),
                                "Teal" to Color(0xFF00897B).toArgb(),
                                "Blue" to Color(0xFF1976D2).toArgb(),
                                "Indigo" to Color(0xFF3949AB).toArgb(),
                                "Purple" to Color(0xFF7B1FA2).toArgb(),
                                "Pink" to Color(0xFFC2185B).toArgb(),
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    presets.forEach { (name, argb) ->
                                        FilterChip(
                                            selected = (if (argb == defaultColor) 0 else argb) == ocrButtonColor,
                                            onClick = {
                                                ocrButtonColorPref.set(if (argb == defaultColor) 0 else argb)
                                            },
                                            label = { Text(name) },
                                            leadingIcon = {
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color(argb)),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    ),
                ),
            ),

            // 4. Behavior
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_behavior),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrTwoFingerGestureEnabled(),
                        title = stringResource(MR.strings.pref_ocr_two_finger_gesture),
                        subtitle = stringResource(MR.strings.pref_ocr_two_finger_gesture_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.ocrAutoOnDownload(),
                        title = stringResource(MR.strings.pref_ocr_auto_on_download),
                        subtitle = stringResource(MR.strings.pref_ocr_auto_on_download_summary),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = videoOcrAudioPadding,
                        title = "Video OCR sentence audio padding",
                        subtitle = "${videoOcrAudioPadding}s before and after the current video time",
                        valueRange = 1..15,
                        steps = 13,
                        onValueChanged = { videoOcrAudioPaddingPref.set(it) },
                    ),
                ),
            ),

            // 5. Cache Storage
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_cache_storage),
                preferenceItems = persistentListOf(
                    run {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            cacheSizeText = withIOContext { cacheManager.getReadableSize() }
                        }
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.pref_ocr_clear_cache),
                            subtitle = "Current cached OCR results: ${cacheSizeText ?: "..."}",
                            onClick = {
                                if (!isClearingCache) {
                                    isClearingCache = true
                                    scope.launch {
                                        val freedSpace = cacheSizeText ?: "0 B"
                                        withIOContext { cacheManager.clear() }
                                        cacheSizeText = withIOContext { cacheManager.getReadableSize() }
                                        isClearingCache = false
                                        withUIContext {
                                            context.toast("Cleared OCR cache ($freedSpace freed)")
                                        }
                                    }
                                }
                            },
                        )
                    },
                ),
            ),
        )
    }
}
