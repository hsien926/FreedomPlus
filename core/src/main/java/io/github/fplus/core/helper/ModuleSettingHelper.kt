// Kotlin · ModuleSettingHelper.kt · Android 7+ / Xposed · 模块设置入口
package io.github.fplus.core.helper

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freegang.extension.isDarkMode
import com.freegang.ktutils.app.KAppUtils
import com.freegang.ktutils.app.KToastUtils
import io.github.fplus.Constant
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.ui.activity.FreedomSettingActivity

/**
 * 模块设置入口。
 *
 * - 内嵌插件模式(默认): 拉起抖音进程内的 [FreedomSettingActivity];
 * - 去插件化模式([ConfigV1.isDisablePlugin] 为 true): 跳转独立模块 App 的 MainActivity。
 *
 * 侧滑栏入口、角落弹窗入口、长按上半入口共用这一套打开逻辑。
 */
object ModuleSettingHelper {
    private const val MODULE_MAIN_ACTIVITY = "io.github.fplus.activity.MainActivity"

    private val open_gate = MonotonicOpenGate()

    fun open(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { open(context) }
            return
        }
        val intent = Intent()
        if (ConfigV1.get().isDisablePlugin) {
            if (!KAppUtils.isAppInstalled(context, Constant.modulePackage)) {
                KToastUtils.show(context, "未安装Freedom+模块!")
                return
            }
            intent.setClassName(Constant.modulePackage, MODULE_MAIN_ACTIVITY)
        } else {
            intent.setClass(context, FreedomSettingActivity::class.java)
        }
        val now = SystemClock.elapsedRealtime()
        if (!open_gate.try_acquire(now)) return
        try {
            intent.putExtra("isDark", context.isDarkMode)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeCustomAnimation(
                context, android.R.anim.slide_in_left, android.R.anim.slide_out_right,
            )
            context.startActivity(intent, options.toBundle())
            if (ConfigV1.get().isDisablePlugin) KToastUtils.show(context, "若设置未生效请尝试重启抖音!")
        } catch (error: Exception) {
            open_gate.release(now)
            throw error
        }
    }
}