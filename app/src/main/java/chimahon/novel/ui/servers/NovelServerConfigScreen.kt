package chimahon.novel.ui.servers

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.model.NovelServer
import chimahon.novel.model.NovelServerType
import chimahon.novel.source.opds.OpdsSource
import chimahon.novel.ui.browse.BrowseNovelSourceScreen
import chimahon.source.kavita.KavitaNovelSource
import chimahon.source.komga.KomgaNovelSource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class NovelServerConfigScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelServerConfigScreenModel() }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Novel Sources",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = screenModel::showAddDialog,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.strings.action_add),
                    )
                }
            },
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                when {
                    state.isLoading -> LoadingScreen()
                    state.servers.isEmpty() -> EmptyScreen(stringRes = MR.strings.information_empty_library, modifier = Modifier)
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(state.servers, key = { it.id }) { server ->
                                val source = createSourceFromServer(server)
                                ServerListItem(
                                    server = server,
                                    onBrowse = if (source != null && server.enabled) {
                                        { navigator.push(BrowseNovelSourceScreen(server, source)) }
                                    } else null,
                                    onEdit = { screenModel.showEditDialog(server) },
                                    onDelete = { screenModel.showDeleteConfirmDialog(server) },
                                    onToggleEnabled = { screenModel.toggleServerEnabled(server) },
                                )
                            }
                        }
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            is NovelServerConfigScreenModel.Dialog.AddEdit -> {
                NovelServerEditDialog(
                    existing = dialog.existing,
                    onDismissRequest = screenModel::closeDialog,
                    onConfirm = screenModel::saveServer,
                )
            }
            is NovelServerConfigScreenModel.Dialog.DeleteConfirm -> {
                AlertDialog(
                    onDismissRequest = screenModel::closeDialog,
                    title = { Text(stringResource(MR.strings.action_delete)) },
                    text = { Text(stringResource(MR.strings.action_delete)) },
                    confirmButton = {
                        TextButton(onClick = { screenModel.deleteServer(dialog.server) }) {
                            Text(stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = screenModel::closeDialog) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            null -> {}
        }
    }
}

private fun createSourceFromServer(server: NovelServer): eu.kanade.tachiyomi.sourcenovel.NovelSource? {
    if (!server.enabled) return null
    return try {
        when (server.type) {
            NovelServerType.OPDS -> OpdsSource(server)
            NovelServerType.KOMGA -> KomgaNovelSource(server)
            NovelServerType.KAVITA -> KavitaNovelSource(server)
        }
    } catch (_: Exception) { null }
}

@Composable
private fun ServerListItem(
    server: NovelServer,
    onBrowse: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onBrowse != null) Modifier.clickable(onClick = onBrowse) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${server.type.name} — ${server.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = server.enabled,
                onCheckedChange = { onToggleEnabled() },
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(MR.strings.action_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelServerEditDialog(
    existing: NovelServer?,
    onDismissRequest: () -> Unit,
    onConfirm: (NovelServer) -> Unit,
) {
    val isEdit = existing != null
    var typeExpanded by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(existing?.type ?: NovelServerType.OPDS) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                if (isEdit) stringResource(MR.strings.action_edit)
                else stringResource(MR.strings.action_add),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Type selector
                ExposedDropdownMenuBox(
                    expanded = typeExpanded,
                    onExpandedChange = { typeExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier
                            .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = typeExpanded,
                        onDismissRequest = { typeExpanded = false },
                    ) {
                        NovelServerType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name) },
                                onClick = {
                                    selectedType = type
                                    typeExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://server:8090") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()
                Text(
                    text = "Authentication (optional)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) {
                        error = "Name is required"
                        return@TextButton
                    }
                    if (baseUrl.isBlank()) {
                        error = "URL is required"
                        return@TextButton
                    }
                    val url = baseUrl.trimEnd('/')
                    val server = NovelServer(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.trim(),
                        type = selectedType,
                        baseUrl = url,
                        username = username.trim().ifBlank { null },
                        apiKey = apiKey.trim().ifBlank { null },
                        enabled = existing?.enabled ?: true,
                        displayOrder = existing?.displayOrder ?: 0,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                    onConfirm(server)
                },
            ) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
