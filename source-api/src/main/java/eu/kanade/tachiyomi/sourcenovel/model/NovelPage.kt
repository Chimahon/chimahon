package eu.kanade.tachiyomi.sourcenovel.model

data class NovelPage(
    val novels: List<SNNovel>,
    val hasNextPage: Boolean,
)
