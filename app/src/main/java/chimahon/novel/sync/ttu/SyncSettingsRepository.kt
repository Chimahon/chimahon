package chimahon.novel.sync.ttu

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SyncSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadFromPrefs())

    val settings: Flow<SyncSettings> = _settings.asStateFlow()

    fun currentSettings(): SyncSettings = _settings.value

    fun update(transform: (SyncSettings) -> SyncSettings) {
        val updated = transform(_settings.value)
        saveToPrefs(updated)
        _settings.value = updated
    }

    private fun loadFromPrefs(): SyncSettings {
        val open = prefs.getBoolean(KEY_AUTO_SYNC_ON_OPEN, false)
        val close = prefs.getBoolean(KEY_AUTO_SYNC_ON_CLOSE, false)
        val periodic = prefs.getBoolean(KEY_AUTO_SYNC_PERIODIC, false)
        val oldAutoSync = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)
        val migrated = oldAutoSync && !prefs.contains(KEY_AUTO_SYNC_ON_OPEN) && !prefs.contains(KEY_AUTO_SYNC_ON_CLOSE) && !prefs.contains(KEY_AUTO_SYNC_PERIODIC)

        val finalOpen = if (migrated) true else open
        val finalClose = if (migrated) true else close
        val finalPeriodic = if (migrated) true else periodic

        return SyncSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            mode = SyncMode.fromRawValue(prefs.getString(KEY_MODE, null)),
            autoSyncEnabled = finalOpen || finalClose || finalPeriodic,
            statisticsSyncEnabled = prefs.getBoolean(KEY_STATS_SYNC_ENABLED, false),
            statisticsSyncMode = StatisticsSyncMode.fromRawValue(prefs.getString(KEY_STATS_SYNC_MODE, null)),
            autoSyncOnOpen = finalOpen,
            autoSyncOnClose = finalClose,
            autoSyncPeriodic = finalPeriodic,
            autoSyncIntervalMins = prefs.getInt(KEY_AUTO_SYNC_INTERVAL_MINS, 10),
        )
    }

    private fun saveToPrefs(settings: SyncSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_MODE, settings.mode.rawValue)
            .putBoolean(KEY_AUTO_SYNC_ENABLED, settings.autoSyncOnOpen || settings.autoSyncOnClose || settings.autoSyncPeriodic)
            .putBoolean(KEY_AUTO_SYNC_ON_OPEN, settings.autoSyncOnOpen)
            .putBoolean(KEY_AUTO_SYNC_ON_CLOSE, settings.autoSyncOnClose)
            .putBoolean(KEY_AUTO_SYNC_PERIODIC, settings.autoSyncPeriodic)
            .putInt(KEY_AUTO_SYNC_INTERVAL_MINS, settings.autoSyncIntervalMins)
            .putBoolean(KEY_STATS_SYNC_ENABLED, settings.statisticsSyncEnabled)
            .putString(KEY_STATS_SYNC_MODE, settings.statisticsSyncMode.rawValue)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ttu-sync-settings"
        private const val KEY_ENABLED = "syncEnabled"
        private const val KEY_MODE = "syncMode"
        private const val KEY_AUTO_SYNC_ENABLED = "autoSyncEnabled"
        private const val KEY_AUTO_SYNC_ON_OPEN = "autoSyncOnOpen"
        private const val KEY_AUTO_SYNC_ON_CLOSE = "autoSyncOnClose"
        private const val KEY_AUTO_SYNC_PERIODIC = "autoSyncPeriodic"
        private const val KEY_AUTO_SYNC_INTERVAL_MINS = "autoSyncIntervalMins"
        private const val KEY_STATS_SYNC_ENABLED = "statsSyncEnabled"
        private const val KEY_STATS_SYNC_MODE = "statsSyncMode"
    }
}
