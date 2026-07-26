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
