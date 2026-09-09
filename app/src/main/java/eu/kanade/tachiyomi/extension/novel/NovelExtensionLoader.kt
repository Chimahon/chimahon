package eu.kanade.tachiyomi.extension.novel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult

object NovelExtensionLoader {

    fun isNovelExtension(appInfo: ApplicationInfo): Boolean {
        val features = appInfo.metaData?.getString("extension.features") ?: return false
        return features.split(",").any { it.trim() == "ireader.extension" }
    }

    internal suspend fun loadNovelExtension(
        context: Context,
        appInfo: ApplicationInfo,
        pkgInfo: PackageInfo,
        classLoader: ClassLoader,
        extName: String,
    ): Pair<eu.kanade.tachiyomi.sourcenovel.NovelSource, Any>? {
        // LNReader JS plugins are handled by NovelPluginManager, not APK loader
        return null
    }
}
