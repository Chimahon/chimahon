package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import mihon.domain.novelextensionrepo.interactor.GetNovelExtensionRepo
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionRepoRestorer(
    private val handler: DatabaseHandler = Injekt.get(),
    private val getExtensionRepos: GetNovelExtensionRepo = Injekt.get(),
) {

    suspend operator fun invoke(
        backupRepo: BackupExtensionRepos,
    ) {
        val dbRepos = getExtensionRepos.getAll()
        val existingReposBySha = dbRepos.associateBy { it.signingKeyFingerprint }
        val existingReposByUrl = dbRepos.associateBy { it.baseUrl }
        val urlExists = existingReposByUrl[backupRepo.baseUrl]
        val shaExists = existingReposBySha[backupRepo.signingKeyFingerprint]

        if (urlExists != null && urlExists.signingKeyFingerprint != backupRepo.signingKeyFingerprint &&
            !urlExists.signingKeyFingerprint.startsWith("NOFINGERPRINT")
        ) {
            error("Already Exists with different signing key fingerprint")
        } else if (shaExists != null && !shaExists.signingKeyFingerprint.startsWith("NOFINGERPRINT")) {
            error("${shaExists.name} has the same signing key fingerprint")
        } else {
            handler.await {
                novel_extension_reposQueries.insert(
                    backupRepo.baseUrl,
                    backupRepo.name,
                    backupRepo.shortName,
                    backupRepo.website,
                    backupRepo.signingKeyFingerprint,
                )
            }
        }
    }
}
