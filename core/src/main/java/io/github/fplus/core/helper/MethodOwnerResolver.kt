// Kotlin · MethodOwnerResolver.kt · JVM 11 / Android 调用层 · 查找继承方法的实际声明类
package io.github.fplus.core.helper

internal fun findMethodDeclaringClass(
    target: Class<*>?,
    name: String,
    vararg parameter_types: Class<*>,
): Class<*>? {
    var owner = target
    while (owner != null) {
        val current = owner
        if (runCatching { current.getDeclaredMethod(name, *parameter_types) }.isSuccess) return current
        owner = current.superclass
    }
    return null
}
