// Kotlin · HPowerKeeperHighRefreshRate.kt · Android API 30+ / LSPosed API 82 · JDK 17
package io.github.fplus.core.hook

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.helper.HighRefreshConfigClient
import io.github.fplus.core.helper.HighRefreshRatePolicy
import io.github.fplus.core.helper.HighRefreshSharedState
import io.github.fplus.core.helper.HighRefreshSyncPolicy
import io.github.fplus.core.helper.RefreshMode
import io.github.fplus.core.helper.SystemHighRefreshPolicy
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

internal object HPowerKeeperHighRefreshRate {
    private const val tag = "Freedom+RefreshSystem"
    private const val class_name = "com.miui.powerkeeper.statemachine.DisplayFrameSetting"
    private const val refresh_interval_ms = 5_000L
    private const val cache_max_age_ms = 20_000L
    private val installed = AtomicBoolean(false)
    private val observer_registered = AtomicBoolean(false)
    private val failure_reported = AtomicBoolean(false)
    private val state_lock = Any()
    private val state_handler by lazy {
        Handler(HandlerThread("FreedomHighRefreshState").apply { start() }.looper)
    }
    private lateinit var effect_method: Method
    private lateinit var context_field: Field
    private lateinit var screen_field: Field
    private lateinit var video_cookie_field: Field
    private lateinit var current_cookie_field: Field
    private lateinit var foreground_field: Field

    @Volatile
    private var shared_state = HighRefreshSharedState()
    private var last_state_read_ms = -1L
    private var next_state_read_ms = 0L
    private var last_request: Request? = null
    private var last_report = ""

    private data class Request(
        val receiver: WeakReference<Any>,
        val context: Context,
        val package_name: String,
        val requested_rate: Int,
        val cookie: Int,
        val video_cookie: Int,
        val handler: Handler?,
    )

    fun install(class_loader: ClassLoader) {
        if (Build.VERSION.SDK_INT < 30 || !installed.compareAndSet(false, true)) return
        runCatching {
            val target_class = Class.forName(class_name, false, class_loader)
            effect_method = target_class.getDeclaredMethod(
                "setScreenEffect", String::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            context_field = field(target_class, "mContext")
            screen_field = field(target_class, "mCurScreenEnabledId")
            video_cookie_field = field(target_class, "COOKIE_VIDEO")
            current_cookie_field = field(target_class, "mCurrentCookie")
            foreground_field = field(target_class, "mCurrentDefaultDisplayInfo")
            check(effect_method.returnType == Void.TYPE) { "PowerKeeper方法返回类型不匹配" }
            check(Context::class.java.isAssignableFrom(context_field.type)) { "PowerKeeper上下文字段不匹配" }
            for (integer_field in listOf(screen_field, video_cookie_field, current_cookie_field)) {
                check(integer_field.type == Int::class.javaPrimitiveType) { "PowerKeeper整数字段不匹配" }
            }
            XposedBridge.hookMethod(effect_method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { process_request(param) }.onFailure { report_failure(it) }
                }
            })
            report("SystemHighRefresh: 已注册PowerKeeper主屏视频策略；只处理抖音，cookie原样保留")
        }.onFailure {
            installed.set(false)
            report_failure(it)
        }
    }

    private fun process_request(param: XC_MethodHook.MethodHookParam) {
        val receiver = param.thisObject ?: return
        val package_name = param.args[0] as? String
        if (package_name != HighRefreshSyncPolicy.host_package) {
            synchronized(state_lock) { last_request = null }
            return
        }
        val app_context = (context_field.get(receiver) as? Context)?.applicationContext ?: return
        val requested_rate = param.args[1] as? Int ?: return
        val cookie = param.args[2] as? Int ?: return
        val video_cookie = video_cookie_field.getInt(
            if (Modifier.isStatic(video_cookie_field.modifiers)) null else receiver,
        )
        val main_scene = is_main_scene(receiver, app_context) &&
            foreground_package(receiver) == HighRefreshSyncPolicy.host_package
        if (!SystemHighRefreshPolicy.is_target_video_request(
                package_name, requested_rate, cookie, video_cookie, main_scene,
            )) {
            // 清除、熄屏、热限制或其它场景到来后，旧视频请求立即失效。
            synchronized(state_lock) { last_request = null }
            return
        }
        val request = Request(
            WeakReference(receiver), app_context, package_name, requested_rate,
            cookie, video_cookie, Looper.myLooper()?.let { Handler(it) },
        )
        synchronized(state_lock) { last_request = request }
        schedule_state_refresh(app_context)
        val target_rate = choose_rate(request, receiver)
        if (target_rate != null) param.args[1] = target_rate
        report_decision(request, target_rate ?: requested_rate, "request")
    }

    private fun schedule_state_refresh(app_context: Context) {
        val now = SystemClock.elapsedRealtime()
        val due = synchronized(state_lock) {
            if (now < next_state_read_ms) false else {
                next_state_read_ms = now + refresh_interval_ms
                true
            }
        }
        if (!due) return
        // Provider启动及Binder读写不进入PowerKeeper的原策略线程。
        state_handler.post {
            runCatching {
                if (!observer_registered.get()) {
                    val observer = object : ContentObserver(state_handler) {
                        override fun onChange(selfChange: Boolean) {
                            refresh_shared_state(app_context)
                        }
                    }
                    app_context.contentResolver.registerContentObserver(
                        HighRefreshConfigClient.state_uri, true, observer,
                    )
                    observer_registered.set(true)
                }
                refresh_shared_state(app_context)
            }.onFailure {
                update_shared_state(null)
                report_failure(it)
            }
        }
    }

    private fun refresh_shared_state(app_context: Context) {
        runCatching {
            val updated = HighRefreshConfigClient.read_state(app_context)
            update_shared_state(updated)
            if (updated != null) {
                failure_reported.set(false)
            } else {
                report_failure(IllegalStateException("共享开关暂不可读，等待下一次场景请求重试"))
            }
        }.onFailure {
            update_shared_state(null)
            report_failure(it)
        }
    }

    private fun update_shared_state(updated: HighRefreshSharedState?) {
        val changed = synchronized(state_lock) {
            val now = SystemClock.elapsedRealtime()
            val previously_enabled = SystemHighRefreshPolicy.is_cached_state_enabled(
                shared_state, last_state_read_ms, now, cache_max_age_ms,
            )
            val next = updated ?: HighRefreshSharedState(false, false, shared_state.revision)
            val different = next != shared_state
            shared_state = next
            last_state_read_ms = if (updated != null) now else -1L
            different || previously_enabled != (next.initialized && next.enabled)
        }
        if (changed) replay_current_request()
    }

    private fun replay_current_request() {
        val request = synchronized(state_lock) { last_request } ?: return
        val handler = request.handler ?: return
        handler.post {
            runCatching {
                if (synchronized(state_lock) { last_request !== request }) return@runCatching
                val receiver = request.receiver.get() ?: return@runCatching
                if (!SystemHighRefreshPolicy.can_replay_scene(
                        foreground_package(receiver), current_cookie_field.getInt(receiver),
                        request.video_cookie, is_main_scene(receiver, request.context),
                    )) return@runCatching
                val target_rate = choose_rate(request, receiver) ?: request.requested_rate
                // 只在原调用Looper重放；关闭时恢复原始视频请求，不写全局刷新率键。
                XposedBridge.invokeOriginalMethod(
                    effect_method, receiver,
                    arrayOf(request.package_name, target_rate, request.cookie),
                )
                report_decision(request, target_rate, "config-change")
            }.onFailure { report_failure(it) }
        }
    }

    private fun choose_rate(request: Request, receiver: Any): Int? {
        val enabled = synchronized(state_lock) {
            SystemHighRefreshPolicy.is_cached_state_enabled(
                shared_state, last_state_read_ms, SystemClock.elapsedRealtime(), cache_max_age_ms,
            )
        }
        if (!enabled) return null
        val display = (request.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val current = display.mode
        val mode = HighRefreshRatePolicy.select_mode(
            RefreshMode(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate),
            display.supportedModes.map {
                RefreshMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            },
        ) ?: return null
        val power_manager = request.context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        val power_save = power_manager.isPowerSaveMode ||
            Settings.System.getInt(request.context.contentResolver, "POWER_SAVE_MODE_OPEN", 0) != 0
        val thermal_status = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { power_manager.currentThermalStatus }.getOrDefault(-1)
        } else -1
        return SystemHighRefreshPolicy.override_rate(
            request.package_name, request.requested_rate, request.cookie, request.video_cookie,
            mode.refresh_rate.roundToInt(), enabled,
            is_main_scene(receiver, request.context), power_save, thermal_status,
        )
    }

    private fun is_main_scene(receiver: Any, app_context: Context): Boolean {
        if (screen_field.getInt(receiver) != Display.DEFAULT_DISPLAY) return false
        val display = (app_context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        return display.state == Display.STATE_ON
    }

    private fun foreground_package(receiver: Any): String? {
        val info = foreground_field.get(receiver) ?: return null
        return field(info.javaClass, "mForegroundPackageName").get(info) as? String
    }

    private fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("${type.name}.$name")
    }

    private fun report_decision(request: Request, submitted_rate: Int, source: String) {
        val state = shared_state
        val signature = "${request.requested_rate}:$submitted_rate:${request.cookie}:${state.revision}:$source"
        val changed = synchronized(state_lock) {
            if (signature == last_report) false else {
                last_report = signature
                true
            }
        }
        if (changed) {
            report("SystemHighRefresh: pkg=${request.package_name} requested=${request.requested_rate} submitted=$submitted_rate cookie=${request.cookie} display=0 enabled=${state.enabled} revision=${state.revision} source=$source")
        }
    }

    private fun report(message: String) {
        Log.i(tag, message)
        XposedBridge.log(message)
    }

    private fun report_failure(error: Throwable) {
        if (failure_reported.compareAndSet(false, true)) {
            report("SystemHighRefresh: 路径未生效，保留系统原策略：${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
