package chimahon.novel.sync.ttu

import android.content.Context

/**
 * Sticky TTU Drive folder name per book (a rename must not orphan the remote
 * folder). Keyed by novel id when registered, by folder otherwise. Plain
 * prefs map — no DB migration for a sync-internal cache.
 */
class TtuFolderNames(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(novelId: Long?, folder: String): String? {
        if (novelId != null) {
            prefs.getString(idKey(novelId), null)?.let { return it }
        }
        return prefs.getString(folderKey(folder), null)
    }

    fun set(novelId: Long?, folder: String, ttuFolderName: String) {
        prefs.edit()
            .putString(folderKey(folder), ttuFolderName)
            .apply {
                if (novelId != null) putString(idKey(novelId), ttuFolderName)
            }
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ttu-sync-folders"

        private fun idKey(novelId: Long) = "id:$novelId"
        private fun folderKey(folder: String) = "folder:$folder"
    }
}
