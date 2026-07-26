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

## 安全约定（不得回退）

- 签名密码/keystore 永不入库（历史上曾泄漏，`.key/` 已 gitignore；密钥轮换待用户决策）
- 内置 HTTP/WS 服务器默认只绑 127.0.0.1；开局域网必须走 token 认证（ServerController）
- Webhook URL 仅允许 https（WebhookRepository.isUrlAllowed）；GitHub 预设同步必须走预览+确认+强制 enabled=false

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
