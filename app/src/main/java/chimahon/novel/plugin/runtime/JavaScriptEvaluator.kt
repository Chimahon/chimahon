package chimahon.novel.plugin.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * One native QuickJS runtime for both J2K extension helpers and Novel novel plugins.
 * Keeping one implementation avoids two incompatible JNI libraries with the same filename.
 *
 * QuickJS is single-threaded: the runtime is confined to one thread, never the
 * shared IO pool (whose threads hop between calls and corrupt the VM).
 */
object JavaScriptEvaluator {
    private val jsDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JavaScriptEvaluator-js").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    suspend fun <T> evaluate(script: String): T =
        withContext(jsDispatcher) {
            QuickJs.create(jsDispatcher).use { runtime ->
                @Suppress("UNCHECKED_CAST")
                runtime.evaluate<Any?>(script, "tachiyomi-extension.js", asModule = false) as T
            }
        }
}



