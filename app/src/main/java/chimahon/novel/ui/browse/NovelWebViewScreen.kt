package chimahon.novel.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.webview.WebViewScreenContent
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.webview.WebViewScreenModel
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Uses the shared browser UI with the user agent configured for novel requests. */
class NovelWebViewScreen(
    private val url: String,
    private val initialTitle: String? = null,
) : Screen(), AssistContentScreen {
    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = rememberScreenModel { WebViewScreenModel(null) }
        val headers = remember {
            mapOf("user-agent" to Injekt.get<NetworkPreferences>().defaultUserAgent().get())
        }
        WebViewScreenContent(
            onNavigateUp = { navigator.pop() },
            initialTitle = initialTitle,
            url = url,
            headers = headers,
            onUrlChange = { assistUrl = it },
            onShare = { model.shareWebpage(context, it) },
            onOpenInBrowser = { model.openInBrowser(context, it) },
            onClearCookies = model::clearCookies,
        )
        LaunchedEffect(Unit) {
            with(DiscordRPCService) {
                discordScope.launchIO { setScreen(context, DiscordScreen.WEBVIEW) }
            }
        }
    }
}
