package mihon.core.migration.migrations

import android.app.Application
import chimahon.novel.plugin.InstalledNovelPlugin
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.NovelInstalledPlugin
import tachiyomi.domain.novel.repository.NovelPluginStoreRepository
import java.io.File

class MigrateNovelPluginRegistryMigration : Migration {
    override val version: Float = 84f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val context = migrationContext.get<Application>() ?: return@withIOContext false
        val store = migrationContext.get<NovelPluginStoreRepository>() ?: return@withIOContext false
        val file = File(File(context.filesDir, "novel-plugins"), "registry.json")
        if (!file.isFile) return@withIOContext true
        val installed = try {
            json.decodeFromString<List<InstalledNovelPlugin>>(file.readText())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Novel plugin registry unreadable, leaving file for fallback" }
            return@withIOContext true
        }
        val now = System.currentTimeMillis()
        installed.forEach { item ->
            runCatching {
                val descriptor = item.descriptor
                store.upsertInstalledPlugin(
                    NovelInstalledPlugin(
                        id = descriptor.id,
                        name = descriptor.name,
                        site = descriptor.site,
                        lang = descriptor.lang,
                        version = descriptor.version,
                        codeUrl = descriptor.codeUrl,
                        iconUrl = descriptor.iconUrl,
                        sha256 = descriptor.sha256,
                        repositoryUrl = item.repositoryUrl,
                        installedAt = now,
                    ),
                )
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel plugin registry migration failed for ${item.descriptor.id}" }
            }
        }
        // Always true like other migrations: the version gate runs once, so every
        // item is best-effort with failures logged, never retried.
        return@withIOContext true
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
