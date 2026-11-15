// Kotlin · HHighRefreshRate.kt · Android API 24+ / LSPosed API 82 · JDK 17
package io.github.fplus.core.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.HighRefreshRatePolicy
import io.github.fplus.core.helper.RefreshMode
import io.github.xpler.core.XplerLog
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class HHighRefreshRate(private val app: Application) {
    private val display_manager by lazy {
        app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }
    private val active_activities = WeakHashMap<Activity, RefreshMode?>()
    private val applied_windows = WeakHashMap<Window, RefreshMode>()
    private val last_display_report = mutableMapOf<Int, String>()
    private val surface_reported = AtomicBoolean(false)

    @Volatile
    private var surface_rate = 0f

    fun install() {
        runCatching {
            // 开关按进程启动时读取；关闭时不注册 Hook、回调或显示监听器。
            if (!ConfigV1.get().isHighRefreshRate) return
            val window_hooks = install_window_hooks()
            val surface_hooks = if (Build.VERSION.SDK_INT >= 30) install_surface_hooks() else 0
            register_callbacks()
            XplerLog.i("HighRefreshRate: hooks windows=$window_hooks surfaces=$surface_hooks")
        }.onFailure { XplerLog.e(it) }
    }

    private fun install_window_hooks(): Int {
        var count = hook_methods(Window::class.java, {
            it.name == "setAttributes" &&
                it.parameterTypes.contentEquals(arrayOf(WindowManager.LayoutParams::class.java))
        }) { params ->
            val window = params.thisObject as? Window ?: return@hook_methods
            val attributes = params.args[0] as? WindowManager.LayoutParams ?: return@hook_methods
            rewrite_window(attributes, window_display(window))
        }
        runCatching {
            val global_class = Class.forName("android.view.WindowManagerGlobal", false, app.classLoader)
            count += hook_methods(global_class, {
                it.name in setOf("addView", "updateViewLayout") &&
                    it.parameterTypes.getOrNull(0) == View::class.java &&
                    it.parameterTypes.getOrNull(1) == ViewGroup.LayoutParams::class.java
            }) { params ->
                val view = params.args[0] as? View ?: return@hook_methods
                val attributes = params.args[1] as? WindowManager.LayoutParams ?: return@hook_methods
                val display = params.args.filterIsInstance<Display>().firstOrNull() ?: view.display
                rewrite_window(attributes, display)
            }
        }.onFailure { XplerLog.e(it) }
        return count
    }

    @RequiresApi(30)
    private fun install_surface_hooks(): Int {
        var count = hook_frame_rate(Surface::class.java, 0)
        runCatching {
            // 仅 Android 11+ 解析此类，避免低版本加载不存在的公开 Transaction API。
            val transaction_class = Class.forName(
                "android.view.SurfaceControl\$Transaction", false, app.classLoader,
            )
            count += hook_frame_rate(transaction_class, 1)
        }.onFailure { XplerLog.e(it) }
        return count
    }

    @RequiresApi(30)
    private fun hook_frame_rate(target_class: Class<*>, rate_index: Int): Int {
        return hook_methods(target_class, { method ->
            val types = method.parameterTypes
            method.name == "setFrameRate" &&
                types.size in (rate_index + 2)..(rate_index + 3) &&
                types.getOrNull(rate_index) == Float::class.javaPrimitiveType &&
                types.drop(rate_index + 1).all { it == Int::class.javaPrimitiveType } &&
                (rate_index == 0 || types[0].name == "android.view.SurfaceControl")
        }) { params ->
            val requested = params.args[rate_index] as? Float ?: return@hook_methods
            val compatibility = params.args[rate_index + 1] as? Int ?: return@hook_methods
            val strategy = params.args.getOrNull(rate_index + 2) as? Int
            val target = HighRefreshRatePolicy.override_surface_rate(
                requested, surface_rate, compatibility, strategy,
            ) ?: return@hook_methods
            params.args[rate_index] = target
            // 这里只改显示投票，不把视频的真实解码帧率伪装成高帧内容。
            params.args[rate_index + 1] = Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
            if (surface_reported.compareAndSet(false, true)) {
                XplerLog.i("HighRefreshRate: Surface 请求上调 $requested -> $target Hz（非视频补帧）")
            }
        }
    }

    private fun hook_methods(
        target_class: Class<*>,
        matches: (Method) -> Boolean,
        before: (XC_MethodHook.MethodHookParam) -> Unit,
    ): Int {
        var count = 0
        for (method in target_class.declaredMethods.filter(matches)) {
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { before(param) }.onFailure { XplerLog.e(it) }
                    }
                })
                count++
            }.onFailure { XplerLog.e(it) }
        }
        return count
    }

    private fun register_callbacks() {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                active_activities[activity] = null
                apply_window(activity)
            }

            override fun onActivityPaused(activity: Activity) = forget_activity(activity)
            override fun onActivityDestroyed(activity: Activity) = forget_activity(activity)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        display_manager?.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(display_id: Int) {
                // 模式、分辨率或折叠屏状态变化后重新选择；参数未变则不重复提交。
                active_activities.keys.toList().forEach { apply_window(it) }
            }

            override fun onDisplayAdded(display_id: Int) = Unit
            override fun onDisplayRemoved(display_id: Int) {
                last_display_report.remove(display_id)
                active_activities.keys.toList().forEach { apply_window(it) }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun forget_activity(activity: Activity) {
        active_activities.remove(activity)
        update_surface_rate()
    }

    private fun apply_window(activity: Activity) {
        runCatching {
            val window = activity.window
            val display = window_display(window)
            val selected = select_mode(display)
            active_activities[activity] = selected
            update_surface_rate()
            val attributes = window.attributes
            if (selected == null || display == null) {
                val previous = applied_windows.remove(window)
                if (previous != null && attributes.preferredDisplayModeId == previous.mode_id &&
                    attributes.preferredRefreshRate == previous.refresh_rate
                ) {
                    attributes.preferredDisplayModeId = 0
                    attributes.preferredRefreshRate = 0f
                    window.attributes = attributes
                }
                return@runCatching
            }
            if (rewrite_window(attributes, display, selected)) window.attributes = attributes
            applied_windows[window] = selected
            val report = "mode=${selected.mode_id} ${selected.width}x${selected.height} " +
                "requested=${selected.refresh_rate}Hz observed=${display.refreshRate}Hz"
            if (last_display_report[display.displayId] != report) {
                last_display_report[display.displayId] = report
                XplerLog.i("HighRefreshRate: display=${display.displayId} $report")
            }
        }.onFailure {
            active_activities[activity] = null
            update_surface_rate()
            XplerLog.e(it)
        }
    }

    private fun update_surface_rate() {
        surface_rate = HighRefreshRatePolicy.common_rate(
            active_activities.values.map { it?.refresh_rate ?: 0f },
        )
    }

    private fun rewrite_window(
        attributes: WindowManager.LayoutParams,
        display: Display?,
        selected: RefreshMode? = select_mode(display),
    ): Boolean {
        if (selected == null || attributes.type !in
            WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
            WindowManager.LayoutParams.LAST_APPLICATION_WINDOW
        ) return false
        if (attributes.preferredDisplayModeId == selected.mode_id &&
            attributes.preferredRefreshRate == selected.refresh_rate
        ) return false
        attributes.preferredDisplayModeId = selected.mode_id
        attributes.preferredRefreshRate = selected.refresh_rate
        return true
    }

    @Suppress("DEPRECATION")
    private fun window_display(window: Window): Display? {
        return window.peekDecorView()?.display ?: window.windowManager?.defaultDisplay
    }

    private fun select_mode(display: Display?): RefreshMode? {
        if (display == null) return null
        fun Display.Mode.describe() = RefreshMode(modeId, physicalWidth, physicalHeight, refreshRate)
        return HighRefreshRatePolicy.select_mode(
            display.mode.describe(), display.supportedModes.map { it.describe() },
        )
    }
}
