package chimahon.novel.data.epub

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: EpubMediaType = EpubMediaType.UNKNOWN,
    val properties: String? = null,
)
