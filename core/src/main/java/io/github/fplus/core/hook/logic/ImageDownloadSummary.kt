// Kotlin · ImageDownloadSummary.kt · JVM 11 / Android 7+ · 图文下载完整性统计
package io.github.fplus.core.hook.logic

import java.io.File

data class ImageDownloadResult(
    val image_file: File?,
    val video_required: Boolean = false,
    val video_file: File? = null,
) {
    val files: List<File> get() = listOfNotNull(image_file, video_file).distinctBy { it.absolutePath }
    val complete: Boolean get() = image_file != null && (!video_required || video_file != null)
    val partial: Boolean get() = files.isNotEmpty() && !complete
}

class ImageDownloadSummary(results: List<ImageDownloadResult>) {
    private val items = results.toList()
    val files: List<File> = items.flatMap { it.files }.distinctBy { it.absolutePath }
    val complete_count = items.count { it.complete }
    val partial_count = items.count { it.partial }
    val failed_count = items.count { it.files.isEmpty() }
    val missing_images = items.count { it.image_file == null }
    val missing_videos = items.count { it.video_required && it.video_file == null }
    val complete: Boolean = items.isNotEmpty() && complete_count == items.size

    fun message(): String = when {
        complete -> "下载成功, 共${files.size}个文件!"
        files.isEmpty() -> "下载失败, 缺少图片${missing_images}、视频${missing_videos}!"
        else -> "下载部分完成: 完整${complete_count}、部分${partial_count}、失败${failed_count}, " +
            "共${files.size}个文件, 缺少图片${missing_images}、视频${missing_videos}!"
    }
}

object DownloadProgress {
    fun percent(real: Long, total: Long): Int = if (total <= 0L) 0 else
        (real.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0).toInt()
}
