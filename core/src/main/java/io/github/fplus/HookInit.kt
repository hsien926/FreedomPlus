// Kotlin · HookInit.kt · Android API 24+ / LSPosed API 82 · JDK 17
package io.github.fplus

import android.app.Application
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.fplus.core.hook.DouYinMain
import io.github.fplus.core.hook.HPowerKeeperHighRefreshRate
import io.github.fplus.core.helper.HighRefreshSyncPolicy
import io.github.xpler.XplerEntrance
import io.github.xpler.core.entrance.ApplicationHookStart
import io.github.xpler.core.proxy.LoadParam

class HookInit : XplerEntrance(), ApplicationHookStart {

    override val modulePackage: String
        get() = Constant.modulePackage

    override val scopes: Set<ApplicationHookStart.Scope>
        get() = Constant.scopes

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName == HighRefreshSyncPolicy.powerkeeper_package) {
            if (lp.processName == lp.packageName) {
                // 此分支不运行抖音插件、资源注入、MMKV或DexKit初始化。
                HPowerKeeperHighRefreshRate.install(lp.classLoader)
            }
            return
        }
        super.handleLoadPackage(lp)
    }

    override fun onCreateBefore(lparam: LoadParam, hostApp: Application) = Unit

    override fun onCreateAfter(lparam: LoadParam, hostApp: Application) {
        DouYinMain(hostApp)
    }
}
