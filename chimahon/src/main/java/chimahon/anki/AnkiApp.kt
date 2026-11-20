package chimahon.anki

import android.content.Context
import android.content.pm.PackageManager

/** An app that exposes the AnkiDroid content-provider API (official or a fork). */
data class AnkiApp(
    val packageName: String,
    val label: String,
) {
    val authority: String get() = "$packageName.flashcards"
    val readWritePermission: String get() = "$packageName.permission.READ_WRITE_DATABASE"
}

val KNOWN_ANKI_APPS = listOf(
    AnkiApp("com.ichi2.anki", "AnkiDroid"),
    AnkiApp("io.github.pedrubik2000.kuma3", "kuma3"),
)

/**
 * The Anki app every [AnkiDroidBridge] talks to. Set at startup from the saved
 * preference and whenever the user picks another app in settings.
 */
object AnkiAppSelection {
    const val DEFAULT_PACKAGE = "com.ichi2.anki"

    @Volatile
    var packageName: String = DEFAULT_PACKAGE

    fun app(packageName: String = this.packageName): AnkiApp =
        KNOWN_ANKI_APPS.firstOrNull { it.packageName == packageName }
            ?: AnkiApp(packageName, packageName)

    /** True when [packageName] is installed and exposes `<pkg>.flashcards`. */
    fun isInstalled(context: Context, packageName: String = this.packageName): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            context.packageManager
                .resolveContentProvider("$packageName.flashcards", 0)
                ?.packageName == packageName
        }.getOrDefault(false)
    }

    /**
     * The AnkiDroid API permission is a custom dangerous permission declared by
     * the Anki app. Android only lets a client request permissions listed in its
     * own manifest, so a fork whose permission is not built into Chimahon cannot
     * be used even though its provider is visible.
     */
    fun canRequestPermission(context: Context, packageName: String = this.packageName): Boolean {
        val permission = app(packageName).readWritePermission
        return runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(permission) == true
        }.getOrDefault(false)
    }
}
