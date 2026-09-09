package eu.kanade.tachiyomi.ui.browse.novelextension.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.plugin.NovelPluginDescriptor
import chimahon.novel.plugin.NovelPluginManager
import chimahon.novel.ui.browse.BrowseNovelSourceScreen
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionDetailsScreenModel(
    private val pkgName: String,
    private val pluginManager: NovelPluginManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) : ScreenModel {

    data class State(
        val name: String = "",
        val version: String = "",
        val lang: String = "",
        val site: String = "",
        val iconUrl: String = "",
        val sourceId: Long = 0L,
        val isInstalled: Boolean = true,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state = _state.asStateFlow()

    init {
        screenModelScope.launch {
            if (pkgName.startsWith("js.")) {
                val id = pkgName.removePrefix("js.")
                pluginManager.catalog.collectLatest { cat ->
                    val inst = cat.installed.find { it.descriptor.id == id }
                    val desc = inst?.descriptor ?: cat.available.find { it.id == id }
                    val source = cat.sources.find {
                        val simple = it as? chimahon.novel.plugin.SimpleLNReaderSource
                        simple?.pluginId == id
                    }
                    if (desc != null) {
                        _state.value = State(
                            name = desc.name,
                            version = desc.version,
                            lang = desc.normalizedLanguage(),
                            site = desc.site,
                            iconUrl = desc.iconUrl,
                            sourceId = source?.id ?: 0L,
                            isInstalled = inst != null,
                        )
                    }
                }
            } else {
                extensionManager.installedNovelExtensionsFlow.collectLatest { exts ->
                    val ext = exts.find { it.pkgName == pkgName }
                    if (ext != null) {
                        _state.value = State(
                            name = ext.name,
                            version = ext.versionName,
                            lang = ext.lang,
                            site = "",
                            iconUrl = "",
                            sourceId = ext.novelSources.firstOrNull()?.id ?: 0L,
                            isInstalled = true,
                        )
                    }
                }
            }
        }
    }

    fun uninstall(onSuccess: () -> Unit) {
        screenModelScope.launch {
            if (pkgName.startsWith("js.")) {
                pluginManager.uninstall(pkgName.removePrefix("js."))
            } else {
                val ext = extensionManager.installedNovelExtensionsFlow.value.find { it.pkgName == pkgName }
                if (ext != null) extensionManager.uninstallExtension(ext)
            }
            onSuccess()
        }
    }
}

data class NovelExtensionDetailsScreen(
    val pkgName: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val screenModel = rememberScreenModel { NovelExtensionDetailsScreenModel(pkgName) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = state?.name ?: stringResource(MR.strings.label_extensions),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val ext = state
            if (ext == null) {
                EmptyScreen(
                    stringRes = MR.strings.empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ext.iconUrl.isNotBlank()) {
                            AsyncImage(
                                model = ext.iconUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(MaterialTheme.shapes.small),
                            )
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ext.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "v${ext.version} • ${LocaleHelper.getSourceDisplayName(ext.lang, context)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "@LNReader",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (ext.site.isNotBlank()) {
                            OutlinedButton(
                                onClick = { runCatching { uriHandler.openUri(ext.site) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(MR.strings.action_open_in_browser))
                            }
                        }
                        if (ext.isInstalled) {
                            Button(
                                onClick = { screenModel.uninstall { navigator.pop() } },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(stringResource(MR.strings.ext_uninstall))
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider()
                }

                item {
                    Text(
                        text = stringResource(MR.strings.label_sources),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ext.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = LocaleHelper.getSourceDisplayName(ext.lang, context),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (ext.sourceId != 0L) {
                                Button(
                                    onClick = {
                                        navigator.push(BrowseNovelSourceScreen(null, ext.sourceId))
                                    },
                                ) {
                                    Text(stringResource(MR.strings.browse))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
