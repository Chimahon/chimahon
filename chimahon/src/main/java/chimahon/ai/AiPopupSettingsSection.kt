package chimahon.ai

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AiPopupSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiPrefs = remember(context) { AiPreferences(context) }

    var useCustomAiPopup by remember { mutableStateOf(aiPrefs.useCustomAiPopup) }
    var savedMainKey by remember { mutableStateOf(aiPrefs.groqApiKey) }
    var savedBackupKey by remember { mutableStateOf(aiPrefs.groqBackupApiKey) }
    var showKeysDialog by remember { mutableStateOf(false) }

    var availableModels by remember { mutableStateOf(aiPrefs.cachedGroqModels) }
    var selectedModel by remember { mutableStateOf(aiPrefs.groqModel) }
    var isRefreshingModels by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    var selectedDesign by remember { mutableStateOf(aiPrefs.popupDesign) }
    var showDesignDialog by remember { mutableStateOf(false) }

    var selectedLanguage by remember { mutableStateOf(aiPrefs.languageMode) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var isBackupBusy by remember { mutableStateOf(false) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBackupBusy = true
            try {
                val exportResult = WordCollectionRepository.exportBackupJson(context)
                exportResult.fold(
                    onSuccess = { jsonContent ->
                        val writeResult = withContext(Dispatchers.IO) {
                            runCatching {
                                val out = context.contentResolver.openOutputStream(uri)
                                    ?: throw IllegalStateException("Unable to open destination file.")
                                out.use { stream ->
                                    stream.write(jsonContent.toByteArray(Charsets.UTF_8))
                                    stream.flush()
                                }
                            }
                        }
                        writeResult.fold(
                            onSuccess = {
                                Toast.makeText(context, "Collections backup saved", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: "Failed to write backup file",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            context,
                            err.message ?: "Failed to prepare collections backup",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            } finally {
                isBackupBusy = false
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBackupBusy = true
            try {
                val readResult = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Unable to read selected backup file.")
                        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                }
                readResult.fold(
                    onSuccess = { rawJson ->
                        val importResult = WordCollectionRepository.importBackupJson(context, rawJson)
                        importResult.fold(
                            onSuccess = { summary ->
                                val msg = buildString {
                                    append("Imported ${summary.wordsImported} word(s)")
                                    append(" (${summary.collectionsAdded} new, ${summary.collectionsMerged} merged)")
                                    if (summary.wordsSkipped > 0) {
                                        append(" • ${summary.wordsSkipped} skipped")
                                    }
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            },
                            onFailure = { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: "Failed to import backup",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    },
                    onFailure = { err ->
                        Toast.makeText(
                            context,
                            err.message ?: "Failed to open backup file",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            } finally {
                isBackupBusy = false
            }
        }
    }

    val keysStatusSubtitle = remember(savedMainKey, savedBackupKey) {
        when {
            savedMainKey.isNotEmpty() && savedBackupKey.isNotEmpty() ->
                "Main & backup keys configured"
            savedMainKey.isNotEmpty() ->
                "Main key configured (no backup key)"
            savedBackupKey.isNotEmpty() ->
                "Backup key configured (main key missing)"
            else ->
                "Not configured — tap to add Groq API keys"
        }
    }

    val currentDesignLabel = remember(selectedDesign) {
        AiPreferences.POPUP_DESIGN_OPTIONS
            .firstOrNull { it.first == selectedDesign }
            ?.second
            ?: AiPreferences.POPUP_DESIGN_OPTIONS.first().second
    }

    val currentLanguageLabel = remember(selectedLanguage) {
        AiPreferences.LANGUAGE_MODE_OPTIONS
            .firstOrNull { it.first == selectedLanguage }
            ?.second
            ?: AiPreferences.LANGUAGE_MODE_OPTIONS.first().second
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1. Enable / Disable Custom AI Popup
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val next = !useCustomAiPopup
                    useCustomAiPopup = next
                    aiPrefs.useCustomAiPopup = next
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Use Custom AI Dictionary Popup",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Replace standard Yomitan lookup dialog with the custom AI breakdown popup",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = useCustomAiPopup,
                onCheckedChange = { checked ->
                    useCustomAiPopup = checked
                    aiPrefs.useCustomAiPopup = checked
                },
            )
        }

        // 2. Groq API Keys (Single clickable preference row -> Material 3 AlertDialog)
        AiSettingsPreferenceRow(
            icon = Icons.Rounded.Key,
            title = "Groq API Keys",
            subtitle = keysStatusSubtitle,
            onClick = { showKeysDialog = true },
        )

        // 3. Groq Model (Clickable row + inline Refresh icon action)
        AiSettingsPreferenceRow(
            icon = Icons.Rounded.Memory,
            title = "Groq Model",
            subtitle = selectedModel,
            onClick = { showModelDialog = true },
            trailingContent = {
                IconButton(
                    onClick = {
                        if (savedMainKey.isEmpty() && savedBackupKey.isEmpty()) {
                            Toast.makeText(context, "Configure a Groq API key first", Toast.LENGTH_SHORT).show()
                            return@IconButton
                        }
                        scope.launch {
                            isRefreshingModels = true
                            val result = CustomAiDictionaryEngine.fetchAvailableModels(savedMainKey, savedBackupKey)
                            isRefreshingModels = false
                            result.fold(
                                onSuccess = { fetched ->
                                    aiPrefs.cachedGroqModels = fetched
                                    availableModels = fetched
                                    Toast.makeText(
                                        context,
                                        "Loaded ${fetched.size} chat models from Groq",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = { err ->
                                    Toast.makeText(
                                        context,
                                        err.message ?: "Failed to fetch Groq models",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    },
                    enabled = !isRefreshingModels,
                ) {
                    if (isRefreshingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh Groq models",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        // 4. Popup Design
        AiSettingsPreferenceRow(
            icon = Icons.Rounded.Palette,
            title = "Popup Design",
            subtitle = currentDesignLabel,
            onClick = { showDesignDialog = true },
        )

        // 5. Prompt Language Mode
        AiSettingsPreferenceRow(
            icon = Icons.Rounded.Language,
            title = "Prompt Language Mode",
            subtitle = currentLanguageLabel,
            onClick = { showLanguageDialog = true },
        )

        // 6. Collections JSON Backup & Restore (Create backup / Import backup side by side)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                Text(
                    text = "Collections Backup",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Save or restore your word collections as a JSON file in Downloads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        createBackupLauncher.launch("chimahon_ai_backup_$dateStamp.json")
                    },
                    enabled = !isBackupBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Create backup",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                OutlinedButton(
                    onClick = {
                        importBackupLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream")
                        )
                    },
                    enabled = !isBackupBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Import backup",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // Dialog: Groq API Keys (API Main Key + API Backup Key [optional])
    if (showKeysDialog) {
        var mainKeyDraft by remember { mutableStateOf(savedMainKey) }
        var backupKeyDraft by remember { mutableStateOf(savedBackupKey) }
        var showMainKey by remember { mutableStateOf(false) }
        var showBackupKey by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showKeysDialog = false },
            title = { Text("Groq API Keys") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = mainKeyDraft,
                        onValueChange = { mainKeyDraft = it },
                        label = { Text("API Main Key") },
                        placeholder = { Text("gsk_...") },
                        singleLine = true,
                        visualTransformation = if (showMainKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showMainKey = !showMainKey }) {
                                Icon(
                                    imageVector = if (showMainKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showMainKey) "Hide main key" else "Show main key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = backupKeyDraft,
                        onValueChange = { backupKeyDraft = it },
                        label = { Text("API Backup Key (optional)") },
                        placeholder = { Text("gsk_...") },
                        supportingText = {
                            Text("Automatically used as failover if the main key hits a rate limit.")
                        },
                        singleLine = true,
                        visualTransformation = if (showBackupKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showBackupKey = !showBackupKey }) {
                                Icon(
                                    imageVector = if (showBackupKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showBackupKey) "Hide backup key" else "Show backup key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        aiPrefs.groqApiKey = mainKeyDraft
                        aiPrefs.groqBackupApiKey = backupKeyDraft
                        savedMainKey = aiPrefs.groqApiKey
                        savedBackupKey = aiPrefs.groqBackupApiKey
                        showKeysDialog = false
                        val toastMsg = when {
                            savedMainKey.isNotEmpty() && savedBackupKey.isNotEmpty() ->
                                "Main and backup Groq API keys saved"
                            savedMainKey.isNotEmpty() ->
                                "Main Groq API key saved"
                            savedBackupKey.isNotEmpty() ->
                                "Backup Groq API key saved"
                            else ->
                                "Groq API keys cleared"
                        }
                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeysDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Dialog: Groq Model Selection & Manual Model ID Override
    if (showModelDialog) {
        var modelInputDraft by remember { mutableStateOf(selectedModel) }
        val modelChoices = remember(availableModels, selectedModel) {
            (listOf(selectedModel) + availableModels)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }

        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Groq Model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = modelInputDraft,
                        onValueChange = { modelInputDraft = it },
                        label = { Text("Model ID") },
                        placeholder = { Text(AiPreferences.DEFAULT_MODEL) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HorizontalDivider()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        modelChoices.forEach { modelId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { modelInputDraft = modelId }
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RadioButton(
                                    selected = modelInputDraft.trim() == modelId,
                                    onClick = { modelInputDraft = modelId },
                                )
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val resolved = modelInputDraft.trim().ifEmpty { AiPreferences.DEFAULT_MODEL }
                        aiPrefs.groqModel = resolved
                        selectedModel = resolved
                        if (resolved !in availableModels) {
                            val updated = (listOf(resolved) + availableModels).distinct()
                            aiPrefs.cachedGroqModels = updated
                            availableModels = updated
                        }
                        showModelDialog = false
                        Toast.makeText(context, "Model set to $resolved", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Dialog: Popup Design
    if (showDesignDialog) {
        AiSingleChoiceDialog(
            title = "Popup Design",
            options = AiPreferences.POPUP_DESIGN_OPTIONS,
            selectedId = selectedDesign,
            onSelect = { id ->
                selectedDesign = id
                aiPrefs.popupDesign = id
                showDesignDialog = false
            },
            onDismiss = { showDesignDialog = false },
        )
    }

    // Dialog: Prompt Language Mode
    if (showLanguageDialog) {
        AiSingleChoiceDialog(
            title = "Prompt Language Mode",
            options = AiPreferences.LANGUAGE_MODE_OPTIONS,
            selectedId = selectedLanguage,
            onSelect = { id ->
                selectedLanguage = id
                aiPrefs.languageMode = id
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

@Composable
private fun AiSettingsPreferenceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailingContent?.invoke()
    }
}

@Composable
private fun AiSingleChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(id) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RadioButton(
                            selected = selectedId == id,
                            onClick = { onSelect(id) },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}