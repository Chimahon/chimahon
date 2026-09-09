package mihon.domain.novelextensionrepo.interactor

import mihon.domain.novelextensionrepo.repository.NovelExtensionRepoRepository

class GetNovelExtensionRepoCount(
    private val repository: NovelExtensionRepoRepository,
) {
    fun subscribe() = repository.getCount()
}
