package io.github.fplus.core.hook

import android.graphics.Color
import android.view.View
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 地点 / 剪映 / 商品 / 小程序等 feed「锚点」卡片的底色透明。
 *
 * 这些卡片都是 `com.ss.android.ugc.aweme.views.RoundedLinearLayout`（同一个圆角胶囊类），
 * 深色底由它自己的 background 绘制。若只在绑定后清一次 background，用户会先看到
 * 带底的卡片、几百毫秒后才变透明（实测反馈的「先看到不透明遮罩然后才没了」）。
 *
 * 所以直接在设置入口处拦掉：凡是这个类在设背景，且开了全屏沉浸、没勾「不透明地点标签」，
 * 一律置空，卡片从第一次绘制就是透明底。
 *
 * 现版抖音这几类锚点共用同一个类，做不到只挑地点，属于已知取舍。
 */
class HAnchorBackground : BaseHook() {
    companion object {
        private const val ANCHOR_ROUNDED_LAYOUT = "com.ss.android.ugc.aweme.views.RoundedLinearLayout"
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> = NoneHook::class.java

    init {
        hookBackgroundSetter()
    }

    private fun hookBackgroundSetter() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setBackground", "android.graphics.drawable.Drawable") {
                    onBefore {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (!shouldForceTransparent(view)) return@hookBlockRunning
                            args[0] = null
                        }.onFailure { XplerLog.e(it) }
                    }
                }
                .method("setBackgroundColor", "int") {
                    onBefore {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (!shouldForceTransparent(view)) return@hookBlockRunning
                            args[0] = Color.TRANSPARENT
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 先比类名（几乎零成本），命中后才读配置，避免每次 setBackground 都翻 MMKV。
     *
     * 注意：`RoundedLinearLayout` 是抖音的**通用圆角容器**，右侧按钮、关注按钮等都用它。
     * 早先只按类名判断，结果把全 App 的圆角背景都清掉了（表现为「左右两侧控件变得很透明」）。
     * 这里必须再确认它确实在锚点容器里（`MaxWidthFrameLayout` / `FeedAnchorLayout`）。
     */
    private fun shouldForceTransparent(view: View): Boolean {
        if (view.javaClass.name != ANCHOR_ROUNDED_LAYOUT) return false
        if (!config.isImmersive || config.isLocationAnchorOpaque) return false

        // 还没挂到父级（XML inflate 阶段）：等挂上再判，避免误伤普通控件
        if (view.parent == null) {
            view.post {
                runCatching {
                    // 仍没父级就别再动（也不要再 post，避免死循环）；后续 setBackground 会再进来
                    if (view.parent != null && isInsideAnchor(view)) view.background = null
                }.onFailure { XplerLog.e(it) }
            }
            return false
        }
        return isInsideAnchor(view)
    }

    /** 祖先里是否有锚点容器（地点/剪映/商品/小程序卡片都挂在这类容器下） */
    private fun isInsideAnchor(view: View): Boolean {
        var node: View? = view
        var level = 0
        while (node != null && level < 5) {
            val name = node.javaClass.name
            if (name.contains("MaxWidthFrameLayout") || name.contains("FeedAnchorLayout")) return true
            node = node.parent as? View
            level++
        }
        return false
    }
}
