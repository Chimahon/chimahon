package chimahon.dictionary

import chimahon.anki.AnkiProfile
import chimahon.anki.AnkiProfileStore

/**
 * @param profileStore        the shared [AnkiProfileStore] singleton
 * @param readMangaOverride   manga-level override for [mangaId], or ""
 * @param readAnimeOverride   anime-level override for [animeId], or ""
 * @param readSourceOverride  source-level override for [sourceId], or ""
 * @param readNovelOverride   novel-level override for [novelId], or ""
 */
class DictionaryProfileResolver(
    private val profileStore: AnkiProfileStore,
    private val readMangaOverride: (mangaId: Long) -> String,
    private val readAnimeOverride: (animeId: Long) -> String,
    private val readSourceOverride: (sourceId: Long) -> String,
    private val readNovelOverride: (novelId: String) -> String = { "" },
) {

    /**
     * Resolve the profile for a reader session.
     *
     * Resolution priority (highest first):
     * 1. Manga override  – user pinned a specific profile to this manga ID.
     * 2. Anime override  – user pinned a specific profile to this anime ID.
     * 3. Novel override  – user pinned a specific profile to this novel ID.
     * 4. Source override – user pinned a specific profile to this source ID.
     * 5. Language match  – first profile in the list whose [AnkiProfile.languageCode]
     *    matches the source's language code (non-empty, non-"all" sources only).
     * 6. Global active   – whatever is currently selected in Settings.
     *
     * Novel-before-source: the entry-specific override wins, and this is safe
     * for manga/anime callers — they return at 1/2 when set and pass a blank
     * novelId, so the swap only ever affects novel sessions.
     *
     * @param mangaId   ID of the manga being read (0 if unknown / novel context)
     * @param animeId   ID of the anime being watched (0 if unknown)
     * @param sourceId  ID of the source (0 if unknown)
     * @param sourceLang BCP-47 language code from the source, e.g. "ja", "all", "" (unknown)
     * @param novelId   ID of the novel being read ("" if unknown)
     * @return the resolved [AnkiProfile]; never null (falls back to first available)
     */
    fun resolve(
        mangaId: Long = 0L,
        animeId: Long = 0L,
        sourceId: Long = 0L,
        sourceLang: String = "",
        novelId: String = "",
    ): AnkiProfile {
        val profiles = profileStore.getProfiles()
        if (profiles.isEmpty()) return profileStore.getActiveProfile()

        // 1. Manga-level override
        if (mangaId != 0L) {
            val overrideId = readMangaOverride(mangaId)
            val found = profiles.firstOrNull { it.id == overrideId }
            if (found != null) return found
        }

        // 2. Anime-level override
        if (animeId != 0L) {
            val overrideId = readAnimeOverride(animeId)
            val found = profiles.firstOrNull { it.id == overrideId }
            if (found != null) return found
        }

        // 3. Novel-level override (above source: entry beats source)
        if (novelId.isNotBlank()) {
            val overrideId = readNovelOverride(novelId)
            val found = profiles.firstOrNull { it.id == overrideId }
            if (found != null) return found
        }

        // 4. Source-level override
        if (sourceId != 0L) {
            val overrideId = readSourceOverride(sourceId)
            val found = profiles.firstOrNull { it.id == overrideId }
            if (found != null) return found
        }

        // 5. Language auto-match (skip for "all" / empty / unknown)
        if (sourceLang.isNotBlank() && sourceLang != "all") {
            val langMatch = profiles.firstOrNull {
                it.languageCode.equals(sourceLang, ignoreCase = true)
            }
            if (langMatch != null) return langMatch
        }

        // 6. Global active profile
        return profileStore.getActiveProfile()
    }

    companion object {
        /** SharedPreferences key for a manga-level profile override. */
        fun mangaOverrideKey(mangaId: Long) = "pref_dict_profile_manga_$mangaId"

        /** SharedPreferences key for an anime-level profile override. */
        fun animeOverrideKey(animeId: Long) = "pref_dict_profile_anime_$animeId"

        /** SharedPreferences key for a source-level profile override. */
        fun sourceOverrideKey(sourceId: Long) = "pref_dict_profile_source_$sourceId"

        /** SharedPreferences key for a novel-level profile override. */
        fun novelOverrideKey(novelId: String) = "pref_dict_profile_novel_$novelId"
    }
}
