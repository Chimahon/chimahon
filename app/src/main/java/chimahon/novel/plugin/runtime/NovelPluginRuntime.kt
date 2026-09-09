package chimahon.novel.plugin.runtime

import android.content.Context
import chimahon.novel.error.NovelFailure
import chimahon.novel.error.novelRequire
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class NovelPluginRuntime(
    context: Context,
    private val pluginId: String,
    private val siteUrl: String,
    private val dispatcher: CoroutineDispatcher,
) {
    private val library = NovelPluginLibrary(context, pluginId, siteUrl)

    suspend fun open(code: String): NovelPluginInstance {
        novelRequire(code.length in 1..MAX_CODE_CHARS, NovelFailure.Code.PluginCodeSize)
        val runtime = QuickJs.create(dispatcher)
        try {
            withTimeout(EVALUATION_TIMEOUT_MS) { library.setup(runtime) }
            withTimeout(EVALUATION_TIMEOUT_MS) { runtime.evaluate<Any?>(sanitize(code), "$pluginId.js", asModule = false) }
            withTimeout(EVALUATION_TIMEOUT_MS) {
                runtime.evaluate<Any?>(
                    """
                    globalThis.__novelPluginClass =
                        (typeof exports !== 'undefined' && exports.default) ||
                        (typeof module !== 'undefined' && module.exports && (module.exports.default || module.exports));
                    if (!globalThis.__novelPluginClass) throw new Error('Plugin has no default export');
                    globalThis.plugin = typeof globalThis.__novelPluginClass === 'function'
                        ? new globalThis.__novelPluginClass()
                        : globalThis.__novelPluginClass;
                    if (!globalThis.plugin) throw new Error('Plugin could not be instantiated');
                    """.trimIndent(),
                    "novel-plugin-loader.js",
                    asModule = false,
                )
            }
            return NovelPluginInstance(runtime, library, dispatcher)
        } catch (error: Throwable) {
            // Close on the dispatcher thread; the caller may be Main/IO.
            runCatching { withContext(dispatcher) { runtime.close() } }
            library.cleanup()
            throw error
        }
    }

    private fun sanitize(code: String): String =
        code
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
            .replace(Regex("[\u0001-\u0008\u000B\u000C\u000E-\u001F]"), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    companion object {
        private const val MAX_CODE_CHARS = 8 * 1024 * 1024
        private const val EVALUATION_TIMEOUT_MS = 30_000L
    }
}

internal class NovelPluginInstance(
    private val runtime: QuickJs,
    private val library: NovelPluginLibrary,
    private val dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    suspend fun evaluate(script: String): Any? =
        withTimeout(30_000L) { runtime.evaluate<Any?>(script, "novel-plugin-call.js", asModule = false) }

    suspend fun execute(script: String): Any? = evaluate(script)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun close() {
        // Cannot suspend: post to the dispatcher thread. Never throws (must not mask finally).
        val executor = (dispatcher as? kotlinx.coroutines.ExecutorCoroutineDispatcher)?.executor
        if (executor != null) {
            runCatching { executor.execute { runCatching { runtime.close() } } }
                .onFailure { runCatching { runtime.close() } }
        } else {
            runCatching { runtime.close() }
        }
        library.cleanup()
    }
}
