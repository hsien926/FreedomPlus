package io.github.fplus.core.hook

import android.os.Bundle
import android.view.View
import com.freegang.extension.forEachWhereChild
import com.freegang.extension.removeInParent
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.HookEntity
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HCommentListPageFragment : BaseHook() {
    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.commentListPageFragmentClazz ?: NoneHook::class.java
    }

    override fun onInit() {
        HCommentColorModeViewMode()
    }

    @OnAfter("onViewCreated")
    fun onViewCreatedAfter(
        params: MethodParam,
        view: View,
        savedInstanceState: Bundle?,
    ) {
        hookBlockRunning(params) {
            view.forEachWhereChild {
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