package eu.kanade.tachiyomi.sourcenovel.model

sealed class ChapterContent {
    data class Text(val text: String) : ChapterContent()
    data class Html(val html: String) : ChapterContent()
    data class Images(val urls: List<String>) : ChapterContent()
    data class Mixed(val items: List<ContentItem>) : ChapterContent()

    companion object {
        fun text(text: String) = Text(text)
        fun html(html: String) = Html(html)
        fun images(urls: List<String>) = Images(urls)
        fun mixed(items: List<ContentItem>) = Mixed(items)
    }
}

sealed class ContentItem {
    data class Text(val text: String) : ContentItem()
    data class Image(val url: String) : ContentItem()
    data class Html(val html: String) : ContentItem()
    data class Images(val urls: List<String>) : ContentItem()
    data class Mixed(val items: List<ContentItem>) : ContentItem()
}
