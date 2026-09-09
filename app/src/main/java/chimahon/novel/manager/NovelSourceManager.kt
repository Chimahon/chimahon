package chimahon.novel.manager

import chimahon.novel.plugin.NovelPluginManager
import chimahon.novel.source.NovelLocalSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.domain.source.novel.model.StubNovelSource
import tachiyomi.domain.source.novel.repository.StubNovelSourceRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelSourceManager(
    private val pluginManager: NovelPluginManager = Injekt.get(),
    private val stubNovelSourceRepository: StubNovelSourceRepository = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allSourcesFlow = MutableStateFlow<Map<Long, NovelsPageSource>>(emptyMap())
    @Volatile private var stubMap: Map<Long, NovelsPageSource> = emptyMap()
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    val catalogueSources: Flow<List<NovelsPageSource>> = allSourcesFlow.map { it.values.toList() }

    init {
        // Stubs for uninstalled extensions, kept warm so detail screens never
        // block the UI thread on a database read.
        scope.launch {
            stubNovelSourceRepository.subscribeAll().collect { stubs ->
                stubMap = stubs.associate { it.id to it }
            }
        }
        scope.launch {
            pluginManager.catalog.collect { catalog ->
                val installedSources = catalog.installed.map { inst ->
                    chimahon.novel.plugin.SimpleLNReaderSource(
                        context = Injekt.get<android.content.Context>().applicationContext,
                        pluginId = inst.descriptor.id,
                        pluginName = inst.descriptor.name,
                        pluginLang = inst.descriptor.normalizedLanguage(),
                        siteUrl = inst.descriptor.site,
                        jsCode = "",
                        codeUrl = inst.descriptor.codeUrl,
                    ) as NovelsPageSource
                }
                // Local imports are always present.
                val localSource = NovelLocalSource(
                    Injekt.get<android.content.Context>().applicationContext,
                )
                val sources = listOf(localSource) + installedSources
                allSourcesFlow.value = sources.associateBy { it.id }

                // Record stub info for every loaded source
                sources.forEach { source ->
                    try {
                        stubNovelSourceRepository.upsertStubSource(source.id, source.lang, source.name)
                    } catch (_: Exception) {}
                }
                _isInitialized.value = true
            }
        }
    }

    fun getNovelSource(sourceId: Long): NovelsPageSource? {
        return allSourcesFlow.value[sourceId]
    }

    fun getOrStub(sourceId: Long): NovelsPageSource {
        getNovelSource(sourceId)?.let { return it }
        return stubMap[sourceId] ?: StubNovelSource(id = sourceId, lang = "", name = "")
    }

    fun getCatalogueSources(): List<NovelsPageSource> {
        return allSourcesFlow.value.values.toList()
    }
}
