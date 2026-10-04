package io.github.fplus.core.config

import android.content.Context
import com.freegang.extension.child
import io.github.fplus.ProjectInfo
import java.io.File

/**
 * 版本信息。
 *
 * 说明：内置的远程版本检查（OTA）已整体下线。
 * 本对象不再持有任何 GitHub API 地址，也不再发起任何网络请求，
 * 只负责维护一份随包分发的本地版本适配列表。
 *
 * 版本适配列表的取用顺序：
 * 1. 配置目录下的缓存副本（首次由 assets 释放）
 * 2. 直接读 assets 内的原始副本
 */
object Version {

    private fun configFile(context: Context): File {
        return context.filesDir
            .child("fplus")
            .child(ProjectInfo.VERSIONS_ASSET)
    }

    /**
     * 从包内释放版本适配列表到配置目录（仅在缓存不存在或为空时写入）。
     */
    fun ensureLocalVersions(context: Context) {
        runCatching {
            val cache = configFile(context)
            if (cache.exists() && cache.length() > 0L) return

            val text = context.assets.open(ProjectInfo.VERSIONS_ASSET).use {
                it.readBytes().decodeToString()
            }
            if (text.isBlank()) return

            cache.parentFile?.mkdirs()
            cache.writeText(text)
        }
    }

    /**
     * 读取版本适配列表。仅读本地，不联网。
     */
    fun getVersions(context: Context): String? {
        return runCatching {
            val cache = configFile(context)
            if (cache.exists() && cache.length() > 0L) {
                return cache.readText()
            }

            val text = context.assets.open(ProjectInfo.VERSIONS_ASSET).use {
                it.readBytes().decodeToString()
            }
            if (text.isBlank()) null else text
        }.getOrNull()
    }
}
