package io.github.fplus.core.hook

import android.os.Looper
import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.hook.logic.FeedAdListLogic
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam

/**
 * 信息流广告兜底清理：在宿主 `notifyDataSetChanged()` **之前**把漏网的广告项真删。
 *
 * ## 解决的是哪一部分广告
 *
 * `HFeedAdFilter` 只在「整表落表」（`AbstractFeedAdapter.LJLILLLLZI` / `LJLJI`）时剔除广告。
 * 走 `LJJLIL(int,int,List)` / `LJJLJ(int,Aweme)` 这类**带插入位置的增量接口**进来的广告，
 * 直接改入参会把抖音自己的索引算错（`docs/修复说明-v1.3.9-PagerAdapter崩溃.md:35` 的结论），
 * 所以以前只能在 ViewHolder 层「隐藏成空白页」，用户侧会看到空白页、甚至只听到声音。
 *
 * 现在改为：不动插入接口的参数，等宿主下一次 `notifyDataSetChanged()` 前，
 * 把 Adapter 内部所有 `List` 字段里 `isAd == true` 的项按引用剔除。
 * 因为清理发生在 notify **之前**，`dataSetChanged()` 写下的 `mExpectedAdapterCount`
 * 就是清理后的 `getCount()`，天然满足「两次 notify 之间 count 恒定」的契约，
 * 不会重演 `Expected adapter item count: 13, found: 15`。
 *
 * ## 为什么挂 `PagerAdapter.notifyDataSetChanged`
 *
 * `LJJLIL` / `LJJLJ` 是从 40.6.0 反编译出来的混淆名，抖音 31.7.1 未必一致；
 * 而 `notifyDataSetChanged` 是 androidx 公共 API，名字跨版本稳定，且任何插入最终都要 notify 才会上屏。
 *
 * ## 失败即放行（重要）
 *
 * 用户明确要求：删不掉就让广告正常播放，不要隐藏、不要留空白页、不要卡流程。
 * 因此这里的每一步都是「能确定才做，否则整体跳过」：
 * 非主线程 / DexKit 未命中 / 没有任何 List 字段 / 没有任何广告项 / 删除会清空某份列表
 * —— 全部直接 return，数据保持原样，广告照常显示播放。
 */
class HFeedAdNotifySweep {
    companion object {
        private const val TAG = "HFeedAdNotifySweep"

        /** androidx 公共 API，跨版本稳定 */
        internal const val PAGER_ADAPTER = "androidx.viewpager.widget.PagerAdapter"

        private var removedPlaces = 0
        private var lastLogAt = 0L
        private var skipCount = 0
        private var lastSkipLogAt = 0L

        /**
         * 真正执行清理。返回是否发生了修改。
         *
         * @param adapter `notifyDataSetChanged` 的调用者（`MethodParam.thisObject`）
         */
        internal fun sweep(adapter: Any?): Boolean {
            if (adapter == null) return false

            val adapterClazz = DexkitBuilder.abstractFeedAdapterClazz ?: return false
            if (!adapterClazz.isInstance(adapter)) return false

            // FeedArrayList 的所有结构修改都有 Ensure.LJ(...,"feed_adapter_data") 主线程校验
            if (Looper.myLooper() != Looper.getMainLooper()) {
                logSkip("非主线程")
                return false
            }

            val lists = FeedAdListLogic.collectListFields(adapter)
            if (lists.isEmpty()) {
                logSkip("无可清理列表")
                return false
            }

            // 第一步：只扫描，不修改。收集「持有广告项」的列表
            val targets = ArrayList<Pair<MutableList<Any?>, List<Any?>>>(lists.size)
            for (list in lists) {
                if (list.isEmpty()) continue
                val ads = list.filter { FeedAdListLogic.isAdAweme(it) }
                if (ads.isNotEmpty()) targets.add(list to ads)
            }

            // 没有广告：绝大多数 notify 走这里，静默返回
            if (targets.isEmpty()) return false

            // 保护：任何一份列表被删空，都会让抖音走 getCount() == 0 的「整表替换但不 notify」
            // 分支（mExpectedAdapterCount 永久过期）。宁可放过这条广告，也不冒这个险。
            if (targets.any { (list, ads) -> list.size - ads.size <= 0 }) {
                logSkip("会清空列表")
                return false
            }

            // 第二步：按引用剔除。单份列表失败只记日志，不影响其它列表
            var removed = 0
            for ((list, ads) in targets) {
                val count = runCatching { FeedAdListLogic.removeByIdentity(list, ads) }
                    .onFailure { KLogCat.tagI(TAG, "剔除失败(已放行): ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault(0)
                removed += count
            }

            if (removed <= 0) return false

            removedPlaces += removed
            val now = System.currentTimeMillis()
            if (now - lastLogAt < 1000L) return true
            lastLogAt = now
            KLogCat.tagI(
                TAG,
                "已真删插队广告 $removed 处(涉及 ${targets.size} 份列表, 累计 $removedPlaces 处)",
            )
            return true
        }

        internal fun logSkip(reason: String) {
            skipCount++
            val now = System.currentTimeMillis()
            if (now - lastSkipLogAt < 10_000L) return
            lastSkipLogAt = now
            KLogCat.tagI(TAG, "跳过清理($reason), 累计 $skipCount 次(广告按原样播放)")
        }
    }

    init {
        HSweepPagerAdapter()
        HSweepFeedAdapter()
    }

    /**
     * 主入口：挂 androidx 的 `PagerAdapter.notifyDataSetChanged`。
     * 抖音的 feed Adapter 若重写后调用 `super`，这里一定会命中。
     */
    inner class HSweepPagerAdapter : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass(PAGER_ADAPTER)

        override fun onInit() {
            KLogCat.tagI(TAG, "主入口已注册, target=${targetClass.name}")
        }

        @OnBefore("notifyDataSetChanged")
        fun notifyBefore(params: MethodParam) {
            if (!ConfigV1.get().isAdBlock) return
            sweep(params.thisObject)
        }
    }

    /**
     * 补充入口：若 Adapter 自己**重写**了 `notifyDataSetChanged` 且不调用 `super`，
     * 上面那个基类 hook 就永远不触发。这里再挂一次「声明在 Adapter 类上」的同名方法。
     *
     * 方法没声明在目标类上时 xpler 会自动跳过（DexKit 未命中时目标类退化为 NoneHook），
     * 属于纯链路兜底，不会重复产生副作用：两次触发时第二次已经找不到广告。
     */
    inner class HSweepFeedAdapter : BaseHook() {
        override fun setTargetClass(): Class<*> =
            DexkitBuilder.abstractFeedAdapterClazz ?: NoneHook::class.java

        override fun onInit() {
            KLogCat.tagI(TAG, "补充入口已注册, target=${targetClass.name}")
        }

        @OnBefore("notifyDataSetChanged")
        fun notifyBefore(params: MethodParam) {
            if (!ConfigV1.get().isAdBlock) return
            sweep(params.thisObject)
        }
    }
}
