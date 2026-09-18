package chimahon.novel.sync.ttu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun TtuSyncSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.currentOrThrow
    val oauthManager = remember { Injekt.get<TtuOAuthManager>() }
    val syncManager = remember { Injekt.get<TtuSyncManager>() }
    val settings by syncManager.settingsFlow.collectAsState(initial = syncManager.loadSettings())
    val syncStatus = remember {
        runCatching { Injekt.get<SyncStatus>() }.getOrNull()
    }
    var ttuSyncJob by remember { mutableStateOf<Job?>(null) }

    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var authStatus by remember { mutableStateOf<DriveAuthStatus?>(null) }
    var directionMenuExpanded by remember { mutableStateOf(false) }
    var isAuthorizing by remember { mutableStateOf(false) }
    var devicePrompt by remember { mutableStateOf<DeviceCodePrompt?>(null) }
    var pollIntervalSeconds by remember { mutableStateOf(5L) }
    var message by remember { mutableStateOf<String?>(null) }
    var copyMessage by remember { mutableStateOf<String?>(null) }
    var statsModeMenuExpanded by remember { mutableStateOf(false) }
    var showAutoSyncDialog by remember { mutableStateOf(false) }

    LaunchedEffect(oauthManager) {
        clientId = oauthManager.clientId
        clientSecret = oauthManager.clientSecret
        authStatus = when {
            !oauthManager.isConfigured -> DriveAuthStatus.MissingConfiguration
            oauthManager.isConnected -> DriveAuthStatus.Connected
            else -> DriveAuthStatus.NotConnected
        }
    }

    LaunchedEffect(devicePrompt, isAuthorizing) {
        val prompt = devicePrompt ?: return@LaunchedEffect
        if (!isAuthorizing) return@LaunchedEffect
        val expiresAtMillis = System.currentTimeMillis() + prompt.expiresInSeconds * 1000L
        var nextIntervalSeconds = pollIntervalSeconds
        while (isAuthorizing && System.currentTimeMillis() < expiresAtMillis) {
            delay(nextIntervalSeconds * 1000L)
            val result = try {
                withContext(Dispatchers.IO) {
                    oauthManager.pollAuthorization(prompt)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                DriveAuthorizationPollResult.Failed(error.message ?: "Authorization failed")
            }
            when (result) {
                is DriveAuthorizationPollResult.Authorized -> {
                    isAuthorizing = false
                    devicePrompt = null
                    authStatus = DriveAuthStatus.Connected
                    message = null
                }
                DriveAuthorizationPollResult.Pending -> Unit
                DriveAuthorizationPollResult.SlowDown -> {
                    nextIntervalSeconds = nextDeviceCodePollIntervalSeconds(nextIntervalSeconds, result)
                    pollIntervalSeconds = nextIntervalSeconds
                }
                DriveAuthorizationPollResult.TransientNetworkFailure -> {
                    nextIntervalSeconds = nextDeviceCodePollIntervalSeconds(nextIntervalSeconds, result)
                    pollIntervalSeconds = nextIntervalSeconds
                    message = "Network issue. Open ${prompt.verificationUrl} and enter ${prompt.userCode} if needed."
                }
                is DriveAuthorizationPollResult.Failed -> {
                    isAuthorizing = false
                    devicePrompt = null
                    authStatus = DriveAuthStatus.Failed(result.message)
                    message = result.message
                }
            }
        }
        if (isAuthorizing) {
            isAuthorizing = false
            devicePrompt = null
            authStatus = DriveAuthStatus.NotConnected
            message = "Authorization code expired."
        }
    }

    fun connectGoogleDrive() {
        if (isAuthorizing) return
        isAuthorizing = true
        message = null
        copyMessage = null
        devicePrompt = null
        scope.launch {
            if (clientId.isBlank() || clientSecret.isBlank()) {
                isAuthorizing = false
                authStatus = DriveAuthStatus.MissingConfiguration
                message = "Configure a Google OAuth client before connecting."
                return@launch
            }
            oauthManager.saveClient(clientId, clientSecret)
            runCatching {
                withContext(Dispatchers.IO) {
                    oauthManager.requestDeviceCode()
                }
            }
                .onSuccess { prompt ->
                    pollIntervalSeconds = prompt.intervalSeconds
                    devicePrompt = prompt
                    authStatus = DriveAuthStatus.NotConnected
                    message = "Open ${prompt.verificationUrl} and enter ${prompt.userCode}."
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUrl)))
                    }
                }
                .onFailure { error ->
                    Log.e("TtuSyncAuth", "Device code request failed", error)
                    isAuthorizing = false
                    val text = error.message ?: "Authorization failed"
                    authStatus = DriveAuthStatus.Failed(text)
                    message = text
                }
        }
    }

    fun signOut() {
        scope.launch {
            oauthManager.revokeAccess()
            syncManager.clearCache()
            authStatus = if (!oauthManager.isConfigured) {
                DriveAuthStatus.MissingConfiguration
            } else {
                DriveAuthStatus.NotConnected
            }
            message = null
            copyMessage = null
            devicePrompt = null
            isAuthorizing = false
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = "TTU sync",
                navigateUp = navigator::pop,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            PreferenceGroupHeader(title = "TTU sync")

            SwitchPreferenceWidget(
                title = "Enable",
                subtitle = "Sync novel progress with ツ reader via Google Drive",
                checked = settings.enabled,
                onCheckedChanged = { checked ->
                    syncManager.updateSettings { s -> s.copy(enabled = checked) }
                },
            )

            TextPreferenceWidget(
                title = "Direction",
                subtitle = "Auto resolves by newest timestamp; Manual uses the library selection actions",
                widget = {
                    Box {
                        TextButton(onClick = { directionMenuExpanded = true }) {
                            Text(settings.mode.rawValue)
                        }
                        DropdownMenu(
                            expanded = directionMenuExpanded,
                            onDismissRequest = { directionMenuExpanded = false },
                        ) {
                            SyncMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.rawValue) },
                                    onClick = {
                                        directionMenuExpanded = false
                                        syncManager.updateSettings { s -> s.copy(mode = mode) }
                                    },
                                )
                            }
                        }
                    }
                },
            )

            val autoSyncSubtitle = remember(settings.autoSyncOnOpen, settings.autoSyncOnClose, settings.autoSyncPeriodic) {
                val parts = mutableListOf<String>()
                if (settings.autoSyncOnOpen) parts.add("On book open")
                if (settings.autoSyncOnClose) parts.add("On book close")
                if (settings.autoSyncPeriodic) parts.add("Periodic while reading")
                if (parts.isEmpty()) "Disabled" else parts.joinToString(", ")
            }

            TextPreferenceWidget(
                title = "Auto Sync",
                subtitle = autoSyncSubtitle,
                onPreferenceClick = { showAutoSyncDialog = true },
            )

            if (settings.autoSyncPeriodic) {
                var intervalMenuExpanded by remember { mutableStateOf(false) }
                val intervalOptions = listOf(1, 3, 10, 15, 30)
                TextPreferenceWidget(
                    title = "Periodic sync interval",
                    subtitle = "${settings.autoSyncIntervalMins} minutes",
                    onPreferenceClick = { intervalMenuExpanded = true },
                    widget = {
                        Box {
                            DropdownMenu(
                                expanded = intervalMenuExpanded,
                                onDismissRequest = { intervalMenuExpanded = false },
                            ) {
                                intervalOptions.forEach { mins ->
                                    DropdownMenuItem(
                                        text = { Text("$mins minutes") },
                                        onClick = {
                                            intervalMenuExpanded = false
                                            syncManager.updateSettings { s -> s.copy(autoSyncIntervalMins = mins) }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }

            if (showAutoSyncDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showAutoSyncDialog = false },
                    title = { Text("Auto Sync Triggers") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val triggers = listOf(
                                "On book open" to settings.autoSyncOnOpen,
                                "On book close" to settings.autoSyncOnClose,
                                "Periodic while reading" to settings.autoSyncPeriodic,
                            )
                            triggers.forEach { (label, checked) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            syncManager.updateSettings { s ->
                                                when (label) {
                                                    "On book open" -> s.copy(autoSyncOnOpen = !s.autoSyncOnOpen)
                                                    "On book close" -> s.copy(autoSyncOnClose = !s.autoSyncOnClose)
                                                    "Periodic while reading" -> s.copy(autoSyncPeriodic = !s.autoSyncPeriodic)
                                                    else -> s
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            syncManager.updateSettings { s ->
                                                when (label) {
                                                    "On book open" -> s.copy(autoSyncOnOpen = it)
                                                    "On book close" -> s.copy(autoSyncOnClose = it)
                                                    "Periodic while reading" -> s.copy(autoSyncPeriodic = it)
                                                    else -> s
                                                }
                                            }
                                        },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(label)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAutoSyncDialog = false }) {
                            Text("OK")
                        }
                    },
                )
            }

            PreferenceGroupHeader(title = "Google Drive")

            TextPreferenceWidget(
                title = "Google Drive",
                subtitle = when (val status = authStatus) {
                    DriveAuthStatus.Connected -> "Connected"
                    DriveAuthStatus.NotConnected -> "Not connected"
                    DriveAuthStatus.MissingConfiguration -> "OAuth client not configured"
                    is DriveAuthStatus.Failed -> status.message
                    null -> ""
                },
            )

            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Device client ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Device client secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (authStatus != DriveAuthStatus.Connected) {
                    Button(
                        onClick = { connectGoogleDrive() },
                        enabled = !isAuthorizing && clientId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudSync,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Connect Google Drive")
                    }
                } else {
                    OutlinedButton(
                        onClick = { signOut() },
                        enabled = !isAuthorizing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign out")
                    }
                }
            }

            devicePrompt?.let { prompt ->
                PreferenceGroupHeader(title = "Authorization")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Authorize Google Drive",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Open the link below and enter the device code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = prompt.verificationUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt.verificationUrl)))
                        },
                    )
                    Text(
                        text = prompt.userCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Google device code", prompt.userCode))
                            copyMessage = "Device code copied"
                        },
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Copy code")
                    }
                }
            }

            copyMessage?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            message?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = if (authStatus is DriveAuthStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (authStatus is DriveAuthStatus.Connected && settings.enabled) {
                PreferenceGroupHeader(title = "Content")

                SwitchPreferenceWidget(
                    title = "Sync reading statistics",
                    subtitle = "Merge per-day reading time and characters with Drive",
                    checked = settings.statisticsSyncEnabled,
                    onCheckedChanged = { checked ->
                        syncManager.updateSettings { s -> s.copy(statisticsSyncEnabled = checked) }
                    },
                )

                if (settings.statisticsSyncEnabled) {
                    TextPreferenceWidget(
                        title = "Statistics sync mode",
                        subtitle = "Merge keeps the larger cumulative per day; Replace overwrites with Drive",
                        widget = {
                            Box {
                                TextButton(onClick = { statsModeMenuExpanded = true }) {
                                    Text(settings.statisticsSyncMode.rawValue)
                                }
                                DropdownMenu(
                                    expanded = statsModeMenuExpanded,
                                    onDismissRequest = { statsModeMenuExpanded = false },
                                ) {
                                    StatisticsSyncMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.rawValue) },
                                            onClick = {
                                                statsModeMenuExpanded = false
                                                syncManager.updateSettings { s -> s.copy(statisticsSyncMode = mode) }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }

                TextPreferenceWidget(
                    title = "Sync all books now",
                    subtitle = "Run a one-time TTU sync for every novel",
                    onPreferenceClick = {
                        if (ttuSyncJob?.isActive == true) {
                            context.toast("Sync in progress")
                            return@TextPreferenceWidget
                        }
                        ttuSyncJob = scope.launch(Dispatchers.IO) {
                            syncStatus?.start()
                            syncStatus?.updateProgress(0f)
                            try {
                                val refs = syncManager.listBooks()
                                var imported = 0
                                var exported = 0
                                var synced = 0
                                var skipped = 0
                                var failed = 0
                                refs.forEachIndexed { index, ref ->
                                    when (val result = syncManager.syncBook(ref)) {
                                        is SyncResult.Imported -> imported++
                                        is SyncResult.Exported -> exported++
                                        is SyncResult.Synced -> synced++
                                        is SyncResult.Skipped -> skipped++
                                        is SyncResult.Failed -> {
                                            failed++
                                            Log.w("TtuSyncUi", "TTU sync failed for '${result.title}': ${result.error}")
                                        }
                                    }
                                    if (refs.isNotEmpty()) {
                                        syncStatus?.updateProgress((index + 1).toFloat() / refs.size.toFloat())
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    context.toast("TTU sync: $imported imported, $exported exported, $synced synced, $skipped skipped, $failed failed")
                                }
                            } finally {
                                syncStatus?.stop()
                            }
                        }
                    },
                )
            }

            PreferenceGroupHeader(title = "Setup")
            DeviceCodeSetup()
        }
    }
}

@Composable
private fun DeviceCodeSetup() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Device Code setup",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = GoogleCloudOAuthConfiguration.introduction,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = GoogleCloudOAuthConfiguration.ttuSetupLinkLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(GoogleCloudOAuthConfiguration.ttuSetupUrl)),
                )
            },
        )
        GoogleCloudOAuthConfiguration.instructions.forEachIndexed { index, instruction ->
            val links = GoogleCloudOAuthConfiguration.instructionLinks
            val linkColor = MaterialTheme.colorScheme.primary
            val annotated = buildAnnotatedString {
                append("${index + 1}. ")
                var cursor = 0
                while (cursor < instruction.length) {
                    val nextLink = links.keys
                        .mapNotNull { label ->
                            val start = instruction.indexOf(label, startIndex = cursor)
                            if (start >= 0) label to start else null
                        }
                        .minByOrNull { it.second }
                    if (nextLink == null) {
                        append(instruction.substring(cursor))
                        break
                    }
                    val (label, start) = nextLink
                    append(instruction.substring(cursor, start))
                    val link = links[label] ?: label
                    withLink(
                        LinkAnnotation.Url(
                            link,
                            TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        append(label)
                    }
                    cursor = start + label.length
                }
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
