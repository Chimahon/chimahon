package chimahon.novel.data.epub

import kotlinx.serialization.Serializable

@Serializable
enum class PageProgressionDirection {
    LTR,
    RTL,
    DEFAULT,
    ;

    companion object {
        fun fromString(value: String?): PageProgressionDirection {
            return when (value?.lowercase()) {
                "ltr" -> LTR
                "rtl" -> RTL
                else -> DEFAULT
            }
        }
    }
}

@Serializable
data class EpubSpine(
    val id: String? = null,
    val toc: String? = null,
    val pageProgressionDirection: PageProgressionDirection = PageProgressionDirection.DEFAULT,
    val items: List<SpineItem> = emptyList(),
)
