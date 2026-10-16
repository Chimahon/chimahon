package chimahon.novel.opds

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OpdsStore(private val preferences: PreferenceStore = Injekt.get()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val catalogPreference = preferences.getString(Preference.privateKey("novel_opds_catalogs"), "[]")
    private val activePreference = preferences.getString("novel_opds_active_catalog", "")

    val catalogChanges get() = catalogPreference.changes()
    fun catalogs(): List<OpdsCatalog> = runCatching {
        json.decodeFromString<List<OpdsCatalog>>(catalogPreference.get())
    }.getOrDefault(emptyList())

    fun active(): OpdsCatalog? = catalogs().let { list -> list.find { it.id == activePreference.get() } ?: list.firstOrNull() }
    fun select(id: String) = activePreference.set(id)
    fun catalog(id: String) = catalogs().find { it.id == id } ?: error("This OPDS catalog is no longer configured")

    @Synchronized
    fun save(catalog: OpdsCatalog) {
        val list = catalogs().toMutableList()
        val index = list.indexOfFirst { it.id == catalog.id }
        if (index < 0) list.add(catalog) else list[index] = catalog
        catalogPreference.set(json.encodeToString(list))
    }

    @Synchronized
    fun remove(id: String) = catalogPreference.set(json.encodeToString(catalogs().filterNot { it.id == id }))

    fun saveBook(book: OpdsBook) = preferences.getString(Preference.privateKey("novel_opds_book_${book.key}"), "")
        .set(json.encodeToString(book))

    fun book(url: String): OpdsBook {
        require(url.startsWith("opds://")) { "Invalid OPDS book" }
        val key = url.substringAfterLast('/')
        require(key.matches(Regex("[0-9a-f]{32}"))) { "Invalid OPDS book identity" }
        return json.decodeFromString<OpdsBook>(
            preferences.getString(Preference.privateKey("novel_opds_book_$key"), "").get(),
        ).also { require(it.url == url) }
    }
}
