package app.cash.quickjs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.lang.reflect.Modifier

/**
 * Guards the binary contract between the app and extensions.
 *
 * Extensions are compiled against `com.github.zhanghai.quickjs-java:quickjs-android`'s
 * `app.cash.quickjs.QuickJs` and resolve it from the app at runtime (see [QuickJs]). The
 * replacement in this module therefore has to expose the same public members, or every
 * extension that evaluates JavaScript dies with `NoSuchMethodError`. Extensions are not
 * rebuilt with the app, so nothing at compile time would catch that.
 */
class QuickJsAbiTest {

    @Test
    fun `matches the class extensions were compiled against`() {
        val actual = QuickJs::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.toAbiSignature() }
            .toSet()

        val expected = setOf(
            "create()",
            "evaluate(String)",
            "evaluate(String,String)",
            "execute(byte[])",
            "compile(String,String)",
            "get(String,Class)",
            "set(String,Class,Object)",
            "close()",
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `keeps the class shape extensions were compiled against`() {
        val type = QuickJs::class.java

        assertTrue(Closeable::class.java.isAssignableFrom(type), "must implement java.io.Closeable")
        assertTrue(Modifier.isFinal(type.modifiers), "the original class is final")
        assertEquals(
            "app.cash.quickjs.QuickJs",
            type.name,
            "the original package and name must be preserved or extensions cannot link",
        )
    }

    @Test
    fun `keeps the exception type extensions catch`() {
        val type = QuickJsException::class.java

        assertEquals("app.cash.quickjs.QuickJsException", type.name)
        assertEquals(RuntimeException::class.java, type.superclass)
        assertEquals(
            setOf("(String)", "(String,String)"),
            type.declaredConstructors.map { it.toAbiSignature() }.toSet(),
        )
    }

    private fun java.lang.reflect.Method.toAbiSignature(): String =
        "$name(${parameterTypes.joinToString(",") { it.abiName() }})"

    private fun <T : java.lang.reflect.Constructor<*>> T.toAbiSignature(): String =
        "(${parameterTypes.joinToString(",") { it.abiName() }})"

    private fun Class<*>.abiName(): String = when {
        isArray -> "${componentType.abiName()}[]"
        this == String::class.java -> "String"
        this == Class::class.java -> "Class"
        this == Any::class.java -> "Object"
        else -> simpleName
    }
}
