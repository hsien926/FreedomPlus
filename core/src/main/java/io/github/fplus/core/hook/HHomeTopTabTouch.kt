package io.github.fplus.core.hook

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.freegang.extension.forEachChild
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * tab 误触复确认：底部「首页」+ 顶部「推荐」。
 *
 * 两处 tab 都不是普通的点击链路：
 *  - 顶部 `HomeTopTabItemViewNew` 自己实现 `View.OnTouchListener`，点击逻辑在混淆类里；
 *  - 底部 `BottomTab` 把点击委托给内部 delegate，点当前「首页」刷新不会经过任何可 hook 的入口。
 *
 * 所以统一改成**包监听**：谁给这两类 tab 设触摸/点击监听，就替换成"先问一句再转发"。
 * 取消就吞掉这次操作，确定才把原始回调执行一次。
 *
 * 底部"当前是不是首页"直接读宿主状态（`MainTab.getSelectedTab()`）：宿主切 tab 时会把目标
 * tab 名写进新旧两个 tab，所以首页 tab 上读到 HOME 就说明当前在首页。不要用 `isSelected()`：
 * 38.6 把它重写成 `operator.LIZJ() || d`，掺了全局能力判断，别的 tab 上也可能返回 true。
 * 也不再依赖 `HomeBottomTabServiceImpl`——该类在 40.x 已不存在。
 */
class HHomeTopTabTouch : BaseHook() {
    companion object {
        private const val TOP_TAB_ITEM = "com.ss.android.ugc.aweme.homepage.tab.ui.HomeTopTabItemViewNew"
        private const val BOTTOM_TAB_ITEM = "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab"
        private const val LABEL_HOME = "首页"
        private const val LABEL_RECOMMEND = "推荐"

        /** 超过这个按住时长就当作长按，交给宿主自己处理 */
        private const val LONG_PRESS_THRESHOLD_MS = 400L
    }

    private val config get() = ConfigV1.get()

    /** 防止我们自己的 setXxxListener 再次进入 hook */
    private var wrapping = false

    /** 弹窗未收起前不重复弹 */
    private var dialogShowing = false

    override fun setTargetClass(): Class<*> = NoneHook::class.java

    init {
        wrapTouchListener()
        wrapClickListener()
    }

    private fun wrapTouchListener() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setOnTouchListener", "android.view.View\$OnTouchListener") {
                    onAfter {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (wrapping || !isTargetTab(view)) return@hookBlockRunning

                            val original = args[0] as? View.OnTouchListener ?: return@hookBlockRunning

                            // 每个 tab 项一份按下时间：用来区分"轻点"和"长按"
                            val downAt = LongArray(1)
                            wrapping = true
                            try {
                                view.setOnTouchListener { v, event ->
                                    handleTouch(v, event, original, downAt)
                                }
                            } finally {
                                wrapping = false
                            }
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }

    private fun wrapClickListener() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setOnClickListener", "android.view.View\$OnClickListener") {
                    onAfter {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (wrapping || !isTargetTab(view)) return@hookBlockRunning

                            val original = args[0] as? View.OnClickListener ?: return@hookBlockRunning

                            wrapping = true
                            try {
                                view.setOnClickListener { v ->
                                    val label = tabLabel(v)
                                    if (!shouldConfirm(v, label)) {
                                        original.onClick(v)
                                        return@setOnClickListener
                                    }
                                    if (dialogShowing) return@setOnClickListener
                                    askConfirm(v, label) {
                                        original.onClick(v)
                                    }
                                }
                            } finally {
                                wrapping = false
                            }
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }

    private fun handleTouch(
        view: View,
        event: MotionEvent,
        original: View.OnTouchListener,
        downAt: LongArray,
    ): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downAt[0] = event.eventTime
        }
        if (!config.isPreventAccidentalTouch) return original.onTouch(view, event)
        if (event.actionMasked != MotionEvent.ACTION_UP) return original.onTouch(view, event)

        // 按住超过长按阈值：交还给宿主，避免和它自己的长按菜单（如「设置单击首页操作」）打架
        if (downAt[0] > 0 && event.eventTime - downAt[0] > LONG_PRESS_THRESHOLD_MS) {
            return original.onTouch(view, event)
        }

        val label = tabLabel(view)
        if (!shouldConfirm(view, label)) return original.onTouch(view, event)
        if (dialogShowing) return true

        // 关键：抬手被我们吞掉后，宿主的"长按"计时器还在跑（它只收到了 DOWN），
        // 弹窗期间会自己冒出「设置单击首页操作」。所以补一个 ACTION_CANCEL 把它的手势状态收干净。
        cancelTouch(view, event, original)

        askConfirm(view, label) {
            replayTouch(view, original)
        }
        // 吞掉这次抬手，宿主收不到点击
        return true
    }

    private fun askConfirm(view: View, label: String, onConfirmed: () -> Unit) {
        dialogShowing = true
        XplerLog.d("AccidentalTouch", "confirm: label=$label class=${view.javaClass.name}")
        showMessageDialog(
            context = view.context,
            title = "温馨提示",
            // 走到这里说明用户已经在该 tab 上（再点一次），所以文案可以明确写"刷新"
            content = if (label == LABEL_HOME) {
                "你已经在首页，是否触发刷新？"
            } else {
                "你已经在推荐，是否触发刷新？"
            },
            cancel = "取消",
            confirm = "确定",
            onConfirm = {
                // 重放期间保持 dialogShowing=true：宿主可能在同一次手势里再触发点击监听，
                // 提前置 false 会把同一次点击又弹一遍。
                try {
                    onConfirmed()
                } finally {
                    dialogShowing = false
                }
            },
            onCancel = { dialogShowing = false },
            onDismiss = { dialogShowing = false },
        )
    }

    /** 把被吞掉的手势收尾：通知原监听取消 + 复位按下态，避免宿主长按计时器在弹窗期间触发 */
    private fun cancelTouch(view: View, event: MotionEvent, original: View.OnTouchListener) {
        runCatching {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            original.onTouch(view, cancel)
            cancel.recycle()
        }.onFailure { XplerLog.e(it) }
        view.isPressed = false
    }

    /** 重放一次完整点击，避免宿主只收到 DOWN 没有 UP 而停在按下态 */
    private fun replayTouch(view: View, original: View.OnTouchListener) {
        val now = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 60L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            original.onTouch(view, down)
            original.onTouch(view, up)
        } catch (e: Throwable) {
            XplerLog.e(e)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun isTargetTab(view: View): Boolean {
        val name = view.javaClass.name
        return name == TOP_TAB_ITEM || name == BOTTOM_TAB_ITEM
    }

    /**
     * 只在"当前就已经在这个 tab 上"时才需要确认（即再点一次刷新），切换一律放行。
     *
     * 判断都用宿主的真实状态，不再用"上一次点了哪个"这种猜法（直播间里点推荐被误拦就是猜错的后果）：
     *  - 底部首页：`MainTab.getSelectedTab()` —— 切 tab 时宿主会把目标 tab 名写进新旧两个 tab，
     *    所以首页 tab 上读到 HOME 就说明当前在首页；
     *  - 顶部推荐：tab 项自己的 `getCurrentTabId()` 与 `ITopTabAbility.currentShowTab()` 比对。
     * 取不到状态时一律不弹（宁可少拦，不能拦导航）。
     */
    private fun shouldConfirm(view: View, label: String): Boolean {
        if (!config.isPreventAccidentalTouch) return false
        // 主界面被直播间/设置页等二级 Activity 盖住时，点 tab 只是"回去"，不该拦
        if (!HMainActivity.isResumed) {
            XplerLog.d("AccidentalTouch", "skip: main not resumed")
            return false
        }
        return when {
            view.javaClass.name == BOTTOM_TAB_ITEM && label == LABEL_HOME -> {
                // 宿主记录的是"上一次切到了哪个 tab"：切换时新旧两个 tab 都会被写入目标 tab 名，
                // 所以首页 tab 上的值 == HOME 就代表当前确实在首页（38.6 / 40.3.0 均已反编译确认）。
                val hostTab = bottomSelectedTab(view)
                val confirm = when (hostTab) {
                    // 方法被改名（读不到）才退回按钮自身的选中态
                    null -> view.isSelected
                    // 空串 = 进程启动后还没发生过切换，而抖音启动必停在首页
                    "" -> true
                    else -> hostTab == "HOME"
                }
                XplerLog.d(
                    "AccidentalTouch",
                    "home tap: type=${bottomTabType(view)} host=$hostTab " +
                        "selected=${view.isSelected} confirm=$confirm"
                )
                confirm
            }

            view.javaClass.name == TOP_TAB_ITEM && label == LABEL_RECOMMEND -> {
                val clicked = view.topTabId()
                // 宿主类在宿主 ClassLoader 里，用 Module 的 Class.forName 找不到
                val current = currentTopTabId(view.javaClass.classLoader)
                if (clicked == null || current == null) {
                    XplerLog.d("AccidentalTouch", "topTab state unknown: clicked=$clicked current=$current")
                    false
                } else {
                    XplerLog.d("AccidentalTouch", "topTab clicked=$clicked current=$current")
                    clicked == current
                }
            }

            else -> false
        }
    }

    /** 底部 tab 自己的类型（`MainTab.getTabType()`，首页为 HOME），取不到返回 null */
    private fun bottomTabType(view: View): String? = runCatching {
        view.javaClass.getMethod("getTabType").invoke(view) as? String
    }.getOrNull()

    /** 宿主记录的当前 tab 类型（`MainTab.getSelectedTab()`），取不到返回 null */
    private fun bottomSelectedTab(view: View): String? = runCatching {
        view.javaClass.getMethod("getSelectedTab").invoke(view) as? String
    }.getOrNull()

    /** tab 项自己的 id（`HomeTopTabItemViewNew.getCurrentTabId()`），取不到返回 null */
    private fun View.topTabId(): String? = runCatching {
        javaClass.getMethod("getCurrentTabId").invoke(this) as? String
    }.getOrNull()

    /** 宿主当前选中的顶部 tab id（`ITopTabAbility.currentShowTab(false)`），取不到返回 null */
    private fun currentTopTabId(loader: ClassLoader?): String? {
        return runCatching {
            val ezHomePage = Class.forName(
                "com.ss.android.ugc.aweme.ability.EzHomePage",
                false,
                loader
            )
            val ability = ezHomePage.getMethod("getTopTabAbility").invoke(null)
                ?: return@runCatching null
            val method = ability.javaClass.getMethod(
                "currentShowTab",
                Boolean::class.javaPrimitiveType!!
            )
            method.invoke(ability, false) as? String
        }.getOrNull()
    }

    /** tab 文案是运行时填的，所以点击那一刻再从子树里取（取第一个短文本） */
    private fun tabLabel(view: View): String {
        var label = ""
        runCatching {
            view.forEachChild { child ->
                if (label.isEmpty() && child is TextView) {
                    val text = child.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty() && text.length <= 6) {
                        label = text
                    }
                }
            }
        }.onFailure { XplerLog.e(it) }
        return label
    }
}
