# ❤️ 心率监控器 - HeartRateMonitor

[](https://www.google.com/search?q=%5Bhttps://github.com/ccc007ccc/HeartRateMonitorMobile/releases%5D\(https://github.com/ccc007ccc/HeartRateMonitorMobile/releases\))

> 一款基于 BLE（蓝牙低功耗）技术的 Android 心率监测应用，支持主界面与悬浮窗双视图展示实时心率，并提供丰富的个性化设置与数据接口。

-----

## ✨ 功能特性

  - 🔵 **蓝牙连接**：扫描并连接支持心率服务的 BLE 设备。
  - 📊 **实时心率显示**：在主界面与悬浮窗同步展示 BPM 数值。
  - 🪟 **悬浮窗功能**：
      - 可悬浮于其他应用上方，实时显示心率。
      - 可自由拖动、隐藏、关闭。
  - ❤️ **心跳动画**：根据心率跳动频率动态变化。
  - ⭐ **设备收藏**：快速收藏常用设备，支持自动连接。
  - 🎨 **悬浮窗自定义设置**：
      - 开启/关闭 BPM 文本与心率图标。
      - 设置文字颜色、背景颜色、边框颜色。
      - 背景/边框透明度调整。
      - 调整圆角、整体大小与图标尺寸。
  - 📡 **强大的数据接口**：
      - **HTTP 服务器**：内置HTTP服务器，可供其他应用或设备通过局域网被动拉取实时心率数据。
      - **Webhook 推送**：功能完善的Webhook系统，可在心率变化时将数据主动推送到多个自定义URL，并支持`{bpm}`占位符。
      - **高度自定义与测试**：支持对每一个Webhook进行独立的URL、Body、Headers配置，并提供一键测试功能。
      - **预设管理**：可以自由新增、编辑、删除和启用/禁用多个Webhook预设，并支持从GitHub一键同步官方预设。

-----

## 📦 安装与运行

1.  **克隆项目**

    ```bash
    git clone https://github.com/ccc007ccc/HeartRateMonitorMobile.git
    ```

2.  **打开项目**

      - 使用 **Android Studio** 打开项目文件夹。
      - 等待 **Gradle** 自动同步依赖。

3.  **构建并运行**

      - 使用真机或模拟器（建议 API ≥ 27）连接。
      - 点击工具栏中的 ▶️ 运行按钮，即可编译并启动应用。

-----

## 🧭 使用指南

1.  **首次权限授予**

      - 启动时请允许所需的 **蓝牙权限** 与 **定位权限**。

2.  **连接心率设备**

      - 点击主页右下角的“扫描”按钮，选择设备连接。
      - 连接成功后将实时显示心率数值。

3.  **使用悬浮窗**

      - 点击顶部悬浮窗按钮可开启/关闭悬浮窗。
      - 悬浮窗支持拖动，自定义显示效果。

4.  **收藏常用设备**

      - 点击设备列表右侧的 ⭐️ 图标添加至收藏。
      - 在设置中启用自动连接更高效。

5.  **自定义设置**

      - 点击顶部设置按钮进入设置界面。
      - 所有悬浮窗显示样式可调，包括颜色、透明度、大小等。

6.  **配置数据接口(如果有需要)**

      - 在 **设置** -\> **数据与服务** 中，可以找到 **服务器设置** 和 **Webhook 设置** 的入口。
      - **服务器设置** 允许你启用/禁用一个本地HTTP服务器并指定端口。
      - **Webhook 设置** 页面可以管理所有推送预设，包括新增、编辑、删除、测试和同步官方预设。

-----

## 🖼️ 截图展示

<div style="display: flex; justify-content: center; gap: 12px; flex-wrap: wrap;">
  <img src="https://github.com/user-attachments/assets/32d1d9a3-9131-44c8-8aee-cc6b0cd4324f" width="250"/>
  <img src="https://github.com/user-attachments/assets/dc9d4568-a956-423c-8940-7a5fd4f55138" width="250"/>
  <img src="https://github.com/user-attachments/assets/d49c5d62-e472-4b2f-946e-29f271f9a67f" width="250"/>
  <img src="https://github.com/user-attachments/assets/2fa8ff21-c46a-462e-8c34-29efa40325ff" width="250"/>
</div>
