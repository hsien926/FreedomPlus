package io.github.fplus.core.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.freegang.extension.forEachChild
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import java.lang.reflect.Method

/**
 * 底 bar（相关搜索 / 上升热点等）背景透明。
 *
 * 38.6.0 的底 bar 是「槽位 + provider」结构：具体 provider 各不相同
 * （HotSpotYellowBarProvider / FeedBottomLongRelatedBarProvider …），
 * 背景统一由 FeedCommonBottomBarPresenter 刷。
 *
 * 两条生效路径（真机验证）：
 * 1. presenter 回调后清一次 + 两拍延迟补清 —— 覆盖「上升热点」；
 * 2. 「相关搜索」会在清完之后继续调用 view.setBackgroundColor 把半透明黑刷回去，
 *    清背景永远追不上，所以直接拦截 View 的背景设置：凡是落在底 bar 槽位（id 名 bs）内的
 *    宽条 View，一律强制透明。
 */
class HBottomBarBackground : BaseHook() {
    companion object {
        private const val PRESENTER = "com.ss.android.ugc.aweme.feed.bottombar.FeedCommonBottomBarPresenter"
        private const val BOTTOM_BAR_INFO = "BottomBarInfo"
        private const val MAX_DEPTH = 16

        /** 向上清理的祖先层数 */
        private const val MAX_ANCESTOR_LEVELS = 4

        /** 祖先高度上限：超过这个高度说明已经到容器层，停止清理 */
        private const val MAX_ANCESTOR_HEIGHT_PX = 480

        /** 延迟补清的两拍（毫秒） */
        private val RETRY_DELAYS = longArrayOf(120L, 600L)
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return findClass(PRESENTER)
    }

    init {
        hookPresenter()
        hookBackgroundSetter()
    }

    private fun hookPresenter() {
        runCatching {
            val clazz = targetClass
            if (clazz.name == NoneHook::class.java.name) return@runCatching

            lparam.hookClass(clazz).methodAll {
                onAfter {
                    hookBlockRunning(this) {
                        val currentMethod = method as? Method ?: return@hookBlockRunning
                        if (currentMethod.parameterTypes.none { it.name.endsWith(BOTTOM_BAR_INFO) }) {
                            return@hookBlockRunning
                        }

                        val barView = args.firstOrNull { it is ViewGroup } as? ViewGroup
                            ?: args.firstOrNull { it is View } as? View
                            ?: return@hookBlockRunning

                        // 「视频控件」透明度：底 bar（相关搜索 / 上升热点）也算视频控件，
                        // 否则左下角简介已半透明、只有这一行还是纯白。
                        applyTranslucency(barView)

                        if (!config.isImmersive || config.isRelatedSearchOpaque) {
                            return@hookBlockRunning
                        }

                        applyTransparent(barView)

                        RETRY_DELAYS.forEach { delayMs ->
                            barView.postDelayed({
                                runCatching {
                                    applyTranslucency(barView)
                                    if (config.isImmersive && !config.isRelatedSearchOpaque) {
                                        applyTransparent(barView)
                                    }
                                }.onFailure { XplerLog.e(it) }
                            }, delayMs)
                        }
                    }.onFailure {
                        XplerLog.e(it)
                    }
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /** 底 bar 的「视频控件」透明度（与左侧简介块一致） */
    private fun applyTranslucency(view: View) {
        if (!config.isTranslucent) return
        // 已经在简介块里的就别再压一次，否则 0.5 × 0.5 = 25%
        var node: View? = view.parent as? View
        var level = 0
        while (node != null && level < 8) {
            if (node.javaClass.name == "com.ss.android.ugc.aweme.feed.ui.AwemeIntroInfoLayout") return
            node = node.parent as? View
            level++
        }

        val alpha = config.translucentValue[1].coerceIn(0, 100) / 100f
        if (view.alpha != alpha) view.alpha = alpha
    }

    private fun hookBackgroundSetter() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setBackgroundColor", "int") {
                    onBefore {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (!shouldForceTransparent(view)) return@hookBlockRunning
                            args[0] = Color.TRANSPARENT
                        }.onFailure { XplerLog.e(it) }
                    }
                }
                .method("setBackground", "android.graphics.drawable.Drawable") {
                    onBefore {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (!shouldForceTransparent(view)) return@hookBlockRunning
                            args[0] = null
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 只处理「宽条、且祖先里有 id 名 bs（底 bar 槽位）」的 View。
     * 先做尺寸过滤，避免在 inflate 阶段对成百上千个 View 反复走父链。
     */
    private fun shouldForceTransparent(view: View): Boolean {
        if (!config.isImmersive || config.isRelatedSearchOpaque) return false
        if (view.height !in 60..220) return false
        if (view.width < view.resources.displayMetrics.widthPixels * 0.8f) return false

        var node: Any? = view
        var level = 0
        while (node is View && level < 12) {
            if (idName(node) == "bs") return true
            node = node.parent
            level++
        }
        return false
    }

    /** 清 bar 自身 + 子树 + 向上若干层祖先的背景 */
    private fun applyTransparent(barView: View) {
        clearBarBackground(barView, 0)
        clearAncestorBackgrounds(barView)
    }

    private fun clearBarBackground(view: View, depth: Int) {
        if (depth > MAX_DEPTH) return

        view.background = ColorDrawable(Color.TRANSPARENT)

        if (view !is ViewGroup) return
        view.forEachChild { child ->
            clearBarBackground(child, depth + 1)
        }
    }

    /** 「相关搜索」的底纹画在 bar 的祖先上（container/daf 一层），这里向上清几层 */
    private fun clearAncestorBackgrounds(barView: View) {
        var parent = barView.parent
        var level = 0

        while (parent is ViewGroup && level < MAX_ANCESTOR_LEVELS) {
            val height = parent.height
            if (height in 1..MAX_ANCESTOR_HEIGHT_PX) {
                parent.background = ColorDrawable(Color.TRANSPARENT)
            }
            parent = parent.parent
            level++
        }
    }

    private fun idName(view: View): String {
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("-")
    }
}
