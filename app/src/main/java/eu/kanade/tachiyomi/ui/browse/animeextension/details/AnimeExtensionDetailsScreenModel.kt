package eu.kanade.tachiyomi.ui.browse.animeextension.details

import android.content.Context
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.animeextension.interactor.AnimeExtensionSourceItem
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionSources
import eu.kanade.domain.source.anime.interactor.ToggleAnimeIncognito
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionDetailsScreenModel(
    pkgName: String,
    context: Context,
    private val network: NetworkHelper = Injekt.get(),
    private val animeExtensionManager: AnimeExtensionManager = Injekt.get(),
    private val getAnimeExtensionSources: GetAnimeExtensionSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
    private val toggleAnimeIncognito: ToggleAnimeIncognito = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<AnimeExtensionDetailsScreenModel.State>(State()) {

    private val _events: Channel<AnimeExtensionDetailsEvent> = Channel()
    val events: Flow<AnimeExtensionDetailsEvent> = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            launch {
                animeExtensionManager.installedExtensionsFlow
                    .map { it.firstOrNull { extension -> extension.pkgName == pkgName } }
                    .collectLatest { extension ->
                        if (extension == null) {
                            _events.send(AnimeExtensionDetailsEvent.Uninstalled)
                            return@collectLatest
                        }
                        mutableState.update { state ->
                            state.copy(extension = extension)
                        }
                    }
            }
            launch {
                state.collectLatest { state ->
                    if (state.extension == null) return@collectLatest
                    getAnimeExtensionSources.subscribe(state.extension)
                        .map {
                            it.sortedWith(
                                compareBy(
                                    { !it.enabled },
                                    { item ->
                                        item.source.name.takeIf { item.labelAsName }
                                            ?: LocaleHelper.getSourceDisplayName(item.source.lang, context).lowercase()
                                    },
                                ),
                            )
                        }
                        .catch { throwable ->
                            logcat(LogPriority.ERROR, throwable)
                            mutableState.update { it.copy(_sources = persistentListOf()) }
                        }
                        .collectLatest { sources ->
                            mutableState.update { it.copy(_sources = sources.toImmutableList()) }
                        }
                }
            }
            launch {
                preferences.incognitoAnimeExtensions()
                    .changes()
                    .map { pkgName in it }
                    .distinctUntilChanged()
                    .collectLatest { isIncognito ->
                        mutableState.update { it.copy(isIncognito = isIncognito) }
                    }
            }
        }
    }

    fun clearCookies() {
        val extension = state.value.extension ?: return

        val urls = extension.sources
            .filterIsInstance<AnimeHttpSource>()
            .mapNotNull { it.baseUrl.takeUnless { url -> url.isEmpty() } }
            .distinct()

        val cleared = urls.sumOf {
            try {
                network.cookieJar.remove(it.toHttpUrl())
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to clear cookies for $it" }
                0
            }
        }

        logcat { "Cleared $cleared cookies for: ${urls.joinToString()}" }
    }

    fun uninstallExtension() {
        val extension = state.value.extension ?: return
        animeExtensionManager.uninstallExtension(extension)
    }

    fun toggleSource(sourceId: Long) {
        toggleSource.await(sourceId)
    }

    fun toggleSources(enable: Boolean) {
        state.value.extension?.sources
            ?.map { it.id }
            ?.let { toggleSource.await(it, enable) }
    }

    fun toggleIncognito(enable: Boolean) {
        state.value.extension?.pkgName?.let { packageName ->
            toggleAnimeIncognito.await(packageName, enable)
        }
    }

    fun getDictionaryProfiles(): List<chimahon.anki.AnkiProfile> {
        return Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().profileStore.getProfiles()
    }

    fun getSourceProfileOverride(sourceId: Long): String {
        val key = chimahon.dictionary.DictionaryProfileResolver.sourceOverrideKey(sourceId)
        return Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().rawProfileOverride(key).get()
    }

    fun setSourceProfileOverride(sourceId: Long, profileId: String?) {
        val key = chimahon.dictionary.DictionaryProfileResolver.sourceOverrideKey(sourceId)
        if (profileId == null) {
            Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().rawProfileOverride(key).delete()
        } else {
            Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().rawProfileOverride(key).set(profileId)
        }
    }

    fun resolveAutoProfile(sourceId: Long): chimahon.anki.AnkiProfile {
        val source = Injekt.get<AnimeSourceManager>().getOrStub(sourceId)
        return Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().profileResolver.resolve(
            sourceId = 0L, // 0 to avoid hitting the source override itself
            sourceLang = source.lang,
        )
    }

    @Immutable
    data class State(
        val extension: AnimeExtension.Installed? = null,
        val isIncognito: Boolean = false,
        private val _sources: ImmutableList<AnimeExtensionSourceItem>? = null,
    ) {

        val sources: ImmutableList<AnimeExtensionSourceItem>
            get() = _sources ?: persistentListOf()

        val isLoading: Boolean
            get() = extension == null || _sources == null
    }
}

sealed interface AnimeExtensionDetailsEvent {
    data object Uninstalled : AnimeExtensionDetailsEvent
}
