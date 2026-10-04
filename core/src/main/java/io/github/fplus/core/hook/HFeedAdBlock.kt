package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam

/**
 * 信息流广告拦截（40.6.0）
 *
 * ## 挂载点的来历
 * 前面几轮用真机逐个否掉了这些候选：
 *   - `RecommendFeedFetchPresenter` / `FullFeedFollowFetchPresenter`（fork 原方案）—— 40.6.0 已无此类
 *   - `AbstractFeedAdapter` —— getCount 仅 1~7 项、滑动后不再被调用，非主 feed
 *   - `HolderObserver` —— 反编译确认只把 isAd 汇入统计打点，不做拦截
 * 最终靠探针在真机确认：`VideoViewHolder` 的 `bind` / `getAweme` / `onViewHolderSelected`
 * **确实会被主 feed 调用**（前一轮"bind 未被调用"的结论是我的日志阈值设成每 20 次才输出的误判）。
 *
 * ## 做法
 * 在 `VideoViewHolder.bind(...)` 前置拦截：命中 `isAd` 的项直接阻断方法体，让它不被绑定到视图上。
 * 判定用 `Aweme.isAd`（public 字段，`getIsAdAweme()` 的字节码就是直接读它），全程反射、不引用
 * aweme 代理类（那些是 compileOnly，缺类时会抛 NoClassDefFoundError）。
 */
class HFeedAdBlock : BaseHook() {
    companion object {
        private const val TAG = "HFeedAdBlock"

        private var blocked = 0
        private var bound = 0
        private var firstLogged = false

        /** awemeType 分布统计：用于判断"没有广告"还是"广告用别的标记" */
        private val typeCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        /**
         * awemeType 分布统计开关（诊断用）。
         *
         * 实测本机推荐流未出现 `isAd=true` 的项（累计观察 250 项，awemeType 分布为
         * 0/68/55 三类），因此无法在本机验证拦截效果。保留该统计便于在"能刷到广告"的
         * 环境下确认；日志阈值设为每 200 次，避免正常使用时的噪音。
         */
        @Volatile
        var statsEnabled: Boolean = true

        private const val STATS_INTERVAL = 200
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.videoViewHolderClazz ?: NoneHook::class.java

    override fun onInit() {
        KLogCat.tagI(TAG, "信息流广告拦截已注册, target=${targetClass.name}")
    }

    /** `bind(Aweme)` 与 `bind(Aweme,int)` 都会走到这里 */
    @OnBefore("bind")
    fun bindBefore(params: MethodParam) {
        if (!config.isAdBlock) return
        val aweme = params.args.firstOrNull() ?: return

        // 首项诊断：确认 bind 真的进来了、aweme 的实际类型、isAd 字段是否可读
        if (!firstLogged) {
            firstLogged = true
            val readable = runCatching { aweme.javaClass.getField("isAd"); true }.getOrDefault(false)
            KLogCat.tagI(
                TAG,
                "首个 bind 项: class=${aweme.javaClass.name}, isAd=${isAdAweme(aweme)}, isAd字段可读=$readable",
            )
        }

        bound++
        if (statsEnabled) {
            val type = readInt(aweme, "awemeType")
            typeCounts[type] = (typeCounts[type] ?: 0) + 1
            if (bound % STATS_INTERVAL == 0) {
                val dist = typeCounts.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}x${it.value}" }
                KLogCat.tagI(TAG, "awemeType 分布(累计 $bound 项): $dist")
            }
        }

        if (!isAdAweme(aweme)) return

        blocked++
        KLogCat.tagI(TAG, "拦截信息流广告项 (累计拦截 $blocked, 已放行 $bound)")
        // void 方法：阻断方法体，使该广告项不被绑定到视图
        params.setResultVoid()
    }

    /**
     * 是否广告项。依据 `Aweme.isAd` public 字段（Gson 注解为 `is_ads`）；
     * 读不到按非广告处理（宁可放过，不可误杀）。
     */
    private fun isAdAweme(item: Any): Boolean = runCatching {
        item.javaClass.getField("isAd").getBoolean(item)
    }.getOrDefault(false)

    /** 读取 int 字段，失败给 -1（诊断用） */
    private fun readInt(item: Any, field: String): Int = runCatching {
        item.javaClass.getField(field).getInt(item)
    }.getOrDefault(-1)
}
