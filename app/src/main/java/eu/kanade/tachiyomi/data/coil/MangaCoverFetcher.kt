package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getOrDefault
import coil3.request.Options
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher.Companion.USE_CUSTOM_COVER_KEY
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.IOException

/**
 * A [Fetcher] that fetches cover image for [Manga] object.
 *
 * It uses [Manga.thumbnailUrl] if custom cover is not set by the user.
 * Disk caching for library items is handled by [CoverCache], otherwise
 * handled by Coil's [DiskCache].
 *
 * Available request parameter:
 * - [USE_CUSTOM_COVER_KEY]: Use custom cover if set by user, default is true
 */
class MangaCoverFetcher(
    // KMK -->
    private val mangaCover: MangaCover,
    private val url: String? = mangaCover.url,
    // private val url: String?,
    // KMK <--
    private val isLibraryManga: Boolean,
    private val options: Options,
    private val coverFileLazy: Lazy<File?>,
    private val customCoverFileLazy: Lazy<File>,
    private val diskCacheKeyLazy: Lazy<String>,
    private val sourceLazy: Lazy<HttpSource?>,
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val imageLoader: ImageLoader,
) : Fetcher {

    // KMK -->
    private val scope by lazy { CoroutineScope(Dispatchers.IO) }
    private val uiPreferences = Injekt.get<UiPreferences>()
    private val themeCoverBased = uiPreferences.themeCoverBased().get()
    private val preloadLibraryColor = uiPreferences.preloadLibraryColor().get()
    // KMK <--

    private val diskCacheKey: String
        get() = diskCacheKeyLazy.value

    /**
     * Called each time a cover is displayed
     */
    override suspend fun fetch(): FetchResult {
        // Use custom cover if exists
        val useCustomCover = options.extras.getOrDefault(USE_CUSTOM_COVER_KEY)
        if (useCustomCover) {
            val customCoverFile = customCoverFileLazy.value
            if (customCoverFile.exists()) {
                return fileLoader(customCoverFile)
            }
        }

        // Local manga: never return empty if the manga has any readable page.
        // Try the declared cover first, then explicit cover.* file, then first page
        // image — all read-only, nothing is written to the device.
        if (mangaCover.sourceId == LocalSource.ID) {
            loadLocalCover()?.let { return it }
        }

        // diskCacheKey is thumbnail_url
        if (url == null) error("No cover specified")
        return when (getResourceType(url)) {
            Type.File -> fileLoader(File(url.substringAfter("file://")))
            Type.URI -> fileUriLoader(url)
            Type.URL -> httpLoader()
            null -> error("Invalid image")
        }
    }

    /**
     * Read-only local cover chain: declared url -> cover.* in manga dir ->
     * first image of first chapter. Returns null only if nothing readable exists.
     */
    private suspend fun loadLocalCover(): FetchResult? {
        // 1. Declared cover (content uri / file path) if it still opens
        url?.let { cover ->
            try {
                when (getResourceType(cover)) {
                    Type.File -> {
                        val file = File(cover.substringAfter("file://"))
                        if (file.exists()) return fileLoader(file)
                    }
                    Type.URI -> {
                        val source = UniFile.fromUri(options.context, cover.toUri())
                            ?.openInputStream()
                            ?.source()
                            ?.buffer()
                        if (source != null) {
                            setRatioAndColorsInScope(mangaCover)
                            return SourceFetchResult(
                                source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
                                mimeType = "image/*",
                                dataSource = DataSource.DISK,
                            )
                        }
                    }
                    else -> Unit
                }
            } catch (_: Exception) {
                // Fall through to directory scan below
            }
        }

        // 2-3. Scan local storage (no writes)
        return try {
            val mangaRepository: MangaRepository = Injekt.get()
            val storageManager: StorageManager = Injekt.get()
            val manga = runCatching { mangaRepository.getMangaById(mangaCover.mangaId) }.getOrNull()
                ?: return null
            val baseDir = storageManager.getLocalSourceDirectory() ?: return null
            val mangaDir = baseDir.findFile(manga.url) ?: return null
            if (!mangaDir.isDirectory) return null

            // 2. Explicit cover.* (stem must be "cover", extension case-insensitive
            // via ImageUtil)
            val explicitCover = mangaDir.listFiles().orEmpty()
                .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
                .firstOrNull {
                    try {
                        ImageUtil.isImage(it.name) { it.openInputStream() }
                    } catch (_: Exception) {
                        false
                    }
                }
            if (explicitCover != null) {
                return try {
                    val source = explicitCover.openInputStream().source().buffer()
                    setRatioAndColorsInScope(mangaCover)
                    SourceFetchResult(
                        source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
                        mimeType = "image/*",
                        dataSource = DataSource.DISK,
                    )
                } catch (_: Exception) {
                    null
                } ?: firstPageFallback(mangaDir)
            }

            // 3. First image of first chapter
            firstPageFallback(mangaDir)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Local cover fallback failed for mangaId=${mangaCover.mangaId}" }
            null
        }
    }

    private fun firstPageFallback(mangaDir: UniFile): FetchResult? {
        return try {
            val chapters = mangaDir.listFiles().orEmpty()
                .filterNot { it.name.orEmpty().startsWith('.') }
                .filter {
                    it.isDirectory ||
                        tachiyomi.source.local.io.Archive.isSupported(it) ||
                        it.extension.equals("epub", ignoreCase = true)
                }
                .sortedWith { f1, f2 ->
                    f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty())
                }
            if (chapters.isEmpty()) return null
            for (chapterFile in chapters) {
                readFirstPageBytes(chapterFile)?.let { bytes ->
                    setRatioAndColorsInScope(mangaCover, bufferedSource = Buffer().apply { write(bytes) })
                    return SourceFetchResult(
                        source = ImageSource(
                            source = Buffer().write(bytes),
                            fileSystem = FileSystem.SYSTEM,
                        ),
                        mimeType = "image/*",
                        dataSource = DataSource.DISK,
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readFirstPageBytes(chapterFile: UniFile): ByteArray? {
        return try {
            when {
                chapterFile.isDirectory -> {
                    val entry = chapterFile.listFiles().orEmpty()
                        .sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty())
                        }
                        .firstOrNull {
                            try {
                                !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                            } catch (_: Exception) {
                                false
                            }
                        } ?: return null
                    entry.openInputStream().use { it.readBytes() }
                }
                chapterFile.extension.equals("epub", ignoreCase = true) -> {
                    chapterFile.epubReader(options.context).use { epub ->
                        val entry = epub.getImagesFromPages().firstOrNull() ?: return null
                        epub.getInputStream(entry)?.use { it.readBytes() }
                    }
                }
                else -> {
                    chapterFile.archiveReader(options.context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .firstOrNull {
                                    try {
                                        it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! }
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                        } ?: return null
                        reader.getInputStream(entry.name)?.use { it.readBytes() }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fileLoader(file: File): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover, ogFile = file)
        // KMK <--
        return SourceFetchResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
                diskCacheKey = diskCacheKey,
            ),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun fileUriLoader(uri: String): FetchResult {
        // KMK -->
        setRatioAndColorsInScope(mangaCover)
        // KMK <--
        val source = UniFile.fromUri(options.context, uri.toUri())!!
            .openInputStream()
            .source()
            .buffer()
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun httpLoader(): FetchResult {
        // Only cache separately if it's a library item
        val libraryCoverCacheFile = if (isLibraryManga) {
            coverFileLazy.value ?: error("No cover specified")
        } else {
            null
        }
        if (libraryCoverCacheFile?.exists() == true && options.diskCachePolicy.readEnabled) {
            return fileLoader(libraryCoverCacheFile)
        }

        var snapshot = readFromDiskCache()
        try {
            // Fetch from disk cache
            if (snapshot != null) {
                val snapshotCoverCache = moveSnapshotToCoverCache(snapshot, libraryCoverCacheFile)
                if (snapshotCoverCache != null) {
                    // Read from cover cache after added to library
                    return fileLoader(snapshotCoverCache)
                }

                // Read from snapshot
                // KMK -->
                setRatioAndColorsInScope(mangaCover, bufferedSource = snapshot.toImageSource().source())
                // KMK <--
                return SourceFetchResult(
                    source = snapshot.toImageSource(),
                    mimeType = "image/*",
                    dataSource = DataSource.DISK,
                )
            }

            // Fetch from network
            val response = executeNetworkRequest()
            val responseBody = checkNotNull(response.body) { "Null response source" }
            try {
                // Read from cover cache after library manga cover updated
                val responseCoverCache = writeResponseToCoverCache(response, libraryCoverCacheFile)
                if (responseCoverCache != null) {
                    return fileLoader(responseCoverCache)
                }

                // Read from disk cache
                snapshot = writeToDiskCache(response)
                if (snapshot != null) {
                    // KMK -->
                    setRatioAndColorsInScope(mangaCover, bufferedSource = snapshot.toImageSource().source())
                    // KMK <--
                    return SourceFetchResult(
                        source = snapshot.toImageSource(),
                        mimeType = "image/*",
                        dataSource = DataSource.NETWORK,
                    )
                }

                // KMK -->
                setRatioAndColorsInScope(
                    mangaCover,
                    bufferedSource = ImageSource(
                        source = responseBody.source(),
                        fileSystem = FileSystem.SYSTEM,
                    ).source(),
                )
                // KMK <--
                // Read from response if cache is unused or unusable
                return SourceFetchResult(
                    source = ImageSource(source = responseBody.source(), fileSystem = FileSystem.SYSTEM),
                    mimeType = "image/*",
                    dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK,
                )
            } catch (e: Exception) {
                responseBody.close()
                throw e
            }
        } catch (e: Exception) {
            snapshot?.close()
            throw e
        }
    }

    private suspend fun executeNetworkRequest(): Response {
        val client = sourceLazy.value?.client ?: callFactoryLazy.value
        val response = client.newCall(newRequest()).await()
        if (!response.isSuccessful && response.code != HTTP_NOT_MODIFIED) {
            response.close()
            throw IOException(response.message)
        }
        return response
    }

    private fun newRequest(): Request {
        val request = Request.Builder().apply {
            url(url!!)

            val sourceHeaders = sourceLazy.value?.headers
            if (sourceHeaders != null) {
                headers(sourceHeaders)
            }
        }

        when {
            options.networkCachePolicy.readEnabled -> {
                // don't take up okhttp cache
                request.cacheControl(CACHE_CONTROL_NO_STORE)
            }
            else -> {
                // This causes the request to fail with a 504 Unsatisfiable Request.
                request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
            }
        }

        return request.build()
    }

    private fun moveSnapshotToCoverCache(snapshot: DiskCache.Snapshot, cacheFile: File?): File? {
        if (cacheFile == null) return null
        return try {
            imageLoader.diskCache?.run {
                fileSystem.source(snapshot.data).use { input ->
                    writeSourceToCoverCache(input, cacheFile)
                }
                remove(diskCacheKey)
            }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write snapshot data to cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeResponseToCoverCache(response: Response, cacheFile: File?): File? {
        if (cacheFile == null || !options.diskCachePolicy.writeEnabled) return null
        return try {
            response.peekBody(Long.MAX_VALUE).source().use { input ->
                writeSourceToCoverCache(input, cacheFile)
            }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write response data to cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeSourceToCoverCache(input: Source, cacheFile: File) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.delete()
        try {
            cacheFile.sink().buffer().use { output ->
                output.writeAll(input)
            }
        } catch (e: Exception) {
            cacheFile.delete()
            throw e
        }
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? {
        return if (options.diskCachePolicy.readEnabled) {
            imageLoader.diskCache?.openSnapshot(diskCacheKey)
        } else {
            null
        }
    }

    private fun writeToDiskCache(
        response: Response,
    ): DiskCache.Snapshot? {
        val diskCache = imageLoader.diskCache
        val editor = diskCache?.openEditor(diskCacheKey) ?: return null
        try {
            diskCache.fileSystem.write(editor.data) {
                response.body.source().readAll(this)
            }
            return editor.commitAndOpenSnapshot()
        } catch (e: Exception) {
            try {
                editor.abort()
            } catch (ignored: Exception) {
            }
            throw e
        }
    }

    private fun DiskCache.Snapshot.toImageSource(): ImageSource {
        return ImageSource(
            file = data,
            fileSystem = FileSystem.SYSTEM,
            diskCacheKey = diskCacheKey,
            closeable = this,
        )
    }

    private fun getResourceType(cover: String?): Type? {
        return when {
            cover.isNullOrEmpty() -> null
            cover.startsWith("http", true) || cover.startsWith("Custom-", true) -> Type.URL
            cover.startsWith("/") || cover.startsWith("file://") -> Type.File
            cover.startsWith("content") -> Type.URI
            else -> null
        }
    }

    // KMK -->
    /**
     * [setRatioAndColorsInScope] is called whenever a cover is loaded with [MangaCoverFetcher.fetch]
     *
     * @param bufferedSource if not null then it will load bitmap from [BufferedSource], regardless of [ogFile]
     * @param ogFile if not null then it will load bitmap from [File]. If it's null then it will try to load bitmap
     *  from [CoverCache] using either [CoverCache.customCoverCacheDir] or [CoverCache.cacheDir]
     * @param force if true then it will always re-calculate ratio & color for favorite mangas.
     */
    private fun setRatioAndColorsInScope(
        mangaCover: MangaCover,
        bufferedSource: BufferedSource? = null,
        ogFile: File? = null,
        onlyFavorite: Boolean = !themeCoverBased,
        force: Boolean = false,
    ) {
        if (!preloadLibraryColor) return
        scope.launch {
            try {
                MangaCoverMetadata.setRatioAndColors(mangaCover, bufferedSource, ogFile, onlyFavorite, force)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to set cover ratio & colors: ${e.message}" }
            }
        }
    }
    // KMK <--

    private enum class Type {
        File,
        URI,
        URL,
    }

    class MangaFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<Manga> {

        private val coverCache: CoverCache by injectLazy()
        private val sourceManager: SourceManager by injectLazy()

        override fun create(data: Manga, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                // KMK -->
                // url = data.thumbnailUrl,
                mangaCover = data.asMangaCover(),
                // KMK <--
                isLibraryManga = data.favorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.thumbnailUrl) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.id) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                sourceLazy = lazy { sourceManager.get(data.source) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
            )
        }
    }

    class MangaCoverFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
    ) : Fetcher.Factory<MangaCover> {

        private val coverCache: CoverCache by injectLazy()
        private val sourceManager: SourceManager by injectLazy()

        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                // KMK -->
                // url = data.url,
                mangaCover = data,
                // KMK <--
                isLibraryManga = data.isMangaFavorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.url) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.mangaId) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                sourceLazy = lazy { sourceManager.get(data.sourceId) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
            )
        }
    }

    companion object {
        val USE_CUSTOM_COVER_KEY = Extras.Key(true)

        private val CACHE_CONTROL_NO_STORE = CacheControl.Builder().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE = CacheControl.Builder().noCache().onlyIfCached().build()

        private const val HTTP_NOT_MODIFIED = 304
    }
}
