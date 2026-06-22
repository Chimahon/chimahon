package eu.kanade.tachiyomi.sourcenovel.model

import java.io.Serializable

data class SNChapter(
    val name: String = "",
    val url: String = "",
    val chapter_number: Float = 0f,
    val date_upload: Long = 0L,
    val id: Long = -1,
    val scanlator: String? = null,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val last_page_read: Int = 0,
    val date_fetch: Long = 0L,
    val date_upload_mod: Long = 0L,
    val view_count: Int = 0,
) : Serializable
