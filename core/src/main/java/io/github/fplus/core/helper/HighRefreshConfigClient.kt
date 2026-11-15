// Kotlin · HighRefreshConfigClient.kt · Android API 24+ / ContentProvider IPC · JDK 17
package io.github.fplus.core.helper

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object HighRefreshConfigClient {
    const val schema_version = 1
    const val method_get = "get_state"
    const val method_migrate = "migrate_host_flag"
    const val method_set = "set_enabled"
    const val key_schema = "schema"
    const val key_initialized = "initialized"
    const val key_enabled = "enabled"
    const val key_revision = "revision"
    val state_uri: Uri = Uri.parse("content://io.github.fplus.highrefresh/state")
    private val failure_reported = AtomicBoolean(false)

    fun read_enabled(context: Context?, legacy_enabled: Boolean): Boolean? {
        if (context == null || context.packageName !in setOf(
                HighRefreshSyncPolicy.host_package, HighRefreshSyncPolicy.module_package,
            )) return null
        return runCatching {
            val host = context.packageName == HighRefreshSyncPolicy.host_package
            val response = context.contentResolver.call(
                state_uri,
                if (host) method_migrate else method_get,
                null,
                if (host) Bundle().apply { putBoolean(key_enabled, legacy_enabled) } else null,
            )
            val state = decode(response) ?: error("共享开关响应格式不匹配")
            failure_reported.set(false)
            if (state.initialized) state.enabled else legacy_enabled
        }.onFailure { report_failure(it) }.getOrNull()
    }

    fun write_enabled(context: Context?, enabled: Boolean): Boolean {
        if (context == null || context.packageName !in setOf(
                HighRefreshSyncPolicy.host_package, HighRefreshSyncPolicy.module_package,
            )) return false
        return runCatching {
            val response = context.contentResolver.call(
                state_uri, method_set, null,
                Bundle().apply { putBoolean(key_enabled, enabled) },
            )
            val state = decode(response) ?: error("共享开关写入响应格式不匹配")
            check(state.initialized && state.enabled == enabled) { "共享开关写入未确认" }
            failure_reported.set(false)
            true
        }.onFailure { report_failure(it) }.getOrDefault(false)
    }

    fun read_state(context: Context): HighRefreshSharedState? = runCatching {
        val state = decode(context.contentResolver.call(state_uri, method_get, null, null))
        check(state != null) { "共享开关响应格式不匹配" }
        failure_reported.set(false)
        state
    }.onFailure { report_failure(it) }.getOrNull()

    @Suppress("DEPRECATION")
    fun decode(bundle: Bundle?): HighRefreshSharedState? {
        if (bundle == null || bundle.get(key_schema) != schema_version) return null
        val initialized = bundle.get(key_initialized) as? Boolean ?: return null
        val enabled = bundle.get(key_enabled) as? Boolean ?: return null
        val revision = bundle.get(key_revision) as? Long ?: return null
        if (revision < 0) return null
        return HighRefreshSharedState(initialized, enabled, revision)
    }

    private fun report_failure(error: Throwable) {
        if (failure_reported.compareAndSet(false, true)) {
            Log.w("Freedom+RefreshSync", "共享高刷开关暂不可读写，保留本地设置：${error.message}")
        }
    }
}
