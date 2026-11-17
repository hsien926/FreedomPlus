package io.github.fplus.core.hook

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import com.freegang.extension.forEachWhereChild
import com.freegang.extension.removeInParent
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.CommentPanelState
import io.github.fplus.core.helper.findMethodDeclaringClass
import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.HookEntity
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HCommentListPageFragment : BaseHook() {
    companion object {
        private val panels = CommentPanelState<View> { view ->
            view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                view.getGlobalVisibleRect(Rect())
        }

        fun isPanelShowing(): Boolean = panels.is_open()
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        // 40.6.0 的 onViewCreated 在父类声明，Xpler 只扫描目标类的 declaredMethods。
        return findMethodDeclaringClass(DexkitBuilder.commentListPageFragmentClazz,
            "onViewCreated", View::class.java, Bundle::class.java) ?: NoneHook::class.java
    }

    override fun onInit() {
        KLogCat.tagI("HCommentListPageFragment", "评论生命周期声明类=${targetClass.name}")
        HCommentColorModeViewMode()
    }

    @OnAfter("onViewCreated")
    fun onViewCreatedAfter(
        params: MethodParam,
        view: View,
        savedInstanceState: Bundle?,
    ) {
        hookBlockRunning(params) {
            if (DexkitBuilder.commentListPageFragmentClazz?.isInstance(thisObject) != true) return@hookBlockRunning
            bindVisiblePanel(view)
            view.forEachWhereChild {
                bindVisiblePanel(it)
                if (it.contentDescription?.toString()?.startsWith("搜索，") == true) {
                    it.removeInParent()
                    return@forEachWhereChild true
                }

                false
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun bindVisiblePanel(view: View) {
        if (view.javaClass.name != "com.ss.android.ugc.aweme.comment.widget.CommentNestedLayout") return
        panels.bind(view)
        KLogCat.tagI("HCommentListPageFragment", "评论可见面板已登记=${view.javaClass.name}")
    }

    // 评论颜色模式
    private inner class HCommentColorModeViewMode : HookEntity() {
        override fun setTargetClass(): Class<*> {
            return DexkitBuilder.commentColorModeViewModeClazz ?: NoneHook::class.java
        }

        // 参数类型不能写成 CommentColorMode —— 该类在抖音 40.6.0 已被混淆为 LX/0dQI;
        // （aweme 代理模块里那个名字只存在于编译期），运行时按名解析会抛 NoClassDefFoundError。
        // 改成 Enum<*>（xpler 用 isAssignableFrom 匹配，能命中枚举子类），运行时按常量名取目标枚举值。
        @OnBefore
        fun setCommentColorModeBefore(
            params: MethodParam,
            mode: Enum<*>,
        ) {
            hookBlockRunning(params) {
                if (!config.isCommentColorMode)
                    return

                val targetName = when (config.commentColorMode) {
                    0 -> "MODE_LIGHT"
                    1 -> "MODE_DARK"
                    else -> "MODE_LIGHT_OR_DARK"
                }
                // 走枚举常量当量清单，避开 javaClass 可能是匿名子类、以及字段名变化的问题
                val constants = mode.javaClass.enumConstants ?: return@hookBlockRunning
                val target = constants.firstOrNull { (it as? Enum<*>)?.name == targetName }
                if (target != null) {
                    args[0] = target
                }
            }.onFailure {
                XplerLog.e(it)
            }
        }
    }
}