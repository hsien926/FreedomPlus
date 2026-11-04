package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 信息流广告过滤（数据层实现，39.9.0 / 40.6.0 通用）
 *
 * ## 为什么推翻了上一版的「位置映射」方案（重要，别再改回去）
 *
 * 上一版 hook 了 `AbstractFeedAdapter.getCount()` / `getItem(int)`，让 ViewPager 只看到
 * 「过滤后的位置」。这对 PagerAdapter 是**结构性错误**，会直接崩掉宿主：
 *
 * 1. `VerticalViewPager.LJJIJIL(int)`（= `populate`）里有一条自洽断言：
 *    ```
 *    int count = this.g.getCount();          // g = mAdapter
 *    if (count == this.b) { ...正常装载... }  // b = mExpectedAdapterCount
 *    else throw new IllegalStateException(
 *        "The application's PagerAdapter changed the adapter's contents without calling "
 *      + "PagerAdapter#notifyDataSetChanged! Expected adapter item count: " + b
 *      + ", found: " + count + " ...");
 *    ```
 *    `this.b` **只在** `notifyDataSetChanged()` 触发的 `dataSetChanged()` 里被写成当时的
 *    `getCount()`。也就是契约：**两次 notify 之间 getCount() 必须恒定**。
 *    而位置映射的返回值来自「每次调用都重扫一遍当前窗口」的 `mapping`——它是静态的、跨
 *    adapter 共用、还带一个全局 `scanning` 重入标志。于是 notify 时刻可能是 13（15 项里 2 项
 *    判为广告），滚动结束回调里再算一次变成 15（去掉广告又变回原始项）→ b≠count → populate
 *    抛异常 → `mEndScrollRunnable` 里没人接 → 进程崩溃。这正是真机上看到的那条：
 *    `Expected adapter item count: 13, found: 15`。
 *
 * 2. 抖音自己用 `getCount() == 0` 判断「是不是还空表」：
 *    - `BaseListFragmentPanel.Xh(0Eod)`：
 *      `if (adapter.getCount() == 0) { adapter.LJLILLLLZI(list); return true; }` —— **不 notify**
 *    - `FollowFeedFragmentPanel.LJJJI(int, List)`：
 *      `if (adapter.getCount() == 0) { adapter.LJLILLLLZI(list); } else { 插入 + notify; }`
 *    hook 把 count 改小（甚至改成 0）时，抖音会走「整表替换但不 notify」分支，
 *    pager 里的 b 永久停在旧值，下一次 populate 必崩。
 *
 * 3. 位置映射只翻译了 `getItem`，`getPageWidth(int)`、`getItemPosition(Object)`、
 *    `LJJLIIJ`/`LJJLIIIJJI`、`LJJJLIIL`/`LJJLIL`/`LJJLJ` 全都在「原始索引」空间里工作 →
 *    内容错位、页面反复重建、删除错项。这类补丁永远补不齐。
 *
 * ## 现在的做法：只动「落表」那一刻
 *
 * `AbstractFeedAdapter.LJLILLLLZI(java.util.List)` 是所有整表设置的落地点
 * （`LJLJI(list, boolean)`、`LJJLJLI(int,int,List)` 最终都转调它）。在这里就地把 `isAd` 项剔掉：
 * - 入参列表 + 适配器内同源的平行副本（子类 `N`）一起剔 → `getCount()` / `getItem(int)` /
 *   `LJJLIIJ` 三者索引空间一致；
 * - `getCount()` 永远等于真实数据量 → 上面 1/2/3 全部不成立；
 * - notify 仍由抖音自己发，`mExpectedAdapterCount` 与 `getCount()` 永远同步。
 *
 * Items inserted through `LJJLIL(int,int,List)` / `LJJLJ(int,Aweme)` are left untouched here:
 * rewriting their arguments corrupts the host's positional arithmetic. They are passed through
 * as-is and play normally — never touched at runtime.
 */
class HFeedAdFilter : BaseHook() {
    companion object {
        private const val TAG = "HFeedAdFilter"

        private var removedTotal = 0
        private var lastLoggedSize = -1

        /** `Aweme.isAd` 字段缓存：getField 每次都要遍历，feed 每页几十项时值得缓存 */
        private val adFieldCache = ConcurrentHashMap<Class<*>, Field>()

        /** 明确没有 isAd 字段的类，避免每项都白跑一次 getField */
        private val noAdField = java.util.Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.abstractFeedAdapterClazz ?: NoneHook::class.java

    override fun onInit() {
        KLogCat.tagI(TAG, "已注册(数据层过滤), target=${targetClass.name}")
    }

    /**
     * 整表设置：`AbstractFeedAdapter.LJLILLLLZI(java.util.List)`。
     *
     * 注意方法定义在基类、由子类（如 `X.C1790160wg5`）重写，重写体最后会 `super.LJLILLLLZI(...)`，
     * 所以这里挂基类声明是有效的；xpler 只扫目标类的 declaredMethods，故不能写成通用基类 hook。
     */
    @OnBefore("LJLILLLLZI")
    fun beforeSetItems(params: MethodParam, list: List<*>?) {
        filterInPlace(params, list)
    }

    /** 整表设置带刷新标记：`AbstractFeedAdapter.LJLJI(java.util.List, boolean)` */
    @Suppress("UNUSED_PARAMETER")
    @OnBefore("LJLJI")
    fun beforeSetItemsWithFlag(params: MethodParam, list: List<*>?, isRefresh: Boolean) {
        filterInPlace(params, list)
    }

    /**
     * 31.7.1 entry points: `setData(java.util.List)` / `setData(java.util.List, boolean)`.
     *
     * The method is matched by name only, so both overloads are covered and the list is always
     * `args[0]`. Same rule as above: filtering happens before the adapter takes the list, so the
     * host never sees an inconsistent count.
     */
    @OnBefore("setData")
    fun beforeSetData(params: MethodParam) {
        val list = params.args.firstOrNull() as? List<*> ?: return
        filterInPlace(params, list)
    }

    /**
     * 就地剔除广告项，并把适配器里与入参同源的平行列表抹平。
     *
     * 为什么需要「抹平」：`X.C1790160wg5` 这类子类在 `LJLILLLLZI(list)` 重写体里先把自己的
     * `N`（`FeedArrayList`，`extends ArrayList`）填成入参内容，**然后才** `super.LJLILLLLZI(...)`，
     * 所以基类的 onBefore 触发时 `N` 已经是未过滤的旧内容。`getCount()` 读基类 `s.LIZ`，
     * 而 `LJJLIIJ`/`LJJLIIIJJI`/`getItemPosition` 读 `N` —— 只过滤一边就会索引错位，
     * 表现为页面反复重建、视频与卡片对不上。因此两边都要过滤。
     *
     * `FeedArrayList` 的所有结构修改都会检查是否在主线程（`Ensure.LJ(...,"feed_adapter_data")`），
     * 所以非主线程时只替换入参、不碰 `N`，免得替抖音制造「多线程改数据源」上报。
     */
    @Suppress("UNCHECKED_CAST")
    private fun filterInPlace(params: MethodParam, list: List<*>?) {
        if (!config.isAdBlock) return
        val src = list ?: return
        if (src.isEmpty()) return

        val total = src.size
        val kept = src.filterNot { isAdAweme(it) }
        val removed = total - kept.size

        // Fail open: never empty a whole table. The host treats `getCount() == 0` as a
        // "replace the table without notifying" branch, which leaves the pager's expected
        // count permanently stale.
        if (kept.isEmpty()) {
            KLogCat.tagI(TAG, "skip: cleaning would empty the whole table ($total items), fail open")
            return
        }

        if (removed <= 0) {
            if (lastLoggedSize != total) {
                lastLoggedSize = total
                KLogCat.tagI(TAG, "整表 $total 项, 未发现广告")
            }
            return
        }

        val ads = src.filter { isAdAweme(it) }

        // 1) 过滤入参本身：优先原地改（保留实例），不可变列表退化为替换参数
        val mutated = runCatching {
            val mutable = src as MutableList<Any?>
            mutable.retainAll { !isAdAweme(it) }
            mutable.size == kept.size
        }.getOrDefault(false)

        if (!mutated) {
            // 抖音随后自己会 notify，b 与 count 依旧同步
            params.setArgs(arrayOf<Any?>(kept))
        }

        // 2) 抹平适配器内的平行副本（子类 N 等）
        val adapter = params.thisObject
        if (adapter != null) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                scrubParallelLists(adapter, ads)
            } else {
                KLogCat.tagI(TAG, "非主线程调用, 跳过平行列表抹平(避免宿主的多线程修改上报)")
            }
        }

        removedTotal += removed
        KLogCat.tagI(
            TAG,
            "整表 $total 项, 剔除广告 $removed 项, 剩余 ${kept.size} 项" +
                " (inPlace=$mutated, 累计剔除 $removedTotal)",
        )
    }

    /**
     * 遍历适配器实例（含父类）所有 `List` 类型字段，凡「确实含有我们刚剔除的那几个广告对象」
     * （用引用相等判定）的列表，一并剔除。用产地校验避免误伤无关列表。
     */
    @Suppress("UNCHECKED_CAST")
    private fun scrubParallelLists(adapter: Any, ads: List<Any?>) {
        var cls: Class<*>? = adapter.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (!List::class.java.isAssignableFrom(field.type)) continue

                val value = runCatching {
                    field.isAccessible = true
                    field.get(adapter)
                }.getOrNull() as? MutableList<Any?> ?: continue

                if (value.isEmpty()) continue
                val ownsAd = ads.any { ad -> value.any { it === ad } }
                if (!ownsAd) continue

                runCatching { value.retainAll { !isAdAweme(it) } }
                    .onFailure { KLogCat.tagI(TAG, "平行列表剔除失败: ${it.message}") }
            }
            cls = cls.superclass
        }
    }

    /**
     * 是否广告项。依据 `Aweme.isAd`（Gson 名 `is_ads`，public 字段）。
     * 读不到就按非广告处理 —— 宁可放过，不可误杀，更不能因为读失败去改 count。
     */
    private fun isAdAweme(item: Any?): Boolean {
        if (item == null) return false
        val cls = item.javaClass
        if (noAdField.contains(cls)) return false

        val cached = adFieldCache[cls]
        val field = if (cached != null) {
            cached
        } else {
            val found = runCatching { cls.getField("isAd").apply { isAccessible = true } }.getOrNull()
            if (found == null) {
                noAdField.add(cls)
                return false
            }
            adFieldCache[cls] = found
            found
        }

        return runCatching { field.getBoolean(item) }.getOrDefault(false)
    }
}
