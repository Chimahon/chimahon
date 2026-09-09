package chimahon.novel.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import tachiyomi.core.common.preference.getAndSet
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pin toggle against the novel catalogue pin set (browse + global-search
 * `PinnedOnly` both read it).
 */
class ToggleNovelSourcePin(
    private val preferences: SourcePreferences = Injekt.get(),
) {

    fun await(source: NovelsPageSource) {
        val sourceId = source.id.toString()
        preferences.pinnedNovelSources().getAndSet { pinned ->
            if (sourceId in pinned) pinned - sourceId else pinned + sourceId
        }
    }
}
