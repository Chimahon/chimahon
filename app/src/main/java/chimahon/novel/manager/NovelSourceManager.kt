package chimahon.novel.manager

import chimahon.novel.extension.NovelExtensionManager
import chimahon.novel.model.NovelServer
import chimahon.novel.model.NovelServerStorage
import chimahon.novel.model.NovelServerType
import chimahon.novel.source.opds.OpdsSource
import chimahon.source.kavita.KavitaNovelSource
import chimahon.source.komga.KomgaNovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class NovelSourceWithServer(
    val server: NovelServer,
    val source: NovelSource,
)

class NovelSourceManager(
    private val serverStorage: NovelServerStorage,
    private val extensionManager: NovelExtensionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allSourcesFlow = MutableStateFlow<Map<Long, NovelsPageSource>>(emptyMap())

    val catalogueSources: Flow<List<NovelsPageSource>> = allSourcesFlow.map { it.values.toList() }

    init {
        scope.launch {
            combine(
                serverStorage.getAllServers(),
                extensionManager.loadedNovelSources,
            ) { servers, extensionSources ->
                val merged = mutableMapOf<Long, NovelsPageSource>()
                for (server in servers.filter { it.enabled }) {
                    val source = createSource(server) ?: continue
                    if (source is NovelsPageSource) {
                        merged[source.id] = source
                    }
                }
                for (source in extensionSources) {
                    if (source is NovelsPageSource) {
                        merged[source.id] = source
                    }
                }
                merged
            }.collect { merged ->
                allSourcesFlow.value = merged
            }
        }
    }

    fun getNovelSource(sourceId: Long): NovelSource? {
        return allSourcesFlow.value[sourceId]
    }

    fun getCatalogueSources(): List<NovelsPageSource> {
        return allSourcesFlow.value.values.toList()
    }

    fun getExtensionSources(): List<NovelSource> {
        return extensionManager.loadedNovelSources.value
    }

    fun getEntriesFlow(): Flow<List<NovelSourceWithServer>> {
        return serverStorage.getAllServers().map { servers ->
            servers.filter { it.enabled }.mapNotNull { server ->
                val source = createSource(server) ?: return@mapNotNull null
                NovelSourceWithServer(server, source)
            }
        }
    }

    private fun createSource(server: NovelServer): NovelSource? {
        if (!server.enabled) return null
        return when (server.type) {
            NovelServerType.OPDS -> OpdsSource(server)
            NovelServerType.KOMGA -> KomgaNovelSource(server)
            NovelServerType.KAVITA -> KavitaNovelSource(server)
        }
    }
}
