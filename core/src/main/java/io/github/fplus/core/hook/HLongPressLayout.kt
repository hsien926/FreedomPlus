package io.github.fplus.core.hook

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.freegang.extension.asOrNull
import com.freegang.extension.findFieldGetValue
import com.freegang.extension.forEachWhereChild
import com.freegang.extension.forEachWhereParent
import com.freegang.extension.screenSize
import com.freegang.ktutils.log.KLogCat
import com.freegang.ktutils.other.KAutomationUtils
import com.freegang.ktutils.view.KViewUtils
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.helper.ModuleSettingHelper
import io.github.fplus.core.hook.logic.DownloadLogic
import io.github.fplus.core.hook.logic.cityInfo
import io.github.fplus.core.hook.logic.createDate
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisViewOrNull


class HLongPressLayout : BaseHook() {
    companion object {
        /**
         * 用类名比较替代代理类的 `is`/`as`。
         * aweme 的代理类是 compileOnly（不打进 APK），运行时类由抖音提供；
         * 若该类在目标版本不存在，`is`/`as` 会抛 NoClassDefFoundError。
         * 且这些代码多跑在主线程 post 回调里，无人捕获，会直接打崩抖音。
         */
        private const val PENETRATE_TOUCH_RELATIVE_LAYOUT =
            "com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout"

        /** 视频项根容器，同样只按类名比较：直接引用 `::class.java` 在缺类版本上会抛 NoClassDefFoundError */
        private const val VIDEO_VIEW_HOLDER_ROOT_VIEW =
            "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView"

        private const val TAG = "HLongPressLayout"

        /** 双击后检查播放状态的延时（ms）：抖音的「单击确认」一般在抬手后 ~300ms 触发 */
        private const val RESUME_CHECK_DELAY = 500L

        /** 评论按钮的 contentDescription，按「严 → 宽」排列，命中即用 */
        private val COMMENT_BUTTON_DESCRIPTIONS = listOf(
            Regex("评论(.*?)，按钮"),
            Regex("评论.*按钮"),
        )
        private val COLLECT_BUTTON_DESCRIPTIONS = listOf(Regex("收藏(.*?)，按钮"))
        private val SHARE_BUTTON_DESCRIPTIONS = listOf(Regex("分享(.*?)，按钮"))
    }

    /**
     * 双击命中后吞掉的事件范围。
     *
     * 抖音的原生双击点赞在 `LongPressLayout` 的触摸流程内部触发，与 [android.view.GestureDetector]
     * 一致：**第二次 ACTION_DOWN 就已经成立**。模块原先在 `@OnAfter("onTouchEvent")` 的第二次
     * ACTION_UP 才判定，等判定出来点赞早已执行完，拦不住（38.6.0 实测现象）。
     * 所以默认把「第二次手势」整体吞掉 —— 抖音侧收不到这次点击，原生点赞自然不会发生。
     * 这是唯一不依赖抖音内部实现（混淆类名/方法名）的拦截方式，各版本通用。
     *
     * - [SwallowPolicy.DOWN_AND_UP] 吞 DOWN/MOVE/UP（默认）
     * - [SwallowPolicy.UP_ONLY] 只吞 UP（若吞 DOWN 带来副作用，可切到这里对比验证）
     * - [SwallowPolicy.NONE] 不拦截，仅打开评论区（兜底，等同「双击 = 点赞 + 开评论」）
     */
    private enum class SwallowPolicy { DOWN_AND_UP, UP_ONLY, NONE }

    private val doubleTapSwallowPolicy = SwallowPolicy.DOWN_AND_UP

    private val config
        get() = ConfigV1.get()

    private val LONG_PRESS_TIME = ViewConfiguration.getLongPressTimeout()
    private val DOUBLE_TAP_TIME = ViewConfiguration.getDoubleTapTimeout()

    private var longPressRunnable: Runnable? = null

    /** 上一次「有效轻点」抬起时的 eventTime 与按下坐标；仅在按下未移动、且未变成长按时才有效 */
    private var lastTapUpTime: Long = 0
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f
    private var lastTapValid: Boolean = false

    /** 当前手势的按下时间、按下坐标、是否已被拖动 */
    private var downEventTime: Long = 0
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var gestureMoved: Boolean = false

    /** 当前手势已被判定为「双击的第二下」时，需要按策略吞掉的档位；null 表示不接管 */
    private var swallowPolicy: SwallowPolicy? = null

    /** 正在向宿主补发合成事件（CANCEL / 恢复播放的单击），这些事件不能再走状态机 */
    private var injectingEvent: Boolean = false

    private var touchSlopCache: Int = -1
    private var doubleTapSlopCache: Int = -1

    private var aweme: Aweme? = null

    override fun setTargetClass(): Class<*> {
        return findClass("com.ss.android.ugc.aweme.feed.ui.LongPressLayout")
    }

    /**
     * 触摸状态机。
     *
     * 放在 `@OnBefore` 而不是 `@OnAfter`：只有赶在抖音自己的 `onTouchEvent` 之前，
     * 才能通过 `setResult(true)` 把双击的第二下整体拦下来，原生点赞才不会触发。
     * 长按逻辑一并迁到这里 —— 原先的 `longPressRunnable` 只创建一次并把第一次
     * ACTION_DOWN 的 MotionEvent 永久缓存下来，导致长按的区域判定（上/下半屏）一直用旧坐标。
     */
    @OnBefore("onTouchEvent")
    fun onTouchEventBefore(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            // 模块自己补发的事件不参与状态机，否则会被当成一次真实轻点
            if (injectingEvent)
                return@hookBlockRunning

            val view = thisViewOrNull ?: return@hookBlockRunning

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onGestureDown(params, view, event)
                MotionEvent.ACTION_MOVE -> onGestureMove(params, view, event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onGestureUp(params, event)
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun onGestureDown(params: MethodParam, view: View, event: MotionEvent) {
        downEventTime = event.eventTime
        downX = event.x
        downY = event.y
        gestureMoved = false
        swallowPolicy = null

        // 双击判定与 GestureDetector 对齐：在第二次 ACTION_DOWN 就成立
        if (lastTapValid) {
            val interval = event.eventTime - lastTapUpTime
            val slop = doubleTapSlop(view.context)
            val samePlace = Math.abs(event.x - lastTapX) <= slop
                    && Math.abs(event.y - lastTapY) <= slop
            if (interval in 0..DOUBLE_TAP_TIME.toLong() && samePlace) {
                val policy = handleDoubleTap(view)
                if (policy != null) {
                    swallowPolicy = policy
                    if (policy == SwallowPolicy.DOWN_AND_UP) params.setResult(true)
                    cancelLongPress()
                    lastTapValid = false
                    return
                }
            }
        }

        cancelLongPress()
        val runnable = Runnable { onLongPress(view, downX, downY) }
        longPressRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_TIME.toLong())
    }

    private fun onGestureMove(params: MethodParam, view: View, event: MotionEvent) {
        if (swallowPolicy == SwallowPolicy.DOWN_AND_UP) {
            params.setResult(true)
            return
        }
        if (gestureMoved)
            return

        val slop = touchSlop(view.context)
        if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
            gestureMoved = true
            cancelLongPress()
        }
    }

    private fun onGestureUp(params: MethodParam, event: MotionEvent) {
        cancelLongPress()

        val policy = swallowPolicy
        swallowPolicy = null
        if (policy != null) {
            // 双击已由模块接管：吞掉抬起（DOWN_AND_UP 时 DOWN/MOVE 也已吞掉）
            params.setResult(true)
            lastTapValid = false
            return
        }

        val isTap = event.actionMasked == MotionEvent.ACTION_UP
                && !gestureMoved
                && event.eventTime - downEventTime < LONG_PRESS_TIME
        lastTapValid = isTap
        lastTapUpTime = if (isTap) event.eventTime else 0
        lastTapX = downX
        lastTapY = downY
    }

    /**
     * 双击命中后的处理；返回 null 表示「这次手势不接管，交给抖音」。
     */
    private fun handleDoubleTap(view: View): SwallowPolicy? {
        if (!config.isDoubleClickType)
            return null

        // 点赞模式: 完全保持抖音原生行为
        if (config.doubleClickType != 1)
            return null

        // 评论区详情页里再开一次评论没有意义
        if (HDetailPageFragment.isComment)
            return null

        val policy = doubleTapSwallowPolicy
        val pauseAfterOpen = config.doubleClickPauseVideo
        val wasPlaying = HPlayerController.isPlaying
        KLogCat.tagD(
            TAG,
            "双击命中: swallow=$policy, pause=$pauseAfterOpen, playing=$wasPlaying",
        )

        if (!pauseAfterOpen) {
            // 抖音没收到第二下，会在双击间隔之后把这一下当成「单击」→ 暂停播放。
            // 补一个 CANCEL 让它的手势状态机复位（多数实现会因此撤掉待确认的单击）
            sendCancelToHost(view)
        }

        handler.post { openCommentPanel(view) }

        if (!pauseAfterOpen && wasPlaying) {
            // 兜底：CANCEL 未必能拦住（取决于抖音的实现）。稍后检查一次播放状态，
            // 真的被暂停了再补一次单击把播放恢复回来
            handler.postDelayed({ resumeIfPaused(view) }, RESUME_CHECK_DELAY)
        }
        return policy
    }

    /** 向宿主补发一个 ACTION_CANCEL，让抖音的手势状态机复位 */
    private fun sendCancelToHost(view: View) {
        try {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            injectingEvent = true
            view.onTouchEvent(cancel)
            cancel.recycle()
        } catch (e: Throwable) {
            XplerLog.e(e)
        } finally {
            injectingEvent = false
        }
    }

    /**
     * 双击打开评论区后，如果视频被抖音当成「单击」暂停了，就补一次单击把播放切回来。
     * 直接派发给宿主 View（不走窗口命中测试），评论区已经打开也照样生效。
     */
    private fun resumeIfPaused(view: View) {
        try {
            if (HPlayerController.isPlaying)
                return

            KLogCat.tagD(TAG, "双击后视频被暂停, 补一次单击恢复播放")
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, downX, downY, 0)
            val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, downX, downY, 0)
            injectingEvent = true
            view.onTouchEvent(down)
            view.onTouchEvent(up)
            down.recycle()
            up.recycle()
        } catch (e: Throwable) {
            XplerLog.e(e)
        } finally {
            injectingEvent = false
        }
    }

    /**
     * 打开评论区。找按钮的链路已在 38.6.0 上验证可用（连点时能打开），
     * 这里只做降级与日志，方便后续版本漂移时定位。
     */
    private fun openCommentPanel(view: View) {
        try {
            val holderRootView = resolveHolderRoot(view, allowRootFallback = true)
            if (holderRootView == null) {
                KLogCat.tagW(TAG, "打开评论失败: 未找到视频项根容器")
                return
            }

            val clicked = onClickView(
                parent = holderRootView,
                targetContents = COMMENT_BUTTON_DESCRIPTIONS,
                skipClickThrottle = true,
            )
            if (clicked) {
                KLogCat.tagD(TAG, "打开评论成功")
            } else {
                KLogCat.tagW(TAG, "打开评论失败: 未找到评论按钮")
                KLogCat.tagW(TAG, "可用描述: ${describeChildren(holderRootView)}")
            }
        } catch (e: Throwable) {
            XplerLog.e(e)
        }
    }

    /**
     * 解析视频项根容器。类名比较失败时按需回退到整窗口根 View。
     */
    private fun resolveHolderRoot(view: View, allowRootFallback: Boolean): View? {
        var holder: View? = null
        view.forEachWhereParent { parent ->
            if (parent.javaClass.name == VIDEO_VIEW_HOLDER_ROOT_VIEW) {
                holder = parent
                true
            } else {
                false
            }
        }
        if (holder != null)
            return holder

        if (!allowRootFallback)
            return null

        KLogCat.tagW(TAG, "未命中根容器 $VIDEO_VIEW_HOLDER_ROOT_VIEW, 回退到窗口根 View")
        return view.rootView
    }

    /** 诊断用：列出子 View 上有意义的 contentDescription，便于新版本补匹配规则 */
    private fun describeChildren(parent: View): String {
        val descriptions = mutableListOf<String>()
        parent.forEachWhereChild {
            if (descriptions.size < 12) {
                val description = it.contentDescription?.toString().orEmpty()
                if (description.isNotBlank())
                    descriptions.add(description)
            }
            false
        }
        return descriptions.joinToString(" | ").ifEmpty { "无" }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun touchSlop(context: Context): Int {
        if (touchSlopCache < 0)
            touchSlopCache = ViewConfiguration.get(context).scaledTouchSlop
        return touchSlopCache
    }

    private fun doubleTapSlop(context: Context): Int {
        if (doubleTapSlopCache < 0)
            doubleTapSlopCache = ViewConfiguration.get(context).scaledDoubleTapSlop
        return doubleTapSlopCache
    }

    private fun onLongPress(view: View, x: Float, y: Float) {
        // 预留出长按快进
        if (x < screenSize.width / 8 || x > screenSize.width - screenSize.width / 8)
            return

        // 长按上半部分: 直接唤起模块设置
        if (config.isLongPressSetting && y < screenSize.height / 2) {
            ModuleSettingHelper.open(view.context)
            return
        }

        if (!config.isNeatMode)
            return

        // 上半部分被"长按唤起模块设置"占用时, 清爽模式菜单改为只从下半响应,
        // 避免同一个手势触发两个入口。
        val menuOnTop = config.longPressMode && !config.isLongPressSetting
        if (menuOnTop) {
            if (y > screenSize.height / 2)
                return
        } else {
            if (y < screenSize.height / 2)
                return
        }

        val holderRootView = resolveHolderRoot(view, allowRootFallback = false) as? ViewGroup ?: return
        showOptionsMenu(holderRootView)
    }

    private fun showOptionsMenu(view: ViewGroup) {
        if (HDetailPageFragment.isComment) return

        val items = getChoiceItems(view).toTypedArray()
        showChoiceDialog(
            context = view.context,
            title = "Freedom+",
            items = items,
            onChoice = { _, item, _ ->
                onChoiceSelected(view, item)
            }
        )
    }

    private fun getChoiceItems(view: View): List<String> {
        val items = mutableListOf("评论", "收藏", "分享")

        if (config.isDownload) {
            items.add("下载")
        }

        if (config.isNeatMode) {
            items.add(0, if (!config.neatModeState) "清爽模式" else "普通模式")
        }

        // if (config.isVideoFilter) {
        //     items.add("过滤统计")
        // }

        items.add("视频信息")

        if (!config.isDisablePlugin) {
            items.add("模块设置")
        }
        return items
    }

    private fun onChoiceSelected(view: View, item: CharSequence) {
        when (item) {
            "清爽模式", "普通模式" -> {
                config.neatModeState = !config.neatModeState
                toggleView(view, !config.neatModeState)
                showToast(view.context, if (config.neatModeState) "清爽模式" else "普通模式")
            }

            "评论" -> {
                onClickView(view, targetContents = COMMENT_BUTTON_DESCRIPTIONS)
            }

            "收藏" -> {
                onClickView(view, targetContents = COLLECT_BUTTON_DESCRIPTIONS)
            }

            "分享" -> {
                onClickView(view, targetContents = SHARE_BUTTON_DESCRIPTIONS)
            }

            "下载" -> {
                DownloadLogic(
                    hook = this@HLongPressLayout,
                    context = view.context,
                    aweme = aweme,
                )
            }

            "视频信息" -> {
                singleLaunchMain("$item") {
                    val msg = "视频属地: ${aweme?.cityInfo()}\n发布时间: ${aweme?.createDate()}".trim()
                    showMessageDialog(
                        context = view.context,
                        title = "视频信息",
                        content = msg,
                        singleButton = true,
                    )
                }
            }

            "过滤统计" -> {
                val builder = StringBuilder()
                if (HVerticalViewPager.filterLiveCount > 0) {
                    builder.append("直播过滤: ")
                        .append(HVerticalViewPager.filterLiveCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterImageCount > 0) {
                    builder.append("图文过滤: ")
                        .append(HVerticalViewPager.filterImageCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterAdCount > 0) {
                    builder.append("广告过滤: ")
                        .append(HVerticalViewPager.filterAdCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterLongVideoCount > 0) {
                    builder.append("长视频过滤: ")
                        .append(HVerticalViewPager.filterLongVideoCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterRecommendedCardsCount > 0) {
                    builder.append("推荐卡片过滤: ")
                        .append(HVerticalViewPager.filterRecommendedCardsCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterRecommendedMerchantsCount > 0) {
                    builder.append("推荐商家过滤: ")
                        .append(HVerticalViewPager.filterRecommendedMerchantsCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterEmptyDescCount > 0) {
                    builder.append("空文案过滤: ")
                        .append(HVerticalViewPager.filterEmptyDescCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterOtherCount > 0) {
                    builder.append("关键字过滤: ").append(HVerticalViewPager.filterOtherCount)
                }
                val msg = builder.toString().trim()
                if (msg.isEmpty()) {
                    showToast(view.context, "未过滤视频")
                } else {
                    showToast(view.context, msg)
                }
            }

            "模块设置" -> {
                ModuleSettingHelper.open(view.context)
            }
        }
    }

    private fun toggleView(view: View, visible: Boolean) {
        // VideoViewHolderRootView extends ViewGroup，改用系统类型，避免引用代理类
        val viewHolderRootView = view as? ViewGroup ?: return
        val monitorScrollFrameLayout = viewHolderRootView.children.lastOrNull {
            it.javaClass.name.contains("MonitorScrollFrameLayout")
        }?.asOrNull<ViewGroup>()

        // 清爽模式
        monitorScrollFrameLayout?.children?.forEach {
            if (it.javaClass.name == PENETRATE_TOUCH_RELATIVE_LAYOUT) {
                it.isVisible = visible
            }
        }

        HMainActivity.toggleView(visible)
    }

    /**
     * 在 [parent] 子树里找到目标控件并触发点击。
     *
     * @param targetContents contentDescription 匹配规则，可给多条（严 → 宽）
     * @param skipClickThrottle 跳过 200ms 防抖。双击路径必须跳过，否则连点两下时
     *        第二下会被当成快速点击吃掉（38.6.0 上表现为「点了没反应」）
     * @return 是否已经点击了某个控件
     */
    private fun onClickView(
        parent: View,
        targetView: Class<out View>? = null,
        targetText: Regex = Regex(""),
        targetHint: Regex = Regex(""),
        targetContents: List<Regex> = emptyList(),
        skipClickThrottle: Boolean = false,
    ): Boolean {
        var clicked = false
        parent.forEachWhereChild {
            if (clicked)
                return@forEachWhereChild true

            var needClick = false
            if (it is TextView) {
                needClick = "${it.text}".containsNotEmpty(targetText)
                needClick = needClick || "${it.hint}".containsNotEmpty(targetHint)
            }
            val contentDescription = "${it.contentDescription}"
            needClick = needClick || targetContents.any { content -> contentDescription.containsNotEmpty(content) }
            needClick = needClick || (targetView?.isInstance(it) ?: false)
            if (!needClick || !it.isShown)
                return@forEachWhereChild false

            // 是否具有点击事件
            val onClickListener = KViewUtils.getOnClickListener(it)
            if (onClickListener != null) {
                if (skipClickThrottle || !KViewUtils.isFastClick(200L)) {
                    onClickListener.onClick(it)
                    clicked = true
                }
                return@forEachWhereChild clicked
            }

            // 模拟手势
            val location = IntArray(2) { 0 }
            it.getLocationOnScreen(location)
            if (location[1] > 0 && location[1] < screenSize.height) { // 是否在屏幕内
                location[0] = location[0] + it.right / 2
                location[1] = location[1] + it.bottom / 2
                if (skipClickThrottle || !KViewUtils.isFastClick(200)) {
                    KAutomationUtils.simulateClickByView(it, location[0].toFloat(), location[1].toFloat())
                    clicked = true
                }
                return@forEachWhereChild clicked
            }
            false
        }
        return clicked
    }

    private fun CharSequence.containsNotEmpty(regex: Regex): Boolean {
        if (regex.pattern.isEmpty()) return false
        return contains(regex)
    }

    override fun onInit() {

        // 长按菜单事件拦截
        DexkitBuilder.longPressEventClazz?.also {
            lparam.hookClass(it)
                // 40.6.0 适配：原方法名 onLongPressAwemeSure 在 40.6.0 全库 0 命中，
                // 且新实现的方法名是混淆的（X/0UWT 里为 LIZ）。故改为按参数类型绑定 ——
                // (float, float) 是该长按回调接口方法（X/0w4F 的唯一方法 LIZ(FF)V）的签名。
                .methodAllByParamTypes(
                    Float::class.java,
                    Float::class.java,
                ) {
                    onBefore {
                        val x = args[0] as Float
                        val y = args[1] as Float

                        // 长按上半部分唤起模块设置, 同时拦截抖音原生长按弹窗
                        if (config.isLongPressSetting && y < screenSize.height / 2) {
                            setResultVoid()
                            return@onBefore
                        }

                        if (!config.isNeatMode)
                            return@onBefore

                        aweme = thisObject?.findFieldGetValue { type(Aweme::class.java) }
                            ?: HVideoViewHolder.aweme

                        // 与 onLongPress 的区域判定保持一致: 上半被模块设置占用时,
                        // 清爽模式菜单的拦截区改为下半。
                        val menuOnTop = config.longPressMode && !config.isLongPressSetting
                        if (menuOnTop) {
                            if (y < screenSize.height / 2)
                                setResultVoid()
                        } else {
                            if (y > screenSize.height / 2)
                                setResultVoid()
                        }
                    }
                }
        }

        // 双击点赞事件拦截
        DexkitBuilder.doubleClickEventClazz?.also {
            lparam.hookClass(it)
                .methodAllByParamTypes(
                    View::class.java,
                    MotionEvent::class.java,
                    MotionEvent::class.java,
                    MotionEvent::class.java,
                ) {
                    onBefore {
                        if (!config.isDoubleClickType)
                            return@onBefore

                        if (config.doubleClickType == 1)
                            setResultVoid()
                    }
                }
        }
    }
}