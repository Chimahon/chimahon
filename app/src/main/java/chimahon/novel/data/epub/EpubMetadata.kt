package chimahon.novel.data.epub

data class EpubMetadata(
    val title: String? = null,
    val identifier: String? = null,
    val language: String? = null,
    val creator: EpubCreator? = null,
    val contributor: EpubCreator? = null,
    val publisher: String? = null,
    val date: String? = null,
    val description: String? = null,
    val rights: String? = null,
    val subject: String? = null,
    val coverage: String? = null,
    val format: String? = null,
    val relation: String? = null,
    val source: String? = null,
    val type: String? = null,
    val coverId: String? = null,
)
