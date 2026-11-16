// Kotlin · HighRefreshSyncPolicy.kt · JVM 11 / Android API 24+ · JDK 17
package io.github.fplus.core.helper

internal data class HighRefreshSharedState(
    val initialized: Boolean = false,
    val enabled: Boolean = false,
    val revision: Long = 0L,
)

internal object HighRefreshSyncPolicy {
    const val module_package = "io.github.fplus"
    const val host_package = "com.ss.android.ugc.aweme"
    const val powerkeeper_package = "com.miui.powerkeeper"

    fun can_read(caller_uid: Int, owner_uid: Int, packages: Set<String>): Boolean =
        caller_uid == owner_uid || host_package in packages || powerkeeper_package in packages

    fun can_write(caller_uid: Int, owner_uid: Int, packages: Set<String>): Boolean =
        caller_uid == owner_uid || host_package in packages

    fun migrate(current: HighRefreshSharedState, legacy_enabled: Boolean): HighRefreshSharedState =
        if (current.initialized) current else update(current, legacy_enabled)

    fun update(current: HighRefreshSharedState, enabled: Boolean): HighRefreshSharedState {
        if (current.initialized && current.enabled == enabled) return current
        require(current.revision in 0 until Long.MAX_VALUE) { "共享开关修订号无效" }
        return HighRefreshSharedState(true, enabled, current.revision + 1)
    }
}
