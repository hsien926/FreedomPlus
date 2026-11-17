package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.hook.logic.SourceFilterLogic
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.proxy.MethodParam

/**
 * Filtering inside the feed response callback — the only place where an entry can be dropped
 * without breaking the host's positional bookkeeping.
 *
 * Both presenters are resolved by DexKit, never by a hard-coded obfuscated name:
 *  - recommend feed  -> `recommendFeedFetchPresenter` (`onSuccess`)
 *  - follow feed     -> `fullFeedFollowFetchPresenter` (`onSuccess`)
 *
 * If either class cannot be resolved the hook degrades to `NoneHook` and nothing happens;
 * if the access path inside the callback is unknown, [SourceFilterLogic] fails open and the
 * data is handed to the host untouched.
 */
class HFeedSourceFilter {
    companion object {
        private const val TAG = "HFeedSourceFilter"
    }

    init {
        HRecommendSource()
        HFollowSource()
    }

    /** Recommend feed: list elements are the items themselves. */
    inner class HRecommendSource : BaseHook() {
        override fun setTargetClass(): Class<*> =
            DexkitBuilder.recommendFeedFetchPresenterClazz ?: NoneHook::class.java

        override fun onInit() {
            KLogCat.tagI(TAG, "recommend source hook registered, target=${targetClass.name}")
        }

        @OnBefore("onSuccess")
        fun onSuccessBefore(params: MethodParam) {
            if (!ConfigV1.get().isAdBlock) return
            SourceFilterLogic.filter(params.thisObject, wrapped = false, label = "recommend")
        }
    }

    /** Follow feed: each element wraps the item in a field. */
    inner class HFollowSource : BaseHook() {
        override fun setTargetClass(): Class<*> =
            DexkitBuilder.fullFeedFollowFetchPresenterClazz ?: NoneHook::class.java

        override fun onInit() {
            KLogCat.tagI(TAG, "follow source hook registered, target=${targetClass.name}")
        }

        @OnBefore("onSuccess")
        fun onSuccessBefore(params: MethodParam) {
            if (!ConfigV1.get().isAdBlock) return
            SourceFilterLogic.filter(params.thisObject, wrapped = true, label = "follow")
        }
    }
}
