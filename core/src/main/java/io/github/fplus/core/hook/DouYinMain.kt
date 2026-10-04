package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.ktutils.app.KActivityUtils
import com.freegang.ktutils.app.KAppCrashUtils
import com.freegang.ktutils.app.KAppUtils
import com.freegang.ktutils.app.KToastUtils
import com.freegang.ktutils.log.KLogCat
import io.github.fplus.Constant
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.helper.TimerExitHelper
import io.github.fplus.plugin.injectRes
import io.github.fplus.plugin.proxy.v1.PluginBridge
import io.github.xpler.core.XplerLog
import io.github.xpler.core.XplerModule
import java.util.zip.ZipFile

class DouYinMain(private val app: Application) {
    companion object {
        var timerExitHelper: TimerExitHelper? = null
        var freeExitHelper: TimerExitHelper? = null
    }

    init {
        runCatching {
            exportNative(app)

            // 插件化注入
            PluginBridge.init(app, "com.ss.android.ugc.aweme.setting.ui.AboutActivity")
            injectRes(app.resources)

            // 全局Application
            KAppUtils.setApplication(app)
            KActivityUtils.register(app)

            // 日志工具
            XplerLog.setTag("Freedom+")
            KLogCat.init(app)
            KLogCat.setTag("Freedom+")
            // KLogCat.silence() //静默

            // 全局异常捕获工具
            val intent = Intent()
            val className = "${Constant.modulePackage}.activity.ErrorActivity"
            intent.setClassName(Constant.modulePackage, className)
            KAppCrashUtils.init(app, "抖音异常退出!", intent)

            // 定时退出
            initTimedShutdown(app)

            // search and hook
            DexkitBuilder.running(
                app = app,
                // 缓存版本号：改动 DexkitBuilder 里的任何 matcher 都必须递增它，
                // 否则会直接命中旧缓存（缓存里存的是上次搜索结果，含失败时的 "null"），
                // 表现为「改了 matcher 却毫无效果」。
                // 31 -> 32：40.6.0 适配（mainBottomTabView / 评论区三处）
                // 32 -> 33：40.6.0 适配（防撤回 / 表情包 / tabLanding / detailPage / feedAvatar
                //           / autoPlayController / longPressEvent）
                version = 33,
                searchBefore = {
                    HPhoneWindow()
                    HActivity()
                    HMainActivity()
                    HDetailActivity()
                    HLandscapeFeedActivity()
                    HLivePlayActivity()
                    HDisallowInterceptRelativeLayout()
                    HMainTabStripScrollView()
                    HFlippableViewPager()
                    HPlayerController()
                    HPenetrateTouchRelativeLayout()
                    HInteractStickerParent()
                    HGifEmojiDetailActivity()
                    HEmojiDetailDialog()
                    HDialog()
                },
                searchAfter = {
                    HCrashTolerance()
                    HSideBarNestedScrollView()
                    HCornerExtensionsPopupWindow()
                    HMainBottomTabView()
                    HMainBottomPhotoTab()
                    HCommentListPageFragment()
                    HCommentFeedFragment()
                    HConversationFragment()
                    HSeekBarSpeedModeBottomMask()
                    HLongPressLayout()
                    HVideoViewHolder()
                    HFeedPlayerView()
                    HFeedAvatarPresenter()
                    HHomeBottomTabServiceImpl()
                    HAbstractFeedAdapter()
                    HVerticalViewPager()
                    HDetailPageFragment()
                    HEmojiDetailDialogNew()
                    HEmojiPopupWindow()
                    HBottomCtrlBar()
                    HMessage()
                    HChatListRecyclerViewAdapter()
                    HChatListRecyclerViewAdapterNew()
                    HChatListRecalledHint()
                    // 信息流广告过滤（依赖 DexKit 的 abstractFeedAdapterClazz）
                    HFeedAdFilter()
                    // 信息流广告拦截（依赖 DexKit 的 videoViewHolderClazz，主 feed 的 bind 入口）
                    HFeedAdBlock()
                    // 可玩广告的 ViewHolder（各自 final bind 重写，需单独挂）
                    HFeedAdBlockPlayable()
                    HFeedAdBlockPlayableInteractive()
                    // 主 feed 数据流探针（诊断，默认关闭）
                    HFeedBindProbe()
                    // 运行时 View 树探针（诊断，默认关闭；用于定位 bottomCtrlBar 等静态无法收敛的控件）
                    HViewTreeDump()
                    // 广告拦截：不依赖 DexKit 结果，放最后执行
                    HAdBlock()
                }
            )

        }.onFailure {
            XplerLog.e(it)
            KToastUtils.show(app, "Freedom+ Error: ${it.message}")
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun exportNative(app: Application) {
        val abi = if (KAppUtils.is64BitDalvik()) "arm64-v8a" else "armeabi-v7a"
        val libDir = ConfigV1.getConfigDir(app).child("lib").need()
        val libDexkit = libDir.child("libdexkit.so")
        val libMmkv = libDir.child("libmmkv.so")

        if (!libDexkit.exists() || !libMmkv.exists()) {
            val dexkitSo = "lib/${abi}/libdexkit.so"
            val mmkbSo = "lib/${abi}/libmmkv.so"

            val zipFile = ZipFile(XplerModule.modulePath)
            zipFile.getInputStream(zipFile.getEntry(dexkitSo)).use { input ->
                libDexkit.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            zipFile.getInputStream(zipFile.getEntry(mmkbSo)).use { input ->
                libMmkv.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        System.load(libDexkit.absolutePath)
        ConfigV1.initialize(app) { _ ->
            System.load(libMmkv.absolutePath)
        }
    }

    @Synchronized
    private fun initTimedShutdown(app: Application) {
        val config = ConfigV1.get()
        if (!config.isTimedExit) {
            return
        }

        val timedExit = config.timedShutdownValue[0] * 60 * 1000L
        val freeExit = config.timedShutdownValue[1] * 60 * 1000L

        if (timedExit >= 60 * 1000L * 3) {
            timerExitHelper = TimerExitHelper(app, timedExit, config.keepAppBackend) {
                val second = it / 1000L
                if (second == 30L) {
                    KToastUtils.show(app, "抖音将在30秒后定时退出")
                }
                if (second <= 5) {
                    KToastUtils.show(app, "定时退出倒计时${second}s")
                }

                // KLogCat.d("定时退出进行中: ${second}s")
            }
        }

        if (freeExit >= 60 * 1000L * 3) {
            freeExitHelper = TimerExitHelper(app, freeExit, config.keepAppBackend) {
                val second = it / 1000L
                if (second == 30L) {
                    KToastUtils.show(app, "长时间无操作, 抖音将在30秒后空闲退出")
                }
                if (second <= 5) {
                    KToastUtils.show(app, "空闲退出倒计时${second}s")
                }

                // KLogCat.d("空闲退出进行中: ${second}s")
            }
        }
    }
}