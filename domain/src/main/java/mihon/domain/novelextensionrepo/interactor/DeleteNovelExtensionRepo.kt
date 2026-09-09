package mihon.domain.novelextensionrepo.interactor

import mihon.domain.novelextensionrepo.repository.NovelExtensionRepoRepository

class DeleteNovelExtensionRepo(
    private val repository: NovelExtensionRepoRepository,
) {
    suspend fun await(baseUrl: String) {
        repository.deleteRepo(baseUrl)
    }
}
