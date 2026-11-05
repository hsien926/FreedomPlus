package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.ktutils.media.KMediaUtils
import com.freegang.ktutils.net.KHttpUtils
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.config.EmojiAnimateFormat
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

        /** HEIF 图像序列的 handler_type，见 [IsoBmffProbe] */
        private const val HANDLER_PICT = "pict"

        private const val SUFFIX_MP4 = "mp4"
        private const val SUFFIX_HEIC = "heic"
        private const val SUFFIX_GIF = "gif"
    }

    /**
     * 一次探测 + 一次改名后的结果，提示文案也从它派生，避免"先降 heic 再解释冲突"两段式互相打架。
     *
     * @param handlerType `moov/mdia/hdlr`，探测失败/非 ISO-BMFF 为 null
     * @param pictureSequence 是否 HEIF 图像序列（`hdlr=pict`）
     * @param renamed 是否实际发生过改名
     * @param renameFailed 需要改名但失败了（文件保持原后缀）
     */
    private data class Resolution(
        val file: File,
        val handlerType: String?,
        val pictureSequence: Boolean,
        val renamed: Boolean,
        val renameFailed: Boolean,
    )

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
            if (resultFile == null) {
                hook.showToast(context, "保存失败!")
                return@singleLaunchIO
            }

            val format = EmojiAnimateFormat.fromLabel(config.emojiAnimateFormat)
            val resolution = resolve(resultFile, format)
            logEmojiResult(urlList, resolution, format)

            val hint = hintFor(resolution, format)
            hook.showToast(context, hint ?: "保存成功!")
            KMediaUtils.notifyMediaUpdate(context, resolution.file.absolutePath)
            if (config.vibrate) hook.vibrate(context, 5L)
        }
    }

    /**
     * 按「用户选项 × 真实容器」一次性算出目标后缀，最多改名一次。
     *
     * - `hdlr=pict`（HEIF 图像序列）且选 MP4：沿用旧版命名策略，存 `.mp4`（不转码，
     *   个别播放器可能只显示首帧，但系统相册/多数播放器可当视频播）；
     * - `hdlr=pict` 且选跟随源 / GIF：降级为 `.heic`；
     * - 其它素材维持 KHttpUtils 嗅探出的真实后缀。
     */
    private fun resolve(file: File, format: EmojiAnimateFormat): Resolution {
        val handlerType = runCatching { IsoBmffProbe.handlerType(file) }.getOrNull()
        val pictureSequence = handlerType == HANDLER_PICT
        if (!pictureSequence) {
            return Resolution(file, handlerType, false, renamed = false, renameFailed = false)
        }

        val targetSuffix = if (format == EmojiAnimateFormat.MP4) SUFFIX_MP4 else SUFFIX_HEIC
        if (file.extension.equals(targetSuffix, ignoreCase = true)) {
            return Resolution(file, handlerType, true, renamed = false, renameFailed = false)
        }

        val target = File(file.parentFile, "${file.nameWithoutExtension}.$targetSuffix")
        val renamed = runCatching { file.renameTo(target) }.getOrDefault(false)
        return if (renamed) {
            XplerLog.d("表情保存: 容器修正 ${file.name} -> ${target.name} (format=${format.label}, handler=$handlerType)")
            Resolution(target, handlerType, true, renamed = true, renameFailed = false)
        } else {
            XplerLog.d("表情保存: 容器修正失败, 保留 ${file.name} (format=${format.label}, handler=$handlerType)")
            Resolution(file, handlerType, true, renamed = false, renameFailed = true)
        }
    }

    /**
     * 用户期望格式与真实容器不一致时的提示（一期不转码，按源保存）。
     * 期望已达成且无需说明时返回 null（调用方回落到"保存成功!"）。
     */
    private fun hintFor(resolution: Resolution, format: EmojiAnimateFormat): String? {
        val suffix = resolution.file.extension.lowercase()
        return when (format) {
            EmojiAnimateFormat.SOURCE -> when {
                !resolution.pictureSequence -> null
                resolution.renameFailed -> "该表情为 HEIF 图像序列, 改名 .$SUFFIX_HEIC 失败, 已保留 .$suffix"
                else -> "该表情为 HEIF 图像序列, 相册可能仅显示首帧"
            }

            EmojiAnimateFormat.GIF -> if (suffix == SUFFIX_GIF) {
                null
            } else {
                "该表情源为 ${sourceLabel(suffix)}, 已按源保存, 暂不支持转 GIF"
            }

            EmojiAnimateFormat.MP4 -> when {
                // 选 MP4 且已经拿到 .mp4：pict 情况给一句兼容性说明，其余视为完全成功
                suffix == SUFFIX_MP4 && resolution.pictureSequence ->
                    "已按 MP4 保存（HEIF 图像序列, 个别播放器可能仅显示首帧）"

                suffix == SUFFIX_MP4 -> null
                resolution.renameFailed -> "该表情为 HEIF 图像序列, 改名 .$SUFFIX_MP4 失败, 已保留 .$suffix"
                else -> "该表情源为 ${sourceLabel(suffix)}, 已按源保存, 暂不支持转 MP4"
            }
        }
    }

    private fun sourceLabel(suffix: String): String = when (suffix) {
        "gif" -> "GIF 动图"
        "webp" -> "WebP 动图"
        "mp4" -> "HEVC 视频"
        "heic", "heif" -> "HEIF 图像序列"
        else -> suffix.uppercase().ifEmpty { "未知格式" }
    }

    /**
     * 打印表情落盘的真实情况, 用于定位"动图存成静态首帧"类问题:
     * 需要同时看到源 URL 形态(是否被 CDN 模板改写)、文件头真实容器类型与 ISO-BMFF handler。
     * 只打印 host + path, 不打印 query, 避免把 x-signature/x-expires 写进日志。
     */
    private fun logEmojiResult(
        urlList: List<String>,
        resolution: Resolution,
        format: EmojiAnimateFormat,
    ) {
        val file = resolution.file
        val urls = urlList.joinToString(" | ") { it.logSafeUrl() }
        val head = file.headHex()
        val animatedWebp = file.hasWebpAnimation()
        XplerLog.d(
            "表情保存: urls=[$urls]",
            "表情保存: file=${file.name} size=${file.length()} head=$head" +
                    " animatedWebp=$animatedWebp handler=${resolution.handlerType ?: "-"}" +
                    " format=${format.label} renamed=${resolution.renamed}",
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
