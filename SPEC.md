# HeartRateMonitorMobile 重构与修复规格说明书（SPEC）

> **状态（2026-07-27）：全部阶段已实施完成**。assembleDebug / assembleRelease / testDebugUnitTest 全绿，
> release APK 1.97MB。唯一有意延后项见「明确不做」与 CLAUDE.md「已知待办」。
>
> 依据 2026-07-27 多维度代码评审（63 条发现，34 条 high/medium 经对抗核查确认）制定。
> 原则：不做最小可行修补，选择长期可维护、性能最优的方案；每阶段结束必须通过
> `distrobox enter dev -- ./gradlew :app:assembleDebug` 编译验证，单元测试随阶段补齐。

---

## 阶段 0：编译环境（前置条件）

- distrobox `dev` 容器（Arch Linux）：JDK 25（最新 LTS，archlinux-java 默认）+ Android SDK Platform 37.1 / 36 + Build-Tools 37.0.0/36.0.0，SDK 位于 `~/Android/Sdk`，`local.properties` 指向该路径（不入库）。
- 工具链（在线确认的最新稳定版）：Gradle 9.6.1 + AGP 9.3.1（内置 Kotlin，无独立 KGP 插件）+ KSP 2.3.10（KSP2 独立版本号）+ Room Gradle Plugin 2.8.4。
- compileSdk 37（minor 1，AGP 9 新 DSL `compileSdk { version = release(37) { minorApiLevel = 1 } }`），targetSdk 36（保持已验证的运行时行为），minSdk 27。
- 库版本：core-ktx 1.19.0 / appcompat 1.7.1 / material 1.14.0 / constraintlayout 2.2.1 / activity 1.13.0 / lifecycle 2.11.0 / room 2.8.4 / datastore 1.2.1 / coroutines(-test) 1.10.2。
- 验收：`distrobox enter dev -- ./gradlew :app:assembleDebug` 成功产出 APK。

## 阶段 1：构建与依赖治理（安全 + 体积 + 构建速度）

| # | 项目 | 方案 | 验收 |
|---|------|------|------|
| 1.1 | **签名密钥泄漏（P0）** | 签名配置改从 `keystore.properties`（gitignored）/环境变量读取；`.key/` 停止 git 跟踪并加入 `.gitignore`；`signingConfig` 从 `defaultConfig` 移到 `buildTypes.release`；无密钥文件时 release 回退 debug 签名并打印警告（CI 兼容）。**密钥轮换需用户决策**（换新 key 老用户须卸载重装），SPEC 只做机制修复。 | 仓库中无任何明文密码；`git ls-files` 无 `.key/` |
| 1.2 | 移除 Compose 死代码 | 删 `ui/theme/` 三件套、`kotlin-compose` 插件、`buildFeatures.compose`、全部 compose 依赖（含 test/debug 变体） | 编译通过；APK 无 compose 类 |
| 1.3 | kapt → KSP | Room 编译器改 KSP（`2.0.21-1.0.28`），删除重复的 `annotationProcessor` 声明与 `kotlin-kapt` 插件 | 编译通过，schema 导出仍生效 |
| 1.4 | 依赖全部收进 version catalog | 内联字符串依赖全部迁入 `libs.versions.toml`；lifecycle 统一 2.9.1；删除悬空 `java-websocket`;移除零引用的 `lifecycle-process`、`lifecycle-livedata-ktx`（LiveData 在阶段 2 淘汰） | `app/build.gradle.kts` 无内联版本号 |
| 1.5 | 重写 `proguard-rules.pro` | 删除全部全量 keep（androidx/kotlin/coroutines/库逐个 keep/R 类 keep），仅保留：行号属性、`Webhook`/`WebhookTrigger`（org.json 反射按名存取）| release 构建通过；装包回归蓝牙/数据库/图表/Webhook |
| 1.6 | 语言资源过滤 | `androidResources.localeFilters += "zh-rCN"`（App 纯中文） | resources.arsc 无 85 种语言 |
| 1.7 | 体积清理 | 删各密度 `ic_launcher*.webp` 死图标（minSdk 27 恒用 anydpi-v26）；packaging 排除 META-INF 杂项；PermissionX → 原生 `ActivityResultContracts`（阶段 2 一并做） | APK 明显减小 |
| 1.8 | 构建性能 | `gradle.properties`：parallel、caching、configuration-cache、jvmargs 调优；JVM toolchain 17 | 二次构建显著提速 |

## 阶段 2：架构重构（核心）

### 2.1 目标架构

```
app/
 ├─ core/                     # 组合根与基础设施
 │   ├─ AppContainer.kt       # 手动 DI 容器（唯一组合根，Application 持有）
 │   └─ di 说明：规模（~6k 行）不引入 Hilt/Koin——手动构造透明、零注解处理开销
 ├─ data/
 │   ├─ ble/                  # BleConnectionManager：Kable 封装 + 连接状态机 + 指数退避重连
 │   ├─ db/                   # Room（不变）+ SessionRecorder（会话写入，从 BleService 抽出）
 │   ├─ settings/             # SettingsRepository：Preferences DataStore
 │   │                        #   - SharedPreferencesMigration("app_settings") 平滑迁移老用户
 │   │                        #   - 全部键名/默认值唯一定义，类型化 Flow 读写
 │   │                        #   - 修复 speed_display_enabled 默认值冲突（统一 false）
 │   └─ webhook/              # WebhookRepository（配置缓存内存化，杜绝每次心跳读盘）
 ├─ domain/
 │   ├─ HeartRateRepository.kt  # 进程级单一事实来源：bleState/heartRate/speed/scanResults
 │   │                          # StateFlow 全部在此，Service 与 UI 都从这里取数
 │   └─ AlarmStateMachine.kt    # 报警状态机抽成纯 Kotlin 类（可单元测试）
 ├─ service/                  # 全部瘦身为"系统集成壳"
 │   ├─ BleService.kt         # 只负责：前台保活、通知、把 repository 桥接到系统层
 │   ├─ FloatingWindowService / StatusBarResidentService / HeartRateAlarmService
 │   ├─ HeartRateTileService.kt  # 新增：快速设置磁贴（阶段 3）
 │   ├─ overlay/              # 抽出共享：心跳动画、dpToPx、悬浮窗保活公共基类
 │   └─ server/               # ServerController：HTTP/WS 生命周期 + 端口热重启修复
 └─ ui/                       # Activity + ViewModel（每个有状态页面都有 VM）
```

### 2.2 强制规则

- **通信**：删除全部 `bindService`/`LocalBinder`/`WeakReference<BleService>`/`setBleService` 样板。数据一律经 `HeartRateRepository`（AppContainer 单例）；控制操作经 repository 暴露的方法。
- **响应式**：全面 StateFlow/SharedFlow，LiveData 彻底移除；UI 收集一律 `repeatOnLifecycle(STARTED)`；一次性事件（Toast 等）用 `Channel`。
- **设置**：任何组件不得直接 `getSharedPreferences`;全部经 `SettingsRepository`，`OnSharedPreferenceChangeListener` 全部替换为 DataStore Flow。
- **BLE 重连**：指数退避（5s→10s→30s→60s 封顶，自动重连开启期间不放弃），连接成功重置；`lastConnectedDeviceId` 持久化；扫描请求被拒时调用方可感知。
- **异常**：清除 23 处空 `catch (_: Exception)`——能处理的处理，不能处理的至少 `Log.w` 带上下文；协程作用域用 SupervisorJob。
- **国际化基础**：~~代码/布局中硬编码中文全部抽到 `strings.xml`~~ →
  实施时降级为延后项（见「明确不做」）：App 定位纯中文（localeFilters=zh-rCN），
  全量抽取约 150 处文案属纯机械工程且无功能收益，与大重构叠加会放大回归面。
  新增字符串已尽量入 strings.xml。

### 2.3 安全修复（P0）

- HTTP/WS 服务器：默认绑定 `127.0.0.1`；新增"允许局域网访问"显式开关（带风险提示文案）；随机 token 认证（HTTP `?token=`/`Authorization`，WS 握手校验），token 显示在 ServerActivity 可复制/重置。
- Webhook：URL 仅允许 `https://`（保存与发送双重校验）；GitHub 云端同步改"拉取→预览列表→用户确认合并"，同步条目强制 `enabled=false`；下发 JSON 做 schema 校验。
- 备份：`allowBackup` 收紧，排除健康数据库与 webhook 凭据（`dataExtractionRules`）。
- 状态栏取色：MediaProjection 改为**缩小分辨率的 VirtualDisplay（1/8 尺度）+ 仅采样状态栏高度区域**，仅在"自动颜色"开启时采样；功能不回退，内存/CPU 大幅下降。

## 阶段 3：新功能——快速设置磁贴（QS Tile）

- 新增 `HeartRateTileService`（`BIND_QUICK_SETTINGS_TILE`）。
- **点按**：未运行 → 启动 BleService（自动连接收藏/上次设备）+ FloatingWindowService，磁贴转 Active；运行中 → 停止两者，磁贴转 Inactive。连接后磁贴副标题实时显示 BPM。
- **长按**：打开 App 主界面（MainActivity 声明 `ACTION_QS_TILE_PREFERENCES` intent-filter）。
- Android 14+ FGS 启动限制：磁贴点击处于临时豁免窗口，`startForegroundService` 合规；FGS type 按需声明。

## 阶段 4：UI 一致性重构（保持现有风格，全面打磨）

- **Design token 落地**：新建 `dimens.xml`（spacing 4/8/16/24、radius、touch target 48dp）；裸 `textSize` 全部替换为 M3 `textAppearance` 层级；补全 `surfaceContainer` 色阶（浅/深两套），硬编码色值清零。
- **速修**：新增 `ic_arrow_back`（autoMirrored）替换 6 处反向导航图标；图表 marker 文字改 `?attr/colorOnSurface`（修深色不可读）；替换 2 处 Holo 系统图标；触摸目标补足 48dp；装饰性图标 `contentDescription=@null`，功能图标补语义。
- **心率主卡片重做**：emoji ❤️ → `ic_heart` 矢量 + 按 BPM 节奏的脉冲缩放动画；背景渐变改引用主题色（动态取色生效）；数字用 DisplayLarge + `fontFeatureSettings="tnum"`；断连/连接中/已连接三态视觉区分。
- **控件现代化**：约 12 处原生 SeekBar → Material `Slider`/`RangeSlider`；Toolbar 内 mini FAB → IconToggleButton；设置页链接行统一带图标列表行样式。
- **空状态**：历史页/主页设备列表/Webhook 列表三处"图标+主文案+行动按钮"空状态；扫描中反馈。
- 悬浮窗/状态栏悬浮：心形矢量化并随文字色 tint，跳动与心率同步。

## 阶段 5：测试与 CI

- 单元测试：`AlarmStateMachine`（全状态迁移）、`SettingsRepository` 默认值一致性、BLE 重连退避序列、Webhook URL 校验。
- CI（android.yml）：跑 unit test + lint 并上传报告；`gradle/actions/wrapper-validation`；产物仅 debug 签名。

## 明确不做（本轮）

- 多模块化、迁移 Jetpack Compose、替换 MPAndroidChart/NanoHTTPD/Kable（R8 修复后收益比不足；Kable 0.4x API 大改，作为后续独立任务）
- 签名密钥轮换（需用户决策：换 key = 老用户卸载重装；机制已外部化，轮换时只需替换 keystore.properties）
- targetSdk 37（Android 17 行为变更未经真机验证，compileSdk 已用 37.1，targetSdk 留在 36）
- ~~存量 UI 文案全量抽取 strings.xml~~ → **已完成（追加需求）**：中英双语，
  默认英文 values/ + 中文 values-zh/，220 个 key 键集合一致，跟随系统语言，第三方语言回落英文
- 主页 Toolbar mini FAB 改 IconToggle、主页设备列表空状态插画（纯视觉优化，终审列为遗留美化项）
- 签名密钥轮换：**用户已决策沿用现有 key**（保证老用户可覆盖安装，泄漏风险已知晓并接受）

## 验收总则

1. 每阶段 `assembleDebug` 通过；阶段 1/5 另跑 `assembleRelease` 验证 R8。
2. 单元测试全绿。
3. 行为回归清单：BLE 扫描/连接/断线重连、历史记录/图表、悬浮窗、状态栏常驻、心率报警、HTTP/WS 服务、Webhook 触发、QS 磁贴点按/长按。

---

# v2.2 SPEC：无障碍保活通道（用户可选）

> 需求来源：社区反馈"无障碍版可以不用后台"。目标：利用 AccessibilityService 的
> 系统级绑定实现免通知、抗杀后台的保活与悬浮窗，作为**可选通道**与现有前台服务方案并存。

## 架构设计

### 通道模型
新设置 `keep_alive_channel`：`foreground`（默认，现状）| `accessibility`。
两条通道**互斥渲染悬浮窗、并存不冲突**：

| 关注点 | 前台服务通道（现状） | 无障碍通道（新增） |
|---|---|---|
| 进程保活 | BleService 前台通知 | 系统绑定 AccessibilityService（无通知） |
| 悬浮窗窗口类型 | TYPE_APPLICATION_OVERLAY（需悬浮窗权限） | TYPE_ACCESSIBILITY_OVERLAY（**免悬浮窗权限**，可覆盖游戏沉浸界面） |
| 服务器生命周期 | BleService onCreate/onDestroy | 无障碍服务 onServiceConnected/onDestroy（ServerController 已有重复启动守卫） |
| 磁贴点按 | startForegroundService(BleService)+AUTO_CONNECT | 无障碍活跃时直接调 repository.autoConnect()（不起服务、无通知） |

### 新/改组件
1. **`service/overlay/FloatingWindowHost`**（新，核心抽取）：现 FloatingWindowService 的全部窗口逻辑
   （show/hide/外观/拖动/触摸穿透/catcher）抽为可复用宿主类，构造参数注入 `windowType`；
   FloatingWindowService（TYPE_APPLICATION_OVERLAY）与无障碍服务（TYPE_ACCESSIBILITY_OVERLAY）共用，零复制粘贴。
2. **`core/OverlayCoordinator`**（新，AppContainer 单例）：登记当前活跃 host；
   触摸穿透通知按钮经 `FloatingWindowActionReceiver`（manifest 注册广播）路由到活跃 host（两通道通用）。
3. **`service/HeartRateAccessibilityService`**（新）：
   - 声明最小能力：`canRetrieveWindowContent=false`、无 flags、事件类型仅 typeWindowStateChanged（协议要求非空）、
     description 明确"仅用于悬浮窗展示与保活，不读取任何屏幕内容"（中英双语）；
   - onServiceConnected：置活跃标记 → serverController.start() → 按设置托管悬浮窗 → autoConnectIfEnabled；
   - onDestroy/onUnbind：释放悬浮窗、serverController.stop()（若 BleService 未运行）、清标记。
4. **FloatingWindowService**：显隐条件增加 `channel==foreground`；无障碍通道下不渲染（互斥）。
5. **HeartRateTileService**：点按分支感知通道（见上表）；长按不变。
6. **MainActivity.toggleFloatingWindow**：无障碍通道活跃时跳过悬浮窗权限检查（不需要）。

### 状态栏常驻也走双通道（同一套 windowType 注入）
StatusBarResidentService 的 overlay 同样按通道切换窗口类型，收益比悬浮窗更大：

- **位置不受影响**：两种类型都用 `FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS`，坐标系一致，
  `y=0` 即屏幕顶端，仍能精确落在状态栏区域（现有 x/y 微调设置照常生效）。
- **z 序更高**：`TYPE_ACCESSIBILITY_OVERLAY` 层级高于状态栏本身，不再被系统状态栏内容遮挡
  （现状偶发遮挡问题自然消失）。
- **副作用与处理**：该层级也会浮在下拉通知面板之上 → 无障碍服务监听
  `TYPE_WINDOWS_CHANGED/TYPE_WINDOW_STATE_CHANGED`，检测到系统 UI 展开（通知面板/快捷设置）时
  临时隐藏状态栏 overlay，收起后恢复。
- 无障碍通道下 MediaProjection 自动取色仍可用（互不冲突）；该通道免悬浮窗权限，
  `Settings.canDrawOverlays` 检查在此通道下跳过。
- 自愈轮询（3s）在无障碍通道下保留但可延长间隔（系统绑定后被杀概率极低）。

### 明确不动（影响面控制）
HeartRateAlarmService（预警）、BLE 连接层、历史/对比、Webhook、服务器协议——零改动。

## UI 逻辑
设置页新增「保活方式」区（置于悬浮窗样式区之前）：
1. 两个互斥选项（RadioButton）：
   - **前台服务**（默认）：兼容性最好，通知栏常驻一条通知
   - **无障碍服务**：无通知、抗杀后台、悬浮窗免权限；需在系统无障碍设置中开启
2. 选择无障碍时：先弹说明对话框（用途 + 隐私声明"不读取屏幕内容"）→ 确认后写入设置并跳转系统无障碍设置页
3. 状态行：`已生效` / `未生效——点击去系统设置开启`（实时反映服务运行状态）
4. 系统侧被关闭时自动回退前台服务通道行为（悬浮窗由 FloatingWindowService 兜底渲染）

## 实施后核对修复记录（2026-07-27 多智能体审查）
审查发现 2 blocker + 3 major，均已修复：
1. **双窗叠加**（blocker）：FloatingWindowHost 增 `isAccessibilityHost`，显隐条件 = 开关 AND 本宿主为当前生效通道；前台宿主让位时 onHidden→stopSelf。
2. **状态栏 accessibility overlay 无 window token**（blocker）：TYPE_ACCESSIBILITY_OVERLAY 必须由无障碍服务的 WindowManager 添加，否则 BadToken 静默失败。OverlayCoordinator 登记无障碍 Context，StatusBarResidentService 按通道用它重建视图与 WM。
3. **BleService.onDestroy 误停共享服务器**（major）：通道判断后跳过 stop()。
4. **触摸穿透动作注册被误清**（major）：注册/注销加属主身份校验。
5. **状态栏三处强制悬浮窗权限**（major）：统一改为 `无障碍生效 || canDrawOverlays`。
6. 另修：systemUiExpanded 超时自恢复（8s）；无障碍销毁时按 `bleServiceRunning` 决定是否停服务器；无障碍通道下 MainActivity 不再拉起 BleService（兑现"无通知"）。

## 全项目适配审视修复记录（第二轮，14 功能逐项推演）
| 编号 | 问题 | 修复 |
|---|---|---|
| P0-1 | 冷启动时 ContentProvider 早于无障碍连接，状态栏/预警被权限与后台限制挡死且无人补启 | Initializer 改读**通道设置**（非运行时标记）；无障碍 onServiceConnected 内按设置补启两服务（此刻进程为 BOUND_FOREGROUND_SERVICE，是合法启动窗口） |
| P0-2 | 系统侧关闭无障碍后全应用静默变哑（悬浮窗/状态栏/服务器全失、进程降 cached 断连） | teardown 中回退前台通道：写回 `KEEP_ALIVE_CHANNEL=FOREGROUND` 并拉起 BleService/悬浮窗服务，兑现 SPEC 的回退承诺 |
| P0-3 | 服务器属主判据用 `accessibilityActive`，"切回前台但没关无障碍"时关不掉/擅自开 | 新增 `OverlayCoordinator.isAccessibilityChannel()`（生效 AND 用户所选通道），全项目 8 处判断统一走它 |
| P1-4 | 无 FGS 时后台扫描受定位 app-op 与息屏无过滤扫描双重门控 | Manifest 加 `neverForLocation`；定向自动连接/重连改用 `Filter.Address` 过滤扫描（控制器侧匹配，息屏可用），手动扫描保持无过滤 |
| P1-5 | 后台定位被静默停投后悬浮窗长期显示陈旧速度 | SpeedMonitor 增看门狗：15s 无定位回调即归零 |
| P1-6 | 预警服务 onCreate 时通知闪现 | 随 P0-1 修复自然消除（服务在无障碍连接后才启动，标记已就绪） |
| P2-8/9 | onServiceConnected 重入累积收集器；teardown 后 scope 不可重建 | channelJob 幂等 + scope 可重建 |
| P2-10 | canShowOverlay 间接依赖 overlayContext 导致时序自杀 | 判据改为通道设置 + 生效标记 |
| P2-11 | 磁贴无收藏设备时点击静默无反应 | 补 Toast 提示 |
| 自查 | 无障碍通道下停止自动取色后 MediaProjection 通知不撤销 | stopMediaProjectionSampling 按通道显式 stopForeground |

**平台硬约束（无法规避，已在代码注明）**：MediaProjection 自动取色必须运行在 mediaProjection 型前台服务中，
故该功能开启期间无障碍通道也会有一条通知，关闭后立即撤销。

## 验收清单（实现后逐项核对）
- [ ] 两通道下磁贴点按/长按/再点按停止行为正确
- [ ] 无障碍模式：无通知时悬浮窗存活、锁屏恢复、服务器可用
- [ ] 双通道切换往返无残留窗口/重复窗口
- [ ] 状态栏常驻：两通道下位置正确（x/y 微调生效）、无障碍通道下不被状态栏遮挡、下拉通知面板时临时隐藏
- [ ] 心率预警、多设备对比不受影响
- [ ] 触摸穿透在两通道均可用（长按 + 通知按钮）
- [ ] 中英双语文案；构建+单测全绿
