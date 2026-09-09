package tachiyomi.domain.novel.repository

import tachiyomi.domain.novel.model.NovelInstalledPlugin

interface NovelPluginStoreRepository {

    suspend fun getInstalledPlugins(): List<NovelInstalledPlugin>

    suspend fun getInstalledPluginById(id: String): NovelInstalledPlugin?

    suspend fun upsertInstalledPlugin(plugin: NovelInstalledPlugin)

    suspend fun deleteInstalledPluginById(id: String)
}
