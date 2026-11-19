package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.freegang.extension.asOrNull
import com.freegang.extension.dip2px
import com.freegang.extension.findField
import com.freegang.extension.findFieldGetValue
import com.freegang.extension.findMethodInvoke
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.freegang.extension.getBrotherViewAt
import com.freegang.extension.postDelayedRunning
import com.freegang.extension.postRunning
import com.freegang.extension.setLayoutWidth
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout
import io.github.fplus.core.R
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.AutoPlayHelper
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.plugin.injectRes
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.loader.hostClassloader

class HVideoViewHolder : BaseHook() {
    companion object {
        /** 左侧简介块（昵称/文案/标签都在里面），透明度只压它，不压整条视频 */
        private const val FEED_INTRO_LAYOUT =
            "com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout"

        /** 简介兜底容器（部分视频形态用这个） */
        private const val FEED_DESC_ROOT_LAYOUT =
            "com.ss.android.ugc.aweme.feed.desc.ui.FeedDescRootLayout"

        /** 用类名比较替代代理类的 `is`/`as`，避免其在目标抖音版本不存在时抛 NoClassDefFoundError */
        private const val FEED_RIGHT_SCALE_VIEW =
            "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView"

        /**
         * 两侧控件的补扫时机(ms)。
         *
         * 药丸要等音乐信息回来才从 GONE 翻成 VISIBLE（实测 1~4s 才稳定），前面几档密一点，
         * 否则会出现「先露出来、过一会儿才被藏掉」的闪烁；后面几档兜慢机。
         */
        private val KEYWORD_BLOCK_SCAN_DELAYS = longArrayOf(0L, 150L, 400L, 900L, 1800L, 3000L)

        /** 地点等锚点标签的圆角容器（feed anchor 那一套共用） */
        private const val ANCHOR_ROUNDED_LAYOUT = "com.ss.android.ugc.aweme.views.RoundedLinearLayout"

        @get:Synchronized
        @set:Synchronized
        var aweme: Aweme? = null
    }

    private val config get() = ConfigV1.get()

    private var onDrawMaps = mutableMapOf<String, ViewTreeObserver.OnPreDrawListener?>()

    /**
     * 已经命中关键字、被隐藏过的控件。
     *
     * 药丸是宿主按自己的节奏设成 VISIBLE 的，补扫之间会露出来；命中后把它记下来，
     * 在已有的每帧 OnDraw 回调里做一次 O(1) 复查，宿主一显示就立刻压回 GONE。
     */
    private val keywordTargets = mutableListOf<View>()

    /**
     * 每个视频项根 View 上的布局监听。
     *
     * 药丸是宿主先 setText 再 setVisibility(VISIBLE)，靠定时补扫必然晚一拍；而从 GONE 翻成
     * VISIBLE 一定触发一次 layout，OnGlobalLayout 在 measure/layout 之后、draw 之前回调，
     * 在这里扫一次并摘掉目标，这一帧就画不出来，等于「从未出现」。
     */
    private val keywordLayoutScans = mutableMapOf<View, ViewTreeObserver.OnGlobalLayoutListener>()

    /** 防止扫描里改可见性再次触发 layout，形成回调套回调 */
    private var scanningKeywordBlocks = false

    /**
     * 精确关键字（`,` / `，` 拆分），供「视频项」级扫描使用。
     *
     * 与 [videoOptionBarFilterKeywords] 的正则分开：右侧栏内部走 contains 匹配，
     * 这里只认**文本完全相等**或 contentDescription 包含，避免把简介正文里
     * 偶然出现的同一个词一起埋掉。
     */
    private val videoOptionBarFilterKeywordList by lazy {
        config.videoOptionBarFilterKeywords
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private val videoOptionBarFilterKeywords by lazy {
        config.videoOptionBarFilterKeywords
            .replace("，", ",")
            .replace("\\s".toRegex(), "")
            .removePrefix(",").removeSuffix(",")
            .replace(",".toRegex(), "|")
            .replace("\\|+".toRegex(), "|")
            .toRegex()
    }

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.videoViewHolderClazz ?: NoneHook::class.java
    }

    private fun addOnDraw(view: View?) {
        if (view == null) {
            XplerLog.d("addOnDraw", "view == null")
            return
        }

        val key = Integer.toHexString(System.identityHashCode(view))

        // 右侧栏 / 左侧简介块只解析有限次，避免每帧 DFS
        var railView: View? = null
        var introViews: List<View> = emptyList()
        var railTries = 0
        var introTries = 0
        var nextIntroTryAt = 0L
        var barSlots: List<View> = emptyList()
        var barTries = 0

        // OnPreDraw 每帧都会回调：配置值不能每帧读 MMKV / 重新 split translucentValue。
        // 500ms 刷新一次；设置变更最多延迟半秒生效。
        var lastConfigReadAt = 0L
        var isTranslucent = false
        var leftValue = 0.5f
        var railValue = 0.5f
        var isNeatMode = false
        var neatModeState = false

        onDrawMaps.putIfAbsent(key, ViewTreeObserver.OnPreDrawListener {
            val frameAt = SystemClock.uptimeMillis()
            if (frameAt - lastConfigReadAt >= 500L) {
                lastConfigReadAt = frameAt
                isTranslucent = config.isTranslucent
                if (isTranslucent) {
                    val values = config.translucentValue
                    leftValue = values[1].coerceIn(0, 100) / 100f
                    railValue = values[2].coerceIn(0, 100) / 100f
                }
                isNeatMode = config.isNeatMode
                neatModeState = config.neatModeState
            }

            if (isTranslucent) {

                // 缓存的视图可能被 RecyclerView 回收，脱离窗口就重新解析
                if (railView?.isAttachedToWindow != true) {
                    railView = if (railTries < 60) {
                        view.findFirstChildOfClass(FEED_RIGHT_SCALE_VIEW).also { railTries++ }
                    } else {
                        null
                    }
                }
                val rail = railView

                if (rail != null) {
                    // 只压"控件本身"，不再压 PenetrateTouchRelativeLayout：
                    // 那个容器把视频画面也包在里面，压它会把整条视频一起变暗
                    // （用户反馈的「首页亮度明显低于状态栏」就是它造成的）。
                    if (rail.alpha != railValue) rail.alpha = railValue
                    rail.getBrotherViewAt(1)?.let { music ->
                        if (music.alpha != railValue) music.alpha = railValue
                    }

                    // 简介块是异步 inflate 的：每 500ms 再试；缓存失效（回收/替换）时重新解析
                    if (introViews.isEmpty() || introViews.any { !it.isAttachedToWindow }) {
                        if (introTries < 10) {
                            val now = SystemClock.uptimeMillis()
                            if (now >= nextIntroTryAt) {
                                introViews = rail.findIntroLayouts()
                                introTries++
                                nextIntroTryAt = now + 500L
                                if (introViews.isEmpty() && introTries >= 10) {
                                    XplerLog.d(
                                        "Alpha",
                                        "intro not found, item=${getItemRoot(rail).javaClass.name}"
                                    )
                                }
                            }
                        }
                    }
                    for (intro in introViews) {
                        if (intro.isAttachedToWindow && intro.alpha != leftValue) {
                            intro.alpha = leftValue
                        }
                    }

                    // 底 bar 槽位（相关搜索 / 上升热点）：它不在简介块里，需要单独压。
                    // 一个页面里可能有多份槽位（列表预加载），全部按同一值处理。
                    if (barSlots.isEmpty() || barSlots.any { !it.isAttachedToWindow }) {
                        if (barTries < 10) {
                            barSlots = rail.findBottomBarSlots()
                            barTries++
                        }
                    }
                    for (slot in barSlots) {
                        if (slot.isAttachedToWindow && slot.alpha != leftValue) {
                            slot.alpha = leftValue
                        }
                    }
                }
            }

            if (isNeatMode) {
                if (neatModeState) {
                    view.isVisible = !HPlayerController.isPlaying
                    HMainActivity.toggleView(view.isVisible)
                }
            }

            // 已命中过的控件若被宿主重新设为可见，下一帧前就压回去（列表很小，O(1) 级开销）
            if (keywordTargets.isNotEmpty()) {
                keywordTargets.removeAll { !it.isAttachedToWindow }
                for (target in keywordTargets) {
                    if (target.visibility != View.GONE) {
                        target.isVisible = false
                    }
                }
            }
            // OnPreDraw 在每一帧真正绘制之前回调，alpha 不会被宿主的动画/布局重置覆盖
            true
        })

        view.viewTreeObserver.addOnPreDrawListener(onDrawMaps[key])
    }

    private fun removeOnDraw(view: View?) {
        if (view == null) {
            XplerLog.d("removeOnDraw", "view == null")
            return
        }

        val key = Integer.toHexString(System.identityHashCode(view))
        view.viewTreeObserver.removeOnPreDrawListener(onDrawMaps[key])
    }

    /** 给视频项根 View 挂一次布局监听，保证药丸刚变可见、还没画出来就被处理掉 */
    private fun addKeywordLayoutScan(root: View, rail: View?, keywords: List<String>) {
        if (keywordLayoutScans.containsKey(root)) {
            return
        }

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (scanningKeywordBlocks || !root.isAttachedToWindow) {
                return@OnGlobalLayoutListener
            }
            scanningKeywordBlocks = true
            try {
                scanKeywordBlocks(root, rail, keywords)
            } finally {
                scanningKeywordBlocks = false
            }
        }

        keywordLayoutScans[root] = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** 清掉已离屏视频项上的布局监听，避免长时间刷视频累积引用 */
    private fun pruneKeywordLayoutScans() {
        val iterator = keywordLayoutScans.entries.iterator()
        while (iterator.hasNext()) {
            val (view, listener) = iterator.next()
            if (!view.isAttachedToWindow) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                iterator.remove()
            }
        }
    }

    private fun testOnDraw(tag: String) {
        val array = onDrawMaps.map { "${it.key} = ${it.value}" }.toTypedArray()
        XplerLog.d(tag, *array)
    }

    private fun testAllOnDraw(view: View?) {
        if (view == null) {
            XplerLog.d("removeOnDraw", "view == null")
            return
        }

        val first = view.viewTreeObserver.findFieldGetValue<List<*>> { name("mOnDrawListeners") } ?: return
        XplerLog.d("监听集合", *first.map { "$it" }.toTypedArray())
    }

    private fun openAutoPlay(context: Context) {
        if (!config.isAutoPlay)
            return

        AutoPlayHelper.openAutoPlay(context)
    }

    private fun getContext(params: MethodParam): Context? {
        return params.thisObject?.findMethodInvoke<Context> { name("getContext") }
    }

    private fun getAllView(params: MethodParam): List<View?> {
        val views = params.thisObject?.findFieldGetValue<List<View?>> { type(View::class.java) }
        return views ?: emptyList()
    }

    private fun getWidgetContainer(params: MethodParam): PenetrateTouchRelativeLayout? {
        return params.thisObject?.findFieldGetValue<PenetrateTouchRelativeLayout> {
            type(PenetrateTouchRelativeLayout::class.java)
        }
    }

    /**
     * 定位右侧栏 `FeedRightScaleView`。
     *
     * 38.6.0 上按「字段类型是 View」反射取字段这条老路径取不到（实测每次返回 null，
     * 导致右侧栏过滤/半透明/连播按钮全部空转），所以补一条兜底：遍历整个继承链的
     * View 字段，挑子 View 最多的那个当视频项根，再按类名在子树里搜。
     *
     * 一律用**类名比较**而不是 `is FeedRightScaleView`：aweme 的代理类是 compileOnly，
     * 不打进 APK，运行时由抖音提供；若该类在目标版本不存在，`is`/`as` 会抛
     * NoClassDefFoundError，而这里跑在 post 回调中无人捕获，会直接打崩抖音。
     */
    private fun getFeedRightScaleView(params: MethodParam): ViewGroup? {
        val holder = params.thisObject ?: return null

        runCatching {
            val views = holder.findField { type(View::class.java, true) }?.getValues(holder)
            views?.firstOrNull { (it as? View)?.javaClass?.name == FEED_RIGHT_SCALE_VIEW }
                ?.let { return it as ViewGroup }
        }

        val root = collectViewFields(holder).maxByOrNull { (it as? ViewGroup)?.childCount ?: 0 }
            ?: return null
        var found: ViewGroup? = null
        root.forEachChild { child ->
            if (found == null && child.javaClass.name == FEED_RIGHT_SCALE_VIEW) {
                found = child as? ViewGroup
            }
        }
        return found
    }

    /** 收集整个继承链上所有 View 型字段的值 */
    private fun collectViewFields(holder: Any): List<View> {
        val out = mutableListOf<View>()
        var clazz: Class<*>? = holder.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
                if (value != null) out.add(value)
            }
            clazz = clazz.superclass
        }
        return out
    }

    private fun getFragment(params: MethodParam): Any? {
        return params.thisObject?.findFieldGetValue<Any> {
            type(hostClassloader!!.loadClass("androidx.fragment.app.Fragment"))
        }
    }

    private fun getFragmentType(params: MethodParam): String {
        val fragment = getFragment(params)
        val arguments = fragment?.findMethodInvoke<Bundle> { name("getArguments") }
        return arguments?.getString("com.ss.android.ugc.aweme.intent.extra.EVENT_TYPE") ?: ""
    }

    private fun changeFeedRightScaleView(params: MethodParam) {
        if (!config.isVideoOptionBarFilter) {
            return
        }

        val view = getFeedRightScaleView(params)
        view?.postRunning {
            val isAvatarImageWithLive = videoOptionBarFilterKeywords.pattern.contains("头像")
            view.forEachChild { child ->
                if (isAvatarImageWithLive && child.javaClass.name.contains("AvatarImageWithLive")) {
                    val parentView = child.firstParentOrNull(RelativeLayout::class.java)
                    parentView?.isVisible = false
                }

                if ("${child.contentDescription}".contains(videoOptionBarFilterKeywords)) {
                    val parentView = child.firstParentOrNull(FrameLayout::class.java)
                    parentView?.isVisible = false
                }

                if (child is TextView && "${child.text}".contains(videoOptionBarFilterKeywords)) {
                    val parentView = child.firstParentOrNull(FrameLayout::class.java)
                    parentView?.isVisible = false
                }
            }
            val isMusicContainer = videoOptionBarFilterKeywords.pattern.contains("音乐")
            view.getBrotherViewAt(1)?.isVisible = !isMusicContainer
        }
    }

    /**
     * 右侧栏之外的两侧控件（如右下角「拍同款 / 玩同款」、左下角标签行里的「点击推荐」）。
     *
     * 它们与 `FeedRightScaleView` 是**兄弟节点**（同在视频项根 View 下），不在栏内，
     * 所以 [changeFeedRightScaleView] 扫不到。38.6.0 实测结构：
     *  - 「拍同款 / 玩同款」：`FrameLayout #ack` > `RelativeLayout #ge` > `ViewGroup #z8` > `TextView #z=`
     *  - 「点击推荐」：简介块 > `FrameLayout #ace` > `LinearLayout #dcs` > `TagLayout #r6i` > `LinearLayout #fbg`
     *
     * 命中后隐藏其**父容器**（且尺寸要够小），既能整颗消失，又不会把音乐光盘、
     * 简介正文这类大块带走。
     *
     * 药丸要等音乐信息回来才从 GONE 翻成 VISIBLE（真机实测 1~4s），只在 bind 时扫一次必然漏，
     * 所以按 [KEYWORD_BLOCK_SCAN_DELAYS] 补扫几轮；扫描本身幂等，已隐藏的会跳过。
     */
    private fun hideKeywordBlocks(params: MethodParam) {
        if (!config.isVideoOptionBarFilter) {
            return
        }

        val keywords = videoOptionBarFilterKeywordList
        if (keywords.isEmpty()) {
            return
        }

        // 两侧控件挂在视频项根 View 上，不一定非得先拿到右侧栏：
        // bind 那一刻右侧栏经常还没 inflate 出来，以前在这里直接 return，导致新视频整条都不扫。
        val rail = getFeedRightScaleView(params)
        val container = getWidgetContainer(params)
        val anchor = container ?: rail ?: return
        val root = getItemRoot(anchor)
        if (rail == null) {
            XplerLog.d("VideoOptionBar", "rail not found, scan without rail")
        }

        addKeywordLayoutScan(root, rail, keywords)

        KEYWORD_BLOCK_SCAN_DELAYS.forEach { delay ->
            if (delay == 0L) {
                root.postRunning { scanKeywordBlocks(root, rail, keywords) }
            } else {
                root.postDelayedRunning(delay) {
                    // item 可能已被回收，别在离屏视图上白扫
                    if (!root.isAttachedToWindow) return@postDelayedRunning
                    scanKeywordBlocks(root, rail, keywords)
                }
            }
        }
    }

    /** 扫一遍视频项，把命中关键字的「小控件块」隐藏掉（可重复调用） */
    private fun scanKeywordBlocks(root: View, rail: View?, keywords: List<String>) {
        // ViewHolder 回收后旧 View 会 detach，顺手清掉，避免越积越多
        keywordTargets.removeAll { !it.isAttachedToWindow }
        pruneKeywordLayoutScans()

        val maxWidth = root.width * 0.45f
        val maxHeight = root.height * 0.2f
        if (maxWidth <= 0f || maxHeight <= 0f) {
            return
        }

        root.forEachChild { child ->
            if (child === root || (rail != null && child === rail)) return@forEachChild

            val text = (child as? TextView)?.text?.toString()?.trim().orEmpty()
            val description = child.contentDescription?.toString().orEmpty()
            val parent = child.parent as? ViewGroup

            // 标签行（TagLayout）里的文案会带数量/前后缀（「共 1392 人推荐」），只在这一层允许「包含」匹配；
            // 其余位置仍要求文本完全相等，简介正文、地点 chip 因此从结构上不可能被误命中。
            val textKeyword = keywords.firstOrNull { it == text }
                ?: if (child.isInsideTagLayout()) {
                    keywords.firstOrNull { text.isNotEmpty() && text.contains(it) }
                } else {
                    null
                }
            val keyword = textKeyword
                ?: keywords.firstOrNull { description.isNotEmpty() && description.contains(it) }
                ?: return@forEachChild

            // 尺寸判定：
            //  1. 命中的节点本身就是「紧凑控件」（图标 + 文字、没有子容器）时直接藏它，
            //     不再往上爬到 TagLayout 这种整行容器 —— 整行藏掉会把同行的其它标签一起带走；
            //  2. 否则退回父容器（够小才藏），父容器还没测量（宿主刚 inflate、当前还是 GONE）
            //     时按结构先藏，等宿主把父级设成 VISIBLE 也不会覆盖子级的 GONE；
            //  3. 其余（命中在简介正文这类大块里）不动，避免把整段文案隐藏掉。
            val target = when {
                child is ViewGroup && child.childCount in 2..4 &&
                        !child.hasViewGroupChild() &&
                        child.isSmallBlock(maxWidth, maxHeight) -> child
                parent == null -> null
                parent.isSmallBlock(maxWidth, maxHeight) -> parent
                parent.width <= 0 || parent.height <= 0 ->
                    if (parent.childCount in 2..4) parent else null
                child.isSmallBlock(maxWidth, maxHeight) -> child
                else -> null
            } ?: return@forEachChild

            if (target.visibility == View.GONE) return@forEachChild

            target.isVisible = false
            if (target !in keywordTargets) {
                keywordTargets.add(target)
            }
            // 摘除宿主 View 有风险：抖音的 AsyncQuery 会延迟按结构查子 View，
            // 摘掉 TagLayout 这类容器会 NPE（38.6.0 实测两次：摘完 3 秒 / 2 分钟内必崩）。
            // 只对「右半边的紧凑控件」（拍同款/玩同款药丸）保留 detach 防闪，其余一律只设 GONE。
            val targetGroup = target as? ViewGroup
            val detachAllowed = target.parent is ViewGroup &&
                    targetGroup != null &&
                    targetGroup.childCount <= 4 &&
                    !targetGroup.hasViewGroupChild() &&
                    target.absoluteCenterX() > root.width / 2f
            val detached = if (detachAllowed) {
                runCatching {
                    val container = target.parent as? ViewGroup ?: return@runCatching false
                    container.removeView(target)
                    target.parent == null
                }.getOrDefault(false)
            } else {
                false
            }
            XplerLog.d(
                "VideoOptionBar",
                "hide ${target.javaClass.name}#${target.id} by $keyword " +
                        "matched=${if (text.isNotEmpty()) text else "desc:$description"} detached=$detached"
            )
        }
    }

    /**
     * 地点等「锚点」标签透明（全屏沉浸二级项，默认透明）。
     *
     * 38.6.0 里地点 chip 是 `com.ss.android.ugc.aweme.views.RoundedLinearLayout`
     * （圆角半径 96），外面包 `MaxWidthFrameLayout`，深色底来自它自身的 background，
     * 直接把 background 清掉即可。锚点内容是异步加载的，所以首扫之外再补一次。
     *
     * 注意：商品/小程序等同属 anchor 的卡片用的是同一个类，也会一起变透明。
     */
    private fun applyAnchorTransparency(params: MethodParam) {
        if (!config.isImmersive || config.isLocationAnchorOpaque) {
            return
        }

        val container = getWidgetContainer(params) ?: return
        val root = getItemRoot(container)

        root.postRunning { clearAnchorBackgrounds(root, 0) }
        root.postDelayedRunning(600L) {
            if (!root.isAttachedToWindow) return@postDelayedRunning
            clearAnchorBackgrounds(root, 0)
        }
    }

    private fun clearAnchorBackgrounds(view: View, depth: Int) {
        if (depth > 12) return

        if (view.javaClass.name == ANCHOR_ROUNDED_LAYOUT &&
            view.background != null &&
            view.isInsideAnchor()
        ) {
            view.background = null
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                clearAnchorBackgrounds(view.getChildAt(index), depth + 1)
            }
        }
    }

    /** 祖先里有 anchor 容器才算锚点，避免误清其它 RoundedLinearLayout */
    private fun View.isInsideAnchor(): Boolean {
        var node: View? = this
        var level = 0
        while (node != null && level < 4) {
            val name = node.javaClass.name
            if (name.contains("MaxWidthFrameLayout") || name.contains("FeedAnchorLayout")) return true
            node = node.parent as? View
            level++
        }
        return false
    }

    /** 从 View 的 context 里取 Activity 简单类名（日志归属页面用） */
    private fun android.content.Context.activitySimpleName(): String {
        var ctx: android.content.Context? = this
        var level = 0
        while (ctx != null && level < 6) {
            if (ctx is Activity) return ctx.javaClass.simpleName
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
            level++
        }
        return javaClass.simpleName
    }

    /** 在子树里按类名找第一个 View（找不到返回 null） */
    private fun View.findFirstChildOfClass(className: String): View? {
        var found: View? = null
        runCatching {
            forEachChild { child ->
                if (found == null && child.javaClass.name == className) {
                    found = child
                }
            }
        }.onFailure { XplerLog.e(it) }
        return found
    }

    private fun View.isSmallBlock(maxWidth: Float, maxHeight: Float): Boolean {
        return width in 1..maxWidth.toInt() && height in 1..maxHeight.toInt()
    }

    /** 祖先里是否有 TagLayout（标签行）。只有标签行内的文字才允许「包含」匹配 */
    private fun View.isInsideTagLayout(): Boolean {
        var current: View? = this
        var level = 0
        while (current != null && level < 5) {
            if (current.javaClass.name.contains("TagLayout")) return true
            current = current.parent as? View
            level++
        }
        return false
    }

    /** 自身是否还挂着子容器（有子容器说明是「一行/一块」容器，不能从宿主布局里摘） */
    private fun View.hasViewGroupChild(): Boolean {
        if (this !is ViewGroup) return false
        for (index in 0 until childCount) {
            if (getChildAt(index) is ViewGroup) return true
        }
        return false
    }

    /** 控件在窗口中的水平中心，用来区分左半边（标签行）与右半边（药丸） */
    private fun View.absoluteCenterX(): Float {
        val location = IntArray(2)
        getLocationInWindow(location)
        return location[0] + width / 2f
    }

    /**
     * 从右侧栏往上找到视频项根 View（feed RecyclerView 的直接子 View），不依赖 aweme 类。
     *
     * 加层数上限：万一宿主没有用 RecyclerView 承载 feed，不能一路爬到 decor 去扫整窗。
     */
    private fun getItemRoot(view: View): View {
        var current: View = view
        var level = 0
        while (level < 8) {
            val parent = current.parent as? View ?: return current
            if (parent.javaClass.name.contains("RecyclerView")) return current
            current = parent
            level++
        }
        return current
    }

    private fun changeFeedRightScaleViewAlpha(params: MethodParam) {
        if (!config.isTranslucent) {
            return
        }

        val view = getFeedRightScaleView(params) ?: return

        // 容器实例不猜：直接压右侧栏最近的 PenetrateTouchRelativeLayout，
        // 再按固定比值 [2]/[1] 设右侧栏，保证最终可见恒等于「视频右侧控件」。
        applyFeedRightScaleAlpha(view)

        // 容器上限由 addOnDraw 在绘制时施加，可能晚于这一刻，再补一次
        view.postDelayed({
            runCatching { applyFeedRightScaleAlpha(view) }.onFailure { XplerLog.e(it) }
        }, 400L)
    }

    /**
     * 只压控件本身：
     *  - 右侧栏（含音乐容器）= 「视频右侧控件」；
     *  - 左侧简介块（AwemeIntroInfoLayout）= 「视频控件」。
     *
     * 不再压 PenetrateTouchRelativeLayout —— 它把视频画面也包在里面，
     * 压它会让整条视频变暗（表现为「首页亮度明显低于状态栏」）。
     */
    private fun applyFeedRightScaleAlpha(view: View) {
        if (!config.isTranslucent) return
        val values = config.translucentValue
        val leftValue = values[1].coerceIn(0, 100) / 100f
        val railValue = values[2].coerceIn(0, 100) / 100f

        if (view.alpha != railValue) view.alpha = railValue
        view.getBrotherViewAt(1)?.let { music ->
            if (music.alpha != railValue) music.alpha = railValue
        }

        val intros = view.findIntroLayouts()
        for (intro in intros) {
            if (intro.alpha != leftValue) intro.alpha = leftValue
        }

        XplerLog.d(
            "Alpha",
            "act=${view.context.activitySimpleName()} rail#${view.id} railAlpha=$railValue " +
                    "intros=${intros.joinToString { it.javaClass.simpleName + '#' + it.id + '@' + it.alpha }} " +
                    "introAlpha=$leftValue"
        )
    }

    /** 底 bar 槽位（id 名 bs）：相关搜索 / 上升热点那一行，不在简介块里 */
    private fun View.findBottomBarSlots(): List<View> {
        return runCatching {
            val id = context.resources.getIdentifier("bs", "id", context.packageName)
            if (id == 0) return@runCatching emptyList<View>()
            val found = mutableListOf<View>()
            rootView.forEachChild { child ->
                if (child.id == id) found.add(child)
            }
            // 只保留最外层，避免父子同时压成 0.5 × 0.5
            found.filter { candidate ->
                found.none { other -> other !== candidate && candidate.isUnder(other) }
            }
        }.getOrDefault(emptyList())
    }

    /** 右侧栏所在 item 里的左侧简介块（AwemeIntroInfoLayout + FeedDescRootLayout 都收） */
    private fun View.findIntroLayouts(): List<View> {
        val root = getItemRoot(this)
        val out = mutableListOf<View>()
        runCatching {
            root.forEachChild { child ->
                val name = child.javaClass.name
                if (name == FEED_INTRO_LAYOUT || name == FEED_DESC_ROOT_LAYOUT) out.add(child)
            }
        }.onFailure { XplerLog.e(it) }

        // FeedDescRootLayout 通常就在 AwemeIntroInfoLayout 里面：
        // 两个都设 alpha 会叠成 25%，只保留最外层那个。
        return out.filter { candidate ->
            out.none { other -> other !== candidate && candidate.isUnder(other) }
        }
    }

    /** this 是否在 ancestor 的子树里（最多 12 层） */
    private fun View.isUnder(ancestor: View): Boolean {
        var node: View? = this.parent as? View
        var level = 0
        while (node != null && level < 12) {
            if (node === ancestor) return true
            node = node.parent as? View
            level++
        }
        return false
    }

    private fun adjustMusicContainer(params: MethodParam) {
        val view = getFeedRightScaleView(params)
        val isMusicContainer = view?.getBrotherViewAt(1)?.asOrNull<FrameLayout>()
        isMusicContainer?.setLayoutWidth(52f.dip2px())
    }

    @SuppressLint("SetTextI18n")
    private fun addAutoPlayButtonView(params: MethodParam) {
        if (!config.isAutoPlay)
            return

        if (!config.addAutoPlayButton)
            return

        if (!getFragmentType(params).startsWith("homepage_hot"))
            return

        val view = getFeedRightScaleView(params) ?: return

        val isAdded = view.children.firstOrNull()?.tag == "AutoPlay"
        if (isAdded)
            return

        injectRes(view.context.resources)

        val autoPlayContainer = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                updatePaddingRelative(end = 8f.dip2px())
            }
            tag = "AutoPlay"
        }

        val autoPlayImage = ImageView(view.context).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_play))
            layoutParams = LinearLayout.LayoutParams(
                42f.dip2px(),
                42f.dip2px()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val autoPlayText = TextView(view.context).apply {
            text = "连播"
            textSize = 14f
            setTextColor(Color.parseColor("#E6FFFFFF"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        autoPlayContainer.addView(autoPlayImage)
        autoPlayContainer.addView(autoPlayText)
        autoPlayContainer.setOnClickListener { openAutoPlay(it.context) }

        view.addView(autoPlayContainer, 0)
    }

    @OnBefore("isCleanMode")
    fun isCleanModeBefore(params: MethodParam, view: View?, bool: Boolean) {
        hookBlockRunning(params) {
            if (!config.isNeatMode)
                return

            if (!config.neatModeState)
                return

            setResultVoid()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("openCleanMode")
    fun openCleanModeBefore(params: MethodParam, bool: Boolean) {
        hookBlockRunning(params) {
            if (!config.isNeatMode)
                return

            if (!config.neatModeState)
                return

            setResultVoid()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("getAweme")
    fun getAwemeAfter(params: MethodParam) {
        hookBlockRunning(params) {
            HVideoViewHolder.aweme = result?.asOrNull()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 两侧控件的统一刷新入口。
     *
     * 38.6.0 的 `VideoViewHolder` 没有 `startStayTime(Long)`（模块启动日志里是 NoSuchMethodException），
     * 真正的触发点改为 `bind` + `onViewHolderSelected`；`startStayTime` 只留给可能还存在的旧版本。
     */
    private fun refreshOptionBar(params: MethodParam) {
        changeFeedRightScaleView(params)
        changeFeedRightScaleViewAlpha(params)
        adjustMusicContainer(params)
        addAutoPlayButtonView(params)
        applyAnchorTransparency(params)
        hideKeywordBlocks(params)
    }

    @OnAfter("bind")
    fun bindAfter(params: MethodParam) {
        hookBlockRunning(params) {
            refreshOptionBar(params)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter
    fun startStayTime(params: MethodParam, long: Long?) {
        hookBlockRunning(params) {
            // XplerLog.d("long: $long")
            refreshOptionBar(params)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onViewHolderSelected")
    fun onViewHolderSelectedAfter(params: MethodParam, index: Int) {
        hookBlockRunning(params) {
            // XplerLog.d("onViewHolderSelected")
            val container = getWidgetContainer(params)
            addOnDraw(container)
            refreshOptionBar(params)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onViewHolderUnSelected")
    fun onViewHolderUnSelectedAfter(params: MethodParam) {
        hookBlockRunning(params) {
            // XplerLog.d("onViewHolderUnSelected")
            val container = getWidgetContainer(params)
            removeOnDraw(container)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onPause")
    fun onPauseBefore(params: MethodParam) {
        hookBlockRunning(params) {
            val container = getWidgetContainer(params)
            removeOnDraw(container)
            onDrawMaps.clear()
            keywordTargets.removeAll { !it.isAttachedToWindow }
            pruneKeywordLayoutScans()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onResume")
    fun onResumeAfter(params: MethodParam) {
        hookBlockRunning(params) {
            val container = getWidgetContainer(params)
            addOnDraw(container)
            changeFeedRightScaleView(params)
            hideKeywordBlocks(params)
            addAutoPlayButtonView(params)
        }.onFailure {
            XplerLog.e(it)
        }
    }
}