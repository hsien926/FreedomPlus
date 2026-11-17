# FreedomPlus

**抖音功能增强模块 · Xposed / LSPosed · v1.4.0（versionCode 150）**

[![版本](https://img.shields.io/badge/version-1.4.0-blue)](https://github.com/hsien926/FreedomPlus/releases/tag/v1.4.0)
[![构建](https://github.com/hsien926/FreedomPlus/actions/workflows/build.yml/badge.svg)](https://github.com/hsien926/FreedomPlus/actions/workflows/build.yml)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green)](#环境与适配)
[![许可](https://img.shields.io/badge/license-GPL--3.0-blue)](<LICENSE>)

FreedomPlus 在抖音内提供媒体下载、界面精简、清爽与沉浸播放、手势调整、广告处理、定时退出、WebDAV 媒体上传及实验性高刷新率支持。

**1.4.0 本次重点修复：展开评论后，长按首条评论复制时误打开模块设置。** 评论复制与视频设置入口现在按实际视图来源隔离，评论收起后仍可正常长按视频上半屏进入设置。

本项目基于 [xiaodesetingyongzhanghao/FreedomPlus](https://github.com/xiaodesetingyongzhanghao/FreedomPlus) 二次修改维护。保留原项目来源与 GPL-3.0 许可，详见[来源与许可说明](<NOTICE.md>)。

## 下载 v1.4.0

| 文件 | 用途 |
|---|---|
| [正式 APK](https://github.com/hsien926/FreedomPlus/releases/download/v1.4.0/FreedomPlus-1.4.0-release.apk) | 手机安装，沿用本维护线正式签名 |
| [完整源码 ZIP](https://github.com/hsien926/FreedomPlus/releases/download/v1.4.0/FreedomPlus-1.4.0-source.zip) | 与版本标签对应的工程源码，不含密钥及本地环境配置 |
| [SHA-256 校验文件](https://github.com/hsien926/FreedomPlus/releases/download/v1.4.0/SHA256SUMS.txt) | 核对安装包、源码及配套发布文件 |

[本版 Release](https://github.com/hsien926/FreedomPlus/releases/tag/v1.4.0) · [最新版本](https://github.com/hsien926/FreedomPlus/releases/latest) · [发布说明](<docs/发布说明-v1.4.0.md>) · [更新日志](<CHANGELOG.md>) · [问题反馈](https://github.com/hsien926/FreedomPlus/issues)

> 本次公开版本为 **1.4.0 / 150**，不是早期的 **1.4.0 / 149**。版本代码保留 150，包含最新真机修订逻辑；同签名旧版可覆盖安装并保留配置。不同来源的签名包、Debug 包不能直接覆盖本维护线正式包。

## 安装与启用

1. 安装上方 **正式 APK**。已有同签名版本时直接覆盖安装，不需要清空抖音数据或模块配置。
2. 在 LSPosed 中启用 FreedomPlus，勾选主抖音 **`com.ss.android.ugc.aweme`**。
3. 打开一次 FreedomPlus，按设备系统版本授予保存媒体所需的文件 / 存储权限。
4. **强制停止抖音，再重新打开**；仅从最近任务划掉应用不等于强制停止。
5. 在首页视频的**上半屏中间区域长按**进入模块设置，避开左右边缘的长按快进区域。
6. 按需开启下载、清爽模式、界面精简等功能。模块应用首页也提供设置跳转入口。

**小米系统侧高刷：** 额外勾选 **电量与性能 `com.miui.powerkeeper`**，在模块设置中开启“解锁高刷新率（实验）”，重启抖音。首次追加系统作用域还需重载 PowerKeeper 主进程或重启设备；只重开抖音不会让已运行的 PowerKeeper 加载新 Hook。

详细步骤见[安装与激活](<docs/安装与激活.md>)。“清空下载目录”操作会同时清除配置与下载内容，**不是升级步骤**。

## 1.4.0 修复了什么

| 问题 | 当前处理 |
|---|---|
| 评论行与视频区复用 `LongPressLayout`，局部坐标被误认成视频上半屏 | 只有实际视频 ViewHolder 父链内的触摸才能建立模块手势会话 |
| 评论 `onViewCreated` 实际由父类声明，原 Hook 未绑定 | 沿继承链定位真实方法声明类，只处理目标评论 Fragment 实例 |
| 评论收起后外层 `FrameLayout` 仍显示，导致视频入口被持续屏蔽 | 跟踪真正随评论隐藏的 `CommentNestedLayout`，实时判断可见性 |
| 原生长按及延时回调可能越过当前触摸状态 | 绑定有效视频会话，并再次检查来源与评论面板状态 |

**不改变长按时长、上下半屏与左右边缘阈值，不增加配置项、依赖或权限。** 已有媒体、界面、WebDAV、高刷及兼容代码保留。

### 验证范围

修订逻辑在 **M2102K1C / Android 17 / 抖音 40.6.0（400601）** 上完成以下对照：

| 用例 | 结果 |
|---|---|
| 半屏评论区首条评论：长按 → 原生复制 | 连续 10 次通过，未跳设置 |
| 全屏评论区首条评论：长按 → 原生复制 | 连续 5 次通过，未跳设置 |
| 收起评论后长按视频上半屏 | 正常打开模块设置 |
| 视频下半屏与左右边缘长按 | 原生菜单保留，未误开设置 |
| 核心 JVM 回归 | 199 项通过 |
| Python APK 校验器回归 | 8 项通过 |

真机对照使用内部修订名 1.4.1 / 150，随后公开名称统一为 **1.4.0 / 150** 并重新构建、验签。正式 APK 的 DEX 与该真机验证包逐字节一致；名称统一后的包未另计一次手机安装验收。以上不扩展为所有机型、抖音版本或全部功能均已验证。

## 功能总览

以下为当前保留的功能，不将继承功能全部算作 1.4.0 新增。部分功能依赖抖音内部版本，具体边界见[已知限制](<docs/已知限制.md>)。

### 媒体下载与保存

- **视频、图文图片、背景音乐**下载；下载弹窗可复制作品文案。
- 播放源提供 `Auto` / `H264` / `H265` 优先级，地址缺失时降级处理，不重新编码视频。
- 自定义文件名、按创作者建目录；进度可在弹窗或通知栏显示。
- 可启用“分享 → 复制链接”唤起下载选择。
- 保存评论区图片 / 视频、表情包及相关表情，支持可选震动反馈与媒体库刷新。
- 部分 VVIC / HEIC 图文可使用宿主解码器转为 JPEG，转换依赖解码器可用性。

默认目录为外置存储器 **`Download/Freedom`**，按视频、图片、音乐、表情等类型分类。语音评论保存尚未接入可触发入口。

### 界面、清爽模式与沉浸播放

- 顶部导航、视频控件、右侧控件及底部导航的半透明度调整，范围 `0–100`。
- 按关键字隐藏顶部 / 底部标签及右侧控件；顶部隐藏开启后会限制左右滑动切换。
- 底部拍摄按钮保留、禁止拍摄或移除；移除悬浮挑战 / 评论贴纸。
- 评论区浅色、深色或跟随主题，精简评论展开后的搜索入口。
- 清爽模式隐藏主要控件，暂停时恢复；长按菜单可提供评论、收藏、分享、下载、视频信息及模块设置。
- 上半屏用于设置入口时，清爽菜单改由下半屏响应。
- 状态栏 / 导航栏隐藏与横屏沉浸处理；部分页面可能出现裁切或拉伸。

### 播放、手势与模块管理

- 自动连播、首页连播按钮与启动时默认开启选项。
- 双击打开评论 / 点赞响应类型，底部首页与头像关注等操作的二次确认。
- 达到 10 分钟阈值的视频时长提示；作品发布时间与属地查询，属地需要联网。
- 长按视频上半屏进入设置、模块应用入口、桌面图标显示 / 隐藏。
- 本地更新日志、类匹配日志、DexKit 缓存清除与旧下载目录迁移。
- 消息防撤回为保留项，设置页标注“失效不修”，不恢复服务端已替换的内容。

### 广告处理与内容过滤

- 广告总开关新装默认开启；按匹配到的 SDK 链路处理小程序 / 小游戏广告、插屏及激励视频计时。
- SDK 开屏、横幅、贴片等类型不等于所有抖音广告均可拦截；奖励发放仍取决于目标业务返回结果。
- **信息流广告为实验性功能**：只在可验证、可安全替换的响应源过滤，未知布局原样放行，不用空白页隐藏广告。
- 保留直播、图文、长视频、推荐卡片、商家、空文案与文案关键字过滤，以及弹窗关键字过滤；40.6.0 的部分旧数据入口已缺失。

### 定时退出与 WebDAV

- 运行退出 / 空闲退出分别设置分钟数，低于 3 分钟的对应计时不执行。
- 可改为保留后台，保留直播间暂停空闲计时处理。
- 下载弹窗可将视频、图片或背景音乐上传至配置的 WebDAV 服务器。
- WebDAV 支持连接测试、多配置历史、选择与删除历史配置。

**WebDAV 是媒体上传，不是模块配置备份与恢复。**

### 高刷新率（实验）

同一个 APK、同一个开关处理应用侧和小米 PowerKeeper 系统侧，**新装默认关闭**。

- 在当前物理分辨率下请求屏幕支持的最高有效刷新率，不硬编码 120 Hz。
- 应用侧覆盖窗口模式与 Android 11+ 公开 Java Surface 帧率请求；系统侧针对主抖音、主屏、正常视频场景的低刷新率请求。
- 保留省电、热状态、用户上限及其它系统投票；不禁用 PowerKeeper、Joyose 或温控服务。
- 不修改其它应用、副屏或全局刷新率键；**不做视频补帧，不改变源帧数、时间戳或播放速度**。
- 系统侧基于已核对的 PowerKeeper `4.2.00`，不匹配的 ROM 保留原策略。

启用、关闭、共享开关与对照方法见[高刷新率说明](<docs/高刷新率.md>)。

## 环境与适配

| 项目 | 说明 |
|---|---|
| 框架基准 | Root + LSPosed，以 64 位主抖音为基准 |
| 最低系统要求 | Android 7.0 / API 24；不代表每项功能均已在该版本测试 |
| 本版评论修复真机版本 | 抖音 40.6.0（400601） |
| 历史验证记录 | 31.7.1（310702）、31.7.2（310703）、38.6.0（380600），记录为预装包，不等同于商店正式版验证 |
| 包内适配列表 | 28.x、29.x、30.0.0–30.3.0 中列出的版本及 40.6.0 |
| 40.x 兼容代码 | 保留 40.6.0 启动 / 下载适配与 40.8.0 PagerAdapter 数量失配修复，不是全功能回归承诺 |
| 系统侧高刷 | 小米 PowerKeeper 4.2.00；其它 ROM 未作完整验证 |
| 极速版 / 火山版 | 保留加载规则，未完成完整验证，不使用主抖音共享系统高刷开关 |

[包内适配列表](<app/src/main/assets/versions.json>)不是 28.0.0 到 40.6.0 的连续支持声明。模块首页“版本功能正常”表示列表命中，**不是全部功能测试通过**。

### 需要注意的功能边界

- 信息流广告暂无真实投放下成功拦截的证据；部分小程序 / 小游戏广告入口随版本变化失效。
- 40.6.0 的旧非广告过滤入口、底部整体控制栏、部分旧设置入口不可用；新双击链路仍需独立真机验收。
- 消息防撤回为保留项；语音评论保存没有入口；暂存的“崩溃容错 / 去插件化”设置不在当前页面展示。
- 高刷不保证所有 ROM、源视频或重启持久性；直接 native 帧率请求不在新增保证范围内。

详细归因与日志方式见[已知限制](<docs/已知限制.md>)。

## 权限、数据与网络

- 文件 / 存储访问用于保存媒体和管理目录；`QUERY_ALL_PACKAGES` 用于检测抖音与本机版本；`VIBRATE` 用于可选震动反馈。
- `INTERNET` 用于 WebDAV，注入抖音后的媒体下载、上传与属地查询使用宿主网络环境。
- 主要配置保存在目标应用私有目录；高刷提供方仅共享初始化状态、开关与修订号。
- 模块自身不展示广告，已移除内置远程更新检查；更新日志与适配列表随包分发。
- 当前实现没有新增账号数据、下载记录或运行日志的自动遥测上传。选择 WebDAV 会向所配置服务器上传媒体；属地查询会访问对应接口。

**移除远程更新检查不等于完全不联网。**

## 从源码构建

工具链：**JDK 17 · Android SDK 34 · Gradle Wrapper 8.9**。`ktutils` 与 `xpler` 是已纳入仓库的普通目录，无需初始化 Git 子模块。

```bash
git clone --branch v1.4.0 --depth 1 https://github.com/hsien926/FreedomPlus.git
cd FreedomPlus
./gradlew :core:testDebugUnitTest :app:assembleDebug
```

Windows 在项目根目录使用：

```powershell
.\gradlew.bat :core:testDebugUnitTest :app:assembleDebug
```

Release 构建需自行提供签名配置；仓库与源码 ZIP 不含签名密钥、口令或本地 SDK 配置。CI 只运行测试、编译 Debug 与未签名 Release，**不使用正式密钥，不自动发布安装包**。自己的签名不能直接覆盖官方 Release。

环境、产物及签名配置见[构建指南](<docs/构建.md>)。199 项核心回归与 8 项 Python 校验测试的通过，不替代完整 Lint 或逐机型真机验证。

## 校验与反馈

包名：`io.github.fplus` · 版本资产：`1.4.0-150`。

正式 APK SHA-256：

```text
f653cc0ec6ce1f65ee2202542b9604a263c7e0ef56736e1ff44864178e36ae1f
```

正式签名证书 SHA-256：

```text
d95f4b5986005e90e98a074ac4c918ccd63af338202bbfd81d8e0607b67d8874
```

完整源码、相对上一主线提交的补丁、配套 R8 映射和校验文件放在[本版 Release](https://github.com/hsien926/FreedomPlus/releases/tag/v1.4.0)。反馈时在 [Issues](https://github.com/hsien926/FreedomPlus/issues) 提供设备 / 系统版本、抖音版本、作用域、功能开关、复现步骤及必要的脱敏日志。

## 来源与许可

本项目采用 [GPL-3.0](<LICENSE>)。上游与第三方依赖包括 [FreedomPlus](https://github.com/xiaodesetingyongzhanghao/FreedomPlus)、[DexKit](https://github.com/LuckyPray/DexKit)、[MMKV](https://github.com/Tencent/MMKV)、[Xpler](https://github.com/ThatWorld/xpler)、[ktutils](https://github.com/GangJust/ktutils) 与 [sardine-android](https://github.com/thegrizzlylabs/sardine-android)。完整谱系与各依赖许可见[NOTICE](<NOTICE.md>)。
