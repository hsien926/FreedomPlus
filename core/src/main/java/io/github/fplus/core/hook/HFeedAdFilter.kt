package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam

/**
 * 信息流广告过滤（40.6.0 适配版）
 *
 * ## 为什么不是 fork 的老做法
 * fork 原本把过滤挂在 `RecommendFeedFetchPresenter` / `FullFeedFollowFetchPresenter` 的
 * `onSuccess` 上，改写 `mModel.mData.items`。真机核对 40.6.0 后确认这条路已彻底断掉：
 *   1. 那两个 Presenter 类不存在；
 *   2. 持有 `FeedItemList` 字段的 55 个候选类中，没有任何一个含 `onSuccess` 方法；
 *   3. `FeedItemList.items` 全仓仅 3 处写入且都在其自身文件内（Gson 反序列化填充），
 *      没有可供 hook 的写入点。
 *
 * ## 现在的做法：在 adapter 层做「位置映射」
 * `AbstractFeedAdapter` 是本模块已有的、40.6.0 上能命中的锚点，且挂着三个**未被混淆**的方法：
 *   - `getCount()I`                 数据条数
 *   - `getItem(I)L.../Aweme;`       按位置取项
 *   - `getItems()Ljava/util/List;`  取数据（实测只返回 0~1 项的缓存视图，**不能**当完整列表用）
 *
 * 因此不依赖 `getItems()`，改为：
 *   1. 在 `getCount()` 里按原始总数逐项扫描一次，得到「过滤后位置 → 原始位置」映射；
 *   2. `getCount()` 返回映射长度（RecyclerView 只会绑定这么多项）；
 *   3. `getItem(position)` 收到的是**过滤后**的位置，翻译回原始位置再取真实项。
 * 两者必须同时改，否则会错位或越界。
 *
 * 判定依据是 `Aweme.isAd`（public 字段，实测存在）。全程用字段名 + 反射，**不引用 aweme 代理类**
 * —— 那些类是 compileOnly、不打进 APK，运行时由抖音提供，一旦目标版本缺失就会抛
 * NoClassDefFoundError。
 */
class HFeedAdFilter : BaseHook() {
    companion object {
        private const val TAG = "HFeedAdFilter"

        /** 过滤后位置 -> 原始位置 */
        @Volatile
        private var mapping: IntArray? = null

        /** 映射对应的 adapter 实例与原始总数，用于判断缓存是否过期 */
        @Volatile
        private var sourceRef: Any? = null

        @Volatile
        private var lastTotal = -1

        /** 扫描期间置位，避免递归进自己的 hook */
        @Volatile
        private var scanning = false

        private var totalRemoved = 0
        private var lastLoggedTotal = -1
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.abstractFeedAdapterClazz ?: NoneHook::class.java

    /** 确认真机日志里能看到注册与目标类 */
    override fun onInit() {
        KLogCat.tagI(TAG, "已注册, target=${targetClass.name}")
    }

    /** 条数改为过滤后的大小（顺带按需重建映射） */
    @OnAfter("getCount")
    fun getCountAfter(params: MethodParam) {
        if (!config.isAdBlock) return
        if (scanning) return
        val total = params.result as? Int ?: return
        val map = ensureMapping(params.thisObject, total) ?: return
        params.setResult(map.size)
    }

    /** 位置翻译：入参是过滤后的位置，改取原始位置上的真实项 */
    @OnAfter("getItem")
    fun getItemAfter(params: MethodParam, position: Int) {
        if (scanning) return
        if (!config.isAdBlock) return
        val map = mapping ?: return
        val adapter = params.thisObject ?: return
        if (sourceRef !== adapter) return

        val original = map.getOrNull(position) ?: return
        if (original == position) return   // 前面没有广告，位置无需翻译

        rawGetItem(adapter, original)?.let { params.setResult(it) }
    }

    /**
     * 按原始总数重建映射。
     *
     * 这里**不按 total 做缓存**：实测抖音该 adapter 的 total 长期稳定在个位数（1~7 项，
     * 属滑动加载的窗口），但窗口里的内容会随滑动变化 —— 若只比较 total 就会漏掉
     * 「total 没变但内容换了」的情况，导致广告检测不到。项数很小，每次重扫开销可忽略。
     */
    private fun ensureMapping(adapter: Any?, total: Int): IntArray? {
        if (adapter == null || total <= 0) return null

        scanning = true
        val kept = ArrayList<Int>(total)
        try {
            for (i in 0 until total) {
                val item = rawGetItem(adapter, i)
                if (!isFilteredAweme(item)) kept.add(i)
            }
        } finally {
            scanning = false
        }

        val arr = kept.toIntArray()
        mapping = arr
        sourceRef = adapter
        lastTotal = total

        val removed = total - arr.size
        if (removed > 0) {
            totalRemoved += removed
            KLogCat.tagI(
                TAG,
                "映射重建: 原始 $total 项, 广告 $removed 项, 过滤后 ${arr.size} 项 (累计移除 $totalRemoved)",
            )
        } else if (lastLoggedTotal != total) {
            lastLoggedTotal = total
            KLogCat.tagI(TAG, "映射重建: 原始 $total 项, 未发现广告项")
        }
        return arr
    }

    /**
     * 反射调用原始 `getItem(int)`。
     * 扫描期间 `scanning=true`，因此这次调用不会再进 [getItemAfter]，不会递归。
     */
    private fun rawGetItem(adapter: Any, position: Int): Any? = runCatching {
        adapter.javaClass
            .getMethod("getItem", Int::class.javaPrimitiveType)
            .invoke(adapter, position)
    }.getOrNull()

    /**
     * 是否属于要过滤掉的广告项。
     * 依据 `Aweme.isAd`（public 字段）。读不到就当作不广告——宁可放过，不可误杀。
     */
    private fun isFilteredAweme(item: Any?): Boolean {
        if (item == null) return false
        return runCatching {
            item.javaClass.getField("isAd").getBoolean(item)
        }.getOrDefault(false)
    }
}
