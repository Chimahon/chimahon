package chimahon.novel.data.epub

import kotlinx.serialization.Serializable

@Serializable
data class SpineItem(
    val idref: String,
    val id: String? = null,
    val linear: Boolean = true,
    val type: SpineItemType = SpineItemType.TEXT,
    /** Resolved file:// URL for the image — non-null only when type == IMAGE_ONLY. */
    val imageUrl: String? = null,
)

@Serializable
enum class SpineItemType {
    TEXT,
    IMAGE_ONLY,
}
