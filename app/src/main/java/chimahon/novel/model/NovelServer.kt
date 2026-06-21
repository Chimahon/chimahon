package chimahon.novel.model

import kotlinx.serialization.Serializable

enum class NovelServerType {
    OPDS,
    KOMGA,
    KAVITA,
}

@Serializable
data class NovelServer(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: NovelServerType,
    val baseUrl: String,
    val username: String? = null,
    val password: String? = null,
    val apiKey: String? = null,
    val enabled: Boolean = true,
    val displayOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun requiresAuth(): Boolean = !username.isNullOrBlank() || !apiKey.isNullOrBlank()

    fun sanitizedCopy(): NovelServer = copy(
        apiKey = if (apiKey.isNullOrBlank()) null else "****",
        password = if (password.isNullOrBlank()) null else "****",
    )
}
