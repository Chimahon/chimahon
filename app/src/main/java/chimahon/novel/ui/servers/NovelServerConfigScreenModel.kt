package chimahon.novel.ui.servers

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.model.NovelServer
import chimahon.novel.model.NovelServerStorage
import chimahon.novel.model.NovelServerType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelServerConfigScreenModel(
    private val serverStorage: NovelServerStorage = Injekt.get(),
) : StateScreenModel<NovelServerConfigScreenModel.State>(State()) {

    init {
        loadServers()
    }

    fun loadServers() {
        screenModelScope.launch {
            serverStorage.getAllServers().collect { servers ->
                mutableState.value = mutableState.value.copy(servers = servers.toImmutableList(), isLoading = false)
            }
        }
    }

    fun showAddDialog() {
        mutableState.value = mutableState.value.copy(dialog = Dialog.AddEdit(null))
    }

    fun showEditDialog(server: NovelServer) {
        mutableState.value = mutableState.value.copy(dialog = Dialog.AddEdit(server))
    }

    fun showDeleteConfirmDialog(server: NovelServer) {
        mutableState.value = mutableState.value.copy(dialog = Dialog.DeleteConfirm(server))
    }

    fun saveServer(server: NovelServer) {
        screenModelScope.launch {
            serverStorage.saveServer(server)
            closeDialog()
        }
    }

    fun deleteServer(server: NovelServer) {
        screenModelScope.launch {
            serverStorage.deleteServer(server.id)
            closeDialog()
        }
    }

    fun toggleServerEnabled(server: NovelServer) {
        screenModelScope.launch {
            serverStorage.saveServer(server.copy(enabled = !server.enabled))
        }
    }

    fun closeDialog() {
        mutableState.value = mutableState.value.copy(dialog = null)
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val servers: ImmutableList<NovelServer> = persistentListOf(),
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data class AddEdit(val existing: NovelServer?) : Dialog
        data class DeleteConfirm(val server: NovelServer) : Dialog
    }
}
