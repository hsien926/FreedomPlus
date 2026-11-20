package io.github.fplus.core.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.freegang.extension.dip2px
import com.freegang.extension.firstOrNull
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import kotlinx.coroutines.delay

class HAbstractFeedAdapter : BaseHook() {
    companion object {
        /** 用类名比较替代代理类的 `is`/`as`，避免其在目标抖音版本不存在时抛 NoClassDefFoundError */
        private const val VIDEO_VIEW_HOLDER_ROOT_VIEW =
            "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView"
    }
    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.abstractFeedAdapterClazz ?: NoneHook::class.java
    }

    @OnAfter
    fun methodAfter(
        params: MethodParam,
        // @Param("com.ss.android.ugc.aweme.feed.adapter.FeedTypeConfig") feedTypeConfig: Any?,
        view: View?,
        i: Int,
    ) {
        hookBlockRunning(params) {
            if (!config.isImmersive)
                return

            // KLogCat.d("view: $view")
            if (view is FrameLayout && view.javaClass.name != VIDEO_VIEW_HOLDER_ROOT_VIEW) {

                // 垫高
                view.forEachChild { if (it.background is GradientDrawable) it.background = null }

                // 相关搜索浮层胶囊（SearchTagViewGroup / SearchTagView）背景透明
                clearSearchTagBackgrounds(view)
                val bottomPadding = 58f.dip2px() // BottomTabBarHeight
                val viewGroup = view.children.lastOrNull { it is ViewGroup && it.isVisible } ?: return
                viewGroup.updatePadding(bottom = bottomPadding)

                singleLaunchMain {
                    delay(300)

                    // 标签是 VideoSearchTagPresenter 异步 addView 进来的，bind 当拍通常还没建好
                    clearSearchTagBackgrounds(view)

                    // 尝试修复直播控件漂移
                    val orNull = view.firstOrNull(View::class.java) { it.javaClass.name.endsWith("AutoEnterProgressBar") }
                    val orNull1 = orNull?.firstParentOrNull(LinearLayout::class.java)
                    orNull1?.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 相关搜索的浮层胶囊（SearchTagViewGroup + 子 SearchTagView）不走底 bar 槽位宿主，
     * 这里按类名递归把它们的背景清成透明（不动文字/可见性/点击）。
     */
    private fun clearSearchTagBackgrounds(view: View, depth: Int = 0) {
        if (depth > 12) return
        if (config.isRelatedSearchOpaque) return

        val name = view.javaClass.name
        if (name.contains("SearchTagView")) {
            view.background = ColorDrawable(Color.TRANSPARENT)
        }

        if (view is ViewGroup) {
            view.children.forEach { clearSearchTagBackgrounds(it, depth + 1) }
        }
    }

}