package tachiyomi.domain.novel.model

/**
 * Single home for the read-completion threshold. Progress is a 0.0-1.0
 * fraction; several layers (reader persist, detail sync, migration) must
 * agree on what counts as "finished", so the epsilon lives here, not as
 * copies.
 */
const val NOVEL_READ_COMPLETE_EPSILON = 0.999

fun Double.isNovelReadComplete(): Boolean = this >= NOVEL_READ_COMPLETE_EPSILON
