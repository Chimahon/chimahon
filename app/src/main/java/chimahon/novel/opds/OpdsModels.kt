package chimahon.novel.opds

import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class OpdsCatalog(
    val id: String,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
)

data class OpdsNavigation(val title: String, val url: String)
data class OpdsFeed(
    val title: String,
    val navigation: List<OpdsNavigation>,
    val books: List<OpdsBook>,
    val next: String? = null,
    val search: String? = null,
    val searchDescription: Boolean = false,
)

@Serializable
data class OpdsBook(
    val catalogId: String,
    val entryId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val language: String? = null,
    val cover: String? = null,
    val acquisition: String? = null,
    val detail: String? = null,
) {
    val key: String get() = opdsHash("$catalogId\n$entryId")
    val url: String get() = "opds://$catalogId/$key"
    val folder: String get() = "opds_$key"

    fun toNovel() = SNNovel(
        url = url,
        title = title,
        author = author,
        description = description,
        thumbnail_url = cover,
        lang = language,
        source = OpdsSource.ID,
        initialized = true,
    )
}

internal fun opdsHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
