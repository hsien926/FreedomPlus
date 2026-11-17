// Kotlin · HLongPressLayout.kt · Android API 24 / Xposed · 按 View 隔离触摸会话
package io.github.fplus.core.hook

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
import io.github.fplus.core.helper.TapGestureState
import java.lang.ref.WeakReference
import java.util.WeakHashMap
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

        /** 评论按钮的 contentDescription，按「严 → 宽」排列，命中即用 */
        private val COMMENT_BUTTON_DESCRIPTIONS = listOf(
            Regex("评论(.*?)，按钮"),
            Regex("评论.*按钮"),
        )
        private val COLLECT_BUTTON_DESCRIPTIONS = listOf(Regex("收藏(.*?)，按钮"))
        private val SHARE_BUTTON_DESCRIPTIONS = listOf(Regex("分享(.*?)，按钮"))
    }

    /**
     * 双击命中后对「第二次手势」的处理方式。
     *
     * 抖音的原生双击点赞在 `LongPressLayout` 的触摸流程内部触发，与 [android.view.GestureDetector]
     * 一致：**第二次 ACTION_DOWN 就已经成立**，所以在 `@OnAfter` 的第二次 UP 才判定是拦不住的。
     *
     * 关键词：抖音把「只收到一次完整抬手」当成单击 → 暂停播放；而它只有在收到下一次
     * ACTION_DOWN 时才会撤掉这个「待确认的单击」。因此：
     *
     * - [SwallowPolicy.REBOUND_DOWN]（保持播放）：把第二次 DOWN 的横坐标挪到屏幕另一端再放行 ——
     *   抖音据此撤掉待确认的单击（**不暂停**），又因为位置对不上不会判定为双击（**不点赞**）；
     *   随后的 MOVE/UP 仍然吞掉，免得它又补一次单击确认。
     * - [SwallowPolicy.SWALLOW_ALL]（暂停播放）：整体吞掉，抖音就把前面那一下当单击，顺带暂停。
     */
    private enum class SwallowPolicy { REBOUND_DOWN, SWALLOW_ALL }

    private val config
        get() = ConfigV1.get()

    private val LONG_PRESS_TIME = ViewConfiguration.getLongPressTimeout()
    private val DOUBLE_TAP_TIME = ViewConfiguration.getDoubleTapTimeout()

    private class ViewState(timeout: Long) {
        val tap = TapGestureState(timeout)
        var long_press: Runnable? = null
        var policy: SwallowPolicy? = null
        var injecting = false
        var rebound_event: MotionEvent? = null
        var rebound_dx = 0f
    }

    private val states = WeakHashMap<View, ViewState>()
    private val detach_listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            states.remove(view)?.let { cancelLongPress(it); it.tap.cancel() }
            view.removeOnAttachStateChangeListener(this)
        }
    }
    private var aweme: Aweme? = null

    override fun setTargetClass(): Class<*> = findClass("com.ss.android.ugc.aweme.feed.ui.LongPressLayout")

    @OnBefore("onTouchEvent")
    fun onTouchEventBefore(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            val view = thisViewOrNull ?: return@hookBlockRunning
            val state = states.getOrPut(view) {
                view.addOnAttachStateChangeListener(detach_listener)
                ViewState(LONG_PRESS_TIME.toLong())
            }
            if (state.injecting) return@hookBlockRunning
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onGestureDown(params, view, state, event)
                MotionEvent.ACTION_MOVE -> {
                    if (state.tap.move(event.x, event.y, ViewConfiguration.get(view.context).scaledTouchSlop)) {
                        cancelLongPress(state)
                    }
                    if (state.policy != null) params.setResult(true)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    state.tap.multiple()
                    cancelLongPress(state)
                    if (state.policy != null) params.setResult(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(state)
                    state.tap.finish(event.eventTime, event.actionMasked == MotionEvent.ACTION_CANCEL)
                    if (state.policy != null) params.setResult(true)
                    state.policy = null
                }
            }
        }.onFailure { XplerLog.e(it) }
    }

    @OnAfter("onTouchEvent")
    fun onTouchEventAfter(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            val view = thisViewOrNull ?: return@hookBlockRunning
            val state = states[view] ?: return@hookBlockRunning
            if (state.injecting || state.rebound_event !== event) return@hookBlockRunning
            val dx = state.rebound_dx
            state.rebound_event = null
            state.rebound_dx = 0f
            // 不污染父容器继续使用的原始 MotionEvent；宿主已用副本记录第二次 DOWN。
            event.offsetLocation(-dx, 0f)
            // 第二次 DOWN 撤掉首击确认后立即 CANCEL，关闭宿主长按和 pressed 状态。
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            state.injecting = true
            try { view.onTouchEvent(cancel) } finally { cancel.recycle(); state.injecting = false }
            if (!params.hasThrowable) params.setResult(true)
        }.onFailure { XplerLog.e(it) }
    }

    private fun onGestureDown(params: MethodParam, view: View, state: ViewState, event: MotionEvent) {
        // 另一个 View 收到新手势时，旧 View 的延时长按不能继续生效。
        states.entries.toList().forEach { (other_view, other_state) ->
            cancelLongPress(other_state)
            if (other_view !== view) other_state.tap.cancel()
        }
        state.policy = null
        val configuration = ViewConfiguration.get(view.context)
        val media_id = currentMediaId()
        val candidate = state.tap.begin(event.eventTime, event.x, event.y,
            DOUBLE_TAP_TIME.toLong(), configuration.scaledDoubleTapSlop, media_id)
        if (candidate && config.isDoubleClickType && config.doubleClickType == 1 && !HDetailPageFragment.isComment) {
            val keep_paused = !HPlayerController.isPlaying
            val policy = if (config.doubleClickPauseVideo && !keep_paused) SwallowPolicy.SWALLOW_ALL else SwallowPolicy.REBOUND_DOWN
            // 太窄的容器无法在内部构造足够远的 DOWN，未知布局直接保留原生响应。
            if ((policy == SwallowPolicy.SWALLOW_ALL || view.width > configuration.scaledDoubleTapSlop * 2 + 2) &&
                hasCommentButton(view)) {
                state.tap.consume()
                state.policy = policy
                val generation = state.tap.generation
                val weak_view = WeakReference(view)
                handler.post {
                    val target = weak_view.get()
                    if (target != null && target.isAttachedToWindow && target.isShown &&
                        states[target] === state && state.tap.generation == generation && currentMediaId() == media_id) {
                        openCommentPanel(target)
                    }
                }
                if (policy == SwallowPolicy.SWALLOW_ALL) {
                    params.setResult(true)
                } else {
                    val target_x = if (event.x < view.width / 2f) view.width - 1f else 1f
                    state.rebound_dx = target_x - event.x
                    state.rebound_event = event
                    event.offsetLocation(state.rebound_dx, 0f)
                }
                return
            }
        }
        val x = event.x
        val y = event.y
        val generation = state.tap.generation
        val weak_view = WeakReference(view)
        val runnable = Runnable {
            val target = weak_view.get()
            if (target != null && target.isAttachedToWindow && target.isShown && state.tap.active &&
                !state.tap.moved && state.tap.generation == generation && currentMediaId() == media_id) {
                state.tap.long_press()
                onLongPress(target, x, y)
            }
        }
        state.long_press = runnable
        handler.postDelayed(runnable, LONG_PRESS_TIME.toLong())
    }

    private fun currentMediaId(): String? = HPlayerController.playingAid?.takeIf { it.isNotBlank() }
        ?: runCatching { HVideoViewHolder.aweme?.aid }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun hasCommentButton(view: View): Boolean {
        val nearest = resolveHolderRoot(view, allowRootFallback = false)
        val root = nearest ?: view.rootView
        var count = 0
        root.forEachWhereChild {
            val rect = android.graphics.Rect()
            if (it.isShown && it.isEnabled && it.getGlobalVisibleRect(rect) &&
                COMMENT_BUTTON_DESCRIPTIONS.any { pattern -> it.contentDescription?.contains(pattern) == true }) count++
            false
        }
        // 窗口级兜底只在唯一可见评论入口时使用，防止误开预加载视频。
        return if (nearest != null) count > 0 else count == 1
    }

    private fun openCommentPanel(view: View) {
        try {
            if (!hasCommentButton(view)) return
            val root = resolveHolderRoot(view, allowRootFallback = true) ?: return
            if (!onClickView(root, targetContents = COMMENT_BUTTON_DESCRIPTIONS, skipClickThrottle = true)) {
                KLogCat.tagW(TAG, "打开评论失败: 未找到可点击入口")
            }
        } catch (error: Exception) { XplerLog.e(error) }
    }

    private fun resolveHolderRoot(view: View, allowRootFallback: Boolean): View? {
        var holder: View? = null
        view.forEachWhereParent { parent ->
            if (parent.javaClass.name == VIDEO_VIEW_HOLDER_ROOT_VIEW) { holder = parent; true } else false
        }
        return holder ?: if (allowRootFallback) view.rootView else null
    }

    private fun cancelLongPress(state: ViewState) {
        state.long_press?.let { handler.removeCallbacks(it) }
        state.long_press = null
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
                location[0] = location[0] + it.width / 2
                location[1] = location[1] + it.height / 2
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

        // 双击只由当前 View 的触摸链路处理；不再全局阻断未接管页面的点赞回调。
    }
}
