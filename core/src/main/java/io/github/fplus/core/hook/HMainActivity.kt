package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import com.freegang.extension.appVersionCode
import com.freegang.extension.appVersionName
import com.freegang.extension.contentView
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.freegang.extension.forEachWhereChild
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

        /** MainActivity 是否在前台：被直播间/设置页等二级 Activity 盖住时为 false */
        @Volatile
        var isResumed: Boolean = false

        /**
         * 把直播页的「讲解中 / 已售 N」底栏整体上移一条 tab 的高度。
         *
         * 31.7.2 上这行本来就在 tab 图标上方；38.6 的直播页会画到屏幕最底（实测播放器高 2475，
         * 比屏幕 2340 还高），底栏正好落进 tab 行。沉浸模式把 tab 行清透明后它就露出来了。
         * 这里按文本定位那一行的容器，只做位移，不改宿主结构。
         */
        fun shiftLiveBottomBar(page: View, forcedTabHeight: Int = 0) {
            if (!ConfigV1.get().isImmersive) return
            val tabHeight = if (forcedTabHeight > 0) forcedTabHeight else (bottomTabView?.height ?: 0)
            if (tabHeight <= 0 || page.width <= 0) return

            val pageLoc = IntArray(2)
            page.getLocationOnScreen(pageLoc)
            val pageBottom = pageLoc[1] + page.height
            val tabTop = pageBottom - tabHeight

            // 纯按坐标找"底部那块信息容器"：满宽、高度 150~600、底边伸进 tab 行。
            // 不看文案（服务端下发，各房间不同）、不看类名（动态层，没有稳定类）。
            var candidate: View? = null
            var bestHeight = 0
            val loc = IntArray(2)
            runCatching {
                page.forEachChild { child ->
                    if (child === page) return@forEachChild
                    val name = child.javaClass.name
                    if (name.contains("LivePlayerView") || name.contains("MultiSegmentMaskView")) {
                        return@forEachChild
                    }
                    if (child.width < page.width - 8) return@forEachChild
                    val height = child.height
                    if (height !in 150..600) return@forEachChild
                    child.getLocationOnScreen(loc)
                    val bottom = loc[1] + height
                    if (bottom > tabTop && height > bestHeight) {
                        candidate = child
                        bestHeight = height
                    }
                }
            }.onFailure { XplerLog.e(it) }

            val block = candidate ?: return
            // 宿主自己会用它做动画（如评论面板升降），正在动画时不要抢
            if (block.translationY != 0f) return
            block.translationY = -tabHeight.toFloat()
            XplerLog.d(
                "Immersive",
                "shift live bottom block ${block.javaClass.name} h=$bestHeight by $tabHeight"
            )
        }

        fun toggleView(visible: Boolean) {
            mainTitleBar?.isVisible = visible
            bottomTabView?.isVisible = visible

            // val activity = mainTitleBar?.context?.asOrNull<Activity>() ?: return
            // ImmersiveHelper.immersive(activity, !visible, !visible)
        }
    }

    private val config get() = ConfigV1.get()
    private val clipboardLogic = ClipboardLogic(this)

    /**
     * 底部导航被「全屏沉浸」清成透明前的原始背景。
     *
     * 直播间这类页面是**另一个 Activity 盖在 MainActivity 上**，底部那条仍然露在下面；
     * 背景清掉后看到的是被压暗的底层窗口（灰），和上面的直播画面形成断层。
     * 所以这里保存原值，MainActivity 一进后台就还原，回来 resume 时再重新清。
     */
    private val savedBottomBarBackgrounds = mutableMapOf<View, Drawable?>()
    private var disallowInterceptRelativeLayout: View? = null

    /** 直播页兜底监听的宿主 decor（MainActivity 的整窗） */
    private var liveWatcherDecor: View? = null

    /** 全局布局监听：直播页是异步分层加载的，没有稳定的类/文案锚点，只能每次布局后重扫 */
    private var liveWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** 上次扫描时间，用来限流（decor 视图树很大，不宜每帧都扫） */
    private var liveWatchAt = 0L

    /** 被我们临时抹掉的遮罩 -> 它原本的 alpha（离开直播页要还原） */
    private val zeroedMasks = java.util.WeakHashMap<View, Float>()

    override fun setTargetClass(): Class<*> {
        return MainActivity::class.java
    }

    @OnBefore("onCreate")
    fun onCreateBefore(params: MethodParam, savedInstanceState: Bundle?) {
        hookBlockRunning(params) {
            thisActivity.runCatching {
                val source_intent = intent ?: return@runCatching
                val startModuleSetting = source_intent.getBooleanExtra("startModuleSetting", false)
                if (startModuleSetting) {
                    source_intent.removeExtra("startModuleSetting")
                    val activity = this
                    val setting_intent = Intent(source_intent).apply {
                        setClass(activity, FreedomSettingActivity::class.java)
                        putExtra("isModuleStart", true)
                        putExtra("isDark", activity.isDarkMode)
                    }
                    val options = ActivityOptions.makeCustomAnimation(
                        this,
                        android.R.anim.slide_in_left,
                        android.R.anim.slide_out_right
                    )
                    startActivity(setting_intent, options.toBundle())
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
            isResumed = true

            addClipboardListener(activity)
            initView(activity)
            attachLivePageWatcher(activity)
            is32BisTips(activity)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onPause")
    fun onPause(params: MethodParam) {
        hookBlockRunning(params) {
            isResumed = false
            detachLivePageWatcher()
            removeClipboardListener(thisActivity)
            // 退到直播间/设置页等二级页面时，底部那条仍然可见，先把背景还原回去
            restoreBottomBarBackground()
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

    /**
     * 挂一个全局布局监听，专门处理「直播预览页」的底部。
     *
     * 为什么不用 hook：直播页的「讲解中/用户名/简介」在动态层里，
     * 静态找不到稳定类名（已反编译确认），文案又是服务端下发；而 LivePlayerView 的
     * onAttachedToWindow 在这套里也不一定走我们的 hook。用 decor 的 OnGlobalLayout 重扫，
     * 与页面结构、类名、文案都解耦：只要它完成一次布局，我们就有机会把底部整块挪上去。
     */
    private fun attachLivePageWatcher(activity: Activity) {
        if (!config.isImmersive) return
        if (liveWatcher != null) return
        val decor = activity.window?.decorView ?: return

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val now = SystemClock.uptimeMillis()
            if (now - liveWatchAt < 400L) return@OnGlobalLayoutListener
            liveWatchAt = now
            runCatching { refreshLivePages(decor) }.onFailure { XplerLog.e(it) }
        }
        liveWatcherDecor = decor
        liveWatcher = listener
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun detachLivePageWatcher() {
        val decor = liveWatcherDecor
        val listener = liveWatcher
        liveWatcherDecor = null
        liveWatcher = null
        if (decor == null || listener == null) return
        runCatching {
            decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }.onFailure { XplerLog.e(it) }
    }

    /**
     * 重扫当前窗口：
     *  1. 遮罩 `MultiSegmentMaskView` 一律置透明（hook onDraw 是主路径，这里是兜底）；
     *  2. 当前屏上的直播预览页，把底部信息块整体上移一条 tab 高。
     */
    private fun refreshLivePages(root: View) {
        if (!config.isImmersive) return
        if (root.width <= 0 || root.height <= 0) return

        val tabBar = bottomTabView ?: findBottomTabBar(root)
        val tabHeight = tabBar?.height ?: 0
        if (tabHeight <= 0) return
        if (bottomTabView == null) {
            bottomTabView = tabBar
            XplerLog.d("Immersive", "bottom tab bar resolved by class name: ${tabBar?.javaClass?.name}")
        }

        val pages = mutableListOf<View>()
        val masks = mutableListOf<View>()
        root.forEachChild { child ->
            val name = child.javaClass.name
            when {
                name.contains("LivePreviewFrameLayout") -> pages.add(child)
                name.contains("MultiSegmentMaskView") -> masks.add(child)
            }
        }

        val loc = IntArray(2)
        val visiblePages = pages.filter { page ->
            page.getLocationOnScreen(loc)
            page.width >= root.width - 8 && loc[1] in -16..16   // 只看当前屏这一页
        }

        // 底部渐变压暗层：只抹掉「底边伸进 tab 行」的那些。
        // 普通视频的遮罩止于 tab 顶边之上（实测 2180 < 2205），要保留它衬左下角文案；
        // 直播类页面（包括直播 tab 内嵌页，实测 0,1680-1080,2340）会画到 tab 行里，
        // tab 透明后就是那条灰带——按几何位置判断，比按页面类名稳。
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val tabTop = rootLoc[1] + root.height - tabHeight

        val maskLoc = IntArray(2)
        val hideMasks = HashSet<View>()
        for (mask in masks) {
            mask.getLocationOnScreen(maskLoc)
            if (maskLoc[1] + mask.height <= tabTop + 4) continue
            hideMasks.add(mask)
            if (mask.alpha == 0f) continue
            if (!zeroedMasks.containsKey(mask)) zeroedMasks[mask] = mask.alpha
            mask.alpha = 0f
        }

        // 之前抹过、这次不该抹的还原（页面切走 / 遮罩缩回 tab 上方）
        if (zeroedMasks.isNotEmpty()) {
            val iterator = zeroedMasks.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val mask = entry.key
                if (mask in hideMasks) continue
                if (mask.isAttachedToWindow && mask.alpha != entry.value) {
                    mask.alpha = entry.value
                }
                iterator.remove()
            }
        }

        for (page in visiblePages) {
            shiftLiveBottomBar(page, tabHeight)
        }
    }

    /** 按类名找底部 tab 容器（dexkit 在部分版本上匹配不到时的兜底） */
    private fun findBottomTabBar(root: View): View? {
        var found: View? = null
        runCatching {
            root.forEachWhereChild { child ->
                if (child.javaClass.name.contains("MainBottomTabContainer")) {
                    found = child
                    true
                } else {
                    false
                }
            }
        }.onFailure { XplerLog.e(it) }
        return found
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

                    if (DexkitBuilder.mainBottomTabViewClazz?.name == child.javaClass.name ||
                        (bottomTabView == null && child.javaClass.name.contains("MainBottomTabContainer"))
                    ) {
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
        // 注意：只能压 tab 自己。它的 parent 是 ProTouchDelegateFrameLayout(0,0-1080,2340)，
        // 整屏都包在里面，压 parent 会把视频/简介/「我」页一起变暗（实测）。
        if (config.isTranslucent) {
            val alphaValue = config.translucentValue[3] / 100f
            bottomTabView?.alpha = alphaValue
        }

        // 底部导航栏全局沉浸式
        if (config.isImmersive) {
            applyBottomBarImmersive()
        }
    }

    /** 清掉底部导航及其父容器的背景，并记下原值以便退到二级页面时还原 */
    private fun applyBottomBarImmersive() {
        val parent = bottomTabView?.parentView ?: return

        saveBottomBarBackground(parent)
        parent.background = ColorDrawable(Color.TRANSPARENT)

        bottomTabView?.forEachChild { child ->
            saveBottomBarBackground(child)
            child.background = ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun saveBottomBarBackground(view: View) {
        if (!savedBottomBarBackgrounds.containsKey(view)) {
            savedBottomBarBackgrounds[view] = view.background
        }
    }

    /**
     * 还原底部导航背景。
     *
     * 直播间/设置页等二级 Activity 打开时 MainActivity 会 pause，但底部那条仍可见，
     * 此时若保持透明就会露出被压暗的底层窗口，出现"这一行和上面画面不一样"的断层。
     */
    private fun restoreBottomBarBackground() {
        if (savedBottomBarBackgrounds.isEmpty()) return
        savedBottomBarBackgrounds.forEach { (view, background) ->
            view.background = background
        }
        savedBottomBarBackgrounds.clear()
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
                    // 移除底部间隔：视频页靠它铺满画面。
                    // （直播页实测根本没有 BottomSpace，它的底栏重叠由 HLivePlayerView 的位移解决，
                    //  这里不需要按页面类型区分——之前按"有没有右侧栏"判断会导致部分视频页漏删，
                    //  文案/热搜被顶到很高。）
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
        restoreBottomBarBackground()
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