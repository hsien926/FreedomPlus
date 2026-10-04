package io.github.fplus.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import com.freegang.extension.child
import com.freegang.extension.storageRootFile
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.config.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HomeVM(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()

    // module config
    private val config: ConfigV1 get() = ConfigV1.get()

    // FreedomPlus -> 外置存储器/DCIM/Freedom
    val freedomPlusData
        get() = getApplication<Application>().storageRootFile
            .child(Environment.DIRECTORY_DCIM)
            .child("Freedom")

    // FreedomPlusNew -> 外置存储器/Download/Freedom
    val freedomPlusNewData
        get() = getApplication<Application>().storageRootFile
            .child(Environment.DIRECTORY_DOWNLOADS)
            .child("Freedom")

    // 是否开启去插件化
    val isDisablePlugin get() = config.isDisablePlugin

    // 本地更新日志（随包分发，OTA 已下线后不再联网获取）
    val updateLog: File get() = ConfigV1.getConfigDir(app).child("update.txt")

    /**
     * 版本功能适配提示。
     *
     * OTA 下线后端仍保留「本机抖音版本号是否在已声明适配列表内」这一提示，
     * 但数据来源换成本地：随包分发的 assets/versions.json。
     */
    suspend fun isSupportVersions(versionName: String): String {
        return withContext(Dispatchers.IO) {
            runCatching {
                val versions = Version.getVersions(app)
                if (versions.isNullOrBlank()) {
                    "自行测试功能"
                } else if (versions.contains(versionName)) {
                    "版本功能正常"
                } else {
                    "自行测试功能"
                }
            }.getOrDefault("自行测试功能")
        }
    }
}
