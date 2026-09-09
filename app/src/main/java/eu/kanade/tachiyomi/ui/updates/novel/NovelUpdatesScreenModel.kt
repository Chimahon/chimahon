package eu.kanade.tachiyomi.ui.updates.novel

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.updates.novel.model.NovelUpdatesWithRelations
import tachiyomi.domain.updates.novel.repository.NovelUpdatesRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime

class NovelUpdatesScreenModel(
    private val novelUpdatesRepository: NovelUpdatesRepository = Injekt.get(),
) : StateScreenModel<NovelUpdatesScreenModel.State>(State()) {

    init {
        load()
    }

    fun load() {
        screenModelScope.launch {
            val after = ZonedDateTime.now().minusMonths(3).toInstant().toEpochMilli()
            runCatching {
                novelUpdatesRepository.awaitUpdates(after = after, limit = 500)
            }.onSuccess { updates ->
                mutableState.update {
                    it.copy(isLoading = false, items = updates)
                }
            }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelUpdatesWithRelations> = emptyList(),
    )
}
