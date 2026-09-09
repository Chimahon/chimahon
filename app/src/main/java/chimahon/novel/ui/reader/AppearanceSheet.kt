package chimahon.novel.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chimahon.novel.data.CustomReaderTheme
import chimahon.novel.data.FontManager
import chimahon.novel.data.StatisticsAutostartMode
import chimahon.novel.data.Theme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    viewModel: ReaderViewModel,
    additionalSettings: @Composable ColumnScope.() -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var importedFonts by remember { mutableStateOf(FontManager.getImportedFonts(context)) }
    val allFonts = remember(importedFonts) { FontManager.defaultFonts + importedFonts }

    var isImporting by remember { mutableStateOf(false) }
    var showCustomThemeDialog by remember { mutableStateOf(false) }
    var draftThemeName by remember { mutableStateOf("") }
    var draftBackgroundColor by remember { mutableIntStateOf(viewModel.customBackgroundColor) }
    var draftTextColor by remember { mutableIntStateOf(viewModel.customTextColor) }
    var draftBackgroundInput by remember { mutableStateOf(colorToHex(viewModel.customBackgroundColor)) }
    var draftTextInput by remember { mutableStateOf(colorToHex(viewModel.customTextColor)) }
    var renameTarget by remember { mutableStateOf<CustomReaderTheme?>(null) }
    var deleteTarget by remember { mutableStateOf<CustomReaderTheme?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var einkExpanded by remember { mutableStateOf(false) }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let {
            isImporting = true
            scope.launch {
                val success = FontManager.importFont(context, it)
                if (success) {
                    importedFonts = FontManager.getImportedFonts(context)
                }
                isImporting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )

            // Theme (moved to top)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val currentCustomTheme = viewModel.customThemes.find { it.backgroundColor == viewModel.customBackgroundColor && it.textColor == viewModel.customTextColor }
                    ?: CustomReaderTheme(
                        backgroundColor = viewModel.customBackgroundColor,
                        textColor = viewModel.customTextColor,
                    )
                val customThemeChoices = remember(
                    viewModel.customThemes,
                    viewModel.theme,
                    viewModel.customBackgroundColor,
                    viewModel.customTextColor,
                ) {
                    if (viewModel.theme == Theme.CUSTOM && currentCustomTheme !in viewModel.customThemes) {
                        listOf(currentCustomTheme) + viewModel.customThemes
                    } else {
                        viewModel.customThemes
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    readerThemeOptions.forEach { option ->
                        ReaderThemeSwatchButton(
                            label = option.label,
                            backgroundColor = option.backgroundColor,
                            textColor = option.textColor,
                            splitBackgroundColor = option.splitBackgroundColor,
                            selected = viewModel.theme == option.theme,
                            onClick = { viewModel.updateTheme(option.theme) },
                        )
                    }
                    customThemeChoices.forEachIndexed { index, customTheme ->
                        ReaderThemeSwatchButton(
                            label = customTheme.name.ifBlank { "Custom ${index + 1}" },
                            backgroundColor = customTheme.backgroundColor,
                            textColor = customTheme.textColor,
                            selected = viewModel.theme == Theme.CUSTOM &&
                                viewModel.customBackgroundColor == customTheme.backgroundColor &&
                                viewModel.customTextColor == customTheme.textColor,
                            onClick = { viewModel.applyCustomTheme(customTheme) },
                            onLongClick = {
                                renameTarget = customTheme
                                renameInput = customTheme.name
                            },
                            onDeleteClick = { deleteTarget = customTheme },
                        )
                    }
                    AddThemeButton(
                        onClick = {
                            draftThemeName = ""
                            draftBackgroundColor = viewModel.customBackgroundColor
                            draftTextColor = viewModel.customTextColor
                            draftBackgroundInput = colorToHex(viewModel.customBackgroundColor)
                            draftTextInput = colorToHex(viewModel.customTextColor)
                            showCustomThemeDialog = true
                        },
                    )
                }

                if (viewModel.theme == Theme.SYSTEM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("System uses Sepia", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = viewModel.systemLightSepia,
                            onCheckedChange = { viewModel.updateSystemLightSepia(it) },
                        )
                    }
                }
            }

            // Layout Mode
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !viewModel.continuousMode,
                        onClick = { viewModel.updateContinuousMode(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text("Paginated")
                    }
                    SegmentedButton(
                        selected = viewModel.continuousMode,
                        onClick = { viewModel.updateContinuousMode(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text("Continuous")
                    }
                }
            }

            // Typography
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Typography", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                // Font Family
                var fontExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = fontExpanded,
                    onExpandedChange = { fontExpanded = it },
                ) {
                    OutlinedTextField(
                        value = viewModel.selectedFont,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Font Family") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = fontExpanded,
                        onDismissRequest = { fontExpanded = false },
                    ) {
                        allFonts.forEach { font ->
                            DropdownMenuItem(
                                text = { Text(font) },
                                onClick = {
                                    viewModel.updateSelectedFont(font)
                                    fontExpanded = false
                                },
                            )
                        }
                    }
                }

                // Import Font Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { fontPickerLauncher.launch(arrayOf("application/font-ttf", "application/x-font-ttf", "font/ttf", "application/octet-stream")) },
                        modifier = Modifier.weight(1f),
                        enabled = !isImporting,
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Import Font")
                        }
                    }

                    // Delete imported font button
                    if (importedFonts.contains(viewModel.selectedFont)) {
                        OutlinedButton(
                            onClick = {
                                FontManager.deleteFont(context, viewModel.selectedFont)
                                importedFonts = FontManager.getImportedFonts(context)
                                viewModel.updateSelectedFont(FontManager.defaultFonts.first())
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Delete Font")
                        }
                    }
                }

                // Font Size
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Font Size", style = MaterialTheme.typography.bodyMedium)
                        Text("${if (viewModel.fontSize % 1.0 == 0.0) viewModel.fontSize.toInt() else viewModel.fontSize}px", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = viewModel.fontSize.toFloat(),
                        onValueChange = { viewModel.updateFontSize((it * 2f).roundToInt() / 2.0) },
                        valueRange = 12f..72f,
                        steps = 119,
                    )
                }

                // Line Height
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Line Height", style = MaterialTheme.typography.bodyMedium)
                        Text("%.2f".format(viewModel.lineHeight), style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = viewModel.lineHeight.toFloat(),
                        onValueChange = { viewModel.updateLineHeight((it * 20f).roundToInt() / 20.0) },
                        valueRange = 1.0f..2.5f,
                        steps = 29,
                    )
                }

                // Hide Furigana
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Hide Furigana", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = viewModel.hideFurigana,
                        onCheckedChange = { viewModel.updateHideFurigana(it) },
                    )
                }

                // Keep screen on
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Keep screen on", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = viewModel.keepScreenOn,
                        onCheckedChange = { viewModel.updateKeepScreenOn(it) },
                    )
                }
            }

            // Margins
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Margins", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Horizontal Padding", style = MaterialTheme.typography.bodyMedium)
                        Text("${if (viewModel.horizontalPadding % 1.0 == 0.0) viewModel.horizontalPadding.toInt() else viewModel.horizontalPadding}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = viewModel.horizontalPadding.toFloat(),
                        onValueChange = { viewModel.updateHorizontalPadding((it * 2f).roundToInt() / 2.0) },
                        valueRange = 0f..50f,
                        steps = 99,
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Vertical Padding", style = MaterialTheme.typography.bodyMedium)
                        Text("${if (viewModel.verticalPadding % 1.0 == 0.0) viewModel.verticalPadding.toInt() else viewModel.verticalPadding}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = viewModel.verticalPadding.toFloat(),
                        onValueChange = { viewModel.updateVerticalPadding((it * 2f).roundToInt() / 2.0) },
                        valueRange = 0f..50f,
                        steps = 99,
                    )
                }
            }

            // Layout Settings
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Layout", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

                // Writing Mode
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Writing Mode", style = MaterialTheme.typography.labelMedium)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = viewModel.verticalWriting,
                            onClick = { viewModel.updateVerticalWriting(true) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) {
                            Text("Vertical")
                        }
                        SegmentedButton(
                            selected = !viewModel.verticalWriting,
                            onClick = { viewModel.updateVerticalWriting(false) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) {
                            Text("Horizontal")
                        }
                    }
                }

                // Tap Zone Size
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Tap Zone Size (Navigation)", style = MaterialTheme.typography.bodyMedium)
                        Text("${viewModel.tapZonePercent}%", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = viewModel.tapZonePercent.toFloat(),
                        onValueChange = { viewModel.updateTapZonePercent(it.roundToInt()) },
                        valueRange = 0f..40f,
                        steps = 39,
                    )
                }

                // Advanced Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateLayoutAdvanced(!viewModel.layoutAdvanced) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Advanced", style = MaterialTheme.typography.bodyMedium)
                    Icon(
                        imageVector = if (viewModel.layoutAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (viewModel.layoutAdvanced) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                // Advanced settings - only visible when enabled
                if (viewModel.layoutAdvanced) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Avoid Page Break
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Avoid Page Break", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = viewModel.avoidPageBreak,
                                onCheckedChange = { viewModel.updateAvoidPageBreak(it) },
                                modifier = Modifier.scale(0.85f),
                            )
                        }

                        // Justify Text
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Justify Text", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = viewModel.justifyText,
                                onCheckedChange = { viewModel.updateJustifyText(it) },
                                modifier = Modifier.scale(0.85f),
                            )
                        }

                        // Character Spacing
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Character Spacing", style = MaterialTheme.typography.bodySmall)
                                Text("%.2f".format(viewModel.characterSpacing), style = MaterialTheme.typography.bodySmall)
                            }
                            Slider(
                                value = viewModel.characterSpacing.toFloat(),
                                onValueChange = { viewModel.updateCharacterSpacing((it * 20f).roundToInt() / 20.0) },
                                valueRange = 0f..0.5f,
                                steps = 9,
                            )
                        }

                        // Paragraph Spacing
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Paragraph Spacing", style = MaterialTheme.typography.bodySmall)
                                Text("%.2f em".format(viewModel.paragraphSpacing), style = MaterialTheme.typography.bodySmall)
                            }
                            Slider(
                                value = viewModel.paragraphSpacing.toFloat(),
                                onValueChange = { viewModel.updateParagraphSpacing((it * 20f).roundToInt() / 20.0) },
                                valueRange = 0f..2f,
                                steps = 39,
                            )
                        }
                    }
                }
            }

            // E-Ink
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { einkExpanded = !einkExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("E-Ink", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(
                        imageVector = if (einkExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (einkExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                if (einkExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Refresh on page turn
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Refresh on page turn", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = viewModel.einkRefreshOnPageTurn,
                                onCheckedChange = { viewModel.updateEinkRefreshOnPageTurn(it) },
                            )
                        }

                        // Refresh on scroll/movement
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Refresh on scroll", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = viewModel.einkRefreshOnScroll,
                                onCheckedChange = { viewModel.updateEinkRefreshOnScroll(it) },
                            )
                        }

                        if (viewModel.einkRefreshOnPageTurn) {
// Duration
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Duration", style = MaterialTheme.typography.bodyMedium)
                                        Text("${viewModel.einkRefreshDurationMillis} ms", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Slider(
                                        value = (viewModel.einkRefreshDurationMillis / 100).toFloat(),
                                        onValueChange = { viewModel.updateEinkRefreshDurationMillis((it * 100).roundToInt()) },
                                        valueRange = 1f..15f,
                                        steps = 14,
                                    )
                                }

                                // Delay
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("Delay", style = MaterialTheme.typography.bodyMedium)
                                        Text("${viewModel.einkRefreshDelayMillis} ms", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Slider(
                                        value = (viewModel.einkRefreshDelayMillis / 100).toFloat(),
                                        onValueChange = { viewModel.updateEinkRefreshDelayMillis((it * 100).roundToInt()) },
                                        valueRange = 0f..10f,
                                        steps = 10,
                                    )
                                }

                            // Interval
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("Every Nth page", style = MaterialTheme.typography.bodyMedium)
                                    Text("${viewModel.einkRefreshPageInterval}", style = MaterialTheme.typography.bodyMedium)
                                }
                                Slider(
                                    value = viewModel.einkRefreshPageInterval.toFloat(),
                                    onValueChange = { viewModel.updateEinkRefreshPageInterval(it.roundToInt()) },
                                    valueRange = 1f..10f,
                                    steps = 9,
                                )
                            }

                            // Color
                            Text("Refresh color", style = MaterialTheme.typography.bodyMedium)
                            val einkColor = runCatching { EinkRefreshColor.valueOf(viewModel.einkRefreshColor) }
                                .getOrDefault(EinkRefreshColor.BLACK)
                            val einkColorOptions = listOf(
                                EinkRefreshColor.BLACK to "Black",
                                EinkRefreshColor.WHITE to "White",
                                EinkRefreshColor.WHITE_BLACK to "White→Black",
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                einkColorOptions.forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = einkColor == value,
                                        onClick = { viewModel.updateEinkRefreshColor(value.name) },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = einkColorOptions.size),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Storage
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Chapter Cache Size", style = MaterialTheme.typography.bodyMedium)
                        Text("${viewModel.chapterCacheSizeMb} MB", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = viewModel.chapterCacheSizeMb.toFloat(),
                        onValueChange = { viewModel.updateChapterCacheSizeMb((it / 10f).roundToInt() * 10) },
                        valueRange = 10f..1000f,
                        steps = 98,
                    )
                }
            }

            // Statistics
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Statistics", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val statsOptions = listOf(
                    StatisticsAutostartMode.OFF to "Off",
                    StatisticsAutostartMode.ON to "On open",
                    StatisticsAutostartMode.PAGETURN to "On page turn",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    statsOptions.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = viewModel.statisticsAutostartMode == mode,
                            onClick = { viewModel.updateStatisticsAutostartMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = statsOptions.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            // Additional app-side settings (like volume buttons)
            additionalSettings()
        }
    }

    if (showCustomThemeDialog) {
        CustomThemeDialog(
            themeName = draftThemeName,
            backgroundColor = draftBackgroundColor,
            textColor = draftTextColor,
            backgroundColorInput = draftBackgroundInput,
            textColorInput = draftTextInput,
            onThemeNameChange = { draftThemeName = it },
            onBackgroundColorInputChange = { input ->
                draftBackgroundInput = input
                parseColorInput(input)?.let { draftBackgroundColor = it }
            },
            onTextColorInputChange = { input ->
                draftTextInput = input
                parseColorInput(input)?.let { draftTextColor = it }
            },
            onDismiss = { showCustomThemeDialog = false },
            onConfirm = {
                viewModel.addCustomTheme(
                    CustomReaderTheme(
                        name = draftThemeName,
                        backgroundColor = draftBackgroundColor,
                        textColor = draftTextColor,
                    ),
                )
                showCustomThemeDialog = false
            },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename theme") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Theme name") },
                    placeholder = { Text("Enter a name") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameCustomTheme(target, renameInput)
                        renameTarget = null
                    },
                    enabled = renameInput.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete theme") },
            text = { Text("Delete \"${target.name.ifBlank { "Custom theme" }}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomTheme(target)
                        deleteTarget = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private data class ReaderThemeOption(
    val theme: Theme,
    val label: String,
    val backgroundColor: Int,
    val textColor: Int,
    val splitBackgroundColor: Int? = null,
)

private val readerThemeOptions = listOf(
    ReaderThemeOption(
        theme = Theme.SYSTEM,
        label = "System",
        backgroundColor = 0xFFFFFFFF.toInt(),
        textColor = 0xFF111111.toInt(),
        splitBackgroundColor = 0xFF121212.toInt(),
    ),
    ReaderThemeOption(
        theme = Theme.LIGHT,
        label = "Light",
        backgroundColor = 0xFFFFFFFF.toInt(),
        textColor = 0xFF111111.toInt(),
    ),
    ReaderThemeOption(
        theme = Theme.DARK,
        label = "Dark",
        backgroundColor = 0xFF121212.toInt(),
        textColor = 0xFFE0E0E0.toInt(),
    ),
    ReaderThemeOption(
        theme = Theme.SEPIA,
        label = "Sepia",
        backgroundColor = 0xFFF2E2C9.toInt(),
        textColor = 0xFF3C2C1C.toInt(),
    ),
    ReaderThemeOption(
        theme = Theme.PURE_BLACK,
        label = "AMOLED",
        backgroundColor = 0xFF000000.toInt(),
        textColor = 0xFFE0E0E0.toInt(),
    ),
)

private fun colorToHex(color: Int): String {
    return "#%06X".format(color and 0x00FFFFFF)
}

private fun parseColorInput(value: String): Int? {
    val input = value.trim()
    if (input.isEmpty()) return null
    return parseHexColor(input) ?: parseRgbColor(input)
}

private fun parseHexColor(input: String): Int? {
    val rawHex = input.removePrefix("#")
    val hex = when (rawHex.length) {
        3 -> rawHex.map { "$it$it" }.joinToString("")
        6, 8 -> rawHex
        else -> return null
    }
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val parsed = hex.toLongOrNull(radix = 16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000 or parsed).toInt()
        8 -> parsed.toInt()
        else -> null
    }
}

private fun parseRgbColor(input: String): Int? {
    val body = Regex("""rgba?\((.*)\)""", RegexOption.IGNORE_CASE)
        .matchEntire(input)
        ?.groupValues
        ?.get(1)
        ?: input
    val channels = Regex("""\d{1,3}""")
        .findAll(body)
        .mapNotNull { it.value.toIntOrNull() }
        .take(3)
        .toList()
    if (channels.size != 3 || channels.any { it !in 0..255 }) return null
    val (red, green, blue) = channels
    return 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
}

@Composable
private fun ReaderThemeSwatchButton(
    label: String,
    backgroundColor: Int,
    textColor: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    splitBackgroundColor: Int? = null,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .width(84.dp)
            .height(64.dp)
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = if (onLongClick != null) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showMenu = true
                    }
                } else null,
            ),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    showMenu = false
                    onLongClick?.invoke()
                },
            )
            if (onDeleteClick != null) {
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDeleteClick()
                    },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(backgroundColor))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            ) {
                if (splitBackgroundColor != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth(0.48f)
                            .background(Color(splitBackgroundColor)),
                    )
                }
                Text(
                    text = "Aa",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(textColor),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AddThemeButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(64.dp)
            .height(64.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add custom theme",
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("New", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ColorReviewChip(
    label: String,
    parsedColor: Int?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (parsedColor == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Color(parsedColor ?: 0x00000000), RoundedCornerShape(5.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = parsedColor?.let(::colorToHex) ?: "Invalid",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (parsedColor == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CustomThemeDialog(
    themeName: String,
    backgroundColor: Int,
    textColor: Int,
    backgroundColorInput: String,
    textColorInput: String,
    onThemeNameChange: (String) -> Unit,
    onBackgroundColorInputChange: (String) -> Unit,
    onTextColorInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val parsedBackgroundColor = parseColorInput(backgroundColorInput)
    val parsedTextColor = parseColorInput(textColorInput)
    val backgroundColorValid = parsedBackgroundColor != null
    val textColorValid = parsedTextColor != null
    val previewBackgroundColor = parsedBackgroundColor ?: backgroundColor
    val previewTextColor = parsedTextColor ?: textColor

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(previewBackgroundColor),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = themeName.ifBlank { "Custom" },
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(previewTextColor),
                        )
                        Text(
                            text = "Sample reader text",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(previewTextColor),
                        )
                    }
                }

                OutlinedTextField(
                    value = themeName,
                    onValueChange = onThemeNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text("Theme name") },
                )

                OutlinedTextField(
                    value = backgroundColorInput,
                    onValueChange = onBackgroundColorInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Background") },
                    placeholder = { Text("Hex or RGB") },
                    isError = !backgroundColorValid,
                    supportingText = {
                        if (!backgroundColorValid) {
                            Text("Enter a hex or RGB color")
                        }
                    },
                )
                OutlinedTextField(
                    value = textColorInput,
                    onValueChange = onTextColorInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Text") },
                    placeholder = { Text("Hex or RGB") },
                    isError = !textColorValid,
                    supportingText = {
                        if (!textColorValid) {
                            Text("Enter a hex or RGB color")
                        }
                    },
                )

                Text("Review", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ColorReviewChip(
                        label = "Background",
                        parsedColor = parsedBackgroundColor,
                        modifier = Modifier.weight(1f),
                    )
                    ColorReviewChip(
                        label = "Text",
                        parsedColor = parsedTextColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = backgroundColorValid && textColorValid,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
