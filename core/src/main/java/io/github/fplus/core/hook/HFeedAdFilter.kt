// Kotlin · HFeedAdFilter.kt · Android / Xposed · 未验证适配器布局原样放行
package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.hook.logic.FeedFilterSafety
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 保留 31.x/40.x 整表入口，便于后续验证，但不依据反射猜测数据副本同步关系。
 *
 * 基类 onBefore 可能发生在子类已填好平行副本之后；逐份 retainAll 无法保证原子性。
 * 当前没有已验证的最外层子类整表写入契约，因此所有 adapter 布局原样放行。
 * 实际剔除仍由 HFeedSourceFilter 在已验证的响应源列表完成。
 *
 * 不改 count/getItem，不挂 notifyDataSetChanged，不阻断 bind，也不关闭 isAdBlock。
 */
class HFeedAdFilter : BaseHook() {
    companion object {
        private const val TAG = "HFeedAdFilter"
        private data class EntryKey(val adapter_class: Class<*>, val signature: String)
        private val skipped_entries = Collections.newSetFromMap(ConcurrentHashMap<EntryKey, Boolean>())
    }

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.abstractFeedAdapterClazz ?: NoneHook::class.java

    override fun onInit() {
        FeedFilterSafety.guarded {
            KLogCat.tagI(TAG, "整表入口已注册；未验证 adapter 布局保持原样，target=${targetClass.name}")
        }
    }

    @OnBefore("LJLILLLLZI")
    fun beforeSetItems(params: MethodParam) {
        preserve_unverified_entry(params)
    }

    @OnBefore("LJLJI")
    fun beforeSetItemsWithFlag(params: MethodParam) {
        preserve_unverified_entry(params)
    }

    /** 名称匹配保留 setData(List) 和 setData(List, boolean) 两种入口。 */
    @OnBefore("setData")
    fun beforeSetData(params: MethodParam) {
        preserve_unverified_entry(params)
    }

    private fun preserve_unverified_entry(params: MethodParam) {
        if (!ConfigV1.get().isAdBlock) return
        if (params.args.firstOrNull() !is List<*>) return
        val adapter = params.thisObject ?: return
        FeedFilterSafety.guarded {
            val method = params.method as? Method ?: return@guarded
            val signature = "${method.name}(${method.parameterTypes.joinToString { it.name }})"
            if (skipped_entries.add(EntryKey(adapter.javaClass, signature))) {
                KLogCat.tagI(TAG, "skip ${adapter.javaClass.name}#$signature: no verified outermost whole-table contract; args and parallel lists unchanged")
            }
        }
    }
}
