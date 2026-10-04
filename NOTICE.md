# NOTICE · 来源与依赖许可

本文件记录本项目的代码来源谱系，以及全部第三方依赖的许可归属。

---

## 一、本项目的代码来源

本项目是**基于 [xiaodesetingyongzhanghao/FreedomPlus](https://github.com/xiaodesetingyongzhanghao/FreedomPlus) 进行二次修改更新**的衍生版本。

该基座的代码谱系与本次衍生版本的实际继承关系如下：

| 层次 | 来源 | 本项目的处理 |
|---|---|---|
| 上游基座 | [xiaodesetingyongzhanghao/FreedomPlus](https://github.com/xiaodesetingyongzhanghao/FreedomPlus) | 本项目的起点。工程结构、`app` / `core` / `aweme` 三模块划分、混淆字典 `app/dic.txt`、模块配置与设置界面均继承自此处 |
| 上游演进版本 | [FairyWorld/lsposed_FreedomPlus](https://github.com/FairyWorld/lsposed_FreedomPlus) | 本项目 `core` 模块下的 hook 实现与资源文件与之同源：**168 个文件内容逐字节相同**，另在 16 个文件上做了修改、新增 6 个 hook 类 |
| 工具库 | [GangJust/ktutils](https://github.com/GangJust/ktutils) | `ktutils/` 目录，**80 个文件逐字节相同**，未做修改。包名保持上游的 `com.freegang.ktutils` 不变，以便与上游持续同步 |
| 框架封装 | [ThatWorld/xpler](https://github.com/ThatWorld/xpler) | `xpler/` 目录，34 个文件中的 33 个与上游相同，仅对 `build.gradle.kts` 做了本地化调整（移除发布与签名插件） |

> 上述两个目录在本仓库中以**普通目录**形式纳入版本控制（非 git submodule），以便 `git clone` 后即可直接构建。

上述上游项目均由 **Gang** 开发并以 GPL-3.0 协议开源。本项目按 GPL-3.0 的要求继承该协议，并在 [LICENSE](./LICENSE) 中保留原始版权声明。

## 二、本项目自身的改动

相对上述基座，本项目自行实现的部分：

- `core` 模块新增广告拦截与信息流拦截相关 hook（`HAdBlock`、`HFeedAdBlock`、`HFeedAdBlockPlayable`、`HFeedAdFilter` 等）；
- 抖音 40.6.0 适配：崩溃修复、DexKit matcher 修正、混淆名漂移兼容；
- 移除内置 OTA，模块不再发起任何对外网络请求；
- 构建体系整理：签名配置外部化、依赖版本对齐。

## 三、第三方依赖许可

| 依赖 | 版本 | 许可 | 说明 |
|---|---|---|---|
| [DexKit](https://github.com/LuckyPray/DexKit) | 2.0.2 | Apache-2.0 | 运行时按特征定位混淆类。以 `libdexkit.so` 形式随包分发 |
| [MMKV](https://github.com/Tencent/MMKV) | 1.3.0 | BSD-3-Clause | 键值存储。以 `libmmkv.so` 形式随包分发 |
| [sardine-android](https://github.com/thegrizzlylabs/sardine-android) | v0.8 | Apache-2.0 | WebDAV 客户端 |
| AndroidX / Compose / Material | 见 `gradle/libs.versions.toml` | Apache-2.0 | 界面与基础组件 |
| Kotlin 标准库与协程 | 1.8.10 | Apache-2.0 | 语言运行时 |
| [Xposed API](https://github.com/LSPosed/LSPosed) | 82 | Apache-2.0 | **仅编译期依赖**（`compileOnly`），不随包分发 |

## 四、关于抖音内部类的类型声明

`aweme/` 模块是一个 `compileOnly` 的**类型声明壳**：它只声明抖音内部类与原方法的签名（字段类型、方法返回类型），供编译期引用，自身不打包进 APK、运行时由抖音提供。

这样做是为了避免在 hook 代码里使用硬编码的类名与方法签名。声明内容为**接口最小必要的签名信息**，不含抖音的任何实现代码。

## 五、分发说明

本项目以源码形式在 GitHub 公开，按 GPL-3.0 第 5 条分发。任何基于本项目的衍生作品同样需要以 GPL-3.0 分发，并保留本文件与 LICENSE 中的来源与版权声明。
