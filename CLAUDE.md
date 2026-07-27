# HeartRateMonitorMobile

Android BLE 心率监测应用（纯中文 UI）。2026-07 完成大规模重构，架构约定如下，改动时必须遵守。

## 构建

- 编译环境在 distrobox `dev` 容器：`distrobox enter dev -- ./gradlew :app:assembleDebug`
- 工具链：JDK 25 / Gradle 9.6.1 / AGP 9.3.1（内置 Kotlin，**不要**添加 org.jetbrains.kotlin.android 插件）/ KSP / Room Gradle Plugin
- compileSdk 37（minor 1，见 app/build.gradle.kts 的 compileSdk DSL 块），targetSdk 36，minSdk 27
- 签名：keystore.properties（gitignored）或环境变量 KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD；没有则 release 回退 debug 签名
- 依赖一律进 gradle/libs.versions.toml，禁止在 build.gradle.kts 内联版本号
- 单元测试：`./gradlew :app:testDebugUnitTest`

## 架构（分层规则）

- `core/AppContainer`：手动 DI 组合根（无 Hilt/Koin）。进程级单例只能在这里创建，组件经 `AppContainer.get(context)` 取用。
- `domain/HeartRateRepository`：心率/连接状态的唯一事实来源（StateFlow）。UI、Service、磁贴全部从这里取数。**禁止** bindService/Binder 传数据。
- `data/settings/SettingsRepository`：唯一的设置入口（Preferences DataStore，键定义在 SettingsKeys，默认值唯一定义在 AppSettings）。**禁止**直接 getSharedPreferences；变更监听用 `settings.flowOf { ... }`。
- `ble/BleConnectionManager`：扫描/连接/心率订阅/指数退避自动重连（5s→10s→30s→60s 封顶，无限重试）。
- `service/`：全部是"系统集成壳"（前台保活、通知、窗口），不承载业务逻辑。BleService 生命周期控制 ServerController（HTTP/WS 服务器）。
- BleState 是纯状态（无 UI 文案）；文案映射唯一入口 `ble/BleStateTexts`（UI 与 HTTP/WS 接口共用）。
- UI 层：StateFlow + `repeatOnLifecycle(STARTED)` 收集；一次性事件用 SharedFlow/Channel；**禁止** LiveData。

## 生态与安全定位（维护者明确决策，不要"好心"改回安全默认）

本项目定位**家庭局域网游戏生态**，与桌面版 HeartRateMonitor、HeartRateWidget 互通，兼容优先于安全：

- **签名密钥内置于仓库 `.key/key`**（维护者决策）：任何人克隆编译出的 APK 与官方签名一致，
  社区构建可覆盖安装官方版本。keystore.properties/环境变量可覆盖。
- 内置 HTTP/WS 服务器**绑定所有网卡**（HeartRateWidget/桌面版无认证能力，需免配置直连）；
  Token 认证为可选项（server_auth_required，默认 false）。
- Webhook 允许 **http 与 https**（官方预设、VRChat OSC、sleepy-project 全是 http）；
  manifest 已开 usesCleartextTraffic。
- 仍然保留的防线：GitHub 预设同步走预览+确认+强制 enabled=false；云备份排除健康数据与凭据。

## 多设备对比评测（v2.1）

- 设置开关 comparison_mode_enabled（默认关）门控全部对比 UI；关闭时主页与 v2.0 行为一致
- `ble/ComparisonDeviceManager`：对比设备连接层，独立于主引擎（无 Webhook/历史副作用、独立扫描不打断主连接）
- 指标计算在 `domain/DeviceMetrics.kt`（RollingRate/BpmDiffAccumulator）与 `domain/AccuracyReport.kt`（历史回放 MAE/最大差），全部纯 Kotlin 有单测
- 图表唯一实现 `ui/chart/HeartRateChartController`（多序列/主题化/绝对时间轴），主页与历史详情共用；新增序列配色须走 colorForIndex
- 数据库 v3：RecordingSession → SessionDevice → HeartRateRecord(含 rr 列)；**迁移 2→3 已是正式 Migration，禁止再引入 fallbackToDestructiveMigration**
- HTTP/WS JSON：既有字段=主设备（生态契约），多设备走增量 `devices` 数组

## 保活双通道（v2.2）

设置 `keep_alive_channel`：`FOREGROUND`（默认）| `ACCESSIBILITY`，用户在设置页显式选择。

- `service/overlay/FloatingWindowHost`：悬浮窗唯一实现，windowType 由持有者注入；
  FloatingWindowService（TYPE_APPLICATION_OVERLAY）与 HeartRateAccessibilityService
  （TYPE_ACCESSIBILITY_OVERLAY）共用，**禁止再在服务里复制窗口逻辑**
- `core/OverlayCoordinator`：登记无障碍是否生效 + 下拉面板是否展开 + 触摸穿透动作路由；
  磁贴/主页/状态栏据此分支，**新增依赖通道的行为一律读它，不要各自判断**
- `HeartRateAccessibilityService` 能力必须保持最小：canRetrieveWindowContent=false、
  仅 typeWindowStateChanged（用于下拉面板让位）；**不得为任何功能扩大无障碍权限**
- accessibility overlay 层级高于状态栏与下拉面板 → 状态栏常驻在面板展开时临时隐藏
- 触摸穿透通知按钮走 `FloatingWindowActionReceiver` 广播（跨通道路由），不要改回 startService
- **通道属主判断一律用 `OverlayCoordinator.isAccessibilityChannel()`**（= 系统已开启 AND 用户选了该通道）；
  只看 `accessibilityActive` 会在"切回前台通道但没关无障碍"时误判服务器归属
- 冷启动补启：ContentProvider 早于无障碍连接，其判断只能读**设置**；状态栏/预警的补启在
  `HeartRateAccessibilityService.onServiceConnected` 内完成
- 无障碍被系统关闭时必须回退前台通道（teardown → 写回设置 + 拉起前台服务），否则全应用静默失效
- 定向自动连接/重连使用 `Filter.Address` 过滤扫描（息屏下无过滤扫描不投递结果）；手动扫描保持无过滤
- **无障碍通道 = 全应用零常驻通知**：BleService 不启动（MainActivity/磁贴/预警三处调用点均已判通道），
  StatusBarResidentService 与 HeartRateAlarmService 的 ensureResidentForeground 直接返回并撤掉已有通知。
  新增任何 `startForeground`/`startForegroundService` 调用点都必须先判 `accessibilityActive`
- accessibility overlay 必须用无障碍服务的 WindowManager（携带 window token），
  普通 Service 的 WM 会被 WMS 以 BadToken 静默拒绝——见 OverlayCoordinator.accessibilityContext

## 更新检查（v2.2）

`data/update/UpdateRepository` + `domain/VersionComparator`（纯函数有单测）。
克制原则不得违反：**只在 MainActivity 进入时触发**（磁贴/后台启动绝不检查）、24h 节流、
预发布版不提示、用户可跳过某版本、任何失败静默忽略。

## UI 约定

- Material 3 DayNight + 动态取色；色值/间距用主题 attr 与 @dimen（values/dimens.xml），不裸写
- 滑条用 Material Slider（真实值域写在布局 valueFrom/valueTo）；返回箭头 @drawable/ic_arrow_back
- 心跳动画统一用 service/overlay/HeartbeatAnimator，不要再复制粘贴 ValueAnimator

## 已知待办

- Kable 0.32 → 0.4x 升级（API 大改）作为独立任务
- 历史图表全量加载：长会话（数万行）一次性进内存喂 MPAndroidChart，可在 SQL 层按时间分桶抽稀
- RR 间期（0x2A37 帧内自带）当前在 parseHeartRate 被丢弃；将来做 HRV/压力分析时从解析层带出
- 心率消费约定：**时序敏感的消费方（预警、图表、记录）必须收 `heartRateSamples` 逐样本流**；
  `heartRate` StateFlow（按值去重）只用于"当前值展示"（数字、悬浮窗、通知、磁贴）
- WS 1Hz 恒频推送是对外契约（客户端靠持续推送判活），若改"变化即推+心跳帧"属版本化变更需谨慎
- 签名密钥：用户已决定**沿用现有 key**（保证老用户可覆盖安装）。密钥文件在本地 `.key/key`
  + `keystore.properties`（均已 gitignore）。注意：该 key 曾出现在公开 git 历史中，
  风险已知晓并被接受；如日后要换 key，直接替换 keystore.properties 即可。
- i18n：中英双语已完成——**默认英文 `values/strings.xml`，中文 `values-zh/strings.xml`**
  （跟随系统语言，第三方语言回落英文）。新增用户可见文案必须同时进两个 strings.xml
  （220 个 key，键集合必须保持一致）。数据层持久化字符串（如设备名回退"未知设备"）不翻译。
