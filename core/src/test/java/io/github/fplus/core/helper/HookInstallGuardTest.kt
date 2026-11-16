// Kotlin · HookInstallGuardTest.kt · JUnit 4 / JVM 11 · 可选 Hook 失败隔离回归
package io.github.fplus.core.helper

import org.junit.Assert.*
import org.junit.Test

class HookInstallGuardTest {
    @Test fun missing_optional_class_does_not_stop_other_registrations() {
        val installed = mutableListOf<String>(); val failed = mutableListOf<String>()
        val on_failure: (String, Throwable) -> Unit = { name, _ -> failed.add(name) }
        HookInstallGuard.install("页码", on_failure) { throw NoClassDefFoundError("missing") }
        HookInstallGuard.install("图片", on_failure) { installed.add("图片") }
        HookInstallGuard.install("按钮", on_failure) { installed.add("按钮") }
        assertEquals(listOf("页码"), failed); assertEquals(listOf("图片", "按钮"), installed)
    }
    @Test fun missing_method_is_reported_without_escaping() {
        var failed: Throwable? = null
        HookInstallGuard.install("页码", { _, error -> failed = error }) { throw NoSuchMethodException("changed") }
        assertTrue(failed is NoSuchMethodException)
    }
    @Test(expected = OutOfMemoryError::class) fun fatal_vm_errors_are_not_swallowed() {
        HookInstallGuard.install("页码", { _, _ -> fail("不应吞掉虚拟机错误") }) { throw OutOfMemoryError("test") }
    }
}
