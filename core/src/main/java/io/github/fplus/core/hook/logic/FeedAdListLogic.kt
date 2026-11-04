package io.github.fplus.core.hook.logic

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 信息流广告在「数据层」的判定与清理工具。
 *
 * 被两处复用：
 * - `HFeedAdFilter`：整表落表（`AbstractFeedAdapter.LJLILLLLZI` / `LJLJI`）时剔除广告；
 * - `HFeedAdNotifySweep`：宿主调用 `notifyDataSetChanged()` 前，把增量插入的广告再清一遍。
 *
 * 两者共用同一套 `isAd` 判定与「实例内所有 List 字段」遍历逻辑，避免两份实现漂移。
 *
 * ## 为什么按「引用相等」删
 *
 * 抖音的广告项在 Adapter 里可能同时被多份列表持有（基类 `s.LIZ`、子类的
 * `FeedArrayList N` 等）。按引用删除能保证：只删我们自己判定为广告的那几个对象，
 * 不会因为 `equals` 的语义差异误删正常视频。
 */
object FeedAdListLogic {

    /** `Aweme.isAd` 字段：按类缓存（`getField` 每次都要遍历，feed 每页几十项时值得缓存） */
    private val adFieldCache = ConcurrentHashMap<Class<*>, Field>()

    /** 明确没有 isAd 字段的类，避免每项都白跑一次 getField */
    private val noAdField: MutableSet<Class<*>> =
        Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())

    /** 每个类上「List 类型字段」的检索结果缓存（notify 是高频入口，不能每次都遍历 declaredFields） */
    private val listFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * 是否广告项。依据 `Aweme.isAd`（Gson 名 `is_ads`，public 字段）。
     * 读不到按非广告处理 —— 宁可放过，不可误杀。
     */
    fun isAdAweme(item: Any?): Boolean {
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

    /**
     * 遍历实例（含父类）所有非 static 的 `List` 字段，返回当前实际持有的列表。
     *
     * 读不到的字段（权限/类型不符）直接跳过，不抛异常 —— 调用方据此决定「能不能清理」。
     */
    fun collectListFields(holder: Any): List<MutableList<Any?>> {
        val fields = listFieldCache.computeIfAbsent(holder.javaClass) { clazz ->
            val result = mutableListOf<Field>()
            var cursor: Class<*>? = clazz
            while (cursor != null && cursor != Any::class.java) {
                for (field in cursor.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (!List::class.java.isAssignableFrom(field.type)) continue
                    result.add(field)
                }
                cursor = cursor.superclass
            }
            result
        }

        val result = mutableListOf<MutableList<Any?>>()
        for (field in fields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(holder)
            }.getOrNull() as? MutableList<Any?> ?: continue

            result.add(value)
        }
        return result
    }

    /**
     * 按引用相等从列表里剔除 [ads]，返回实际剔除数量。
     * 列表不可变或剔除过程抛异常时返回 0（宿主数据保持原样，等价于放行）。
     */
    fun removeByIdentity(list: MutableList<Any?>, ads: List<Any?>): Int {
        if (ads.isEmpty() || list.isEmpty()) return 0

        var removed = 0
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val current = iterator.next()
            if (ads.any { it === current }) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    /** 列表里是否持有 [ads] 中任意一个对象（引用相等） */
    fun ownsAny(list: List<Any?>, ads: List<Any?>): Boolean {
        if (ads.isEmpty()) return false
        return list.any { current -> ads.any { it === current } }
    }
}
