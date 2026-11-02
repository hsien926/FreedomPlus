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
        val url = urlList.first()
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
                hook.showToast(context, "保存成功!")
                KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)
                if (config.vibrate) hook.vibrate(context, 5L)
            } else {
                hook.showToast(context, "保存失败!")
            }
        }
    }

    /**
     * 从 URL 路径推测文件后缀, 仅作为落盘占位名.
     * 真实类型仍由 KHttpUtils.download 读文件头决定, 这里只是让嗅探失败时也能有个合理后缀.
     */
    private fun String.guessEmojiSuffix(): String {
        // 只取路径最后一段的扩展名, 避免把域名里的 ".com/obj/xxx" 当成后缀
        val name = substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gif", "webp", "png", "jpg", "jpeg", "heic" -> ".$ext"
            else -> ".webp"
        }
    }
}