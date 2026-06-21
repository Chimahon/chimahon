package eu.kanade.tachiyomi.extension.ireader

import android.content.Context
import io.ktor.client.plugins.cookies.CookiesStorage
import ireader.core.http.AcceptAllCookiesStorage
import ireader.core.http.BrowserEngine
import ireader.core.http.CookieSynchronizer
import ireader.core.http.HttpClients
import ireader.core.http.WebViewCookieJar
import ireader.core.http.WebViewManger
import ireader.core.prefs.Preference
import ireader.core.prefs.PreferenceStore
import ireader.core.source.Dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import tachiyomi.core.common.preference.Preference as MihonPreference
import tachiyomi.core.common.preference.PreferenceStore as MihonPreferenceStore

internal class IReaderRuntime(
    context: Context,
    preferenceStore: MihonPreferenceStore,
) {
    private val appContext = context.applicationContext
    private val browserMutex = Mutex()
    private val cookiesStorage: CookiesStorage = AcceptAllCookiesStorage()
    private val cookiePreferences = IReaderPreferenceStore(preferenceStore, "runtime")
    private val webViewCookieJar = WebViewCookieJar(cookiesStorage)
    private val cookieSynchronizer = CookieSynchronizer(webViewCookieJar)
    private val webViewManager = WebViewManger(appContext)
    private val browserEngine = BrowserEngine(webViewManager, webViewCookieJar)

    private val httpClients = HttpClients(
        context = appContext,
        browseEngine = browserEngine,
        cookiesStorage = cookiesStorage,
        webViewCookieJar = webViewCookieJar,
        preferencesStore = cookiePreferences,
        webViewManager = webViewManager,
    )

    fun dependencies(packageName: String): Dependencies {
        return Dependencies(
            httpClients = httpClients,
            preferences = IReaderPreferenceStore(delegate = cookiePreferences.delegate, namespace = packageName),
        )
    }

    suspend fun fetch(url: String, baseUrl: String?): FetchResult? {
        val absoluteUrl = resolveUrl(url, baseUrl) ?: return null
        return browserMutex.withLock {
            val result = httpClients.browser.fetch(absoluteUrl)
            result.takeIf { it.isSuccess && it.responseBody.isNotBlank() }
                ?.let { FetchResult(absoluteUrl, it.responseBody) }
        }
    }

    fun resolveUrl(url: String, baseUrl: String?): String? {
        val value = url.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"

        val base = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (base.isEmpty()) return null
        return if (value.startsWith('/')) {
            val origin = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: base
            "$origin$value"
        } else {
            "$base/${value.trimStart('/')}"
        }
    }

    data class FetchResult(
        val url: String,
        val html: String,
    )
}

private class IReaderPreferenceStore(
    val delegate: MihonPreferenceStore,
    namespace: String,
) : PreferenceStore {
    private val prefix = "ireader.$namespace."

    override fun getString(key: String, defaultValue: String): Preference<String> =
        delegate.getString(scoped(key), defaultValue).asIReaderPreference()

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        delegate.getLong(scoped(key), defaultValue).asIReaderPreference()

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        delegate.getInt(scoped(key), defaultValue).asIReaderPreference()

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        delegate.getFloat(scoped(key), defaultValue).asIReaderPreference()

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        delegate.getBoolean(scoped(key), defaultValue).asIReaderPreference()

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        delegate.getStringSet(scoped(key), defaultValue).asIReaderPreference()

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        return delegate.getObjectFromString(scoped(key), defaultValue, serializer, deserializer)
            .asIReaderPreference()
    }

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule,
    ): Preference<T> {
        val json = Json {
            ignoreUnknownKeys = true
            this.serializersModule = serializersModule
        }
        return delegate.getObjectFromString(
            key = scoped(key),
            defaultValue = defaultValue,
            serializer = { json.encodeToString(serializer, it) },
            deserializer = { value ->
                runCatching { json.decodeFromString(serializer, value) }.getOrDefault(defaultValue)
            },
        ).asIReaderPreference()
    }

    private fun scoped(key: String) = "$prefix$key"
}

private fun <T> MihonPreference<T>.asIReaderPreference(): Preference<T> {
    return object : Preference<T> {
        override fun key(): String = this@asIReaderPreference.key()
        override fun get(): T = this@asIReaderPreference.get()
        override fun set(value: T) = this@asIReaderPreference.set(value)
        override fun isSet(): Boolean = this@asIReaderPreference.isSet()
        override fun delete() = this@asIReaderPreference.delete()
        override fun defaultValue(): T = this@asIReaderPreference.defaultValue()
        override fun changes(): Flow<T> = this@asIReaderPreference.changes()
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = this@asIReaderPreference.stateIn(scope)
    }
}
