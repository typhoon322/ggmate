# GagMate — Gagguino Coffee Machine Android App

<p align="center">
  <b>English</b> | <a href="#chinese">中文</a>
</p>

<a name="english"></a>

# GagMate

**GagMate** is a native Android companion app for [Gagguino](https://github.com/OpenSourceGagguino/Gagguino)-modified Gaggia espresso machines. It connects to the ggboard Web API running on your machine's ESP32, providing real-time monitoring and control with an intuitive Material3 interface.

## Features

### Dashboard
- **Real-time gauges** — boiler temperature, steam temperature, brew pressure, flow rate (animated arc gauges)
- **Machine controls** — start/stop brew, flush, steam toggle, pump prime
- **Temperature setpoint** — quick +/- adjustment (1°C steps)
- **Live brew chart** — pressure/flow plotted in real time during extraction
- **Status display** — machine state badge (idle/brew/preinfusion/steam), connection indicator with pulsing dot
- **Brew info** — elapsed time, shot volume, pump output percentage, active profile name, current phase

### Profiles
- **List & manage profiles** — view all saved profiles from your machine
- **Import profiles** — from JSON file (system file picker) or paste raw JSON directly
- **Export profiles** — share as JSON via system share sheet
- **Edit profiles** — modify profile name, author, notes, and individual phase parameters (target, time, add/remove phases)
- **Delete profiles** — remove from machine
- **Sample profile** — one-tap test profile generation

### Shot History
- **History list** — view completed brew shots with profile name, date, duration, volume
- **Animated replay** — watch pressure/flow curves replay in real time
- **Playback controls** — play/pause, draggable seekbar
- **Speed adjustment** — 1x / 2x / 4x / 8x playback speed
- **Export shot data** — export individual shot records as JSON for analysis
- **Delete records** — remove from history

### Settings
- **Connection configuration** — host/IP and port for your ggboard
- **Test connection** — verify connectivity to your machine
- **Auto-save** — connection settings persist across app restarts
- **Default IP** — 192.168.0.186 (configurable)

## Installation

### Prerequisites

- Android 8.0+ (API 26+)
- A Gagguino-modified Gaggia espresso machine with ggboard Web API accessible on your network

### Build from source

```bash
git clone git@github.com:typhoon322/ggmate.git
cd ggmote
export ANDROID_HOME=/path/to/your/android-sdk
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Install via ADB

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First-time setup

1. Open the app and go to the **Settings** tab (gear icon)
2. Enter your ggboard IP address (default: `192.168.0.186`) and port (default: `80`)
3. Tap **Test Connection** to verify connectivity
4. Once connected, the **Dashboard** will start polling live machine data

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material3 |
| Architecture | MVVM |
| Networking | Retrofit 2 + OkHttp 4 |
| JSON | Gson |
| Async | Kotlin Coroutines + Flow |
| State | StateFlow, DataStore Preferences |
| Build | Gradle 8.4 + AGP 8.1.0 |

## Project Structure

```
app/src/main/java/com/gagmate/app/
├── MainActivity.kt              # Entry point
├── data/
│   ├── api/
│   │   ├── GgboardApi.kt        # Retrofit API interface + data models
│   │   └── GgboardApiClient.kt  # Retrofit singleton with dynamic base URL
│   ├── model/
│   │   ├── MachineState.kt      # Real-time state model (temp, pressure, flow...)
│   │   ├── ShotProfile.kt       # Brew profile with phases
│   │   └── ProfilesResponse.kt  # API response wrappers
│   └── repository/
│       ├── MachineRepository.kt # ggboard API operations
│       └── SettingsRepository.kt# DataStore preferences
├── theme/
│   ├── Color.kt                 # Espresso-toned color palette
│   ├── Type.kt                  # Material3 typography
│   └── Theme.kt                 # Light/dark theme
└── ui/
    ├── navigation/AppNavigation.kt  # Bottom navigation (4 tabs)
    ├── components/
    │   ├── GaugeView.kt         # Animated arc gauge
    │   ├── BrewChartView.kt     # Real-time pressure/flow chart
    │   ├── StatusIndicator.kt   # Connection pulse + machine status badge
    │   ├── ProfileCard.kt       # Profile list card
    │   └── PhaseIndicator.kt    # Brew phase timeline item
    ├── dashboard/
    │   ├── DashboardScreen.kt   # Main dashboard UI
    │   └── DashboardViewModel.kt# 2s polling, machine controls
    ├── profiles/
    │   ├── ProfilesScreen.kt    # Profile list + import/edit dialogs
    │   └── ProfilesViewModel.kt # JSON parsing, upload, export
    ├── settings/
    │   ├── SettingsScreen.kt    # Connection configuration
    │   └── SettingsViewModel.kt # DataStore persistence
    └── history/
        ├── ShotHistoryScreen.kt # Shot history with animated replay
        └── ShotHistoryViewModel.kt # Shot data management
```

## API Compatibility

GagMate communicates with the standard ggboard REST API exposed by the Gagguino ESP32 firmware:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/state` | GET | Current machine state |
| `/api/profiles` | GET | List saved profiles |
| `/api/profile` | GET/POST | Active profile / upload profile |
| `/api/profile/{id}` | GET/DELETE | Get / delete specific profile |
| `/api/command` | POST | Send commands (flush, steam, setpoint) |
| `/api/command/brew` | POST | Start brew |
| `/api/command/stop` | POST | Stop brew |
| `/api/command/prime` | POST | Prime pump |
| `/api/shots` | GET | Shot history |
| `/api/shots/{id}` | GET/DELETE | Shot detail / delete |
| `/api/settings` | GET | Machine settings |
| `/api/restart` | GET | Restart machine |

## License

MIT License

---

<a name="chinese"></a>

# GagMate — Gagguino 咖啡机 Android 应用

**GagMate** 是为 [Gagguino](https://github.com/OpenSourceGagguino/Gagguino) 改装版 Gaggia 咖啡机开发的 Android 原生配套应用。它通过连接机器 ESP32 上运行的 ggboard Web API，提供实时监控和控制功能，界面采用 Material3 设计。

## 功能

### 仪表盘
- **实时仪表** — 锅炉温度、蒸汽温度、萃取压力、流速（动画弧形仪表）
- **机器控制** — 开始/停止萃取、冲洗（Flush）、蒸汽开关、水泵注水（Prime）
- **温度设定** — 快速 +/- 调节（1°C 步进）
- **实时曲线图** — 萃取过程中压力/流速实时绘制
- **状态显示** — 机器状态标签（空闲/萃取/预浸泡/蒸汽）、连接指示灯（脉冲动画）
- **萃取信息** — 用时、出液量、水泵输出百分比、当前曲线名称、当前阶段

### 曲线管理
- **列表管理** — 查看机器上所有保存的曲线
- **导入曲线** — 从 JSON 文件导入（系统文件选择器）或直接粘贴 JSON 文本
- **导出曲线** — 通过系统分享导出为 JSON
- **编辑曲线** — 修改名称、作者、说明（Notes），编辑各阶段参数（目标值、时间、新增/删除阶段）
- **删除曲线** — 从机器上移除
- **示例曲线** — 一键生成经典浓缩曲线

### 萃取历史
- **历史列表** — 查看已完成萃取记录（曲线名称、日期、用时、出液量）
- **动画回放** — 观看压力/流速曲线实时回放
- **播放控制** — 播放/暂停、可拖拽进度条
- **速度调节** — 1x / 2x / 4x / 8x 倍速回放
- **导出数据** — 将单次萃取记录导出为 JSON 用于分析
- **删除记录** — 从历史中移除

### 设置
- **连接配置** — ggboard 的 IP 地址和端口
- **测试连接** — 验证与咖啡机的连通性
- **自动保存** — 连接设置在应用重启后自动恢复
- **默认 IP** — 192.168.0.186（可配置）

## 安装

### 环境要求

- Android 8.0+ (API 26+)
- 已改装 Gagguino 的 Gaggia 咖啡机，且 ggboard Web API 可在同一网络下访问

### 从源码构建

```bash
git clone git@github.com:typhoon322/ggmate.git
cd ggmote
export ANDROID_HOME=/path/to/your/android-sdk
./gradlew assembleDebug
```

Debug APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`。

### 通过 ADB 安装

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 首次使用

1. 打开应用，进入底部 **设置** 页（齿轮图标）
2. 输入 ggboard IP 地址（默认 `192.168.0.186`）和端口（默认 `80`）
3. 点击 **测试连接** 验证连通性
4. 连接成功后，**仪表盘** 将开始实时轮询咖啡机数据

## 技术栈

| 层 | 技术 |
|----|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material3 |
| 架构 | MVVM |
| 网络 | Retrofit 2 + OkHttp 4 |
| JSON | Gson |
| 异步 | Kotlin 协程 + Flow |
| 状态管理 | StateFlow, DataStore Preferences |
| 构建 | Gradle 8.4 + AGP 8.1.0 |

## 项目结构

```
app/src/main/java/com/gagmate/app/
├── MainActivity.kt              # 程序入口
├── data/
│   ├── api/
│   │   ├── GgboardApi.kt        # Retrofit API 接口 + 数据模型
│   │   └── GgboardApiClient.kt  # Retrofit 单例（动态 base URL）
│   ├── model/
│   │   ├── MachineState.kt      # 实时状态模型（温度、压力、流速…）
│   │   ├── ShotProfile.kt       # 萃取曲线及阶段
│   │   └── ProfilesResponse.kt  # API 响应包装
│   └── repository/
│       ├── MachineRepository.kt # ggboard API 操作
│       └── SettingsRepository.kt# DataStore 持久化
├── theme/
│   ├── Color.kt                 # 咖啡色系调色板
│   ├── Type.kt                  # Material3 排版
│   └── Theme.kt                 # 亮/暗主题
└── ui/
    ├── navigation/AppNavigation.kt  # 底部导航栏（4 个标签页）
    ├── components/
    │   ├── GaugeView.kt         # 动画弧形仪表
    │   ├── BrewChartView.kt     # 实时压力/流速曲线图
    │   ├── StatusIndicator.kt   # 连接指示灯 + 机器状态标签
    │   ├── ProfileCard.kt       # 曲线列表卡片
    │   └── PhaseIndicator.kt    # 萃取阶段时间线
    ├── dashboard/
    │   ├── DashboardScreen.kt   # 仪表盘界面
    │   └── DashboardViewModel.kt# 2 秒轮询、机器控制
    ├── profiles/
    │   ├── ProfilesScreen.kt    # 曲线列表 + 导入/编辑对话框
    │   └── ProfilesViewModel.kt # JSON 解析、上传、导出
    ├── settings/
    │   ├── SettingsScreen.kt    # 连接配置界面
    │   └── SettingsViewModel.kt # DataStore 持久化
    └── history/
        ├── ShotHistoryScreen.kt # 萃取历史（含动画回放）
        └── ShotHistoryViewModel.kt # 萃取数据管理
```

## API 兼容性

GagMate 与 Gagguino ESP32 固件暴露的标准 ggboard REST API 兼容：

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/state` | GET | 获取当前机器状态 |
| `/api/profiles` | GET | 列出已保存曲线 |
| `/api/profile` | GET/POST | 获取当前曲线 / 上传曲线 |
| `/api/profile/{id}` | GET/DELETE | 获取/删除指定曲线 |
| `/api/command` | POST | 发送命令（flush/steam/setpoint） |
| `/api/command/brew` | POST | 开始萃取 |
| `/api/command/stop` | POST | 停止萃取 |
| `/api/command/prime` | POST | 水泵注水 |
| `/api/shots` | GET | 萃取历史列表 |
| `/api/shots/{id}` | GET/DELETE | 萃取详情 / 删除 |
| `/api/settings` | GET | 机器设置 |
| `/api/restart` | GET | 重启机器 |

## 开源协议

MIT License
