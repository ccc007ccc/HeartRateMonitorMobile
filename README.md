# ❤️ 心率监控器 - HeartRateMonitor

**简体中文** | [English](README_EN.md)

![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-blue)
[![Download](https://img.shields.io/badge/Download-APK-blue)](https://github.com/ccc007ccc/HeartRateMonitorMobile/releases)

> 一款基于 BLE（蓝牙低功耗）技术的 Android 心率监测应用，支持主界面与悬浮窗双视图展示实时心率，并提供丰富的个性化设置与数据接口。应用界面中英双语，自动跟随系统语言。

> 🖥️ 想要在 Windows 桌面使用心率悬浮窗？[点击跳转桌面版 HeartRateMonitor](https://github.com/ccc007ccc/HeartRateMonitor)

-----

## ✨ 功能特性

- 🔵 **蓝牙连接**：扫描并连接支持心率服务的 BLE 设备。
- ⭐ **设备管理**：快速收藏常用设备，支持启动时自动连接和意外断开后按指数退避自动重连（5s→10s→30s→60s，不放弃）。
- ❤️ **心跳动画**：根据心率跳动频率动态变化。
- 🧩 **快速设置磁贴**：下拉控制中心一键启动——点按启动心率服务+悬浮窗并自动连接，长按打开应用界面。
- 🔔 **更新提示**：进入应用时静默检查 GitHub 新版本（每天最多一次），有更新才弹一次提示，可选择「不再提示此版本」；后台/磁贴启动不会检查。
- 🛡️ **两种保活方式可选**：默认「前台服务」（兼容性最好）；也可切换到「无障碍服务」——**全程无任何常驻通知**、抗后台清理，悬浮窗免悬浮窗权限且可覆盖全屏游戏。该服务仅用于保活与绘制浮层，不读取任何屏幕内容。
- 📌 **状态栏常驻心率**：在状态栏区域常驻显示心率，支持自动识别背景颜色切换黑/白字。
- 🚨 **心率预警**：姿态感知（静坐/站立/运动）的心率高低限报警，支持自定义阈值、持续时长与重复提醒。
- ⚖️ **多设备对比评测**（设置中开启）：同时连接多台心率设备，主页叠加曲线实时对比，每台设备显示 BPM/采样率/实时差值/平均绝对差；历史会话保存全部设备数据并给出准度小结——评测多块手表/心率带的利器。
- 📊 **心率历史与图表分析**:
    - **自动记录**：可选的后台记录功能，自动为每一次连接（从连接到断开）保存为一段心率历史会话。
    - **历史列表**：清晰展示所有历史记录，包括设备名称、记录起止时间。
    - **批量管理**：通过长按进入多选模式，支持对历史记录进行全选和批量删除。
    - **深度图表分析**：点击任意历史记录，可查看该时段完整的心率变化图表。支持缩放、拖动，并通过触摸在图表上精确查看任意时间点的心率值。
    - **横屏查看**：在图表详情页提供一键横屏功能，以获得更好的数据概览体验。
- 🎨 **高度自定义设置**：
    - **功能开关**：可自由开启或关闭心率记录、心跳动画、自动连接等功能，并为耗电功能提供友好提醒。
    - **悬浮窗样式**：可完全自定义悬浮窗的元素（BPM文本/图标）、颜色（文字/背景/边框）、透明度、圆角、整体大小与图标尺寸。
- 📡 **强大的数据接口 (Webhook & Server)**：
    - **HTTP 服务器**：内置HTTP服务器，可供其他应用或设备被动拉取实时心率数据（JSON 含 `heart_rate`/`status`/`status_key`/`speed` 等字段）。
    - **WebSocket 服务器**: 内置WebSocket服务器，可向所有连接的客户端实时主动推送心率和连接状态。
    - **生态互联**：服务器面向局域网开放（与 [桌面版 HeartRateMonitor](https://github.com/ccc007ccc/HeartRateMonitor)、[HeartRateWidget](https://github.com/ccc007ccc/HeartRateWidget) 无缝互通）；可选的 Token 认证（默认关闭，开启后请求需携带 `Authorization: Bearer <token>` 或 `?token=`）。
    - **Webhook 推送**：完善的Webhook系统，可在“连接成功”、“连接断开”、“心率更新”等多种事件触发时，将数据主动推送到多个自定义URL（http/https 均可，VRChat OSC、sleepy-project 等本地目标开箱即用），支持`{bpm}`/`{speed}`占位符。
    - **预设管理**：可以自由新增、编辑、删除、测试和启用/禁用多个Webhook预设，并支持从GitHub同步官方预设（预览确认后合并，同步条目默认禁用）。

-----

## 📦 安装与运行

1.  **克隆项目**

    ```bash
    git clone https://github.com/ccc007ccc/HeartRateMonitorMobile.git
    ```

2.  **打开项目**

      - 使用 **Android Studio** 打开项目文件夹（需 JDK 17+，本项目开发环境为 JDK 25 + Android SDK Platform 37）。
      - 等待 **Gradle** 自动同步依赖。
      - Release 签名：仓库已内置签名密钥（`.key/key`），克隆后直接构建即可得到与官方发布版**相同签名**的 APK（可覆盖安装官方版本）。如需使用自己的密钥，在根目录放置 `keystore.properties`（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`）即可覆盖。

3.  **构建并运行**

      - 使用真机或模拟器（最低 API 27 / Android 8.1）连接。
      - 点击工具栏中的 ▶️ 运行按钮，即可编译并启动应用。
      - 命令行构建：`./gradlew :app:assembleDebug`；单元测试：`./gradlew :app:testDebugUnitTest`。

-----

## 🧭 使用指南

1.  **首次权限授予**
    - 启动时请允许所需的 **蓝牙权限** 与 **定位权限**。

2.  **开启心率记录 (可选)**
    - 进入 **设置** 页面，找到“功能设置”下的“心率记录功能”开关。
    - 点击开启，App将在您连接设备后自动保存心率数据。此功能默认关闭以节省电量。

3.  **连接心率设备**
    - 点击主页顶部的“扫描”按钮，在设备列表中选择您的设备进行连接。
    - 连接成功后，您将在主页看到实时心率数值和动态图表。
    - 提示：智能手表（如 OPPO Watch）通常只在手表端运动/测量程序运行时才对外推送心率；胸带类设备则是连上即持续推流。

4.  **查看历史记录**
    - 在主页点击“查看心率历史记录”卡片，即可进入历史列表。
    - **长按** 任意记录可进入多选模式进行批量删除。
    - **单击** 任意记录可查看详细的心率图表。

5.  **使用悬浮窗**
    - 点击主页顶部的悬浮窗图标按钮可开启/关闭悬浮窗。
    - 在 **设置 -> 悬浮窗样式** 中可自定义其外观（开启悬浮窗后该设置区才会显示）。

6.  **快速设置磁贴**
    - 下拉控制中心 → 编辑磁贴，把「心率监控」拖入常用区。
    - **点按**：启动心率服务 + 悬浮窗并自动连接收藏/上次设备；**长按**：打开应用界面；再次点按：完整停止。

7.  **状态栏常驻与心率预警**
    - **设置 -> 状态栏常驻心率**：在状态栏区域常驻显示心率，可自动识别背景颜色切换黑/白字。
    - **设置 -> 心率预警**：姿态感知（静坐/站立/运动）的高低心率报警，支持阈值、持续时长与重复提醒。

8.  **配置数据接口 (高级)**
    - 在 **设置** 中可以找到 **服务器设置** 和 **Webhook 设置** 的入口。
    - 开启服务器后，同一局域网内的 [HeartRateWidget](https://github.com/ccc007ccc/HeartRateWidget) / 桌面版可直接连接手机 IP 读取心率；如需防护可在服务器页开启可选的 Token 认证。

-----

## 🖼️ 截图展示

*(建议您在完成功能后，替换为包含新功能界面的最新截图)*

<div style="display: flex; justify-content: center; gap: 12px; flex-wrap: wrap;">
  <img src="https://github.com/user-attachments/assets/ad9dbdd0-d810-4d39-9cc5-b0594812f72a" width="255"/>
  
  <img src="https://github.com/user-attachments/assets/fc25f6fc-37ed-4f63-9e15-a91c27e82557" width="255"/>
  <img src="https://github.com/user-attachments/assets/926f9fd6-b9ce-405a-9cdd-3841def2cd58" width="255"/>
  
  <img src="https://github.com/user-attachments/assets/f0f72d07-830a-459c-aeb2-ecc1fa7379e0" width="255"/>
  <img src="https://github.com/user-attachments/assets/2fa8ff21-c46a-462e-8c34-29efa40325ff" width="255"/>

  <img src="https://github.com/user-attachments/assets/181a2d55-bb49-4199-99f9-1912878ed0f0" width="auto"/>
</div>
