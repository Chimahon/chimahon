package eu.kanade.tachiyomi.extension.novel

import eu.kanade.tachiyomi.sourcenovel.NovelSource

class JsNovelSource(
    private val pluginId: String,
    private val pluginName: String,
    override val lang: String,
) : NovelSource {
    override val id: Long = pluginId.hashCode().toLong() and Long.MAX_VALUE
    override val name: String = pluginName

    override suspend fun getNovelDetails(novel: eu.kanade.tachiyomi.sourcenovel.model.SNNovel): eu.kanade.tachiyomi.sourcenovel.model.SNNovel {
        TODO("JS plugin runtime not yet implemented - placeholder for QuickJS bridge")
    }

    override suspend fun getChapterList(novel: eu.kanade.tachiyomi.sourcenovel.model.SNNovel): List<eu.kanade.tachiyomi.sourcenovel.model.SNChapter> {
        TODO("JS plugin runtime not yet implemented")
    }

    override suspend fun getChapterContent(chapter: eu.kanade.tachiyomi.sourcenovel.model.SNChapter): eu.kanade.tachiyomi.sourcenovel.model.ChapterContent {
        TODO("JS plugin runtime not yet implemented")
    }
}

object JsNovelPluginManager {
    fun loadPlugins(): List<JsNovelSource> = emptyList()
    fun getFilterList(pluginId: String): eu.kanade.tachiyomi.source.model.FilterList = eu.kanade.tachiyomi.source.model.FilterList()
}
