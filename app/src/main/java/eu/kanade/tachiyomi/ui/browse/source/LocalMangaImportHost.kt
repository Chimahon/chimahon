package eu.kanade.tachiyomi.ui.browse.source

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private val MangaPickerMimeTypes = arrayOf(
    "application/zip", "application/x-cbz",
    "application/x-rar", "application/x-cbr",
    "application/x-7z-compressed", "application/x-cb7",
    "application/x-tar", "application/x-cbt",
    "application/epub+zip", "application/json", "application/octet-stream",
)

/**
 * State holder for the shared local-import dialog (same options as the
 * Browse Sources "+" button). Set [showImportDialog] to true to open it.
 */
class LocalMangaImportState {
    var showImportDialog by mutableStateOf(false)
    var pendingImport by mutableStateOf<PendingImportData?>(null)
}

@Composable
fun rememberLocalMangaImportState(): LocalMangaImportState {
    return remember { LocalMangaImportState() }
}

/**
 * Shared local import dialogs: first the multi-option picker dialog, then the
 * destination folder dialog. Read-only reuse of [ImportHandler]; when
 * [includeNovelOption] is false only Manga Folder / Manga Files are shown
 * (used by the manga library FAB).
 */
@Composable
fun LocalMangaImportDialogs(
    state: LocalMangaImportState,
    includeNovelOption: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mangaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                state.pendingImport = PendingImportData.Files(uris)
            }
        },
    )

    val mangaFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                state.pendingImport = PendingImportData.Folder(uri)
            }
        },
    )

    val novelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                scope.launch { ImportHandler.importNovels(context, uris) }
            }
        },
    )

    if (state.showImportDialog) {
        AlertDialog(
            onDismissRequest = { state.showImportDialog = false },
            title = { Text(stringResource(MR.strings.action_add)) },
            text = { Text("Import local files to library") },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.showImportDialog = false
                        mangaFolderPicker.launch(null)
                    },
                ) {
                    Text("Manga Folder")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            state.showImportDialog = false
                            mangaPicker.launch(MangaPickerMimeTypes)
                        },
                    ) {
                        Text("Manga Files")
                    }
                    if (includeNovelOption) {
                        TextButton(
                            onClick = {
                                state.showImportDialog = false
                                novelPicker.launch(arrayOf("application/epub+zip"))
                            },
                        ) {
                            Text(stringResource(MR.strings.novel_singular))
                        }
                    }
                }
            },
        )
    }

    state.pendingImport?.let { data ->
        DestinationFolderDialog(
            pendingImportData = data,
            onDismissRequest = { state.pendingImport = null },
            onImport = { folderName ->
                state.pendingImport = null
                scope.launch {
                    when (data) {
                        is PendingImportData.Files -> ImportHandler.importMangaFiles(context, data.uris, folderName)
                        is PendingImportData.Folder -> ImportHandler.importMangaFolder(context, data.uri, folderName)
                    }
                }
            },
        )
    }
}
