package app.cash.quickjs

import com.dokar.quickjs.QuickJs as DokarQuickJs
import com.dokar.quickjs.QuickJsException as DokarQuickJsException
import com.dokar.quickjs.binding.JsFunction
import com.dokar.quickjs.binding.JsProperty
import com.dokar.quickjs.binding.ObjectBinding
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * A drop-in replacement for the `app.cash.quickjs.QuickJs` class that extensions are
 * compiled against, implemented on top of the QuickJS engine this app already ships
 * (`io.github.dokar3:quickjs-kt`, whose native library is `libquickjs.so`).
 *
 * Why this exists: `com.github.zhanghai.quickjs-java:quickjs-android` and
 * `io.github.dokar3:quickjs-kt` both ship a native library at the identical APK path
 * `lib/<abi>/libquickjs.so`, but expose two unrelated JNI ABIs
 * (`Java_app_cash_quickjs_QuickJs_*` vs `Java_com_dokar_quickjs_QuickJs_*`). Only one file
 * can occupy that path, and the loser has every native method fail to link at runtime
 * with `UnsatisfiedLinkError: No implementation found`. Rather than packaging the same
 * engine twice, this class keeps the `app.cash.quickjs` surface extensions link against
 * while delegating to the engine that is actually present.
 *
 * Extensions resolve this class from the app, not from their own APK: extension APKs
 * bundle a copy of the original, but [eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader]
 * consults the app's class loader first. That is what makes replacing the class safe.
 *
 * The public signatures must not drift from the class being replaced, or extensions
 * compiled against it will fail with `NoSuchMethodError`; see `QuickJsAbiTest`.
 *
 * QuickJS is single-threaded. Every call is confined to one dedicated thread and the
 * caller blocks on it, which is what the original synchronous JNI API did.
 */
class QuickJs private constructor() : Closeable {

    private val runtime: DokarQuickJs = DokarQuickJs.create(dispatcher)

    @JvmOverloads
    fun evaluate(code: String, fileName: String = DEFAULT_FILE_NAME): Any? = onJs {
        onJsThread { evaluate<Any?>(code, fileName, asModule = false) }
    }

    fun execute(bytecode: ByteArray): Any? = onJs {
        onJsThread { evaluate<Any?>(bytecode) }
    }

    fun compile(code: String, fileName: String): ByteArray = onJs {
        onJsThread { compile(code, fileName, false) }
    }

    /**
     * Exposes [value] to JavaScript as the global object [name]. Only interfaces can be
     * bound, and an interface must declare each method name at most once.
     */
    fun <T : Any> set(name: String, type: Class<T>, value: T) {
        val methods = bindableMethods(name, type)
        require(type.isInstance(value)) { "$value is not an instance of $type" }
        onJs {
            onJsThread { defineBinding(name, InterfaceBinding(value, methods)) }
        }
    }

    /**
     * Returns a proxy for the JavaScript global object [name], typed as [type].
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(name: String, type: Class<T>): T {
        bindableMethods(name, type)
        return Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            JsObjectHandler(this, name),
        ) as T
    }

    override fun close() = onJs {
        onJsThread { close() }
    }

    private fun bindableMethods(name: String, type: Class<*>): List<Method> {
        require(name.isNotBlank()) { "name must not be blank" }
        require(type.isInterface) { "Only interfaces can be bound. Received: $type" }
        require(type.interfaces.isEmpty()) { "$type must not extend other interfaces" }

        val methods = type.methods.sortedBy { it.name }
        methods.groupBy { it.name }.forEach { (methodName, overloads) ->
            require(overloads.size == 1) { "$methodName is overloaded in $type" }
        }
        return methods
    }

    /**
     * Runs [block] on the engine's thread and blocks until it finishes. [block] runs on a
     * thread no caller can be on, so this cannot deadlock.
     */
    private fun <T> onJsThread(block: suspend DokarQuickJs.() -> T): T = runBlocking {
        withContext(dispatcher) { runtime.block() }
    }

    companion object {
        internal const val DEFAULT_FILE_NAME = "?"

        /**
         * QuickJS is not thread safe. One thread is shared by every instance, matching
         * `JavaScriptEvaluator`, and is never a thread an extension can call from.
         */
        private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QuickJs-shim-js").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        @JvmStatic
        fun create(): QuickJs = QuickJs()
    }
}

/**
 * Bridges the engine's [DokarQuickJsException] into the exception type extensions catch,
 * so extension code that catches `QuickJsException` keeps working.
 */
private inline fun <T> onJs(block: () -> T): T = try {
    block()
} catch (error: DokarQuickJsException) {
    throw QuickJsException(error.message ?: "JavaScript error")
}

private class InterfaceBinding(
    private val target: Any,
    private val methods: List<Method>,
) : ObjectBinding {
    override val properties: List<JsProperty> = methods.map { JsProperty(it.name, false, true, true) }

    override val functions: List<JsFunction> = methods.map { JsFunction(it.name, false) }

    override fun getter(name: String): Any? = null

    override fun setter(name: String, value: Any?) = Unit

    override fun invoke(name: String, args: Array<Any?>): Any? {
        val method = methods.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("$name is not bound")
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }
}

/**
 * Forwards interface calls to the matching method of a JavaScript global object.
 */
private class JsObjectHandler(
    private val quickJs: QuickJs,
    private val globalName: String,
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "toString" -> "QuickJsObject($globalName)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        }

        val arguments = args.orEmpty().joinToString(",", prefix = "[", postfix = "]") {
            it.toJsLiteral()
        }
        val call = "globalThis[${globalName.toJsLiteral()}][${method.name.toJsLiteral()}]($arguments)"
        return quickJs.evaluate(call, "$globalName.${method.name}.js")
    }
}

/** Renders [this] as a JavaScript literal. JSON is a subset of JavaScript, so it suffices. */
private fun Any?.toJsLiteral(): String = when (this) {
    null -> "null"
    is Boolean, is Number -> toString()
    is Enum<*> -> toString()
    else -> toString().asJsString()
}

private fun String.asJsString(): String = buildString {
    append('"')
    forEach { char ->
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char == '\n' -> append("\\n")
            char == '\r' -> append("\\r")
            char == '\t' -> append("\\t")
            char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
    append('"')
}
