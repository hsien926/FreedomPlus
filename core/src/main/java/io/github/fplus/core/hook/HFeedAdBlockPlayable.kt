package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.lparam
import io.github.xpler.core.proxy.MethodParam

/**
 * 信息流广告拦截 —— 可玩广告的 ViewHolder 覆盖
 *
 * 背景：`HFeedAdBlock` 挂在 `VideoViewHolder.bind` 上。实测主 feed 的普通项会走那里
 * （真机 bind 被调用 120+ 次），但**可玩广告不走**——它们用的是
 * `PlayableVideoViewHolder` / `InteractivePlayableVideoViewHolder`，
 * 这两个类各自 `public final bind(Aweme)` **重写了父类方法**（final，不转发 super），
 * 因此挂在父类上的 hook 对它们无效。这里为它们各补一个 hook。
 *
 * 判定与阻断逻辑与 `HFeedAdBlock` 一致：读 `Aweme.isAd`（`@SerializedName("is_ads")`），
 * 命中即 `setResultVoid()` 阻断 bind。
 */
abstract class FeedPlayableAdBlockBase : BaseHook() {
    protected val config get() = ConfigV1.get()

    /** 按类名加载；类不存在时退化为 NoneHook（xpler 会安全跳过） */
    protected fun loadOrNone(name: String): Class<*> = try {
        Class.forName(name, false, lparam.classLoader)
    } catch (_: Throwable) {
        NoneHook::class.java
    }

    protected fun handleBind(params: MethodParam, tag: String) {
        if (!config.isAdBlock) return
        val aweme = params.args.firstOrNull() ?: return
        if (!isAdAweme(aweme)) return

        KLogCat.tagI(tag, "拦截可玩广告项, class=${aweme.javaClass.simpleName}")
        params.setResultVoid()
    }

    private fun isAdAweme(item: Any): Boolean = runCatching {
        item.javaClass.getField("isAd").getBoolean(item)
    }.getOrDefault(false)
}

/**
 * 可玩广告 ViewHolder。
 * 注意：注解方法必须写在本类里 —— xpler 只扫描 `this::class.java.declaredMethods`，
 * 写在基类里不会被识别。
 */
class HFeedAdBlockPlayable : FeedPlayableAdBlockBase() {
    override fun setTargetClass(): Class<*> =
        loadOrNone("com.ss.android.ugc.aweme.feed.adapter.playable.PlayableVideoViewHolder")

    override fun onInit() {
        KLogCat.tagI("HFeedAdBlock", "可玩广告拦截已注册, target=${targetClass.name}")
    }

    @OnBefore("bind")
    fun bindBefore(params: MethodParam) {
        handleBind(params, "HFeedAdBlock")
    }
}

/** 互动式可玩广告 ViewHolder */
class HFeedAdBlockPlayableInteractive : FeedPlayableAdBlockBase() {
    override fun setTargetClass(): Class<*> =
        loadOrNone("com.ss.android.ugc.aweme.feed.adapter.playable.InteractivePlayableVideoViewHolder")

    override fun onInit() {
        KLogCat.tagI("HFeedAdBlock", "互动可玩广告拦截已注册, target=${targetClass.name}")
    }

    @OnBefore("bind")
    fun bindBefore(params: MethodParam) {
        handleBind(params, "HFeedAdBlock")
    }
}
