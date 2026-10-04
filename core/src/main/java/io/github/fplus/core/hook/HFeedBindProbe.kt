package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam
import java.util.Collections

/**
 * 主 feed 数据流探针（诊断用）
 *
 * 用途：判断 `VideoViewHolder`（DexKit 唯一命中的 ViewHolder 类）到底有没有参与主 feed。
 * 前几轮已经用真机否掉了两条线：`AbstractFeedAdapter`（getCount 仅 1~7 项）与
 * `VideoViewHolder.bind`（注册成功但从未被调用）。本轮改为**同时探多个方法**，
 * 只要其中任何一个被调用，就说明该类在主 feed 链路上，问题只在于入口选错；
 * 若全都不被调用，则主 feed 用的是别的 ViewHolder（其下有 26 个子类）。
 *
 * 只观测、不修改行为。默认开启，便于本轮定位；定位完成后应关闭。
 */
class HFeedBindProbe : BaseHook() {
    companion object {
        private const val TAG = "HFeedBindProbe"

        /** 每个探测点只报一次，避免刷屏 */
        private val logged = Collections.synchronizedSet(mutableSetOf<String>())

        /** 累计 bind 次数与广告数 */
        private var bindCount = 0
        private var adCount = 0
        private var lastReport = 0

        /**
         * 诊断开关，默认关闭。
         *
         * 已完成使命：真机确认 `VideoViewHolder` 的 `bind` / `getAweme` / `onViewHolderSelected`
         * 都会被主 feed 调用，据此另建了 `HFeedAdBlock` 做实际拦截。留此探针便于日后排查。
         */
        @Volatile
        var enabled: Boolean = false

        private fun hit(name: String) {
            if (logged.add(name)) KLogCat.tagI(TAG, "[命中] $name() 被调用")
        }
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> =
        DexkitBuilder.videoViewHolderClazz ?: NoneHook::class.java

    /** 顺带把目标类上实际能找到的候选方法列出来，确认 xpler 的检索范围 */
    override fun onInit() {
        val names = targetClass.declaredMethods
            .filter { it.name in setOf("bind", "unBind", "getAweme", "onViewHolderSelected", "bindStoryList") }
            .map { m -> "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})" }
            .sorted()
        KLogCat.tagI(TAG, "探针已注册, target=${targetClass.name}")
        KLogCat.tagI(TAG, "该类的候选方法: ${if (names.isEmpty()) "(无)" else names.joinToString(" / ")}")
    }

    @OnAfter("getAweme")
    fun getAwemeAfter(params: MethodParam) {
        if (!enabled) return
        hit("getAweme")
    }

    @OnAfter("unBind")
    fun unBindAfter(params: MethodParam) {
        if (!enabled) return
        hit("unBind")
    }

    @OnAfter("onViewHolderSelected")
    fun onViewHolderSelectedAfter(params: MethodParam) {
        if (!enabled) return
        hit("onViewHolderSelected")
    }

    @OnAfter("bind")
    fun bindAfter(params: MethodParam) {
        if (!enabled) return
        hit("bind")

        val aweme = params.args.firstOrNull() ?: return
        val isAd = readIsAd(aweme)
        bindCount++
        if (isAd) adCount++

        if (bindCount - lastReport >= 20) {
            lastReport = bindCount
            KLogCat.tagI(TAG, "bind 统计: 累计 $bindCount 项, 其中广告 $adCount 项")
        }
    }

    private fun readIsAd(aweme: Any): Boolean = runCatching {
        aweme.javaClass.getField("isAd").getBoolean(aweme)
    }.getOrDefault(false)
}
