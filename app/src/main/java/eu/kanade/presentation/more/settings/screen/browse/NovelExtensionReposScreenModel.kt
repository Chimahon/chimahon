package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import chimahon.novel.plugin.NovelPluginManager
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.novelextensionrepo.interactor.CreateNovelExtensionRepo
import mihon.domain.novelextensionrepo.interactor.DeleteNovelExtensionRepo
import mihon.domain.novelextensionrepo.interactor.GetNovelExtensionRepo
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionReposScreenModel(
    private val getNovelExtensionRepo: GetNovelExtensionRepo = Injekt.get(),
    private val createNovelExtensionRepo: CreateNovelExtensionRepo = Injekt.get(),
    private val deleteNovelExtensionRepo: DeleteNovelExtensionRepo = Injekt.get(),
    private val novelPluginManager: NovelPluginManager = Injekt.get(),
) : StateScreenModel<NovelRepoScreenState>(NovelRepoScreenState.Loading) {

    private val _events: Channel<NovelRepoEvent> = Channel(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    init {
        screenModelScope.launchIO {
            getNovelExtensionRepo.subscribeAll()
                .collectLatest { repos ->
                    mutableState.update {
                        NovelRepoScreenState.Success(repos = repos.toImmutableSet())
                    }
                }
        }
    }

    fun createRepo(baseUrl: String) {
        screenModelScope.launchIO {
            when (createNovelExtensionRepo.await(baseUrl)) {
                CreateNovelExtensionRepo.Result.Success -> {
                    novelPluginManager.refresh()
                }
                CreateNovelExtensionRepo.Result.InvalidUrl -> _events.send(NovelRepoEvent.InvalidUrl)
                CreateNovelExtensionRepo.Result.RepoAlreadyExists -> _events.send(NovelRepoEvent.RepoAlreadyExists)
                CreateNovelExtensionRepo.Result.Error -> {}
            }
        }
    }

    fun refreshRepos() {
        val status = state.value
        if (status is NovelRepoScreenState.Success) {
            screenModelScope.launchIO {
                novelPluginManager.refresh()
            }
        }
    }

    fun deleteRepo(baseUrl: String) {
        screenModelScope.launchIO {
            deleteNovelExtensionRepo.await(baseUrl)
            novelPluginManager.refresh()
        }
    }

    fun showDialog(dialog: NovelRepoDialog) {
        mutableState.update {
            when (it) {
                NovelRepoScreenState.Loading -> it
                is NovelRepoScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                NovelRepoScreenState.Loading -> it
                is NovelRepoScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed class NovelRepoEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : NovelRepoEvent()
    data object InvalidUrl : LocalizedMessage(MR.strings.invalid_repo_name)
    data object RepoAlreadyExists : LocalizedMessage(MR.strings.error_repo_exists)
}

sealed class NovelRepoDialog {
    data object Create : NovelRepoDialog()
    data class Delete(val repo: String) : NovelRepoDialog()
}

sealed class NovelRepoScreenState {

    @Immutable
    data object Loading : NovelRepoScreenState()

    @Immutable
    data class Success(
        val repos: ImmutableSet<ExtensionRepo>,
        val dialog: NovelRepoDialog? = null,
    ) : NovelRepoScreenState() {
        val isEmpty: Boolean
            get() = repos.isEmpty()
    }
}
