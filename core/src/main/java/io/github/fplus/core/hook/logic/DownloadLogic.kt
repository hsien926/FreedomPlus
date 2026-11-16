// Kotlin · DownloadLogic.kt · Android 7+ / Xposed · 视频图文音乐下载
package io.github.fplus.core.hook.logic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.extension.pureFileName
import com.freegang.extension.pureName
import com.freegang.extension.secureFilename
import com.freegang.ktutils.app.IProgressNotification
import com.freegang.ktutils.app.KNotifiUtils
import com.freegang.ktutils.app.KToastUtils
import com.freegang.ktutils.media.KMediaUtils
import com.freegang.ktutils.net.KHttpUtils
import com.freegang.ktutils.text.KTextUtils
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.ugc.aweme.ImageUrlStruct
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.CommentMediaResolver
import io.github.webdav.WebDav
import io.github.xpler.core.XplerLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/// 下载(视频/图文/音乐)逻辑
class DownloadLogic(
    val hook: BaseHook,
    val context: Context,
    val aweme: Aweme?,
) {

    companion object {
        private val config get() = ConfigV1.get()
        private val webdav: WebDav get() = WebDav(config.webDavConfig)
        private var downloadNotifyId = 1
    }

    private lateinit var mShortId: String
    private lateinit var mPureNickname: String
    private lateinit var mOwnerDir: String

    private lateinit var mVideoParent: File
    private lateinit var mMusicParent: File
    private lateinit var mImageParent: File
    private lateinit var mPureFileName: String

    init {
        runCatching {
            if (aweme != null) {
                // 整理内容
                // 如果uniqueId为空, shortId为账号
                mShortId = if (aweme.author.uniqueId.isNullOrEmpty()) {
                    aweme.author.shortId
                } else {
                    aweme.author.uniqueId
                }
                mPureNickname = aweme.author.nickname.pureFileName

                // mOwnerDir: 如果需要按视频创作者单独创建文件夹: `/外置存储器/Download/Freedom/${video|music|picture}/昵称(账号)`
                mOwnerDir = if (config.ownerDir) "${mPureNickname}(${mShortId})" else ""

                // 默认下载路径: `/外置存储器/Download/Freedom/video`
                mVideoParent = ConfigV1.getFreedomDir(context).child("video")

                // 默认下载路径: `/外置存储器/Download/Freedom/music`
                mMusicParent = ConfigV1.getFreedomDir(context).child("music")

                // 默认下载路径: `/外置存储器/Download/Freedom/picture`
                mImageParent = ConfigV1.getFreedomDir(context).child("picture")

                // 构建文件名
                mPureFileName = if (aweme.desc.isNullOrBlank()) {
                    "${mPureNickname}_${mShortId}_${System.currentTimeMillis() / 1000}"
                } else {
                    "${mPureNickname}_${mShortId}_${aweme.desc.pureFileName}"
                }
                showChoiceDialog(aweme)
            } else {
                hook.showToast(context, "未获取到基本信息")
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 显示下载选择弹层
     * @param aweme
     */
    private fun showChoiceDialog(aweme: Aweme) {
        val urlList = aweme.getImageUrlList()
        val items = mutableListOf("文案", if (urlList.isEmpty()) "视频" else "图片", "背景音乐")
        if (config.isWebDav) {
            items.add(if (urlList.isEmpty()) "视频(WebDav)" else "图片(WebDav)")
            items.add("背景音乐(WebDav)")
        }
        hook.showInputChoiceDialog(
            context = context,
            title = "Freedom+",
            showInput1 = config.ownerDir,
            input1Hint = "创作者: $mOwnerDir",
            input1DefaultValue = mOwnerDir,
            input2Hint = "文件名: $mPureFileName",
            input2DefaultValue = mPureFileName,
            items = items.toTypedArray(),
            onChoice = { _, owner, filename, item, _ ->
                // 如果有重命名
                if (owner.isNotBlank()) mOwnerDir = owner
                mVideoParent = mVideoParent.child(mOwnerDir.pureFileName)
                mMusicParent = mMusicParent.child(mOwnerDir.pureFileName)
                mImageParent = mImageParent.child(mOwnerDir.pureFileName)
                if (filename.isNotBlank()) {
                    val tempFile = File(filename)
                    mVideoParent = mVideoParent.child(tempFile.parentFile?.path ?: "")
                    mMusicParent = mMusicParent.child(tempFile.parentFile?.path ?: "")
                    mImageParent = mImageParent.child(tempFile.parentFile?.path ?: "")
                    mPureFileName = tempFile.pureName
                }
                when (item) {
                    "文案" -> copyDesc(aweme)
                    "视频" -> downloadVideo(aweme)
                    "图片" -> downloadImages(aweme)
                    "背景音乐" -> downloadMusic(aweme)
                    "视频(WebDav)" -> downloadVideo(aweme, true)
                    "图片(WebDav)" -> downloadImages(aweme, true)
                    "背景音乐(WebDav)" -> downloadMusic(aweme, true)
                }
            }
        )
    }

    /**
     * 复制文案
     * @param aweme
     */
    private fun copyDesc(aweme: Aweme) {
        if (KTextUtils.isEmpty(aweme.desc)) {
            KToastUtils.show(context, "文案为空或获取失败")
            return
        }
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("视频文案", aweme.desc))
        KToastUtils.show(context, "文案复制成功")
    }

    /**
     * 下载视频
     * @param aweme
     */
    private fun downloadVideo(aweme: Aweme, isWebDav: Boolean = false) {
        val videoUrlList = when (config.videoCoding) {
            "H265" -> aweme.getH265VideoUrlList()
            "H264" -> aweme.getH264VideoUrlList()
            "Auto" -> aweme.getAutoVideoUrlList()
            else -> emptyList()
        }

        if (videoUrlList.isEmpty()) {
            hook.showToast(context, "未获取到视频信息")
            return
        }
        // 构建视频文件名
        mPureFileName = mPureFileName.secureFilename(".mp4")
        if (config.notificationDownload) {
            showDownloadByNotification(videoUrlList, mVideoParent, mPureFileName, isWebDav)
        } else {
            showDownloadByDialog(videoUrlList, mVideoParent, mPureFileName, isWebDav)
        }
    }

    /**
     * 下载背景音乐
     * @param aweme
     */
    private fun downloadMusic(aweme: Aweme, isWebDav: Boolean = false) {
        val musicUrlList = aweme.getMusicUrlList()
        if (musicUrlList.isEmpty()) {
            hook.showToast(context, "未获取到背景音乐")
            return
        }
        // 构建视频文件名
        mPureFileName = mPureFileName.secureFilename(".mp3")
        if (config.notificationDownload) {
            showDownloadByNotification(musicUrlList, mMusicParent, mPureFileName, isWebDav)
        } else {
            showDownloadByDialog(musicUrlList, mMusicParent, mPureFileName, isWebDav)
        }
    }

    /**
     * 下载图片
     * @param aweme
     */
    private fun downloadImages(aweme: Aweme, isWebDav: Boolean = false) {
        val struct_list = aweme.getImageUrlList().toList()
        if (struct_list.isEmpty()) {
            hook.showToast(context, "未获取到图片信息")
            return
        }
        if (config.notificationDownload) {
            hook.showDownloadNotification(
                context = context,
                notifyId = downloadNotifyId++,
                title = mPureFileName,
                listener = { notify ->
                    hook.singleLaunchIO(mPureFileName) {
                        download_image_files(struct_list, notify, isWebDav)
                    }
                },
            )
        } else {
            hook.showProgressDialog(
                context = context,
                title = "Freedom+",
                listener = { dismiss, notify ->
                    hook.singleLaunchIO(mPureFileName) {
                        try {
                            download_image_files(struct_list, notify, isWebDav)
                        } finally {
                            hook.refresh { dismiss() }
                        }
                    }
                },
            )
        }
    }

    private suspend fun download_image_files(
        structs: List<ImageUrlStruct>,
        notify: IProgressNotification,
        is_webdav: Boolean,
    ) {
        val save_video = config.isLivePhotoVideo
        val results = structs.mapIndexed { index, image ->
            val video_required = save_video && runCatching { image.video != null }.getOrDefault(false)
            val video_url = if (video_required) runCatching {
                image.animatedVideoUrlList().firstOrNull { it.isNotBlank() }
            }.getOrNull() else null
            download_image_item(index, image, video_url, video_required, notify, "${index + 1}/${structs.size} %s%%")
        }
        val summary = ImageDownloadSummary(results)
        summary.files.forEach { file ->
            runCatching { KMediaUtils.notifyMediaUpdate(context, file.absolutePath) }.onFailure { XplerLog.e(it) }
        }
        val download_message = summary.message()
        val initial_message = if (is_webdav && summary.files.isNotEmpty()) {
            "$download_message 正在上传已保存文件至WebDAV!"
        } else download_message
        publish_image_result(notify, initial_message)
        if (!is_webdav || summary.files.isEmpty()) return

        // 两个入口共用这份清单，不能扫描整个目录把历史文件和残缺文件一起上传。
        val uploaded = summary.files.count { uploadToWebDav(it) }
        val upload_message = if (uploaded == summary.files.size) "上传WebDAV成功!" else
            "上传WebDAV成功${uploaded}, 失败${summary.files.size - uploaded}!"
        publish_image_result(notify, "$download_message $upload_message")
    }

    private fun publish_image_result(notify: IProgressNotification, message: String) {
        hook.refresh {
            notify.setFinishedText(message)
            hook.showToast(context, message)
        }
    }

    // 显示下载 Notification
    private fun showDownloadByNotification(
        urlList: List<String>,
        parentPath: File,
        pureFileName: String,
        isWebDav: Boolean = false,
    ) {
        // 发送通知
        hook.showDownloadNotification(
            context = context,
            notifyId = downloadNotifyId++,
            title = pureFileName,
            listener = {
                // 下载逻辑
                hook.singleLaunchIO(pureFileName) {
                    val resultFile = downloadFile(
                        url = urlList.first(),
                        downloadFile = File(parentPath.need(), pureFileName),
                        notify = it,
                        progressText = "下载中 %s%%",
                    )
                    if (resultFile != null) {
                        val message = if (isWebDav) "下载成功, 正在上传WebDav!" else "下载成功!"
                        it.setFinishedText(message)
                        hook.showToast(context, message)
                        KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)

                        // 上传WebDav
                        if (isWebDav) {
                            val uploadStatus = uploadToWebDav(resultFile)
                            it.setFinishedText("上传WebDav${if (uploadStatus) "成功!" else "失败!"}")
                            hook.showToast(context, "上传WebDav${if (uploadStatus) "成功!" else "失败!"}")
                        }
                    } else {
                        it.setFinishedText("下载失败!")
                        hook.showToast(context, "下载失败!")
                    }
                }
            }
        )
    }

    // 显示下载 Dialog
    private fun showDownloadByDialog(
        urlList: List<String>,
        parentPath: File,
        pureFileName: String,
        isWebDav: Boolean = false,
    ) {
        // 进度条Dialog
        hook.showProgressDialog(
            context = context,
            title = "Freedom+",
            listener = { dismiss, notify ->
                // 下载逻辑
                hook.singleLaunchIO(pureFileName) {
                    val resultFile = downloadFile(
                        url = urlList.first(),
                        downloadFile = File(parentPath.need(), pureFileName),
                        notify = notify,
                        progressText = "%s%%",
                    )
                    if (resultFile != null) {
                        hook.refresh { dismiss.invoke() }
                        val message = if (isWebDav) "下载成功, 正在上传WebDav!" else "下载成功!"
                        notify.setFinishedText(message)
                        hook.showToast(context, message)
                        KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)

                        // 上传WebDav
                        if (isWebDav) {
                            val uploadStatus = uploadToWebDav(resultFile)
                            hook.showToast(context, "上传WebDav${if (uploadStatus) "成功!" else "失败!"}")
                        }
                    } else {
                        hook.refresh { dismiss.invoke() }
                        notify.setFinishedText("下载失败!")
                        hook.showToast(context, "下载失败!")
                    }
                }
            }
        )
    }

    /**
     * 下载至本地
     */
    private suspend fun downloadFile(
        url: String,
        downloadFile: File,
        notify: IProgressNotification,
        progressText: String,
    ): File? {
        return withContext(Dispatchers.IO) {
            KHttpUtils.download(
                sourceUrl = url,
                file = downloadFile,
            ) { real, total, e ->
                if (e != null) {
                    XplerLog.e(e)
                    return@download
                }
                val progress = DownloadProgress.percent(real, total)
                if (notify is KNotifiUtils.ProgressNotification) {
                    notify.notifyProgress(progress, progressText)
                } else {
                    hook.refresh { notify.notifyProgress(progress, progressText) }
                }
            }
        }
    }

    private suspend fun download_image_item(
        index: Int,
        image: ImageUrlStruct,
        video_url: String?,
        video_required: Boolean,
        notify: IProgressNotification,
        progress_text: String,
    ): ImageDownloadResult {
        val image_file = download_image_asset(
            CommentMediaResolver.image_urls(image).firstOrNull(),
            File(mImageParent.need(), mPureFileName.secureFilename("_${index + 1}.jpg")),
            notify, progress_text, transcode_image = true,
        )
        val video_file = if (video_required) download_image_asset(
            video_url,
            File(mImageParent.need(), mPureFileName.secureFilename("_${index + 1}.mp4")),
            notify, progress_text,
        ) else null
        return ImageDownloadResult(image_file, video_required, video_file)
    }

    private suspend fun download_image_asset(
        url: String?,
        file: File,
        notify: IProgressNotification,
        progress_text: String,
        transcode_image: Boolean = false,
    ): File? {
        if (url.isNullOrBlank()) return null
        return try {
            downloadFile(url, file, notify, progress_text)?.also {
                if (transcode_image) HeifTranscoder.transcodeInPlace(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            XplerLog.e(error)
            null
        }
    }

    /**
     * 上传至WebDav
     */
    private suspend fun uploadToWebDav(file: File): Boolean {
        return try {
            val directoryName = file.parentFile!!.absolutePath.substringAfter("Freedom")
            webdav.createDirectory(directoryName = directoryName, parentPath = "Freedom", true)
            webdav.put(file = file, "Freedom".plus(directoryName))
            true
        } catch (e: Exception) {
            XposedBridge.log(e)
            false
        }
    }
}