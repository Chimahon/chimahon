package eu.kanade.tachiyomi.ui.browse.novelextension

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.screen.browse.ExtensionReposScreen
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun novelExtensionsTab(
    novelExtensionsScreenModel: NovelExtensionsScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current

    val state by novelExtensionsScreenModel.state.collectAsState()
    var privateExtensionToUninstall by remember { mutableStateOf<Extension?>(null) }

    return TabContent(
        titleRes = MR.strings.label_novel_extensions,
        badgeNumber = state.updates.takeIf { it > 0 },
        searchEnabled = true,
        actions = persistentListOf(
            AppBar.OverflowAction(
                title = stringResource(MR.strings.action_webview_refresh),
                onClick = novelExtensionsScreenModel::findAvailableExtensions,
            ),
            AppBar.OverflowAction(
                title = stringResource(MR.strings.label_extension_repos),
                onClick = { navigator.push(ExtensionReposScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            BackHandler(enabled = state.searchQuery != null) {
                novelExtensionsScreenModel.search(null)
            }
            ExtensionScreen(
                state = state,
                contentPadding = contentPadding,
                searchQuery = state.searchQuery,
                onLongClickItem = { extension ->
                    when (extension) {
                        is Extension.Available -> novelExtensionsScreenModel.installExtension(extension)
                        is Extension.Installed -> {
                            if (!extension.isBuiltIn) {
                                if (context.isPackageInstalled(extension.pkgName)) {
                                    novelExtensionsScreenModel.uninstallExtension(extension)
                                } else {
                                    privateExtensionToUninstall = extension
                                }
                            }
                        }
                        else -> {
                            if (context.isPackageInstalled(extension.pkgName)) {
                                novelExtensionsScreenModel.uninstallExtension(extension)
                            } else {
                                privateExtensionToUninstall = extension
                            }
                        }
                    }
                },
                onClickItemCancel = novelExtensionsScreenModel::cancelInstallUpdateExtension,
                onClickUpdateAll = novelExtensionsScreenModel::updateAllExtensions,
                onOpenWebView = { extension ->
                    extension.sources.getOrNull(0)?.let {
                        navigator.push(
                            WebViewScreen(
                                url = it.baseUrl,
                                initialTitle = it.name,
                                sourceId = it.id,
                            ),
                        )
                    }
                },
                onInstallExtension = novelExtensionsScreenModel::installExtension,
                onOpenExtension = { navigator.push(ExtensionDetailsScreen(it.pkgName)) },
                onTrustExtension = { novelExtensionsScreenModel.trustExtension(it) },
                onUninstallExtension = { novelExtensionsScreenModel.uninstallExtension(it) },
                onUpdateExtension = novelExtensionsScreenModel::updateExtension,
                onRefresh = novelExtensionsScreenModel::findAvailableExtensions,
            )

            privateExtensionToUninstall?.let { extension ->
                AlertDialog(
                    title = { Text(text = stringResource(MR.strings.ext_confirm_remove)) },
                    text = { Text(text = stringResource(MR.strings.remove_private_extension_message, extension.name)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                novelExtensionsScreenModel.uninstallExtension(extension)
                                privateExtensionToUninstall = null
                            },
                        ) { Text(text = stringResource(MR.strings.ext_remove)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { privateExtensionToUninstall = null }) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                    onDismissRequest = { privateExtensionToUninstall = null },
                )
            }
        },
    )
}
