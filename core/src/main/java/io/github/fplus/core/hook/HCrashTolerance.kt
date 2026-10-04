package io.github.fplus.core.hook

import com.ss.android.ugc.aweme.kiwi.model.QModel
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.entity.CallMethods
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

/// 崩溃容错，处理官方可能造成的系列崩溃问题
class HCrashTolerance {
    companion object {
        const val TAG = "HCrashTolerance"
    }

    val config get() = ConfigV1.get()

    init {
        HPoiFeed()
        HLivePhoto()
        HTabLanding()
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
}