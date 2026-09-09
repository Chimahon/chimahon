package chimahon.novel.plugin

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.novelextensionrepo.repository.NovelExtensionRepoRepository
import tachiyomi.domain.novel.model.NovelInstalledPlugin
import tachiyomi.domain.novel.repository.NovelPluginStoreRepository
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NovelPluginCatalog(
    val repositories: List<NovelPluginRepository> = emptyList(),
    val available: List<NovelPluginDescriptor> = emptyList(),
    val installed: List<InstalledNovelPlugin> = emptyList(),
    val sources: List<NovelsPageSource> = emptyList(),
)

private const val LNREADER_DEFAULT_INDEX_URL =
    "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"

// Hayai parity caps: bound hostile/oversized payloads before parsing or executing.
private const val MAX_REPOSITORY_BYTES = 4 * 1024 * 1024
private const val MAX_REPOSITORY_PLUGINS = 10_000
private const val MAX_PLUGIN_BYTES = 8 * 1024 * 1024

class NovelPluginManager(
    private val context: Context,
    private val novelExtensionRepoRepository: NovelExtensionRepoRepository = Injekt.get(),
) {
    private val mutex = Mutex()
    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private val pluginsDir = java.io.File(context.filesDir, "novel-plugins").apply { mkdirs() }
    private val registryFile = java.io.File(pluginsDir, "registry.json")
    private val indexCacheDir = java.io.File(pluginsDir, "index-cache").apply { mkdirs() }

    private fun loadInstalledRegistry(): List<InstalledNovelPlugin> {
        if (!registryFile.exists()) return emptyList()
        return try { jsonCodec.decodeFromString<List<InstalledNovelPlugin>>(registryFile.readText()) } catch (_: Exception) { emptyList() }
    }

    private suspend fun persistInstalledRegistry(installed: List<InstalledNovelPlugin>) {
        try {
            val tmp = java.io.File(pluginsDir, "registry.json.tmp")
            tmp.writeText(jsonCodec.encodeToString(installed))
            if (!tmp.renameTo(registryFile)) {
                registryFile.delete()
                tmp.renameTo(registryFile)
            }
        } catch (_: Exception) {}
        // Dual-write until the registry file is deleted: the DB is authoritative.
        runCatching {
            val store = Injekt.get<NovelPluginStoreRepository>()
            val wanted = installed.map { it.descriptor.id }.toSet()
            store.getInstalledPlugins()
                .map { it.id }
                .filterNot { it in wanted }
                .forEach { runCatching { store.deleteInstalledPluginById(it) } }
            installed.forEach { runCatching { store.upsertInstalledPlugin(it.toRow()) } }
        }
    }

    private fun InstalledNovelPlugin.toRow(): NovelInstalledPlugin {
        return NovelInstalledPlugin(
            id = descriptor.id,
            name = descriptor.name,
            site = descriptor.site,
            lang = descriptor.lang,
            version = descriptor.version,
            codeUrl = descriptor.codeUrl,
            iconUrl = descriptor.iconUrl,
            sha256 = descriptor.sha256,
            repositoryUrl = repositoryUrl,
            installedAt = System.currentTimeMillis(),
        )
    }

    private fun NovelInstalledPlugin.toInstalled(): InstalledNovelPlugin {
        return InstalledNovelPlugin(
            descriptor = NovelPluginDescriptor(
                id = id,
                name = name,
                site = site,
                lang = lang,
                version = version,
                codeUrl = codeUrl,
                iconUrl = iconUrl,
                sha256 = sha256,
            ),
            repositoryUrl = repositoryUrl,
        )
    }

    /** DB-first installed list; adopts file rows when the DB is empty (pre-migration). */
    private suspend fun reconcileInstalledWithDb() {
        val store = runCatching { Injekt.get<NovelPluginStoreRepository>() }.getOrNull() ?: return
        val dbRows = runCatching { store.getInstalledPlugins() }.getOrNull().orEmpty()
        if (dbRows.isNotEmpty()) {
            val mapped = dbRows.map { it.toInstalled() }
            _catalog.value = _catalog.value.copy(installed = mapped)
            persistInstalledRegistry(mapped)
        } else {
            val fileRows = _catalog.value.installed
            fileRows.forEach { runCatching { store.upsertInstalledPlugin(it.toRow()) } }
        }
    }

    private fun indexCacheFile(repoUrl: String): java.io.File {
        return java.io.File(indexCacheDir, "index-${repoUrl.hashCode().toString(16)}.json")
    }

    private fun loadCachedIndex(repoUrl: String): List<NovelPluginDescriptor> {
        val file = indexCacheFile(repoUrl)
        if (!file.exists()) return emptyList()
        return try {
            jsonCodec.decodeFromString<CachedPluginIndex>(file.readText()).plugins
        } catch (_: Exception) { emptyList() }
    }

    private fun saveCachedIndex(repoUrl: String, plugins: List<NovelPluginDescriptor>) {
        try {
            val file = indexCacheFile(repoUrl)
            val tmp = java.io.File(indexCacheDir, "${file.name}.tmp")
            tmp.writeText(jsonCodec.encodeToString(CachedPluginIndex(System.currentTimeMillis(), plugins)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {}
    }

    private fun loadAllCachedAvailable(): List<NovelPluginDescriptor> {
        val files = try { indexCacheDir.listFiles() } catch (_: Exception) { null } ?: return emptyList()
        return files
            .filter { it.isFile && it.name.startsWith("index-") && it.name.endsWith(".json") }
            .flatMap { file ->
                try { jsonCodec.decodeFromString<CachedPluginIndex>(file.readText()).plugins } catch (_: Exception) { emptyList() }
            }
    }

    private val _catalog = MutableStateFlow(
        NovelPluginCatalog(
            repositories = emptyList(),
            installed = loadInstalledRegistry(),
            available = loadAllCachedAvailable(),
            sources = emptyList(),
        ),
    )
    val catalog: StateFlow<NovelPluginCatalog> = _catalog.asStateFlow()

    suspend fun refresh(): NovelPluginCatalog = mutex.withLock { refreshInternal() }

    /**
     * Seeds the default LNReader plugin repo exactly once (Hayai parity), so a
     * fresh install shows extensions without manual setup. Never re-adds: if the
     * user deletes all repos, that choice is respected.
     */
    private suspend fun seedDefaultRepoIfNeeded() {
        try {
            val prefs = context.getSharedPreferences("chimahon_novel_plugin", Context.MODE_PRIVATE)
            if (prefs.getBoolean("default_repo_seeded", false)) return
            if (novelExtensionRepoRepository.getAll().isNotEmpty()) {
                prefs.edit().putBoolean("default_repo_seeded", true).apply()
                return
            }
            novelExtensionRepoRepository.insertRepo(
                baseUrl = LNREADER_DEFAULT_INDEX_URL,
                name = "LNReader Plugins",
                shortName = null,
                website = LNREADER_DEFAULT_INDEX_URL,
                signingKeyFingerprint = "NOFINGERPRINT_${LNREADER_DEFAULT_INDEX_URL.hashCode().toString(16)}",
            )
            prefs.edit().putBoolean("default_repo_seeded", true).apply()
        } catch (_: Exception) {
        }
    }

    private suspend fun refreshInternal(): NovelPluginCatalog {
        seedDefaultRepoIfNeeded()
        reconcileInstalledWithDb()
        val dbRepos = novelExtensionRepoRepository.getAll()
        val pluginRepos = dbRepos.map { NovelPluginRepository(it.name, it.baseUrl, enabled = true) }
        val allDescriptors = mutableListOf<NovelPluginDescriptor>()

        val client = Injekt.get<NetworkHelper>().client
        supervisorScope {
            pluginRepos.map { repo ->
                async { fetchRepoDescriptors(repo, client) }
            }.awaitAll()
        }.flatten().let { allDescriptors.addAll(it) }

        val distinctAvailable = allDescriptors.groupBy { it.id }
            .map { (_, list) -> list.maxWithOrNull(Comparator { a, b -> parseVersion(a.version).compareTo(parseVersion(b.version)) }) ?: list.first() }
            .sortedWith(compareBy({ it.normalizedLanguage() }, { it.name.lowercase() }))

        val sources = distinctAvailable.map { desc ->
            SimpleLNReaderSource(
                context = context,
                pluginId = desc.id,
                pluginName = desc.name,
                pluginLang = desc.normalizedLanguage(),
                siteUrl = desc.site,
                jsCode = "",
                codeUrl = desc.codeUrl,
            ) as eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
        }
        _catalog.value = NovelPluginCatalog(
            repositories = pluginRepos,
            available = distinctAvailable,
            installed = _catalog.value.installed,
            sources = sources,
        )
        return _catalog.value
    }

    /**
     * Fetches + parses one repo, falling back to its cached index.
     * Runs inside [supervisorScope] so one dead repo never stalls the rest.
     */
    private suspend fun fetchRepoDescriptors(
        repo: NovelPluginRepository,
        client: okhttp3.OkHttpClient,
    ): List<NovelPluginDescriptor> {
        return try {
            val repoDescriptors = mutableListOf<NovelPluginDescriptor>()
            var body: String? = null
            var successfulUrl: String? = null
            val clean = repo.url.trimEnd('/')
            val urlsToTry = buildList {
                add(clean)
                if (!clean.endsWith(".json")) {
                    add("$clean/plugins.min.json")
                    add("$clean/.dist/plugins.min.json")
                    add("$clean/dist/plugins.min.json")
                    add("$clean/index.min.json")
                    add("$clean/.dist/index.min.json")
                    add("$clean/dist/index.min.json")
                    add("$clean/plugins.json")
                    add("$clean/index.json")
                }
            }
            for (url in urlsToTry) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")
                        .build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) continue
                    if (response.body.contentLength() > MAX_REPOSITORY_BYTES) continue
                    val b = response.body.string()
                    if (b.length > MAX_REPOSITORY_BYTES) continue
                    if (!b.isNullOrBlank()) {
                        body = b
                        successfulUrl = url
                        break
                    }
                } catch (_: Exception) {
                    continue
                }
            }
            if (!body.isNullOrBlank() && successfulUrl != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                try {
                    val root = json.parseToJsonElement(body!!)
                    val pluginsArray: kotlinx.serialization.json.JsonArray? = when (root) {
                        is kotlinx.serialization.json.JsonArray -> root
                        is kotlinx.serialization.json.JsonObject -> {
                            (root["plugins"] ?: root["extensions"] ?: root["data"]) as? kotlinx.serialization.json.JsonArray
                        }
                        else -> null
                    }
                    if (pluginsArray != null) {
                        for (elem in pluginsArray) {
                            if (repoDescriptors.size >= MAX_REPOSITORY_PLUGINS) break
                            val o = elem as? kotlinx.serialization.json.JsonObject ?: continue
                            fun getField(vararg keys: String): String {
                                for (k in keys) {
                                    val v = o[k] ?: continue
                                    if (v is kotlinx.serialization.json.JsonPrimitive) {
                                        return v.content
                                    }
                                }
                                return ""
                            }
                            val id = getField("id", "pkg", "pkgName")
                            if (id.isBlank()) continue
                            val name = getField("name").ifBlank { id }
                            val site = getField("site")
                            val lang = getField("lang", "language").ifBlank { "en" }
                            val version = getField("version", "versionName").ifBlank { "1.0.0" }
                            val rawCodeUrl = getField("url", "codeUrl", "path")
                            val iconUrl = getField("iconUrl", "icon")
                            val sha256 = getField("sha256").lowercase()
                                .takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: ""
                            val codeUrl = resolveCodeUrl(rawCodeUrl, successfulUrl)
                            repoDescriptors.add(NovelPluginDescriptor(id, name, site, lang, version, codeUrl, iconUrl, sha256))
                        }
                    }
                    logcat(LogPriority.INFO) { "Parsed ${repoDescriptors.size} novel plugins from repository $successfulUrl" }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to parse plugins JSON from $successfulUrl" }
                }
            }
            if (repoDescriptors.isNotEmpty()) {
                saveCachedIndex(repo.url, repoDescriptors)
                repoDescriptors
            } else {
                val cached = loadCachedIndex(repo.url)
                if (cached.isNotEmpty()) {
                    logcat(LogPriority.INFO) { "Using cached index (${cached.size} plugins) for repository ${repo.url}" }
                }
                cached
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Error fetching novel repository ${repo.url}" }
            loadCachedIndex(repo.url)
        }
    }

    suspend fun addRepository(name: String, url: String) {
        novelExtensionRepoRepository.insertRepo(
            baseUrl = url,
            name = name,
            shortName = null,
            website = url,
            signingKeyFingerprint = "NOFINGERPRINT_${url.hashCode().toString(16)}",
        )
        refresh()
    }

    suspend fun removeRepository(url: String) {
        novelExtensionRepoRepository.deleteRepo(url)
        refresh()
    }

    suspend fun install(pluginId: String) = mutex.withLock {
        val desc = _catalog.value.available.find { it.id == pluginId } ?: return@withLock
        if (desc.codeUrl.isNotBlank()) {
            try {
                val client = Injekt.get<NetworkHelper>().client
                val req = okhttp3.Request.Builder().url(desc.codeUrl)
                    .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) return@withLock
                if (resp.body.contentLength() > MAX_PLUGIN_BYTES) return@withLock
                val code = resp.body.string()
                if (code.isBlank() || code.length > MAX_PLUGIN_BYTES) return@withLock
                if (desc.sha256.isNotBlank() && sha256Hex(code) != desc.sha256) {
                    logcat(LogPriority.WARN) { "Checksum mismatch for novel plugin $pluginId, refusing install" }
                    return@withLock
                }
                java.io.File(pluginsDir, "${desc.id}.js").writeText(code)
            } catch (_: Exception) {
                return@withLock
            }
        }
        val currentInstalled = _catalog.value.installed.toMutableList()
        val entry = InstalledNovelPlugin(desc, desc.codeUrl)
        val existingIndex = currentInstalled.indexOfFirst { it.descriptor.id == pluginId }
        if (existingIndex >= 0) {
            currentInstalled[existingIndex] = entry
        } else {
            currentInstalled.add(entry)
        }
        _catalog.value = _catalog.value.copy(installed = currentInstalled)
        persistInstalledRegistry(currentInstalled)
    }

    suspend fun uninstall(pluginId: String) = mutex.withLock {
        try { java.io.File(pluginsDir, "$pluginId.js").delete() } catch (_: Exception) {}
        val filtered = _catalog.value.installed.filterNot { it.descriptor.id == pluginId }
        if (filtered.size != _catalog.value.installed.size) {
            _catalog.value = _catalog.value.copy(installed = filtered)
            persistInstalledRegistry(filtered)
        }
    }

    suspend fun update(pluginId: String) {
        refresh()
        if (hasUpdate(pluginId)) install(pluginId)
    }

    fun hasUpdate(pluginId: String): Boolean {
        val installed = _catalog.value.installed.find { it.descriptor.id == pluginId } ?: return false
        val available = _catalog.value.available.find { it.id == pluginId } ?: return false
        return parseVersion(available.version).compareTo(parseVersion(installed.descriptor.version)) > 0
    }

    fun createSourceForPlugin(descriptor: NovelPluginDescriptor, code: String): NovelsPageSource {
        return SimpleLNReaderSource(
            context = context,
            pluginId = descriptor.id,
            pluginName = descriptor.name,
            pluginLang = descriptor.lang,
            siteUrl = descriptor.site,
            jsCode = code,
            codeUrl = descriptor.codeUrl,
        )
    }
}

@Serializable
data class NovelPluginDescriptor(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val codeUrl: String = "",
    val iconUrl: String = "",
    val sha256: String = "",
) {
    fun normalizedLanguage(): String = normalizeNovelLanguage(lang)
}

/**
 * Single code space for novel languages (ISO codes + "all"/"other").
 * Every filter screen and list must use this — never raw [NovelPluginDescriptor.lang].
 */
fun normalizeNovelLanguage(lang: String): String {
        val n = lang.trim().lowercase().filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }
        if (n.contains("english") || n == "en") return "en"
        if (n.contains("chinese") || n.contains("中文") || n.contains("汉语") || n.contains("漢語") || n == "zh") return "zh"
        if (n.contains("japanese") || n.contains("日本語") || n == "ja") return "ja"
        if (n.contains("korean") || n.contains("한국어") || n == "ko") return "ko"
        if (n.contains("arabic") || n.contains("العربية") || n == "ar") return "ar"
        if (n.contains("french") || n.contains("français") || n == "fr") return "fr"
        if (n.contains("spanish") || n.contains("español") || n == "es") return "es"
        if (n.contains("portuguese") || n.contains("português") || n == "pt") return "pt"
        if (n.contains("russian") || n.contains("русский") || n == "ru") return "ru"
        if (n.contains("indonesian") || n == "id") return "id"
        if (n.contains("turkish") || n.contains("türkçe") || n == "tr") return "tr"
        if (n.contains("thai") || n.contains("ไทย") || n == "th") return "th"
        if (n.contains("vietnamese") || n.contains("việt") || n == "vi") return "vi"
        if (n.contains("polish") || n.contains("polski") || n == "pl") return "pl"
        if (n.contains("ukrainian") || n.contains("українська") || n == "uk") return "uk"
        if (n.contains("german") || n.contains("deutsch") || n == "de") return "de"
        if (n.contains("italian") || n.contains("italiano") || n == "it") return "it"
        if (n.contains("multi") || n == "all") return "all"
        return n.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "other"
}

private fun sha256Hex(input: String): String {
    return try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }
}

private fun parseVersion(v: String): List<Int> {    return v.trim().removePrefix("v").substringBefore('+').substringBefore('-')
        .split('.').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
}

private operator fun List<Int>.compareTo(other: List<Int>): Int {
    for (i in 0 until maxOf(size, other.size)) {
        val a = getOrNull(i) ?: 0
        val b = other.getOrNull(i) ?: 0
        if (a != b) return a.compareTo(b)
    }
    return 0
}

private fun resolveCodeUrl(rawUrl: String, indexUrl: String): String {
    if (rawUrl.isBlank()) return ""
    if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
    val indexDir = indexUrl.substringBeforeLast('/').trimEnd('/')
    val cleanRaw = rawUrl.removePrefix("./")
    return if (cleanRaw.startsWith("/")) {
        val protocol = indexDir.substringBefore("://")
        val hostAndRest = indexDir.substringAfter("://")
        val host = hostAndRest.substringBefore('/')
        "$protocol://$host$cleanRaw"
    } else {
        "$indexDir/$cleanRaw"
    }
}

@Serializable
data class InstalledNovelPlugin(
    val descriptor: NovelPluginDescriptor,
    val repositoryUrl: String,
)

@Serializable
data class CachedPluginIndex(
    val fetchedAt: Long,
    val plugins: List<NovelPluginDescriptor>,
)

data class NovelPluginRepository(
    val name: String,
    val url: String,
    val enabled: Boolean,
)
