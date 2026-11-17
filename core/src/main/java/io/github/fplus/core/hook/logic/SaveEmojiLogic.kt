// Kotlin · SaveEmojiLogic.kt · Android API 24+ · 按源偏好选择与真实媒体格式保存
package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.ktutils.io.FileKind
import com.freegang.ktutils.io.KFileUtils
import com.freegang.ktutils.media.KMediaUtils
import com.freegang.ktutils.net.KHttpUtils
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.config.EmojiAnimateFormat
import io.github.xpler.core.XplerLog
import java.io.File

class SaveEmojiLogic(
    private val hook: BaseHook,
    private val context: Context,
    private val urlList: List<String>,
) {
    companion object {
        private val config get() = ConfigV1.get()
    }

    private data class Resolution(val file: File, val kind: FileKind?, val handlerType: String?)

    init {
        runCatching {
            val format = EmojiAnimateFormat.fromLabel(config.emojiAnimateFormat)
            val url = EmojiMediaSelection.selectUrl(urlList, format)
            if (url == null) {
                hook.showToast(context, "未获取到表情内容")
            } else onSaveEmoji(url, format)
        }.onFailure { XplerLog.e(it) }
    }

    private fun onSaveEmoji(url: String, format: EmojiAnimateFormat) {
        hook.singleLaunchIO(url) {
            val parentPath = ConfigV1.getFreedomDir(context).child("emoji").need()
            hook.showToast(context, "保存表情, 请稍后..")
            // URL 后缀只用于挑选候选源；未知内容保持 .bin，绝不根据 URL 伪装为图片或视频。
            val resultFile = KHttpUtils.download(
                sourceUrl = url,
                file = File(parentPath, "${System.currentTimeMillis()}.bin"),
            )
            if (resultFile == null) {
                hook.showToast(context, "保存失败!")
                return@singleLaunchIO
            }
            val resolution = Resolution(resultFile, KFileUtils.getFileKind(resultFile), IsoBmffProbe.handlerType(resultFile))
            logEmojiResult(resolution, format)
            hook.showToast(context, EmojiMediaSelection.hint(resolution.kind, resolution.handlerType, format) ?: "保存成功!")
            KMediaUtils.notifyMediaUpdate(context, resolution.file.absolutePath)
            if (config.vibrate) hook.vibrate(context, 5L)
        }
    }

    private fun logEmojiResult(resolution: Resolution, format: EmojiAnimateFormat) {
        val file = resolution.file
        val urls = urlList.joinToString(" | ") { it.logSafeUrl() }
        XplerLog.d(
            "表情保存: urls=[$urls]",
            "表情保存: file=${file.name} size=${file.length()} head=${file.headHex()}" +
                " kind=${resolution.kind?.name ?: "未知"} handler=${resolution.handlerType ?: "未知"}" +
                " format=${format.label}",
        )
    }
}

private fun String.logSafeUrl(): String = runCatching {
    val uri = java.net.URI(this)
    "${uri.host}${uri.path}"
}.getOrDefault("地址不可解析")

private fun File.headHex(limit: Int = 16): String = runCatching {
    inputStream().use { input ->
        val bytes = com.freegang.ktutils.net.DownloadIntegrity.readPrefix(input, limit)
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}.getOrDefault("")
