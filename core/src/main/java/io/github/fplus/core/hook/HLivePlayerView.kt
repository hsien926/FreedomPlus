package io.github.fplus.core.hook

import android.view.View
import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 直播页出现时，把它的「讲解中」底栏上移一条 tab 的高度。
 *
 * 38.6.0 的直播页会画到屏幕最底（播放器高 2475 > 屏幕 2340），底栏正好落进 tab 行；
 * 沉浸模式下 tab 行是透明的，于是就叠在一起。31.7.2 本身没这问题（底栏在 tab 上方），
 * 这里按宿主真实布局补一个位移，而不是把 tab 行改回不透明。
 */
class HLivePlayerView : BaseHook() {
    companion object {
        private const val LIVE_PLAYER = "com.bytedance.android.livesdkapi.roomplayer.LivePlayerView"
        private const val SHIFT_DELAY_MS = 700L
    }

    override fun setTargetClass(): Class<*> {
        return findClass(LIVE_PLAYER)
    }

    init {
        hookAttach()
    }

    private fun hookAttach() {
        runCatching {
            lparam.hookClass(targetClass)
                .method("onAttachedToWindow") {
                    onAfter {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            // 直播页是分层异步加载的，隔两拍各试一次
                            view.postDelayed({
                                runCatching {
                                    val page = view.findLivePageRoot() ?: return@runCatching
                                    HMainActivity.shiftLiveBottomBar(page)
                                }.onFailure { XplerLog.e(it) }
                            }, SHIFT_DELAY_MS)
                            view.postDelayed({
                                runCatching {
                                    val page = view.findLivePageRoot() ?: return@runCatching
                                    HMainActivity.shiftLiveBottomBar(page)
                                }.onFailure { XplerLog.e(it) }
                            }, SHIFT_DELAY_MS * 3)
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }

    /** 往上找直播页的根容器（LivePreviewFrameLayout），找不到就退回上一层 */
    private fun View.findLivePageRoot(): View? {
        var node: View? = this
        var level = 0
        while (node != null && level < 8) {
            if (node.javaClass.name.contains("LivePreviewFrameLayout")) return node
            node = node.parent as? View
            level++
        }
        return parent as? View
    }
}
