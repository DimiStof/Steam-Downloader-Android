package com.example.steamfetch

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.future.await
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CompletionStage
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * JavaSteam's newer versions changed some method names and whether calls are blocking, suspend,
 * or return futures. These helpers call such members by name, whichever style they use, and
 * produce a readable list of what actually exists when a name is missing.
 */
object Reflect {

    private fun isNoArgOrSuspendOnly(m: Method): Boolean {
        val p = m.parameterTypes
        return p.isEmpty() || (p.size == 1 && p[0] == Continuation::class.java)
    }

    /** First public method with one of [names] that takes no arguments (suspend allowed). */
    fun find(target: Any, vararg names: String): Method? =
        names.firstNotNullOfOrNull { n ->
            target.javaClass.methods.firstOrNull { it.name == n && isNoArgOrSuspendOnly(it) }
        }

    /** Calls [m], awaiting suspend functions, futures and Deferreds, and returns the plain result. */
    suspend fun invokeAwait(target: Any, m: Method): Any? {
        val raw: Any? = try {
            if (m.parameterTypes.isNotEmpty()) {
                suspendCoroutineUninterceptedOrReturn<Any?> { cont -> m.invoke(target, cont) }
            } else {
                m.invoke(target)
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        return when (raw) {
            is CompletionStage<*> -> raw.await()
            is Deferred<*> -> raw.await()
            else -> raw
        }
    }

    /** Reads a Kotlin/Java property by name through its getter. */
    fun prop(target: Any, name: String): Any? {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val m = target.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?: error("No '$name' on ${target.javaClass.name}. Available: ${members(target)}")
        return m.invoke(target)
    }

    fun members(target: Any): String =
        target.javaClass.methods
            .filter { it.declaringClass != Any::class.java }
            .map { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }
            .distinct()
            .sorted()
            .joinToString(", ")
}
