// Kotlin · EmojiMediaSelection.kt · JVM / Android API 24+ · 表情源选择与真实格式提示
package io.github.fplus.core.hook.logic

import com.freegang.ktutils.io.FileKind
import io.github.fplus.core.config.EmojiAnimateFormat

object EmojiMediaSelection {
    fun selectUrl(urls: List<String>, format: EmojiAnimateFormat): String? {
        val candidates = urls.filter { it.isNotBlank() }
        val preferred = when (format) {
            EmojiAnimateFormat.SOURCE -> null
            EmojiAnimateFormat.GIF -> "gif"
            EmojiAnimateFormat.MP4 -> "mp4"
        }
        return preferred?.let { suffix -> candidates.firstOrNull { knownExtension(it) == suffix } }
            ?: candidates.firstOrNull()
    }

    fun knownExtension(url: String): String? {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val suffix = name.substringAfterLast('.', "").lowercase()
        return suffix.takeIf { it in setOf("gif", "webp", "png", "jpg", "jpeg", "heic", "heif", "mp4", "m4a") }
    }

    /** 扩展名由完整文件识别得来；期望格式只影响挑源，绝不把图片序列伪装成视频。 */
    fun hint(kind: FileKind?, handlerType: String?, format: EmojiAnimateFormat): String? {
        if (kind == null) return "内容格式未能确认，已保存原始文件（.bin），未进行转码"
        val pictureSequence = kind == FileKind.HEIC && handlerType == "pict"
        val targetMet = when (format) {
            EmojiAnimateFormat.SOURCE -> true
            EmojiAnimateFormat.GIF -> kind == FileKind.GIF
            EmojiAnimateFormat.MP4 -> kind == FileKind.MP4
        }
        if (targetMet) {
            return if (pictureSequence) "已按源保存为 HEIF 图像序列（.heic），相册可能仅显示首帧" else null
        }
        val source = when (kind) {
            FileKind.GIF -> "GIF 图片"
            FileKind.WEBP -> "WebP 图片"
            FileKind.MP4 -> "MP4 视频"
            FileKind.M4A -> "MP4 音频"
            FileKind.HEIC -> if (pictureSequence) "HEIF 图像序列" else "HEIF 图片"
            FileKind.JPG -> "JPEG 图片"
            FileKind.PNG -> "PNG 图片"
        }
        return "源为 $source，已按真实格式保存（${kind.suffix}），暂不支持转 ${format.label}"
    }
}
