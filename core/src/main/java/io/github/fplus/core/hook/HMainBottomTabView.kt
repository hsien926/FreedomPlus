package io.github.fplus.core.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.freegang.extension.forEachChild
import com.freegang.extension.parentView
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisViewGroup
import java.util.Collections
import java.util.WeakHashMap

class HMainBottomTabView : BaseHook() {
    companion object {
        /** 底栏蒙层图片类名，用字符串比较，避免 aweme 桩类在运行时不存在导致 NoClassDefFoundError */
        private const val DUX_IMAGE_VIEW = "com.bytedance.dux.image.DuxImageView"

        /** 已注册过 addOnGlobalLayoutListener 的底栏实例，弱引用避免重复注册/内存泄漏 */
        private val layoutListenerRegistered: MutableSet<ViewGroup> =
            Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    }

    private val config get() = ConfigV1.get()

    init {
        // 接管底栏蒙层：抖音通过 setMaskVisibility/setMaskAlpha 控制蒙层，改背景对它无效
        runCatching {
            val clazz = DexkitBuilder.mainBottomTabViewClazz
            if (clazz != null) {
                lparam.hookClass(clazz)
                    .method("setMaskVisibility", "int") {
                        onBefore {
                            if (config.isImmersive)
                                args[0] = View.GONE
                        }
                    }
                    .method("setMaskAlpha", "float") {
                        onBefore {
                            if (config.isImmersive)
                                args[0] = 0f
                        }
                    }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.mainBottomTabViewClazz ?: NoneHook::class.java
    }

    @OnAfter
    fun methodAfter(params: MethodParam, i: Int) {
        hookBlockRunning(params) {
            // 底部导航栏透明度（只压 tab 自身；parent 是整屏容器，压了会全屏变暗）
            if (config.isTranslucent) {
                val alphaValue = config.translucentValue[3] / 100f
                thisViewGroup.alpha = alphaValue
            }

            // 底部导航栏全局沉浸式
            if (config.isImmersive) {
                applyImmersive(thisViewGroup)
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 真正让底栏透明：背景置透明 + 隐藏铺满整条底栏的 DuxImageView 蒙层。
     * 幂等，可重复调用。
     */
    private fun applyImmersive(viewGroup: ViewGroup) {
        viewGroup.background = ColorDrawable(Color.TRANSPARENT)
        viewGroup.parentView?.background = ColorDrawable(Color.TRANSPARENT)

        viewGroup.forEachChild { child ->
            child.background = ColorDrawable(Color.TRANSPARENT)
            if (child.javaClass.name == DUX_IMAGE_VIEW && child.isVisible) {
                child.visibility = View.GONE
            }
        }

        // 关闭评论区后抖音只改 visibility、不再回调 setMaskVisibility，这里兜底重新隐藏
        if (!layoutListenerRegistered.add(viewGroup))
            return

        viewGroup.viewTreeObserver.addOnGlobalLayoutListener {
            if (!config.isImmersive)
                return@addOnGlobalLayoutListener

            viewGroup.forEachChild { child ->
                if (child.javaClass.name == DUX_IMAGE_VIEW && child.isVisible) {
                    child.visibility = View.GONE
                }
            }
        }
    }
}
