package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.ocr.ModelDownloader
import eu.kanade.tachiyomi.data.panel.PanelModelDownloader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private data class OcrModelStatus(val installed: Boolean, val bytes: Long, val version: String?)

@Composable
internal fun getOcrModelsGroup(): Preference.PreferenceGroup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocr = remember { Injekt.get<ModelDownloader>() }
    val panel = remember { Injekt.get<PanelModelDownloader>() }
    val titles = listOf(
        stringResource(MR.strings.model_ocr_lens),
        stringResource(MR.strings.model_ocr_paddle),
        stringResource(MR.strings.model_panel_detection),
    )
    val descriptions = listOf(
        "Reads text offline when Local OCR is selected.",
        "Reads text offline when Paddle OCR is selected.",
        "Finds manga panels for panel-by-panel navigation.",
    )
    var statuses by remember { mutableStateOf<List<OcrModelStatus>>(emptyList()) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var refreshing by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf(false) }

    // Downloads finish in the background. Refresh disk usage off the UI thread.
    LaunchedEffect(refreshing) {
        while (true) {
            statuses = withIOContext {
                val installed = listOf(ocr.isDownloaded, ocr.isPaddleDownloaded, panel.isDownloaded)
                val versions = listOf(ocr.lensVersionInstalled, ocr.paddleVersionInstalled, null)
                listOf("screenai_models", "paddle_ocr", "panel_detector").mapIndexed { index, directory ->
                    val bytes = File(context.filesDir, directory).walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    OcrModelStatus(installed[index], bytes, versions[index])
                }
            }
            delay(5_000)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = ocr.importFromUri(uri)
            context.toast(if (result.isSuccess) "OCR models imported" else "Import failed: ${result.exceptionOrNull()?.message}")
            refreshing++
        }
    }

    selected?.let { index ->
        val status = statuses.getOrNull(index)
        AlertDialog(
            onDismissRequest = { if (!deleting) selected = null },
            title = { Text(titles[index]) },
            text = {
                Text(
                    descriptions[index] + if (status?.installed == true) {
                        "\n\nDelete these downloaded files to free ${Formatter.formatFileSize(context, status.bytes)}? They can be downloaded again when needed."
                    } else {
                        "\n\nDownload this model to use the feature on this device."
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    if (status?.installed == true) {
                        deleting = true
                        scope.launch {
                            val deleted = withIOContext {
                                when (index) {
                                    0 -> ocr.deleteLensModels()
                                    1 -> ocr.deletePaddleModels()
                                    else -> panel.deleteModel()
                                }
                            }
                            context.toast(if (deleted) MR.strings.model_deleted else MR.strings.cache_delete_error)
                            deleting = false
                            selected = null
                            refreshing++
                        }
                    } else {
                        when (index) {
                            0 -> ocr.triggerDownload()
                            1 -> ocr.triggerPaddleDownload()
                            else -> panel.triggerDownload()
                        }
                        selected = null
                    }
                }) { Text(if (status?.installed == true) "Delete model" else "Download") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { selected = null }) { Text("Cancel") }
            },
        )
    }

    val rows = titles.mapIndexed { index, title ->
        val status = statuses.getOrNull(index)
        val state = when {
            status == null -> "Checking…"
            status.installed -> "Installed${status.version?.let { " v$it" }.orEmpty()}"
            status.bytes > 0 -> "Incomplete download"
            else -> "Not downloaded"
        }
        Preference.PreferenceItem.TextPreference(
            title = title,
            subtitle = "$state${status?.let { " · ${Formatter.formatFileSize(context, it.bytes)}" }.orEmpty()}\n${descriptions[index]}",
            enabled = status != null && !deleting,
            onClick = { selected = index },
        )
    }
    return Preference.PreferenceGroup(
        title = stringResource(MR.strings.pref_downloaded_models),
        preferenceItems = (rows + Preference.PreferenceItem.TextPreference(
            title = "Import OCR models",
            subtitle = "Import models.zip for Local OCR from device storage",
            onClick = { importLauncher.launch(arrayOf("application/zip")) },
        )).toPersistentList(),
    )
}
