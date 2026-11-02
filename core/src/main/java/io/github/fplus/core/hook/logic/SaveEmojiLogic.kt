package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.ktutils.media.KMediaUtils
import com.freegang.ktutils.net.KHttpUtils
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import java.io.File

// 保存表情逻辑
class SaveEmojiLogic(
    private val hook: BaseHook,
    private val context: Context,
    private val urlList: List<String>,
) {
    companion object {
        private val config get() = ConfigV1.get()
    }

    init {
        runCatching {
            if (urlList.isEmpty()) {
                hook.showToast(context, "未获取到表情内容")
                return@runCatching
            }
            onSaveEmoji(urlList)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun onSaveEmoji(urlList: List<String>) {
        // 同一个表情可能下发多个地址(HEVC/HEIF 变体与 GIF/WebP 直链混在一起),
        // 优先挑路径上写着 gif / webp 的那条; 都没有时退回第一条, 与旧行为一致.
        val url = urlList.firstOrNull { it.knownEmojiExtension() == ".gif" }
            ?: urlList.firstOrNull { it.knownEmojiExtension() == ".webp" }
            ?: urlList.first()
        hook.singleLaunchIO(url) {
            // 默认保存路径: `/外置存储器/Download/Freedom/emoji`
            val parentPath = ConfigV1.getFreedomDir(context).child("emoji").need()

            // 构建保存文件名: 后缀只作为占位, KHttpUtils.download 会按文件头嗅探并改写为真实类型.
            // 不能写死 .webp —— 动图是 GIF, 写死后相册/微信按静态 WebP 解码, 只剩首帧.
            hook.showToast(context, "保存表情, 请稍后..")
            val resultFile = KHttpUtils.download(
                sourceUrl = url,
                file = File(parentPath, "${System.currentTimeMillis()}${url.guessEmojiSuffix()}"),
            )
            if (resultFile != null) {
                logEmojiResult(urlList, resultFile)
                hook.showToast(context, "保存成功!")
                KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)
                if (config.vibrate) hook.vibrate(context, 5L)
            } else {
                hook.showToast(context, "保存失败!")
            }
        }
    }

    /**
     * 打印表情落盘的真实情况, 用于定位"动图存成静态首帧"类问题:
     * 需要同时看到源 URL 形态(是否被 CDN 模板改写)与文件头真实容器类型.
     * 只打印 host + path, 不打印 query, 避免把 x-signature/x-expires 写进日志。
     */
    private fun logEmojiResult(urlList: List<String>, file: File) {
        val urls = urlList.joinToString(" | ") { it.logSafeUrl() }
        val head = file.headHex()
        val animatedWebp = file.hasWebpAnimation()
        XplerLog.d(
            "表情保存: urls=[$urls]",
            "表情保存: file=${file.name} size=${file.length()} head=$head animatedWebp=$animatedWebp",
        )
    }

    /**
     * 从 URL 路径里识别图片扩展名(带点), 识别不出返回 null.
     * 只取路径最后一段, 避免把域名里的 ".com/obj/xxx" 当成后缀.
     */
    private fun String.knownEmojiExtension(): String? {
        val name = substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gif", "webp", "png", "jpg", "jpeg", "heic", "mp4" -> ".$ext"
            else -> null
        }
    }

    /**
     * 落盘占位后缀. 真实类型仍由 KHttpUtils.download 读文件头决定,
     * 这里只是让嗅探失败时也能有个合理后缀.
     */
    private fun String.guessEmojiSuffix(): String = knownEmojiExtension() ?: ".webp"
}

/** 日志用: 只保留 host + path, 去掉带签名的 query */
private fun String.logSafeUrl(): String = runCatching {
    val uri = java.net.URI(this)
    "${uri.host}${uri.path}"
}.getOrDefault("<unparsable-url>")

/** 读取文件头十六进制, 用于确认落盘的真实容器类型 */
private fun File.headHex(limit: Int = 16): String = runCatching {
    inputStream().use { input ->
        val buffer = ByteArray(limit)
        val count = input.read(buffer)
        if (count <= 0) "" else buffer.copyOf(count).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}.getOrDefault("")

/** 粗略判断 WebP 里是否存在动画块(ANIM/ANMF), 用于区分"动图 webp"与"静态首帧 webp" */
private fun File.hasWebpAnimation(): Boolean = runCatching {
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        val count = input.read(buffer)
        if (count <= 0) return@use false
        val text = String(buffer, 0, count, Charsets.ISO_8859_1)
        text.contains("ANIM") || text.contains("ANMF")
    }
}.getOrDefault(false)