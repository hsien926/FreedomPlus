# FreedomPlus

> 抖音去广告与功能增强的 Xposed / LSPosed 模块，开源、无广告、不联网上传任何数据。

[![GitHub release](https://img.shields.io/github/v/release/hsien926/FreedomPlus?display_name=release)](https://github.com/hsien926/FreedomPlus/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](./LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B%20(API%2024%2B)-green)]()

---

## 关于本项目

本项目是**基于 [xiaodesetingyongzhanghao/FreedomPlus](https://github.com/xiaodesetingyongzhanghao/FreedomPlus) 进行二次修改更新**的衍生版本，在其基础上完成了抖音 40.x 版本的适配、广告拦截能力的重构与扩充，以及一批真机验证中发现的问题修复。

本版本相对基座的实质性改动：

- **新增广告拦截** —— 小程序广告、小游戏广告、激励视频、插屏四类广告的拦截链路，全部改用运行时反射定位目标类与方法，不写死任何混淆类名；
- **新增信息流广告拦截** —— 在 `VideoViewHolder.bind` 入口阻断广告项绑定（**注意：本机未观察到信息流广告投放，该功能尚未取得生效证据，详见[已知限制](docs/已知限制.md)**）；
- **抖音 40.6.0 适配** —— 修复模块导致抖音启动即崩溃的问题，DexKit matcher 命中数从 7 提升到 18；
- **移除内置 OTA** —— 模块不再发起任何对外网络请求，「检查更新」退化为读取随包分发的本地更新日志。

详细的代码来源与依赖许可见 [NOTICE.md](NOTICE.md)。

**声明**：本项目是独立维护的衍生版本，与上述基座仓库的维护者无隶属关系。

---

## 功能

### 广告拦截

- 小程序 / 小游戏广告拦截
- 激励视频广告拦截
- 插屏广告拦截
- 信息流广告拦截（实验性，未取得生效证据）

### 下载与保存

- 视频无水印下载
- 评论区视频 / 图片保存
- 表情包保存
- 语音评论保存
- 视频下载自定义编码格式与自定义文件名
- 复制链接下载弹窗

### 界面增强

- 首页控件半透明（防烧屏）
- 首页清爽模式，隐藏大部分控件
- 顶部 Tab 栏自定义隐藏
- 底部 Tab 栏自定义隐藏
- 移除底部加号按钮
- 视频右侧控件自定义隐藏
- 沉浸式全屏播放

### 使用体验

- 聊天消息防撤回
- 禁用双击点赞 / 双击打开评论区
- 视频过滤（直播、广告、长视频、文案关键字、弹窗关键字等）
- 自动连播
- 定时退出 / 空闲退出
- WebDAV 备份与恢复模块配置

完整的逐版本变更见 [CHANGELOG.md](CHANGELOG.md)。

---

## 安装

### 前置条件

- 已 root 并安装 **LSPosed**（其他 Xposed 框架自测；本项目在 LSPosed 上验证）
- 抖音 **64 位**版本（网络上流传的历史版本大多为 32 位，32 位下模块不稳定）
- Android 8.0 及以上（`minSdk 24`）

### 步骤

1. 从 [Releases](https://github.com/hsien926/FreedomPlus/releases) 下载 `FreedomPlus-1.3.6-release.apk` 并安装；
2. 打开 LSPosed，在「模块」中启用 **FreedomPlus**；
3. 在作用域中勾选**抖音**（`com.ss.android.ugc.aweme`，极速版 / 火山版为独立包名，需另行勾选）；
4. 给抖音授予**文件读写权限** —— 模块需要该权限读取配置与写入下载文件；
5. 强制停止抖音后重新打开。

> **升级安装提示**：本项目的 APK 使用固定签名。一旦你安装过本项目的历史版本，后续版本可直接覆盖安装、配置不丢失。如果你之前装的是其它来源的 FreedomPlus，由于签名不同，覆盖安装会被系统拒绝，需要先卸载旧版。

### 验证模块是否生效

打开 FreedomPlus 应用，首页「模块状态」应显示 `LSPosed加载成功!`，下方会同时显示本机抖音版本号与适配结论。

若显示「加载失败」，依次检查：LSPosed 中作用域是否勾选抖音 → 抖音是否已强制停止重启 → 是否使用了 64 位抖音。

---

## 适配版本

| 项 | 说明 |
|---|---|
| 实测基准 | 抖音 **40.6.0**（小米 M2102K1C / Android 17） |
| 声明适配 | 抖音 **28.0.0 ~ 40.6.0** |
| 配置依据 | 随包分发的 `assets/versions.json` |

抖音会持续重命名与混淆内部类。本项目的定位策略以**未混淆的类、方法、字段**为锚点（例如 `Aweme.isAd`、`BaseAd.adParser`），并借助 [DexKit](https://github.com/LuckyPray/DexKit) 做特征匹配，因此在混淆名漂移时具备一定自愈能力；但锚点本身被移除时相关功能会静默失效并记录日志。

**遇到问题时请先查阅 [已知限制](docs/已知限制.md)**，其中逐条列出了 40.6.0 下已确认失效或未验证的功能及其归因。

---

## 构建

环境要求与完整步骤见 [docs/构建.md](docs/构建.md)。最短路径：

```bash
git clone https://github.com/hsien926/FreedomPlus.git
cd FreedomPlus

# 安装 JDK 17 与 Android SDK（需包含 platforms;android-34）
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 调试包
./gradlew assembleDebug

# 发布包（需先配置 keystore.properties，见构建文档）
./gradlew assembleRelease
```

Windows 下请使用 `gradlew.bat`。

---

## 权限说明

| 权限 | 用途 |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` / `READ|WRITE_EXTERNAL_STORAGE` | 读写模块配置、保存下载的视频与图片 |
| `QUERY_ALL_PACKAGES` | 检测抖音是否安装及其版本号，用于适配提示 |
| `INTERNET` | WebDAV 备份功能；**模块本身不会向任何服务器上报数据** |
| `VIBRATE` | 下载完成等操作的可选震动反馈 |

模块的运行状态、hook 命中情况只写入本机 logcat（TAG 为 `Freedom+`），不落盘、不外传。

---

## 开源库

本项目依赖以下开源库，在此致谢：

- [DexKit](https://github.com/LuckyPray/DexKit) —— 运行时按特征定位混淆类
- [MMKV](https://github.com/Tencent/MMKV) —— 键值存储
- [sardine-android](https://github.com/thegrizzlylabs/sardine-android) —— WebDAV 客户端
- [Xpler](https://github.com/ThatWorld/xpler) —— Kotlin 风格的 Xposed 封装
- [ktutils](https://github.com/GangJust/ktutils) —— Kotlin 工具库

各依赖的许可条款见 [NOTICE.md](NOTICE.md)。

---

## 重要说明

- 本项目代码开源，供开发者学习参考使用；
- 欢迎 issues 与 pull requests；
- 请遵守开源协议复制与修改本项目代码，并在衍生作品中保留许可与来源说明；
- 若认为本项目内容存在不当之处，请通过 [Issues](https://github.com/hsien926/FreedomPlus/issues) 联系。

---

## License

[GPL-3.0](./LICENSE)

```
Copyright (C) 2023 Gang
Copyright (C) 2026 hsien926

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
