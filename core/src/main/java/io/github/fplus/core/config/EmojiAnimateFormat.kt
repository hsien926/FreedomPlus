package io.github.fplus.core.config

/**
 * 「下载动图格式」选项。
 *
 * 持久化直接存 [label]（与 `videoCoding` 存 "Auto"/"H264"/"H265" 的做法一致），
 * 避免配置值到显示名之间多一层映射；改文案只动这里。
 *
 * 注意：本枚举只描述**用户期望**，实际能否达成由素材真实容器决定
 * （见 `IsoBmffProbe` 与 `SaveEmojiLogic`）：一期不做转码，
 * 素材与期望不一致时按真实容器落盘并提示，不静默改内容。
 */
enum class EmojiAnimateFormat(val label: String) {
    /** 按素材真实容器落盘（默认，与历史行为一致） */
    SOURCE("跟随源"),

    /** 优先选择 GIF 候选源；没有该候选时保留首选源并说明实际保存格式。 */
    GIF("GIF"),

    /**
     * 优先选择 MP4 候选源；实际扩展名由完整文件的真实轨道决定。
     * HEIF 图像序列保持 .heic，即使选择 MP4 也不伪装为视频；本模块不做转码。
     */
    MP4("MP4"),
    ;

    companion object {
        val labels: List<String> = values().map { it.label }

        fun fromLabel(value: String?): EmojiAnimateFormat =
            values().firstOrNull { it.label == value } ?: SOURCE
    }
}
