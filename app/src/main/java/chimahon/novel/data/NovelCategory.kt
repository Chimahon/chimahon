package chimahon.novel.data

import kotlinx.serialization.Serializable

@Serializable
data class NovelCategory(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val order: Int = 0,
    val flags: Long = 0,
    val hidden: Boolean = false,
) {
    val isSystemCategory: Boolean
        get() = id == UNCATEGORIZED_ID

    companion object {
        const val UNCATEGORIZED_ID = "default"
    }
}
