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

    /** 期望 GIF */
    GIF("GIF"),

    /**
     * 期望 MP4：抖音部分动图表情下发的是 HEVC 素材（`ftyp=msf1`），直接命名为 `.mp4`。
     *
     * 含 `hdlr=pict` 的 HEIF 图像序列也一样命名 `.mp4`（沿用旧版行为，与「跟随源」降级 `.heic` 相区分）；
     * 本模块一期不做转码，个别播放器对 `pict` 容器可能只显示首帧。
     */
    MP4("MP4"),
    ;

    companion object {
        val labels: List<String> = values().map { it.label }

        fun fromLabel(value: String?): EmojiAnimateFormat =
            values().firstOrNull { it.label == value } ?: SOURCE
    }
}
