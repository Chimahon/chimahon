package app.cash.quickjs

/**
 * A drop-in replacement for `app.cash.quickjs.QuickJsException`, the exception thrown by
 * the removed `com.github.zhanghai.quickjs-java` class. Extensions catch it by name, so
 * the package, class name and both constructors must stay exactly as they were.
 *
 * See [QuickJs] for why the original class is replaced.
 */
class QuickJsException : RuntimeException {

    constructor(message: String) : super(message)

    constructor(message: String, jsStackTrace: String) : super(message) {
        addJavaScriptStack(jsStackTrace)
    }

    private fun addJavaScriptStack(jsStackTrace: String) {
        val frames = parseJavaScriptStack(jsStackTrace)
        if (frames.isNotEmpty()) {
            stackTrace = stackTrace + frames
        }
    }

    private companion object {
        /**
         * Matches the frames QuickJS puts in a JavaScript exception message, in the form
         *
         *     at someFunction (source.js:12)
         *     at someFunction (eval at <anonymous>)
         *
         * Lines that do not match are skipped, so an unexpected format degrades to a plain
         * stack trace instead of masking the cause.
         */
        private val FRAME = Regex("""^\s*at\s+(?:([^\s(]+)\s+)?\((.*)\)$""")

        private fun parseJavaScriptStack(jsStackTrace: String): List<StackTraceElement> =
            jsStackTrace.lineSequence().mapNotNull { line ->
                val match = FRAME.matchEntire(line) ?: return@mapNotNull null
                val location = match.groupValues[2]
                StackTraceElement(
                    match.groupValues[1].ifBlank { null },
                    null,
                    location.substringBefore(':').ifBlank { "<eval>" },
                    location.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 } ?: -1,
                )
            }.toList()
    }
}
