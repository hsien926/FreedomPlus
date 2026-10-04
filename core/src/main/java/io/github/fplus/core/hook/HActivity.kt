package io.github.fplus.core.hook

import android.app.Activity
import android.view.MotionEvent
import androidx.core.view.updatePadding
import com.freegang.extension.contentView
import com.freegang.extension.navBarInteractionMode
import com.freegang.extension.navigationBarHeight
import com.ss.android.ugc.aweme.live.LivePlayActivity
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.ImmersiveHelper
import io.github.fplus.core.ui.activity.FreedomSettingActivity
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HActivity : BaseHook() {
    private val config get() = ConfigV1.get()

    companion object {
        /** 横屏视频页；该页在 focus 变化时需要强制全沉浸，见 onWindowFocusChangedAfter */
        private const val LANDSCAPE_FEED_ACTIVITY =
            "com.ss.android.ugc.aweme.longervideo.landscape.home.activity.LandscapeFeedActivity"
    }

    override fun setTargetClass(): Class<*> {
        return Activity::class.java
    }

    @OnBefore("dispatchTouchEvent")
    fun dispatchTouchEventBefore(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            val activity = thisObject as Activity
            DouYinMain.freeExitHelper?.restart()

            if (activity is FreedomSettingActivity)
                return

            if (activity is LivePlayActivity)
                DouYinMain.freeExitHelper?.cancel()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onResume")
    fun onResumeBefore(params: MethodParam) {
        hookBlockRunning(params) {
            val activity = thisObject as Activity

            if (activity is LivePlayActivity) {
                DouYinMain.freeExitHelper?.cancel()
            } else {
                DouYinMain.freeExitHelper?.restart()
            }

            if (DouYinMain.timerExitHelper?.isPaused == true) {
                DouYinMain.timerExitHelper?.restart()
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onWindowFocusChanged")
    fun onWindowFocusChangedAfter(params: MethodParam, boolean: Boolean) {
        hookBlockRunning(params) {
            singleLaunchMain {
                val activity = thisObject as Activity

                if (activity is FreedomSettingActivity)
                    return@singleLaunchMain

                // 横屏视频页强制全沉浸（不看用户配置）。
                // 这里补的原因：本项目早先在 HLandscapeFeedActivity 里用
                // @OnAfter("onWindowFocusChanged") 想达到同样效果，但该方法定义在父类
                // android.app.Activity 上，而 xpler 只检索目标类的 declaredMethods
                // （见 HookEntity.getHookTargetAllMethods），因此那个 hook 从未绑上。
                if (activity.javaClass.name == LANDSCAPE_FEED_ACTIVITY) {
                    ImmersiveHelper.immersive(
                        activity = activity,
                        hideStatusBar = true,
                        hideNavigationBars = true,
                    )
                    return@singleLaunchMain
                }

                immersive(activity)
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun immersive(activity: Activity) {
        if (config.isImmersive) {
            ImmersiveHelper.immersive(
                activity = activity,
                hideStatusBar = config.systemControllerValue[0],
                hideNavigationBars = config.systemControllerValue[1],
            )
            ImmersiveHelper.systemBarColor(activity)

            // 底部三键导航
            if (activity.navBarInteractionMode == 0 && !config.systemControllerValue[1]) {
                activity.contentView.apply {
                    updatePadding(bottom = context.navigationBarHeight)
                }
            }
        }
    }
}