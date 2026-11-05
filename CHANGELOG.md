# 更新日志

本文件记录 FreedomPlus 的版本变更。

- `v1.3.6` 起为**本项目（hsien926/FreedomPlus）**的版本号；
- `v1.3.5` 及更早的条目继承自上游基座 [xiaodesetingyongzhanghao/FreedomPlus](https://github.com/xiaodesetingyongzhanghao/FreedomPlus) 与其同源演进版本，一并在下方保留，方便对照历史行为。

---

## 未发布

### 新增

- 设置 →「保存表情包/评论视频、图片」→「相关设置」新增「下载动图格式」（跟随源 / GIF / MP4，默认跟随源）。该项只描述期望格式：按素材真实容器落盘，期望与素材不一致时弹说明，本期不做 GIF/MP4 互转。选「MP4」时抖音的 HEVC 动图（含 `hdlr=pict` 的 HEIF 图像序列）统一命名 `.mp4`，与旧版行为一致；选「跟随源」才降级 `.heic` 并提示。

### 修复

- **双击视频响应类型不生效（38.6.0 实测）**：选「打开评论」后双击仍然只是点赞。原因有两个：① 模块手工维护的 `numberOfTaps` 计数器只在「两次抬起间隔 ≥300ms」时才置 1，双击后归零，后续快速连点会一直判不出来，所以表现为「点三下才打开评论区」；② 判定写在 `@OnAfter("onTouchEvent")` 的第二次 ACTION_UP，而抖音（与 `GestureDetector` 一致）在第二次 ACTION_DOWN 就已处理完双击点赞，判得再对也拦不住。现改为 `@OnBefore("onTouchEvent")` 内的手势状态机：第二次 ACTION_DOWN 命中（间隔 ≤ `getDoubleTapTimeout()`、位移 ≤ `getScaledDoubleTapSlop()`、上一手势是有效轻点）即判定双击，选「打开评论」时把该次手势整体吞掉，抖音侧收不到这次点击，原生点赞不会发生，同时异步点击评论按钮打开评论区；选「点赞视频」或主开关关闭时完全不介入。顺带修掉长按坐标的旧 bug（`longPressRunnable` 只创建一次，把第一次 ACTION_DOWN 的坐标永久缓存，导致上/下半屏判定一直用旧坐标）。
- **双击设置项交互**：点击整行会先打开主开关再弹「请选择双击响应模式」，子标题显示当前模式；此前点整行只弹对话框、开关仍关闭，容易误以为设置不生效。
- **双击打开评论区后视频被暂停**：吞掉第二下点击后，抖音会把这次手势当成「单击」，顺带暂停了播放。改为在命中双击时把第二次 `ACTION_DOWN` 的坐标挪到屏幕外再放行 —— 抖音据此撤掉「待确认的单击」（**不暂停**），又因为位置对不上不会判定为双击（**也不点赞**），随后的 MOVE/UP 仍由模块吞掉；另保留一次 500ms 后的播放状态兜底检查。「请选择双击响应模式」里「打开评论」右侧新增「暂停播放」开关（默认**关闭**），打开后保留抖音原生的暂停行为。
- **Tab-switch crash (`FeedItemFragmentVM.getFeedAnimation()` NPE)**: on Douyin 31.7.1, leaving the home tab paused the current page, whose fragment VM had never been initialized because the module used to block `VideoViewHolder.bind`. The three view-holder level interceptors are removed, so `bind` runs normally and the VM is always created. A narrow guard is added: it swallows only a `NullPointerException` whose message contains `getFeedAnimation` / `FeedItemFragmentVM` (hooked on `FeedWidgetAnimComponent` and `FeedComponentsRoot` `handlePauseP`); every other exception still surfaces.
- **Feed list sanitizing reverted to the whole-table path**: an earlier attempt removed items right before `notifyDataSetChanged()`. The adapter keeps several list copies and not all of them are reachable, so counts and items drifted apart — `getView` received null, which showed up as a black page and the `Aweme.setActivityId` NPE. That logic is removed entirely. Items are only filtered when the host replaces the whole table; anything inserted through positional APIs is passed through untouched and plays normally.
- **Filtering moved to the feed response callback**: both presenters resolved by DexKit (`recommendFeedFetchPresenter` / `fullFeedFollowFetchPresenter`) expose `onSuccess`, so entries are dropped there — before the adapter is populated, which means no count/index bookkeeping is involved at all. The access path inside the callback is discovered adaptively and cached; if the path, the list or the write is unavailable, or filtering would empty the list, the data is passed through untouched and only one log line is emitted.
- **设置页重建偶发闪退（`BadParcelableException: ClassNotFoundException androidx.compose.material.DrawerValue`）**：模块设置页跑在抖音进程内，Host 的 `savedInstanceState` 用了抖音（或其插件）的 ClassLoader；Compose 的 `rememberSaveable`（`Scaffold` 默认 `DrawerState`）把模块内的 compose 枚举按 Serializable 写进该 Bundle，重建时宿主 ClassLoader 解析不到，直接抛异常。现在 `BaseActivity.onCreate` 先把 Bundle 的 ClassLoader 换成 `PluginClassloader`（模块优先、宿主兜底）再恢复状态。
- **HEIF 图像序列的落盘后缀**：抖音部分动图表情下发 `ftyp=msf1` + `hdlr=pict`（`HEIF/ImageSequence`）。现按 `moov/mdia/hdlr` 细分：选「MP4」时仍命名 `.mp4`（与旧版一致，系统相册/多数播放器可播，个别播放器只出首帧）；选「跟随源」或「GIF」时降级 `.heic` 并提示「相册可能仅显示首帧」。

---

## v1.3.9

### 修复

- **抖音 40.8.0 运行后触发 `PagerAdapter` 崩溃**：旧版信息流过滤改写 `getCount()` / `getItem()`，使 Adapter 对外计数偏离真实数据量。抖音又用 `getCount() == 0` 判断“整表替换但不通知”，导致 `VerticalViewPager` 保存的期望数量长期停在旧值，最终抛出 `IllegalStateException: The application's PagerAdapter changed the adapter's contents without calling PagerAdapter#notifyDataSetChanged!`。
- 信息流过滤改为数据落表阶段剔除广告，并同步清理 Adapter 内同源的平行列表，保证 `getCount()`、`getItem()` 与页面索引始终处于同一空间；补充只针对该异常的兜底刷新，避免滚动结束回调中的残余失配直接终止进程。
- 本修复已在**小米手机**完成真机验证。其他品牌手机需自行测试。

### 变更

- 版本号升级至 `v1.3.9`（versionCode 145）。

---

## v1.3.8

本次更新修复真机验证中发现的下载崩溃，并新增一个更顺手的模块设置入口。

### 新增

- **长按唤起模块设置**：在抖音内长按屏幕上半部分（避开左右边缘的长按快进区）直接打开模块设置，替代此前不好用的「侧边栏 / 加号弹窗」入口。原有两个入口保留可用；模块设置中新增「长按上半部分打开模块设置」开关（默认开启），关闭后行为完全恢复旧版。

### 修复

- **视频 / 图文下载报错**：抖音 40.6.0 把 `Video` 的播放地址字段整体混淆（`playAddr` → `a`、`playAddrH265` → `b`、`h264PlayAddr` → `d`），模块按旧字段名访问时抛 `NoSuchFieldError: No instance field playAddr`，点「下载」即弹崩溃页。现改用 Gson `@SerializedName` 注解值（`play_addr` / `play_addr_265` / `play_addr_h264`）作为运行时可读锚点定位字段，混淆名再变也不受影响；同时补齐按编码降级链（265 → h264 → play_addr）与图片空 URL 跳过，下载成功率更高、不再整组中断。
- **评论区视频保存**：同步改用同一套地址解析，不再受字段混淆影响。

### 变更

- 版本号升级至 `v1.3.8`（versionCode 144）。

---

## v1.3.6

**本项目首个公开版本。** 基于上游基座二次开发，在保留全部原有功能的前提下，围绕抖音 40.x 完成了适配与广告拦截能力建设。

### 新增

- **广告拦截（4 类）**：小程序 / 小游戏广告、激励视频、插屏广告的拦截链路。实现不写死任何混淆类名，全部在运行时反射定位目标类与方法；锚点缺失时记录日志并跳过，不会误伤其它逻辑。
- **信息流广告拦截**：在 `VideoViewHolder.bind` 入口阻断广告项到视图的绑定，覆盖 `VideoViewHolder`、`PlayableVideoViewHolder`（可玩广告）、`InteractivePlayableVideoViewHolder`（互动可玩广告）三条链路。
  > 本机测试环境未出现信息流广告投放（累计观察 250 项 bind，`isAd=true` 数量为 0），因此**该功能尚未取得生效证据**，请以实际投放环境为准。
- **模块状态页增强**：首页直接显示本机抖音版本号与适配结论，无需翻日志。

### 修复

- **模块导致抖音启动即崩溃**：`HMainActivity` 对代理类的 `is` / `as` 强制解析会在目标版本缺类时抛 `NoClassDefFoundError`，且该调用位于主线程 post 回调中无人捕获。已改为类名比较并对 `initView` 整体加保护。
- **广告拦截完全不执行**：拦截组使用 `NoneHook` 占位时会被上层直接跳过 `onInit()`，导致 hook 从未注册。已改用 `EmptyHook`。
- **小程序广告开关恒不生效**：原判定取第一个参数（上下文对象）去比对广告类型名，而真实接口的广告类型是第二个参数的枚举对象。已改为按参数逐个匹配枚举 `name()`。
- **侧边栏功能失效**：目标类被移动到了 `sidebar.panel.ui` 子包下，匹配规则已兼容新旧两条路径。
- **DexKit 缓存导致修 matcher 无效**：修改匹配规则后必须同步递增缓存版本号，否则会读到上一次搜索结果（含失败缓存的 `null`）。已递增并加注释说明。
- **其它 40.6.0 适配**：DexKit 匹配命中数由 7 提升至 18。

### 变更

- **移除内置 OTA**：删除对 GitHub API 的远程版本检查与更新提示，模块不再发起任何对外网络请求。「检查更新」按钮保留，行为改为展示随包分发的本地更新日志。
- **首页「源码地址」与「问题反馈」指向本仓库**，不再指向上游仓库。
- **签名配置外部化**：工程内不再包含任何密钥文件，release 签名改由 `keystore.properties` 提供（见 [docs/构建.md](docs/构建.md)）。

### 已知限制

详见 [docs/已知限制.md](docs/已知限制.md)。摘要：

- 信息流广告拦截未取得生效证据（测试环境无广告投放）；
- 小程序广告的「跳过展示」链路在 40.6.0 上方法名已混淆，退化为不生效，由广告类型总开关兜底；
- 40.6.0 下底部控制栏已被拆分为多个独立 View，相关的隐藏能力语义上已不成立。

---

## v1.3.5

- 优化模块弹窗展示和暗色背景
- 优化模块资源 id 前缀避免注入冲突
- 支持如：元萝卜等部分免 root 框架

## v1.3.4

- 修复高版本已撤回提示文本不显示问题
- 修复部分已知问题

## v1.3.3

- 修复高版本评论区颜色不跟随主题问题
- 修复部分已知问题

## v1.3.2

- 增加清爽模式菜单视频信息查询
- 移除清爽模式菜单过滤统计选项
- 修复文件下载不通知媒体库刷新的问题

## v1.3.1

- 增加横屏播放单独的状态栏沉浸逻辑
- 增加根据内容自动匹配下载文件的格式（表情包无法发送微信问题）
- 调整不再需要手动勾选崩溃容错处理（默认开启）
- 调整改回下载链接取为取第一项（格式不统一问题）
- 修复部分情况下复制链接为前一个视频问题
- 修复新版本下清爽模式偶尔不隐藏顶、底部控件问题
- 修复其它已知问题

## v1.3.0

- 增加官方自动连播默认开启选项
- 修复定时、空闲退出计时冲突问题
- 修复部分小问题

## v1.2.9

- 增加底部选项自定义关键字隐藏
- 增加部分误触复确认弹窗提示
- 增加崩溃拦截容错处理选项
- 调整获取下载链接为随机下标
- 调整空闲退出在直播间时暂停计时
- 修复清爽模式长按偶弹官方菜单问题
- 移除双击视频暂停选项
- 调整部分逻辑（可能需要重新设置模块）

## v1.2.8

- 修复定时、空闲后台重复退出问题
- 移除启动时底部提示

## v1.2.7

- 增加聊天消息防止撤回
- 增加视频下载自定义编码格式
- 增加定时、空闲退出支持保留应用后台
- 增加全屏播放「复制链接」唤起下载弹窗
- 恢复「分享 → 复制链接」，需手动开启功能
- 调整部分逻辑（可能需要重新设置模块）
- 修复部分小问题

## v1.2.6

- 增加下载弹窗文案复制选项
- 增加视频右侧控件自定义隐藏
- 增加视频右侧控件在视频控件透明基础上的再次调整
- 增加评论区展开时顶部搜索按钮移除（无需设置）
- 增加部分版本暂停视频时底部控制栏隐藏
- 调整状态栏、导航栏沉浸模式为自动隐藏
- 移除「分享 → 复制链接」无水印下载方式
- 修复部分小问题

## v1.2.5

- 增加火山版支持
- 增加清爽模式下暂停视频显示控件
- 增加状态栏、底部导航栏被动显示后再次隐藏
- 增加弹窗过滤（实验性功能）
- 修复好友分享视频无法获取到基本信息
- 修复新版抖音部分资源冲突造成的崩溃
- 移除模块对读写、媒体权限的检测
- 调整部分逻辑（可能需要重新设置模块）

## v1.2.4

- 增加极速版支持
- 增加评论区颜色模式调整
- 增加几个视频过滤关键字
- 增加模块首页清空数据目录操作
- 增加兼容 Android 13+ 媒体操作权限
- 增加沉浸式可隐藏状态栏、底部导航栏
- 移除沉浸式底部垫高
- 移除沉浸式长按快进黑色背景
- 调整首页控件透明度最小值为 0
- 调整下载数据迁移至 Download 目录
- 修复部分小问题

## v1.2.3

- 增加全屏沉浸式播放
- 增加底部加号按钮移除
- 增加悬浮挑战 / 评论移除
- 增加自定义首页控件透明度
- 增加双击视频响应模式切换
- 增加清爽模式弹窗下载选项
- 增加去插件化后直接跳转模块首页（需安装）
- 增加首页左上角加号情况模块设置的跳转
- 移除 x86 的库依赖文件
- 移除设置页底部模块设置的跳转
- 调整部分逻辑，现在应该更流畅了

## v1.2.2

- 增加视频时长超过 10 分钟提示开关
- 调整视频时长提示为超过 10 分钟

## v1.2.1

- 增加底部加号按钮隐藏
- 增加底部加号按钮禁止拍摄
- 增加视频时长超过 3 分钟提示
- 调整定时、空闲退出逻辑
- 修复空闲退出 BUG

## v1.2.0

- 增加相关表情长按保存
- 增加视频类型、文案关键字过滤
- 调整表情包保存逻辑
- 修复旧版本抖音兼容性
- 修复部分已知问题

## v1.1.9

- 增加隐藏群聊底部分享卡片
- 修复部分已知问题

## v1.1.8

- 增加模块图标隐藏
- 增加 WebDav 多配置
- 增加定时、空闲退出
- 增加去插件化，遇闪退可尝试开启该项
- 修复部分其它已知问题

## v1.1.7

- 增加语音评论保存
- 调整视频下载清晰度
- 调整抖音支持版本提示
- 修复图文下载问题

## v1.1.6

- 增加抖音设置页模块入口
- 增加长按视频唤起模块菜单
- 移除抖音内置模块更新提示
- 修复插件化产生的一个错误
- 修复部分其它已知问题

## v1.1.5

- 修复下载对话框不关闭
- 修复下载通知栏在 UI 线程下造成视频卡顿
- 修复部分机型开启清爽模式后长按弹窗不响应
- 修复部分其它已知问题

## v1.1.4

- 增加禁用双击点赞
- 调整清爽模式逻辑
- 调整评论区图片格式、保存路径
- 调整表情包、评论区图片 / 视频保存后刷新相册
- 调整模块配置为 MMKV（安装包体积增加近 3 MB）

## v1.1.3

- 适配抖音 25.6.0、25.7.0、25.8.0、25.9.0
- 隐藏评论区「我也发一张」
- 调整清爽模式为半屏长按
- 调整模块内置菜单
- 修复部分小问题

## v1.1.2

- 适配抖音 25.5.0
- 调整清爽模式切换逻辑
- 修复自定义文件名空白字符下载失败

## v1.1.1

- 增加视频 / 图文下载时自定义文件名
- 增加首页视频清爽模式
- 增加震动反馈开关
- 修复部分页面顶部仍被重构

## v1.1.0

- 适配抖音 25.2.0
- 修复好友分享内容顶部被重构
- 修复首页顶部 tab 切换时半透明丢失
- 修复底部弹层样式表情包无法保存
- 适配列表仅作为参考，请自行测试各项功能

## v1.0.9

- 视频 / 图文下载文件名账号问题

## v1.0.8

- 修复评论区图片保存造成崩溃
- 修复评论区图片只能保存第一张情况
- 修复用户主页图文详情顶部被重构
- 修复 Android 11 以上部分机型存储权限
- minSdk 更改为 24
- 致谢 @LuckyPray/DexKit

## v1.0.7

- 适配抖音 25.0.0、25.1.0
- 增加首页控件半透明
- 兼容原版 Xposed 框架模块显示未加载
- 兼容抖音旧预览表情包保存
- 兼容抖音评论区视频保存（部分机型可能无法使用）
- 修复 Xpler 参数 args 空指针无法 hook 情况
- 修复弹窗在 Android 13 偶现多次弹出情况
- 修复 WebDav 上传通知栏提示

## v1.0.6

- 适配抖音 24.9.0
- 增加 WebDav

## v1.0.5

- 适配抖音 24.8.0
- 适配抖音 23.5.0、23.6.0、23.7.0、23.8.0、23.9.0

## v1.0.4

- 适配抖音 24.7.0
- 优化 Hook 逻辑

## v1.0.3

- 适配抖音 24.0.0、24.1.0、24.2.0、24.3.0
- 修复评论区图片 24.4.0 无法保存情况

## v1.0.2

- 适配抖音 24.6.0
- 更换评论区图片的判断逻辑

## v1.0.1

- 文件名过长无法下载
- 关注页未获取到基本信息

## v1.0.0

- 适配抖音 24.4.0、24.5.0
