package mihon.core.migration.migrations

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

/**
 * Converts the novel default category from a String category id to an Int so it
 * matches the manga and anime preferences, which both use Int with -1 meaning
 * unset. The old value was stored under the same key, so it is read before the
 * type changes and rewritten in place.
 */
class MigrateNovelDefaultCategoryMigration : Migration {
    override val version: Float = 86f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val key = "novel_default_category"
        if (!prefs.contains(key)) return true

        val stored = prefs.all[key]
        if (stored !is String) return true

        // "" was the unset sentinel and "default" was the UI id for the system
        // category, whose database id is 0.
        val migrated = when {
            stored.isEmpty() -> -1
            stored == "default" -> 0
            else -> stored.toLongOrNull()?.toInt() ?: -1
        }
        prefs.edit { putInt(key, migrated) }
        return true
    }
}