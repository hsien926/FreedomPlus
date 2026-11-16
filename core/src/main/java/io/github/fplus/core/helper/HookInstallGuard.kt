// Kotlin · HookInstallGuard.kt · JVM 11 / Android 7+ · 独立 Hook 注册保护
package io.github.fplus.core.helper

object HookInstallGuard {
    fun install(name: String, on_failure: (String, Throwable) -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            on_failure(name, error)
        }
    }
}
