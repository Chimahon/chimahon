package tachiyomi.data.novel

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.novel.model.NovelInstalledPlugin
import tachiyomi.domain.novel.repository.NovelPluginStoreRepository

class NovelPluginStoreRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelPluginStoreRepository {

    override suspend fun getInstalledPlugins(): List<NovelInstalledPlugin> {
        return handler.awaitList { novel_pluginsQueries.getInstalledPlugins(::mapPlugin) }
    }

    override suspend fun getInstalledPluginById(id: String): NovelInstalledPlugin? {
        return handler.awaitOneOrNull { novel_pluginsQueries.getInstalledPluginById(id, ::mapPlugin) }
    }

    override suspend fun upsertInstalledPlugin(plugin: NovelInstalledPlugin) {
        handler.await {
            novel_pluginsQueries.upsertInstalledPlugin(
                id = plugin.id,
                name = plugin.name,
                site = plugin.site,
                lang = plugin.lang,
                version = plugin.version,
                codeUrl = plugin.codeUrl,
                iconUrl = plugin.iconUrl,
                sha256 = plugin.sha256,
                repositoryUrl = plugin.repositoryUrl,
                installedAt = plugin.installedAt,
            )
        }
    }

    override suspend fun deleteInstalledPluginById(id: String) {
        handler.await { novel_pluginsQueries.deleteInstalledPluginById(id) }
    }

    private fun mapPlugin(
        id: String,
        name: String,
        site: String,
        lang: String,
        version: String,
        code_url: String,
        icon_url: String,
        sha256: String,
        repository_url: String,
        installed_at: Long,
    ): NovelInstalledPlugin {
        return NovelInstalledPlugin(
            id = id,
            name = name,
            site = site,
            lang = lang,
            version = version,
            codeUrl = code_url,
            iconUrl = icon_url,
            sha256 = sha256,
            repositoryUrl = repository_url,
            installedAt = installed_at,
        )
    }
}
