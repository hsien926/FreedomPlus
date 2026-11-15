// Kotlin · HighRefreshProvider.kt · Android API 24+ / 受控 ContentProvider · JDK 17
package io.github.fplus.core.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import io.github.fplus.core.helper.HighRefreshConfigClient
import io.github.fplus.core.helper.HighRefreshSharedState
import io.github.fplus.core.helper.HighRefreshSyncPolicy

class HighRefreshProvider : ContentProvider() {
    private val state_lock = Any()
    private lateinit var preferences: SharedPreferences

    override fun onCreate(): Boolean {
        val app_context = context?.applicationContext ?: return false
        preferences = app_context.getSharedPreferences("high_refresh_shared_v1", 0)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforce_access(method != HighRefreshConfigClient.method_get)
        var changed = false
        val result = synchronized(state_lock) {
            val current = read_state()
            val next = when (method) {
                HighRefreshConfigClient.method_get -> current
                HighRefreshConfigClient.method_migrate ->
                    HighRefreshSyncPolicy.migrate(current, enabled_argument(extras))
                HighRefreshConfigClient.method_set ->
                    HighRefreshSyncPolicy.update(current, enabled_argument(extras))
                else -> throw IllegalArgumentException("未知高刷配置操作")
            }
            if (next != current) {
                check(preferences.edit()
                    .putBoolean(HighRefreshConfigClient.key_initialized, next.initialized)
                    .putBoolean(HighRefreshConfigClient.key_enabled, next.enabled)
                    .putLong(HighRefreshConfigClient.key_revision, next.revision)
                    .commit()) { "共享高刷开关保存失败" }
                changed = true
            }
            encode(next)
        }
        if (changed) {
            context?.contentResolver?.notifyChange(HighRefreshConfigClient.state_uri, null, false)
        }
        return result
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require_state_uri(uri)
        enforce_access(false)
        val state = synchronized(state_lock) { read_state() }
        val cursor = MatrixCursor(arrayOf("initialized", "enabled", "revision"), 1)
        cursor.addRow(arrayOf(if (state.initialized) 1 else 0, if (state.enabled) 1 else 0, state.revision))
        context?.let { cursor.setNotificationUri(it.contentResolver, HighRefreshConfigClient.state_uri) }
        return cursor
    }

    override fun getType(uri: Uri): String {
        require_state_uri(uri)
        return "vnd.android.cursor.item/vnd.io.github.fplus.highrefresh"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("请通过高刷配置专用call接口写入")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("共享高刷开关不支持删除")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("请通过高刷配置专用call接口写入")

    private fun read_state() = HighRefreshSharedState(
        preferences.getBoolean(HighRefreshConfigClient.key_initialized, false),
        preferences.getBoolean(HighRefreshConfigClient.key_enabled, false),
        preferences.getLong(HighRefreshConfigClient.key_revision, 0L),
    )

    private fun encode(state: HighRefreshSharedState) = Bundle().apply {
        putInt(HighRefreshConfigClient.key_schema, HighRefreshConfigClient.schema_version)
        putBoolean(HighRefreshConfigClient.key_initialized, state.initialized)
        putBoolean(HighRefreshConfigClient.key_enabled, state.enabled)
        putLong(HighRefreshConfigClient.key_revision, state.revision)
    }

    @Suppress("DEPRECATION")
    private fun enabled_argument(extras: Bundle?): Boolean {
        val value = extras?.get(HighRefreshConfigClient.key_enabled)
        require(value is Boolean) { "高刷开关必须是布尔值" }
        return value
    }

    private fun enforce_access(write: Boolean) {
        val app_context = context ?: throw IllegalStateException("高刷配置提供方尚未就绪")
        val caller_uid = Binder.getCallingUid()
        val packages = app_context.packageManager.getPackagesForUid(caller_uid)?.toSet().orEmpty()
        val allowed = if (write) {
            HighRefreshSyncPolicy.can_write(caller_uid, Process.myUid(), packages)
        } else {
            HighRefreshSyncPolicy.can_read(caller_uid, Process.myUid(), packages)
        }
        if (!allowed) throw SecurityException("调用方不能访问共享高刷开关")
    }

    private fun require_state_uri(uri: Uri) {
        require(uri == HighRefreshConfigClient.state_uri) { "未知高刷配置地址" }
    }
}
