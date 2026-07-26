# ❤️ Heart Rate Monitor - HeartRateMonitorMobile

[简体中文](README.md) | **English**

![Platform](https://img.shields.io/badge/platform-Android-green)
![Language](https://img.shields.io/badge/language-Kotlin-blue)
[![Download](https://img.shields.io/badge/Download-APK-blue)](https://github.com/ccc007ccc/HeartRateMonitorMobile/releases)

> An Android heart rate monitoring app based on BLE (Bluetooth Low Energy). Shows real-time heart rate on the main screen and in a floating window, with rich customization options and data interfaces. The UI is fully bilingual (Chinese / English) and follows the system language automatically.

> 🖥️ Want a heart rate overlay on your Windows desktop? [Check out the desktop version of HeartRateMonitor](https://github.com/ccc007ccc/HeartRateMonitor)

-----

## ✨ Features

- 🔵 **BLE Connectivity**: Scan and connect to any BLE device exposing the standard Heart Rate service.
- ⭐ **Device Management**: Favorite your devices, auto-connect on launch, and auto-reconnect after unexpected disconnects with exponential backoff (5s → 10s → 30s → 60s, never gives up).
- ❤️ **Heartbeat Animation**: The heart icon beats in sync with your actual heart rate.
- 🧩 **Quick Settings Tile**: One tap from the notification shade — tap to start the service + floating window and auto-connect; long-press to open the app; tap again to stop everything.
- 📌 **Persistent Status Bar Heart Rate**: Always-on heart rate display in the status bar area, with automatic black/white text based on background brightness.
- 🚨 **Heart Rate Alerts**: Posture-aware (sitting / standing / exercising) high/low heart rate alarms with configurable thresholds, duration, and repeat interval.
- ⚖️ **Multi-device benchmarking** (enable in Settings): connect several heart rate monitors at once, overlay their curves live, and see BPM / packet rate / live delta / mean absolute difference per device; sessions record every device and history charts include an accuracy summary — perfect for comparing watches and chest straps.
- 📊 **History & Chart Analysis**:
    - **Automatic recording** (optional): every connection session is saved as a heart rate history entry.
    - **History list** with device name and session start/end times; long-press for multi-select and batch delete.
    - **Detailed charts**: pinch-to-zoom, drag, and touch to inspect the heart rate at any point in time; one-tap landscape mode.
- 🎨 **Deep Customization**:
    - Toggles for history recording, heartbeat animation, auto-connect, and more — with friendly warnings for battery-heavy features.
    - Fully customizable floating window: elements (BPM text / icon), colors (text / background / border), opacity, corner radius, and size.
- 📡 **Powerful Data Interfaces (Webhook & Server)**:
    - **HTTP server**: other apps and devices can pull real-time heart rate as JSON (`heart_rate`, `status`, `status_key`, `speed`, …).
    - **WebSocket server**: pushes heart rate and connection state to all connected clients in real time.
    - **Ecosystem interop**: servers are LAN-reachable out of the box, pairing seamlessly with the [desktop HeartRateMonitor](https://github.com/ccc007ccc/HeartRateMonitor) and [HeartRateWidget](https://github.com/ccc007ccc/HeartRateWidget); optional token authentication (off by default; when on, requests need `Authorization: Bearer <token>` or `?token=`).
    - **Webhook push**: fire configurable HTTP requests on "connected", "disconnected", and "heart rate updated" events, with `{bpm}` / `{speed}` placeholders. Both http and https targets are supported — VRChat OSC, sleepy-project, and other local endpoints work out of the box.
    - **Preset management**: create, edit, test, and toggle multiple webhook presets; sync official presets from GitHub (with preview and confirmation — synced entries are disabled by default).

-----

## 📦 Build & Run

1.  **Clone the project**

    ```bash
    git clone https://github.com/ccc007ccc/HeartRateMonitorMobile.git
    ```

2.  **Open the project**

      - Open the folder in **Android Studio** (JDK 17+ required; this project is developed with JDK 25 + Android SDK Platform 37).
      - Wait for **Gradle** to sync dependencies.
      - Release signing: the signing key ships in the repo (`.key/key`), so a fresh clone builds an APK with the **same signature** as official releases (installable over them). To use your own key, place a `keystore.properties` file (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) in the project root to override.

3.  **Build and run**

      - Connect a device or emulator (minimum API 27 / Android 8.1).
      - Press ▶️ Run, or build from the command line: `./gradlew :app:assembleDebug`; unit tests: `./gradlew :app:testDebugUnitTest`.

-----

## 🧭 User Guide

1.  **First launch**
    - Grant the requested **Bluetooth** and **Location** permissions.

2.  **Enable history recording (optional)**
    - Go to **Settings** and toggle "History Recording". Sessions are then saved automatically. Off by default to save battery.

3.  **Connect a heart rate device**
    - Tap the scan button on the main screen and pick your device from the list.
    - Note: smartwatches (e.g. OPPO Watch) typically only broadcast heart rate while their own workout/measurement app is running; chest straps stream continuously.

4.  **View history**
    - Open the history page from the toolbar icon. **Long-press** entries for batch delete; **tap** an entry for the detailed chart.

5.  **Floating window**
    - Toggle it with the floating-window button on the main screen; customize it under **Settings → Floating Window Style** (the section appears once the floating window is enabled).

6.  **Quick Settings tile**
    - Edit your Quick Settings panel and add the "Heart Rate Monitor" tile.
    - **Tap**: start the service + floating window and auto-connect; **long-press**: open the app; tap again: stop everything.

7.  **Status bar display & heart rate alerts**
    - **Settings → Persistent Status Bar Heart Rate**: always-on heart rate in the status bar with automatic text color.
    - **Settings → Heart Rate Alert**: posture-aware high/low alarms with thresholds, duration, and repeat interval.

8.  **Data interfaces (advanced)**
    - Find **Server Settings** and **Webhook Settings** inside **Settings**.
    - Once enabled, [HeartRateWidget](https://github.com/ccc007ccc/HeartRateWidget) / the desktop app on the same network can connect to your phone's IP directly; optional token authentication is available on the server page.

-----

## 🖼️ Screenshots

<div style="display: flex; justify-content: center; gap: 12px; flex-wrap: wrap;">
  <img src="https://github.com/user-attachments/assets/ad9dbdd0-d810-4d39-9cc5-b0594812f72a" width="255"/>
  <img src="https://github.com/user-attachments/assets/fc25f6fc-37ed-4f63-9e15-a91c27e82557" width="255"/>
  <img src="https://github.com/user-attachments/assets/926f9fd6-b9ce-405a-9cdd-3841def2cd58" width="255"/>
  <img src="https://github.com/user-attachments/assets/f0f72d07-830a-459c-aeb2-ecc1fa7379e0" width="255"/>
  <img src="https://github.com/user-attachments/assets/2fa8ff21-c46a-462e-8c34-29efa40325ff" width="255"/>
  <img src="https://github.com/user-attachments/assets/181a2d55-bb49-4199-99f9-1912878ed0f0" width="auto"/>
</div>
