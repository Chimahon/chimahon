package chimahon.novel.data.epub

data class TocEntry(
    val id: String,
    val label: String,
    val href: String? = null,
    val children: List<TocEntry> = emptyList(),
)
