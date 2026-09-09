package mihon.domain.novelextensionrepo.interactor

import kotlinx.coroutines.flow.Flow
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.novelextensionrepo.repository.NovelExtensionRepoRepository

class GetNovelExtensionRepo(
    private val repository: NovelExtensionRepoRepository,
) {
    fun subscribeAll(): Flow<List<ExtensionRepo>> = repository.subscribeAll()

    suspend fun getAll(): List<ExtensionRepo> = repository.getAll()
}
