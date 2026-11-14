package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import chimahon.DictionaryRepository
import chimahon.anki.AnkiProfile
import chimahon.diagnostics.LookupProfiler
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.ui.graphics.Color as ComposeColor

class ProcessTextLookupActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ProcessTextLookupRequest.fromIntent(intent) ?: run {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setFinishOnTouchOutside(true)

        // Start the query here, not from the popup's first effect. The overlay's first
        // composition is several hundred ms of cold Compose work, and the lookup used to run
        // only after it finished; starting both makes the query disappear inside that window.
        LookupProfilingAndroid.install(this)
        val dictionaryPreferences = Injekt.get<DictionaryPreferences>()
        val activeProfile = dictionaryPreferences.profileStore.getActiveProfile()
        val repository = Injekt.get<DictionaryRepository>()
        val profilingId = LookupProfiler.start(request.query)
        val deferredLookup = lifecycleScope.async(Dispatchers.Default) {
            val paths = getDictionaryPaths(this@ProcessTextLookupActivity, activeProfile)
            repository.lookupWithSearchResolution(request.query, paths, activeProfile, profilingId)
        }

        setComposeContent {
            ProcessTextLookupOverlay(
                query = request.query,
                activeProfile = activeProfile,
                repository = repository,
                deferredLookup = deferredLookup,
                onClose = ::finish,
            )
        }
    }
}

@Composable
private fun ProcessTextLookupOverlay(
    query: String,
    activeProfile: AnkiProfile,
    repository: DictionaryRepository,
    deferredLookup: Deferred<DictionaryRepository.LookupResult2>,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val density = LocalDensity.current
    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val webView = rememberDictionaryPopupWebView(activeProfile.languageCode)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
    ) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val popupHeightPx = with(density) {
            dictionaryPreferences.popupHeight().get().dp
                .coerceAtMost(maxHeight * 0.8f)
                .toPx()
        }
        val anchorY = ((screenHeightPx - popupHeightPx) / 2f - with(density) { 8.dp.toPx() })
            .coerceAtLeast(0f)

        Box(modifier = Modifier.fillMaxSize()) {
            OcrLookupPopup(
                visible = true,
                lookupString = query,
                fullText = query,
                charOffset = 0,
                onDismiss = onClose,
                webView = webView,
                repository = repository,
                anchorX = screenWidthPx / 2f,
                anchorY = anchorY,
                anchorWidth = 1f,
                anchorHeight = 1f,
                isVertical = false,
                activeProfile = activeProfile,
                initialLookupDeferred = deferredLookup,
                type = "novel",
                usePopup = false,
            )
        }
    }
}
