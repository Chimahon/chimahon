package chimahon.novel.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.novelServerDataStore by preferencesDataStore(name = "novel_servers")

private val SERVERS_KEY = stringPreferencesKey("servers")

class NovelServerStorage(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllServers(): Flow<List<NovelServer>> {
        return context.novelServerDataStore.data.map { prefs ->
            val raw = prefs[SERVERS_KEY]
            if (raw != null) {
                runCatching { json.decodeFromString<List<NovelServer>>(raw) }
                    .getOrDefault(emptyList())
                    .sortedBy { it.displayOrder }
            } else {
                emptyList()
            }
        }
    }

    suspend fun saveServer(server: NovelServer) {
        context.novelServerDataStore.edit { prefs ->
            val servers = loadAll(prefs).toMutableList()
            val idx = servers.indexOfFirst { it.id == server.id }
            if (idx >= 0) servers[idx] = server else servers.add(server)
            prefs[SERVERS_KEY] = json.encodeToString(servers)
        }
    }

    suspend fun deleteServer(serverId: String) {
        context.novelServerDataStore.edit { prefs ->
            val servers = loadAll(prefs).filter { it.id != serverId }
            prefs[SERVERS_KEY] = json.encodeToString(servers)
        }
    }

    suspend fun updateServerOrder(serverId: String, newOrder: Int) {
        context.novelServerDataStore.edit { prefs ->
            val servers = loadAll(prefs).map {
                if (it.id == serverId) it.copy(displayOrder = newOrder) else it
            }
            prefs[SERVERS_KEY] = json.encodeToString(servers)
        }
    }

    private fun loadAll(prefs: androidx.datastore.preferences.core.Preferences): List<NovelServer> {
        val raw = prefs[SERVERS_KEY] ?: return emptyList()
        return runCatching { json.decodeFromString<List<NovelServer>>(raw) }
            .getOrDefault(emptyList())
    }
}
