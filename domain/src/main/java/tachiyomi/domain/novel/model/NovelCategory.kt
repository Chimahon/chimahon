package tachiyomi.domain.novel.model

data class NovelCategory(
    val id: Long,
    val name: String,
    val order: Int,
    val flags: Long,
    val hidden: Boolean,
) {
    val isSystemCategory: Boolean
        get() = id == SYSTEM_CATEGORY_ID

    companion object {
        /**
         * Row 0 is the system "Default"/uncategorized slot; membership in it is
         * represented by the ABSENCE of join rows (manga convention), never by an
         * explicit row.
         */
        const val SYSTEM_CATEGORY_ID = 0L
    }
}
