package mihon.domain.novelextensionrepo.interactor

import logcat.LogPriority
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.novelextensionrepo.repository.NovelExtensionRepoRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat

class CreateNovelExtensionRepo(
    private val repository: NovelExtensionRepoRepository,
) {
    suspend fun await(indexUrl: String, customName: String? = null): Result {
        val httpUrl = indexUrl.trim().toHttpUrlOrNull() ?: return Result.InvalidUrl
        val urlString = httpUrl.toString().trimEnd('/')

        val repoName = customName?.takeIf { it.isNotBlank() }
            ?: if (urlString.contains("lnreader", ignoreCase = true)) {
                "LNReader Plugins"
            } else {
                httpUrl.host
            }
        val repoToInsert = ExtensionRepo(
            baseUrl = urlString,
            name = repoName,
            shortName = null,
            website = urlString,
            signingKeyFingerprint = "NOFINGERPRINT_${urlString.hashCode().toString(16)}",
        )

        return insert(repoToInsert)
    }

    private suspend fun insert(repo: ExtensionRepo): Result {
        return try {
            repository.insertRepo(
                repo.baseUrl,
                repo.name,
                repo.shortName,
                repo.website,
                repo.signingKeyFingerprint,
            )
            Result.Success
        } catch (e: SaveExtensionRepoException) {
            logcat(LogPriority.WARN, e) { "SQL Conflict attempting to add new novel repository ${repo.baseUrl}" }
            return handleInsertionError(repo)
        }
    }

    private suspend fun handleInsertionError(repo: ExtensionRepo): Result {
        // Novel repos carry synthetic NOFINGERPRINT_* fingerprints (no repo.json to
        // fetch), so same-URL is the only possible conflict — no fingerprint flow.
        return if (repository.getRepo(repo.baseUrl) != null) {
            Result.RepoAlreadyExists
        } else {
            Result.Error
        }
    }

    sealed interface Result {
        data object InvalidUrl : Result
        data object RepoAlreadyExists : Result
        data object Success : Result
        data object Error : Result
    }
}
