package io.github.fplus

/**
 * 仓库与发布信息集中配置。
 *
 * 这是本项目对外公开的唯一身份来源，改仓库地址只需要改这里一处。
 * 内置的远程版本检查（OTA）已整体下线：模块不再发起任何对外网络请求，
 * 「检查更新」的语义退化为读取随包分发的本地更新日志（assets/update.txt）。
 */
object ProjectInfo {

    /** GitHub 账号 */
    const val OWNER = "hsien926"

    /** 仓库名 */
    const val REPO = "FreedomPlus"

    /** 本模块上游基座（二次开发所依据的上游仓库） */
    const val BASE_REPO_URL = "https://github.com/xiaodesetingyongzhanghao/FreedomPlus"

    /** 本模块仓库地址 */
    const val REPO_URL = "https://github.com/$OWNER/$REPO"

    /** 版本适配列表（随包分发，不联网获取） */
    const val VERSIONS_ASSET = "versions.json"

    /**
     * 二进制与配置说明，用于界面「源码地址」卡片下方的说明文案。
     * 用户可见，措辞保持中性、准确。
     */
    const val ORIGIN_NOTE =
        "本项目是基于 $BASE_REPO_URL 进行二次修改更新的衍生版本。"
}
