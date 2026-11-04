package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager
import com.ss.android.ugc.aweme.kiwi.model.QModel
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.CallMethods
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

/// 崩溃容错，处理官方可能造成的系列崩溃问题
class HCrashTolerance {
    companion object {
        const val TAG = "HCrashTolerance"

        /** 暂停链路的动画组件（抖音 31.7.1 实测类名，未被混淆） */
        internal const val FEED_WIDGET_ANIM =
            "com.ss.android.ugc.aweme.feed.plato.business.contentconsumption.widget.FeedWidgetAnimComponent"

        /** 暂停链路的根组件，作为类名漂移时的第二道网 */
        internal const val FEED_COMPONENTS_ROOT =
            "com.ss.android.ugc.aweme.feed.plato.core.FeedComponentsRoot"

        private var pauseNpeSwallowed = 0
        private var pauseNpeLastLogAt = 0L

        /**
         * 定向吞掉「暂停链路里 FeedItemFragmentVM 为空」的 NPE。
         *
         * 特征（抖音 31.7.1 实测）：
         * ```
         * NullPointerException: Attempt to invoke virtual method
         *   'QLiveData FeedItemFragmentVM.getFeedAnimation()' on a null object reference
         * at VideoViewHolder.LJIILIIL
         * at FeedWidgetAnimComponent.handlePauseP
         * ```
         * 触发路径是「点侧边栏切 tab」→ StateManager.onNodeShow 写状态 LiveData →
         * 观察者调 handlePauseP → 当前页 ViewHolder 的 VM 尚未初始化。
         *
         * 只认 `NullPointerException` + message 关键字，别的异常一律照抛，避免掩盖真实问题。
         */
        internal fun swallowPauseNpe(params: MethodParam, from: String): Boolean {
            val throwable = params.throwable ?: return false
            if (throwable !is NullPointerException) return false

            val message = throwable.message ?: return false
            if (!message.contains("getFeedAnimation") && !message.contains("FeedItemFragmentVM")) {
                return false
            }

            params.setThrowable(null)
            pauseNpeSwallowed++

            // 限速：切 tab 时可能连续触发多次，避免刷日志
            val now = System.currentTimeMillis()
            if (now - pauseNpeLastLogAt < 1000L) return true
            pauseNpeLastLogAt = now
            KLogCat.tagI(TAG, "已拦截暂停链路 NPE 第${pauseNpeSwallowed}次(来自$from): $message")
            return true
        }
    }

    val config get() = ConfigV1.get()

    init {
        HPoiFeed()
        HLivePhoto()
        HTabLanding()
        HFeedPagerPopulate()
        HFeedPauseNpe()
        HFeedPauseNpeRoot()
    }

    inner class HPoiFeed : BaseHook(), CallMethods {
        override fun setTargetClass(): Class<*> {
            return findClass("com.ss.android.ugc.aweme.poi.anchor.poi.flavor.PoiFeedAnchor")
        }

        override fun callOnBeforeMethods(params: MethodParam) {

        }

        override fun callOnAfterMethods(params: MethodParam) {
            hookBlockRunning(params) {
                resultOrThrowable
            }.onFailure {
                params.setThrowable(null)
                // KToastUtils.show(KAppUtils.getApplication, "尝试崩溃拦截:${it.message}")
            }
        }
    }

    inner class HLivePhoto : BaseHook() {

        override fun setTargetClass(): Class<*> {
            return DexkitBuilder.livePhotoClazz ?: NoneHook::class.java
        }

        @OnAfter
        fun methodAfter(params: MethodParam, qModel: QModel?) {
            hookBlockRunning(params) {
                resultOrThrowable
            }.onFailure {
                params.setThrowable(null)
                // KToastUtils.show(KAppUtils.getApplication, "尝试崩溃拦截:${it.message}")
            }
        }
    }

    inner class HTabLanding : BaseHook() {

        override fun setTargetClass(): Class<*> {
            return DexkitBuilder.tabLandingClazz ?: NoneHook::class.java
        }

        // 40.6.0 适配：原写法 `@OnAfter` + `(params, item: VideoItemParams?)` 会被 xpler 解析成
        // paramTypes=[VideoItemParams]，而目标类（TabLandGuideModule）在 40.6.0 的 22 个方法
        // 无一接收该类型，必然绑不上。改为按方法名绑定且不声明额外参数（只按名字过滤）。
        @OnAfter("onCreateView")
        fun methodAfter(params: MethodParam) {
            hookBlockRunning(params) {
                resultOrThrowable
            }.onFailure {
                params.setThrowable(null)
                // KToastUtils.show(KAppUtils.getApplication, "尝试崩溃拦截:${it.message}")
            }
        }
    }

    /**
     * 【崩溃容错】`VerticalViewPager.populate` 的 PagerAdapter 数量失配断言。
     *
     * ## 崩的是什么
     * ```
     * java.lang.IllegalStateException: The application's PagerAdapter changed the adapter's
     * contents without calling PagerAdapter#notifyDataSetChanged! Expected adapter item count: 13,
     * found: 15 Pager id: com.ss.android.ugc.aweme:id/viewpager
     * Pager class: class com.ss.android.ugc.aweme.common.widget.RTViewPager
     * Problematic adapter: class X.C1790160wg5
     *   at VerticalViewPager.LJJIJIL(populate)
     *   at VerticalViewPager.LJJIJIIJIL         ← 只有一句 LJJIJIL(this.h)
     *   at X.1YgM.run                           ← 真身 X.RunnableC2797451YgM，VerticalViewPager 的私有
     *   at X.1YgP.run                              Runnable（AOSP mEndScrollRunnable 那一套：
     *   at bytex.async.stack.broken_chain...       setScrollState(IDLE) 后 populate），被 ByteX
     *                                              broken_chain 拆成了两个 run 块
     * ```
     *
     * ## 这不是抖音的「检测」
     * `LJJIJIL(int)` = `populate(int newCurrentItem)`，头部就是 AOSP ViewPager 的原生自洽检查：
     * ```
     * int count = this.g.getCount();
     * if (count == this.b) { ...正常装载... }   // b = mExpectedAdapterCount
     * else throw new IllegalStateException("... Expected adapter item count: " + this.b
     *                                      + ", found: " + count + ...);
     * ```
     * `this.b` **只在** `notifyDataSetChanged()` → `dataSetChanged()` 里被写成当时的 `getCount()`。
     * 契约是「两次 notify 之间 getCount() 恒定」；任何在两次 notify 之间改变 `getCount()` 的东西
     * ——模块 hook 或宿主自身的插入/删除竞态——都会在滚动结束那一刻炸掉。
     * 与签名校验、反调试、账号风控都无关。
     *
     * ## 兜底策略
     * 1. 只吞这一条（`IllegalStateException` 且 message 含 `PagerAdapter`），别的异常照旧抛出，
     *    避免把真实问题藏起来；
     * 2. 下一帧（post 到主线程，避免在 populate 里再进 populate）调一次
     *    `adapter.notifyDataSetChanged()`，让抖音自己把 `b` 刷成当前 `getCount()`，
     *    pager 随后能正常装载 —— 不依赖任何混淆字段名，版本无关。
     *
     * 注意：这只是「不崩」的安全网。真凶（旧版 `HFeedAdFilter` 改写 `getCount()`）必须从根上
     * 修掉，否则表现为「不崩但翻不动页」。
     */
    inner class HFeedPagerPopulate : BaseHook() {
        private var swallowed = 0
        private var lastRecoverAt = 0L

        override fun setTargetClass(): Class<*> {
            return VerticalViewPager::class.java
        }

        /** `LJJIJIL(int)` 是 final public 的 populate；onAfter 里能拿到被抛出的异常 */
        @OnAfter("LJJIJIL")
        fun populateAfter(params: MethodParam) {
            val th = params.throwable ?: return
            if (th !is IllegalStateException) return
            if (th.message?.contains("PagerAdapter") != true) return

            params.setThrowable(null)
            swallowed++
            KLogCat.tagI(
                TAG,
                "已吞掉 PagerAdapter 数量失配异常(第${swallowed}次): " +
                    "${th.message?.substringBefore(" Pager id").orEmpty()}",
            )

            // 限速：notify 本身会重挂 b 并触发新一轮 populate，若 count 仍在抖动就别死循环
            val now = System.currentTimeMillis()
            if (now - lastRecoverAt < 300L) return
            lastRecoverAt = now

            val pager = params.thisObject ?: return
            refresh {
                runCatching {
                    val adapter = pager.javaClass.getMethod("getAdapter").invoke(pager)
                        ?: return@runCatching
                    adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
                }.onFailure { XplerLog.e(it) }
            }
        }
    }

    /**
     * 【崩溃容错】`FeedWidgetAnimComponent.handlePauseP` 抛出
     * `FeedItemFragmentVM.getFeedAnimation()` 的 NPE。
     *
     * 成因见 [swallowPauseNpe] 的注释：该 ViewHolder 的 VM 为空，暂停时被判空指针。
     * 根因已从数据层处理（广告不再进入 Adapter，`bind` 正常执行、VM 正常创建），
     * 这里只是最后一道网，覆盖宿主自身或其它模块造成的同款异常 —— 不吞则整个进程被
     * `KAppCrashUtils` 结束（表现为「抖音异常退出!」）。
     */
    inner class HFeedPauseNpe : BaseHook() {
        override fun setTargetClass(): Class<*> {
            return findClass(FEED_WIDGET_ANIM)
        }

        @OnAfter("handlePauseP")
        fun handlePauseAfter(params: MethodParam) {
            swallowPauseNpe(params, "FeedWidgetAnimComponent")
        }
    }

    /**
     * 同上，目标换成 `FeedComponentsRoot.handlePauseP`：
     * 组件类名若在后续版本漂移，[HFeedPauseNpe] 会静默失效，
     * 由这一层在异常冒泡到根组件时兜住。
     */
    inner class HFeedPauseNpeRoot : BaseHook() {
        override fun setTargetClass(): Class<*> {
            return findClass(FEED_COMPONENTS_ROOT)
        }

        @OnAfter("handlePauseP")
        fun handlePauseAfter(params: MethodParam) {
            swallowPauseNpe(params, "FeedComponentsRoot")
        }
    }
}
