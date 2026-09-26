package eu.kanade.tachiyomi.data.animedownload

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadProvider.Companion.TMP_DIR_SUFFIX
import eu.kanade.tachiyomi.data.download.UniFileAsStringSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class AnimeDownloadCache(
    private val context: Context,
    private val provider: AnimeDownloadProvider = Injekt.get(),
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    private val renewInterval = 1.hours.inWholeMilliseconds

    private var lastRenew = 0L
    private var renewalJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1000L)
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        get() = File(context.cacheDir, "anime_dl_index_cache_v1")

    private val rootDirMutex = Mutex()
    private var rootDir = RootDirectory(storageManager.getAnimeDownloadsDirectory())

    init {
        scope.launch {
            rootDirMutex.withLock {
                try {
                    if (diskCacheFile.exists()) {
                        val diskCache = diskCacheFile.inputStream().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        rootDir = diskCache
                        lastRenew = System.currentTimeMillis()
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to initialize anime download cache from disk" }
                    diskCacheFile.delete()
                }
            }
        }

        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)
    }

    fun isEpisodeDownloaded(
        episodeName: String,
        episodeScanlator: String?,
        animeTitle: String,
        sourceId: Long,
        skipCache: Boolean = false,
    ): Boolean {
        if (skipCache) {
            val source = animeSourceManager.getOrStub(sourceId)
            return provider.findEpisodeDir(episodeName, episodeScanlator, animeTitle, source) != null
        }

        renewCache()

        val sourceDir = rootDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(animeTitle)]
            if (animeDir != null) {
                val episodeDirName = provider.getEpisodeDirName(episodeName, episodeScanlator)
                return episodeDirName in animeDir.episodeDirs
            }
        }
        return false
    }

    fun getDownloadCount(anime: Anime): Int {
        renewCache()

        val sourceDir = rootDir.sourceDirs[anime.source]
        if (sourceDir != null) {
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(anime.title)]
            if (animeDir != null) {
                return animeDir.episodeDirs.size
            }
        }
        return 0
    }

    suspend fun addEpisode(episodeDirName: String, animeUniFile: UniFile, anime: Anime) {
        rootDirMutex.withLock {
            var sourceDir = rootDir.sourceDirs[anime.source]
            if (sourceDir == null) {
                val source = animeSourceManager.get(anime.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDir.sourceDirs += anime.source to sourceDir
            }

            val animeDirName = provider.getAnimeDirName(anime.title)
            var animeDir = sourceDir.animeDirs[animeDirName]
            if (animeDir == null) {
                animeDir = AnimeDirectory(animeUniFile)
                sourceDir.animeDirs += animeDirName to animeDir
            }

            animeDir.episodeDirs += episodeDirName
        }

        notifyChanges()
    }

    suspend fun removeEpisode(episode: Episode, anime: Anime) {
        rootDirMutex.withLock {
            val sourceDir = rootDir.sourceDirs[anime.source] ?: return
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(anime.title)] ?: return
            provider.getValidEpisodeDirNames(episode.name, episode.scanlator).forEach {
                if (it in animeDir.episodeDirs) {
                    animeDir.episodeDirs -= it
                }
            }
        }

        notifyChanges()
    }

    suspend fun removeEpisodes(episodes: List<Episode>, anime: Anime) {
        rootDirMutex.withLock {
            val sourceDir = rootDir.sourceDirs[anime.source] ?: return
            val animeDir = sourceDir.animeDirs[provider.getAnimeDirName(anime.title)] ?: return
            episodes.forEach { episode ->
                provider.getValidEpisodeDirNames(episode.name, episode.scanlator).forEach {
                    if (it in animeDir.episodeDirs) {
                        animeDir.episodeDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    suspend fun removeAnime(anime: Anime) {
        rootDirMutex.withLock {
            val sourceDir = rootDir.sourceDirs[anime.source] ?: return
            val animeDirName = provider.getAnimeDirName(anime.title)
            if (sourceDir.animeDirs.containsKey(animeDirName)) {
                sourceDir.animeDirs -= animeDirName
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: AnimeSource) {
        rootDirMutex.withLock {
            rootDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.animeDirs.values.sumOf { animeDir ->
                animeDir.episodeDirs.size
            }
        }
    }

    fun getTotalDownloadSize(): Long {
        renewCache()

        return rootDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.dir?.length() ?: 0L
        }
    }

    fun getDownloadSize(anime: Anime): Long {
        renewCache()

        return rootDir.sourceDirs[anime.source]?.animeDirs?.get(
            provider.getAnimeDirName(anime.title),
        )?.dir?.length() ?: 0L
    }

    fun invalidateCache() {
        lastRenew = 0L
        renewalJob?.cancel()
        diskCacheFile.delete()
        renewCache()
    }

    private fun renewCache() {
        if (lastRenew + renewInterval >= System.currentTimeMillis() || renewalJob?.isActive == true) {
            return
        }

        renewalJob = scope.launchIO {
            if (lastRenew == 0L) {
                _isInitializing.emit(true)
            }

            // Wait for sources to finish loading before mapping directories back to source ids.
            // Without this a cold start can build an empty sourceMap, which makes every
            // downloaded episode look like it was never downloaded until the next renewal.
            // Same guard as the manga cache.
            var sources = emptyList<AnimeSource>()
            withTimeoutOrNull(30.seconds) {
                animeSourceManager.isInitialized.first { it }
                sources = animeSourceManager.getOnlineSources() + animeSourceManager.getStubSources()
            }
            if (sources.isEmpty()) {
                sources = animeSourceManager.getOnlineSources() + animeSourceManager.getStubSources()
            }
            val sourceMap = sources.associate { provider.getSourceDirName(it).lowercase() to it.id }

            val updatedRootDir = RootDirectory(storageManager.getAnimeDownloadsDirectory())

            updatedRootDir.sourceDirs = updatedRootDir.dir?.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.isNullOrBlank() }
                .mapNotNull { dir ->
                    val sourceId = sourceMap[dir.name!!.lowercase()]
                    sourceId?.let { it to SourceDirectory(dir) }
                }
                .toMap()

            updatedRootDir.sourceDirs.values.map { sourceDir ->
                async {
                    sourceDir.animeDirs = sourceDir.dir?.listFiles().orEmpty()
                        .filter { it.isDirectory && !it.name.isNullOrBlank() }
                        .associate { it.name!! to AnimeDirectory(it) }

                    sourceDir.animeDirs.values.forEach { animeDir ->
                        val episodeDirs = animeDir.dir?.listFiles().orEmpty()
                            .mapNotNull {
                                when {
                                    it.name?.endsWith(TMP_DIR_SUFFIX) == true -> null
                                    it.isDirectory -> it.name
                                    else -> null
                                }
                            }
                            .toMutableSet()

                        animeDir.episodeDirs = episodeDirs
                    }
                }
            }.awaitAll()

            rootDirMutex.withLock {
                rootDir = updatedRootDir
            }

            _isInitializing.emit(false)
        }.also {
            it.invokeOnCompletion(onCancelling = true) { exception ->
                if (exception != null && exception !is CancellationException) {
                    logcat(LogPriority.ERROR, exception) { "AnimeDownloadCache: failed to create cache" }
                }
                lastRenew = System.currentTimeMillis()
                notifyChanges()
            }
        }
    }

    private fun notifyChanges() {
        scope.launchNonCancellable {
            _changes.send(Unit)
        }
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1000)
            ensureActive()
            val bytes = ProtoBuf.encodeToByteArray(rootDir)
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to write anime download disk cache" }
            }
        }
    }
}

@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var animeDirs: Map<String, AnimeDirectory> = mapOf(),
)

@Serializable
private class AnimeDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var episodeDirs: MutableSet<String> = mutableSetOf(),
)
