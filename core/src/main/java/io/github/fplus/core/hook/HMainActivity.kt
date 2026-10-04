package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import com.freegang.extension.appVersionCode
import com.freegang.extension.appVersionName
import com.freegang.extension.contentView
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.freegang.extension.is64BitDalvik
import com.freegang.extension.isDarkMode
import com.freegang.extension.parentView
import com.freegang.extension.postRunning
import com.freegang.extension.removeInParent
import com.freegang.ktutils.log.KLogCat
import com.ss.android.ugc.aweme.homepage.ui.titlebar.MainTitleBar
import com.ss.android.ugc.aweme.homepage.ui.view.MainTabStripScrollView
import com.ss.android.ugc.aweme.main.MainActivity
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.AutoPlayHelper
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.hook.logic.ClipboardLogic
import io.github.fplus.core.hook.logic.DownloadLogic
import io.github.fplus.core.ui.activity.FreedomSettingActivity
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import io.github.xpler.core.moduleVersionName
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisActivity
import io.github.xpler.core.thisContext
import kotlinx.coroutines.delay

class HMainActivity : BaseHook() {
    companion object {
        /**
         * 用类名比较替代 `is` 检查，避免代理类在当前抖音版本不存在时抛 NoClassDefFoundError。
         *
         * 40.6.0 适配：这两个类都搬了包 ——
         *   旧：com.ss.android.ugc.aweme.homepage.ui.view.MainTabStripScrollView（已不存在）
         *   旧：com.ss.android.ugc.aweme.homepage.ui.titlebar.MainTitleBar（已不存在）
         * 新路径见下（均在 classes37）。原常量在 40.6.0 恒不匹配，导致 mainTitleBar 为 null、
         * 「隐藏顶部选项」整体空转。
         */
        private const val MAIN_TAB_STRIP_SCROLL_VIEW =
            "com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.scroll.MainTabStripScrollView"
        private const val MAIN_TITLE_BAR =
            "com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar"

        /** 顶部单个 tab 项（40.6.0 实测类名，动态创建） */
        private const val TOP_TAB_ITEM_VIEW =
            "com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew"

        @SuppressLint("StaticFieldLeak")
        var mainTitleBar: View? = null

        @SuppressLint("StaticFieldLeak")
        var bottomTabView: View? = null

        fun toggleView(visible: Boolean) {
            mainTitleBar?.isVisible = visible
            bottomTabView?.isVisible = visible

            // val activity = mainTitleBar?.context?.asOrNull<Activity>() ?: return
            // ImmersiveHelper.immersive(activity, !visible, !visible)
        }
    }

    private val config get() = ConfigV1.get()
    private val clipboardLogic = ClipboardLogic(this)
    private var disallowInterceptRelativeLayout: View? = null

    override fun setTargetClass(): Class<*> {
        return MainActivity::class.java
    }

    @OnBefore("onCreate")
    fun onCreateBefore(params: MethodParam, savedInstanceState: Bundle?) {
        hookBlockRunning(params) {
            thisActivity.runCatching {
                val startModuleSetting = intent?.getBooleanExtra("startModuleSetting", false) ?: false
                if (startModuleSetting) {
                    intent.setClass(this, FreedomSettingActivity::class.java)
                    intent.putExtra("isModuleStart", true)
                    intent.putExtra("isDark", isDarkMode)
                    val options = ActivityOptions.makeCustomAnimation(
                        this,
                        android.R.anim.slide_in_left,
                        android.R.anim.slide_out_right
                    )
                    startActivity(intent, options.toBundle())
                    finish()
                }
            }.onFailure {
                XplerLog.e(it)
            }
        }
    }

    @OnAfter("onCreate")
    fun onCreateAfter(params: MethodParam, savedInstanceState: Bundle?) {
        hookBlockRunning(params) {
            val activity = thisActivity
            XplerLog.d("version: ${activity.moduleVersionName} - ${activity.appVersionName}(${activity.appVersionCode})")
            DouYinMain.timerExitHelper?.restart()

            openAutoPlay(activity)
            hookTopTabItems()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 顶部 tab 项的**动态**隐藏。
     *
     * 40.6.0 的顶部 tab 项（`HomeTopTabItemViewNew`）是动态创建/重建的，而 [initMainTitleBar]
     * 只在 onResume 跑一次。真机日志证实：`initMainTitleBar` 被调用 3 次，但前两次时
     * `mainTitleBar` 底下还是空的，子项是之后才建出来的 —— 所以那种一次性隐藏会被重建覆盖，
     * 表现为「开关打开了但 tab 没隐藏」。
     *
     * 这里改挂到 tab 项自己的 `setContentDescription` 上：文案刚设好就判定并隐藏，
     * 无论抖音重建多少次都能跟上。
     */
    private fun hookTopTabItems() {
        runCatching {
            // 注意：**不能**挂 setContentDescription —— 那是 View 的方法，HomeTopTabItemViewNew
            // 自己并没有声明它；而 xpler 的 methodAllByParamTypes 只检索 declaredMethods，
            // 对继承方法会静默找不到（这个坑之前踩过一次）。
            // 该自己声明了 onAttachedToWindow，改挂这里。
            lparam.hookClass(TOP_TAB_ITEM_VIEW)
                .method("onAttachedToWindow") {
                    onAfter {
                        val view = thisObject as? View ?: return@onAfter
                        // 延迟一拍：onAttachedToWindow 时 contentDescription 通常还没设好
                        view.postDelayed({
                            runCatching { applyTopTabHide(view) }.onFailure { XplerLog.e(it) }
                        }, 150L)
                    }
                }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /** 按关键词判断并隐藏单个顶部 tab 项 */
    private fun applyTopTabHide(view: View) {
        if (!config.isHideTopTab) return
        val desc = view.contentDescription?.toString() ?: return
        val regex = topTabKeywordsRegex() ?: return
        if (desc.contains(regex)) {
            view.isVisible = false
            KLogCat.tagI("HMainActivity", "已隐藏顶部选项: '$desc'")
        }
    }

    /** 把用户配置的关键词串转成正则（半角/全角逗号、空白都归一化） */
    private fun topTabKeywordsRegex(): Regex? {
        val raw = config.hideTopTabKeywords
        if (raw.isBlank()) return null
        return raw
            .replace("，", ",")
            .replace("\\s".toRegex(), "")
            .removePrefix(",").removeSuffix(",")
            .replace(",", "|")
            .replace("\\|+".toRegex(), "|")
            .toRegex()
    }

    @OnAfter("onResume")
    fun onResume(params: MethodParam) {
        hookBlockRunning(params) {
            val activity = thisObject as Activity

            addClipboardListener(activity)
            initView(activity)
            is32BisTips(activity)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onPause")
    fun onPause(params: MethodParam) {
        hookBlockRunning(params) {
            removeClipboardListener(thisActivity)
            clearView()
            saveConfig(thisContext)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun addClipboardListener(activity: Activity) {
        if (!config.isDownload) return
        if (!config.copyLinkDownload) return

        clipboardLogic.addClipboardListener(activity) { clipData, firstText ->
            DownloadLogic(
                this@HMainActivity,
                activity,
                HVideoViewHolder.aweme,
            )
        }
    }

    private fun removeClipboardListener(activity: Activity) {
        clipboardLogic.removeClipboardListener(activity)
    }

    private fun initView(activity: Activity) {
        activity.contentView.postRunning {
            // 整体加保护：下面任何一处类解析失败都不该把抖音打崩
            runCatching {
                it.forEachChild { child ->
                    // 注意：这里不能写 `child is MainTabStripScrollView`。
                    // aweme 里的代理类是 compileOnly（不打进 APK），运行时由抖音提供；
                    // 而 MainTabStripScrollView 在抖音 40.x 已不存在，`is` 检查会抛
                    // NoClassDefFoundError，且这里跑在 post 回调里没有捕获 —— 直接把抖音打崩。
                    // 改用类名比较，不触发类解析。
                    if (child.javaClass.name == MAIN_TAB_STRIP_SCROLL_VIEW) {
                        val lp = child.layoutParams
                        if (lp is RelativeLayout.LayoutParams) {
                            child.layoutParams = lp.apply {
                                this.addRule(RelativeLayout.CENTER_IN_PARENT)
                            }
                        }
                    }

                    if (child.javaClass.name == MAIN_TITLE_BAR) {
                        mainTitleBar = child
                    }

                    if (DexkitBuilder.mainBottomTabViewClazz?.name == child.javaClass.name) {
                        bottomTabView = child
                    }

                    if (child.javaClass.name.contains("DisallowInterceptRelativeLayout")) {
                        disallowInterceptRelativeLayout = child
                    }
                }

                initMainTitleBar()
                initBottomTabView()
                initDisallowInterceptRelativeLayout()
            }.onFailure {
                XplerLog.e(it)
            }
        }
    }

    private fun initMainTitleBar() {
        // 兜底：onResume 时若 tab 项已存在就直接隐藏。
        // 主体逻辑在 hookTopTabItems() —— tab 项是动态建的，只在这里隐藏会被重建覆盖。
        if (config.isHideTopTab) {
            val keywordsRegex = topTabKeywordsRegex()
            if (keywordsRegex != null) {
                mainTitleBar?.forEachChild { child ->
                    val desc = "${child.contentDescription}"
                    if (desc.isNotEmpty() && desc != "null" && desc.contains(keywordsRegex)) {
                        child.isVisible = false
                    }
                }
            }
        }

        // 顶部选项卡透明度
        if (config.isTranslucent) {
            val alphaValue = config.translucentValue[0] / 100f
            mainTitleBar?.alpha = alphaValue
        }
    }

    private fun initBottomTabView() {
        // 隐藏底部选项卡
        if (config.isHideBottomTab) {
            val keywordsRegex = config.hideBottomTabKeywords
                .replace("，", ",")
                .replace("\\s".toRegex(), "")
                .removePrefix(",").removeSuffix(",")
                .replace(",".toRegex(), "|")
                .replace("\\|+".toRegex(), "|")
                .toRegex()

            bottomTabView?.forEachChild { child ->
                val desc = "${child.contentDescription}"
                if (desc.contains(keywordsRegex)) {
                    val tabItem = child.firstParentOrNull(ViewGroup::class.java) { parent ->
                        parent.javaClass.name.startsWith("X")
                    }

                    tabItem?.isVisible = false
                }
            }
        }

        // 底部导航栏透明度
        if (config.isTranslucent) {
            val alphaValue = config.translucentValue[3] / 100f
            bottomTabView?.parentView?.alpha = alphaValue
        }

        // 底部导航栏全局沉浸式
        if (config.isImmersive) {
            bottomTabView?.parentView?.background = ColorDrawable(Color.TRANSPARENT)
            bottomTabView?.forEachChild {
                it.background = ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    private fun initDisallowInterceptRelativeLayout() {
        if (!config.isImmersive)
            return

        disallowInterceptRelativeLayout?.postRunning {
            runCatching {
                it.forEachChild { child ->
                    // 移除顶部间隔
                    if (child.javaClass.name == "android.view.View") {
                        child.removeInParent()
                    }
                    // 移除底部间隔
                    if (child.javaClass.name == "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace") {
                        child.removeInParent()
                    }
                }
            }.onFailure {
                XplerLog.e(it)
            }
        }
    }

    private fun openAutoPlay(context: Context) {
        if (!config.isAutoPlay)
            return

        if (!config.defaultAutoPlay)
            return

        launchMain {
            delay(2000L)
            AutoPlayHelper.openAutoPlay(context)
        }
    }

    private fun clearView() {
        mainTitleBar = null
        bottomTabView = null
        disallowInterceptRelativeLayout = null
    }

    // 保存配置信息
    private fun saveConfig(context: Context) {
        config.versionConfig = config.versionConfig.copy(
            dyVersionName = context.appVersionName,
            dyVersionCode = context.appVersionCode
        )
    }

    private fun is32BisTips(context: Context) {
        singleLaunchMain {
            delay(2000L)

            if (context.is64BitDalvik) {
                return@singleLaunchMain
            }

            val version = config.versionConfig
            val cacheVersion = "${version.dyVersionName}_${version.dyVersionCode}"
            val currentVersion = "${context.appVersionName}_${context.appVersionCode}"
            if (cacheVersion.compareTo(currentVersion) != 0) {
                config.is32BitTips = true
            }

            if (!config.is32BitTips) {
                return@singleLaunchMain
            }

            showMessageDialog(
                context = context,
                title = "温馨提示",
                content = "当前抖音32位，使用过程中可能出现严重卡顿、花屏等现象，建议更换抖音64位。",
                cancel = "此版本不再提示",
                confirm = "确定",
                onCancel = {
                    config.is32BitTips = false
                },
                onConfirm = {

                }
            )
        }

        /*showComposeDialog(context) { onClosedHandle ->
            FMessageDialog(
                title = "温馨提示",
                cancel = "此版本不再提示",
                confirm = "确定",
                onCancel = {
                    onClosedHandle.invoke()
                    config.is32BitTips = false
                },
                onConfirm = {
                    onClosedHandle.invoke()
                }
            ) {
                Text(
                    text = "当前抖音32位，使用过程中可能出现严重卡顿、花屏等现象，建议更换抖音64位。",
                )
            }
        }*/
    }
}