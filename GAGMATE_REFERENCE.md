# GagMate — Gaggiuino Android Client Reference

> 文档版本: 2026-07-29  
> 目标设备: Gaggiuino Gen3 (STM32U585, PCB v3b/v3.1)  
> 通信协议: WebSocket (实时) + REST API (非实时)  
> 数据编码: Custom Protobuf (WS) / JSON (REST)

---

## 目录

1. [架构概览](#1-架构概览)
2. [页面与路由](#2-页面与路由)
3. [WebSocket 协议](#3-websocket-协议)
4. [REST API](#4-rest-api)
5. [数据模型](#5-数据模型)
6. [Repository 层](#6-repository-层)
7. [UI 组件](#7-ui-组件)
8. [系统服务](#8-系统服务)
9. [数据库](#9-数据库)

---

## 1. 架构概览

```
                   ┌─────────────────────┐
                   │     MainActivity     │
                   │  onCreate → init()   │
                   └────────┬────────────┘
                            │
              ┌─────────────┴─────────────┐
              │       AppContainer        │
              │      (Service Locator)    │
              └──────┬──────┬──────┬──────┘
                     │      │      │
          ┌──────────┘      │      └──────────┐
          ▼                 ▼                 ▼
  MachineSession     Repositories         Room DB
  Manager                                    │
  (WS)                                   LocalData
          │                 │             Repo
          │                 ▼
          ▼          MachineRepository
      StateFlows          (REST)
          │
          ├── sensorSnapshot: StateFlow<SensorSnapshot>
          ├── machineState: StateFlow<SystemState>
          ├── machineMode: StateFlow<Int>
          ├── shotSnapshot: StateFlow<ShotSnapshot?>
          ├── brewActive: StateFlow<Boolean>  // 萃取进行中 (JSON sensor_data_update.brewActive OR  protobuf ShotSnapshot.timeInShot>0, 见看门狗)
          ├── currentProfiles: StateFlow<List<ProfileRef>>
          ├── selectedProfileName: StateFlow<String>
          ├── connectionState: StateFlow<ConnectionState>
          ├── messages: SharedFlow<ProtoMessage>
          └── profileDataReceived: SharedFlow<Pair<String, List<BrewPhase>>>
```

**核心文件**: [`AppContainer.kt`](app/src/main/java/com/gagmate/app/data/repository/AppContainer.kt)  
初始化所有单例.

**应用入口**: [`MainActivity.kt`](app/src/main/java/com/gagmate/app/MainActivity.kt)  
在 `onCreate()` 中按以下顺序初始化:
1. `NetworkLogger.init(this)` — 网络日志
2. `ApiDebugLogger.init(this)` — API 调试日志
3. `CrashLogger.init(this)` — 崩溃日志
4. `AppContainer.init(this)` — 初始化所有单例 (DB, Repository, Session)
5. `AppContainer.machineSession.start(appScope)` — 启动 WS 连接
6. `setContent { AppNavigation() }` — 渲染 UI

---

## 2. 页面与路由

导航实现: [`AppNavigation.kt`](app/src/main/java/com/gagmate/app/ui/navigation/AppNavigation.kt)  
使用 Jetpack Compose Navigation + Bottom Navigation Bar.

| 标签 | 路由 | Composable | ViewModel |
|------|------|-----------|-----------|
| 仪表盘 | `dashboard` | `DashboardScreen` | `DashboardViewModel` |
| 曲线 | `profiles` | `ProfilesScreen` | `ProfilesViewModel` |
| 萃取历史 | `history` | `ShotHistoryScreen` | `ShotHistoryViewModel` |
| 设置 | `settings` | `SettingsScreen` | `SettingsViewModel` |

### 2.0 屏幕方向（强制竖屏 / 仅全屏图表横屏）

- **默认全部页面强制竖屏、禁止自动旋转**：由 `AndroidManifest.xml` 中 `MainActivity` 的 `android:screenOrientation="portrait"` 与 `AppNavigation` 中的**集中式** `DisposableEffect(currentRoute)` 双重锁定（取当前路由，非图表页一律 `SCREEN_ORIENTATION_PORTRAIT`）。这样非图表页绝不会跟随设备传感器自动横竖切换。
- **唯一例外**：两个全屏图表目的地——实时曲线 `livecurve`（`LiveCurveScreen`）与萃取历史横屏图表 `history_chart/{shotId}`（`ShotChartFullScreen`）——切换为 `SCREEN_ORIENTATION_LANDSCAPE`，以获得横向宽幅绘图空间。
- 方向控制**已集中到 `AppNavigation`**，各页面不再各自 `requestedOrientation`：`LiveCurveScreen` / `ShotChartFullScreen` / `ProfileDetailScreen` 原有的「进入设方向、退出设 UNSPECIFIED」逻辑已全部移除（旧逻辑在退出时回到 `UNSPECIFIED` 会导致非图表页重新跟随传感器自动旋转，正是此前「其他页面不锁竖屏」的根因）。
- 以 `currentRoute` 为键（而非各页面 `DisposableEffect(Unit)`），保证 chart→chart 跳转保持横屏、chart→任何其它页回到竖屏，且无「退出重置再进入」竞态。

### 2.1 仪表盘 — DashboardScreen

**文件**: [`DashboardScreen.kt`](app/src/main/java/com/gagmate/app/ui/dashboard/DashboardScreen.kt)  
**ViewModel**: [`DashboardViewModel.kt`](app/src/main/java/com/gagmate/app/ui/dashboard/DashboardViewModel.kt)

**功能**:
- 机器状态栏 (状态指示灯 + 连接状态 + 当前 profile 名)
- **机器读数仪表 (已置顶)**: 仅保留当前机器 **温度** 与 **压力** 两个仪表 (原置底的 蒸汽温度/泵流速仪表已移除, 因其值为硬编码 0)
- "当前曲线" 卡片 (`ProfileGlobalsCard`): 绘制当前激活 profile 的设定曲线. 数据源: `machineRepo.fetchProfilePhases(selectedProfileId, selectedProfileName)` — **WS `g_prof`→`d_prof` 取实时值** (`session.requestProfilePhases`), 但 `d_prof` protobuf **不携带 curve 类型** (恒 `FLAT`), 故曲线类型来自 **shot 内嵌 `profile.phases`** (按 name/id 匹配, 落库于 `shot_records.embedded_phases_json`), 其 **real `EASE_*` curve 字符串** 按 phase 序叠加到 WS 实时值上, 使图表呈现 EASE_OUT/EASE_IN_OUT 缓动而非直线. REST `GET /api/profile/{id}` 在本机固件返回 SPA HTML (dead), 不作曲线来源. 会话 `_selectedProfileId` 来自 `d_prof_dict` 选中项.
- 机器控制面板:
  - 冲洗按钮 `session.setOpMode(2)` (`MODE_FLUSH`)
  - TARE 按钮 `session.tareScale()` (`c_tare_pend`)
  - 温度设定 +/- `session.updateActiveProfileTemperature()`
  - 目标重量 +/- `session.updateActiveProfileWeight()`
- 实时萃取曲线图 `BrewChartView` (仅在流式数据时显示)
- 当前萃取信息 (时间/体积/泵速)

**数据流**: 直接从 `AppContainer.machineSession` 的 StateFlow 读取 (不再经过 ViewModel 中转)  
`directSensorT` / `directSensorP` / `directBrewActive` / `directProfileName` / `directMode` / `directConnState` / `directSelectedProfileId` / `restProfilePhases` (WS 实时回填, 见 §3.4)

### 2.2 曲线管理 — ProfilesScreen

**文件**: [`ProfilesScreen.kt`](app/src/main/java/com/gagmate/app/ui/profiles/ProfilesScreen.kt)  
**ViewModel**: [`ProfilesViewModel.kt`](app/src/main/java/com/gagmate/app/ui/profiles/ProfilesViewModel.kt)

**功能**:
- 曲线列表 (从本地 Room DB 读取, 通过 REST 同步)
- 曲线详情弹窗 `ProfileDetailDialog` (含 `BrewChartView` 图表预览 + `PhaseCard` 列)
- 曲线编辑弹窗 `ProfileEditDialog` (编辑名称/作者/备注/各阶段参数)
- 粘贴 JSON 导入 `PasteJsonDialog`
- 创建示例曲线
- 删除/导出曲线
- 打开详情即通过 `fetchProfilePhases(id, name)` 取当前曲线定义 (WS 实时值 + shot 内嵌 profile 的 curve 类型叠加, 详见 §3.4 / §6.3), 用 `CurveChart` 绘制设定曲线 (含正确缓动).

**Phase 数据类型**:
- `BrewPhase` — 本地存储格式 (字段: name, type, target, time, condition, next)
- `PhaseV3` — API 通信格式 (字段: target/stopConditions/type/skip/name)
- `PhaseTarget` — 阶段目标值 (start/end/curve/time)

### 2.3 萃取历史 — ShotHistoryScreen

**文件**: [`ShotHistoryScreen.kt`](app/src/main/java/com/gagmate/app/ui/history/ShotHistoryScreen.kt)  
**ViewModel**: [`ShotHistoryViewModel.kt`](app/src/main/java/com/gagmate/app/ui/history/ShotHistoryViewModel.kt)

**功能**:
- 萃取记录列表 (卡片式)
- 卡片展开后显示 `ShotReplaySection` (回放曲线)
- 导出 JSON / 删除 (三次确认)
- 全屏图表 `ShotChartFullScreen` (触摸交叉线 + 数据提示)

### 2.4 设置 — SettingsScreen

**文件**: [`SettingsScreen.kt`](app/src/main/java/com/gagmate/app/ui/settings/SettingsScreen.kt)  
**ViewModel**: [`SettingsViewModel.kt`](app/src/main/java/com/gagmate/app/ui/settings/SettingsViewModel.kt)

**功能**:
- 连接配置 (主机/端口)
- 连接测试
- 手动同步 (Sync)
- 语言切换
- Crash Log 导出/清除
- 网络日志导出
- WS Data Overlay 开关

---

## 3. WebSocket 协议

**端点**: `ws://{host}/ws`  
**帧类型**: BINARY (protobuf)  
**编码**: 自定义 protobuf-like (field 1=命令名, field 2=payload)

**核心解析入口**:
- [`MachineSessionManager.kt`](app/src/main/java/com/gagmate/app/data/session/MachineSessionManager.kt)
- [`ProtoCodec.kt`](app/src/main/java/com/gagmate/app/data/protocol/ProtoCodec.kt) — 编解码
- [`ProtoCommands.kt`](app/src/main/java/com/gagmate/app/data/protocol/ProtoCommands.kt) — 命令常量 + 构建器
- [`ProtoDecoder.kt`](app/src/main/java/com/gagmate/app/data/protocol/ProtoDecoder.kt) — 各类 payload 解析
- [`ProtoMessage.kt`](app/src/main/java/com/gagmate/app/data/protocol/ProtoMessage.kt) — 消息类型定义 + `parseProtoMessage()`

### 3.1 发往机器 (c\_* / g\_*)

| 命令 | 常量 | 构建器 | Payload |
|------|------|--------|---------|
| 选择曲线 | `c_upd_act_prof_id` | `buildSelectProfile(id)` | field 1 varint = profileId |
| 更新活跃曲线 | `c_upd_act_prof` | `buildUpdateActiveProfileCmd(payload)` | 完整 profile protobuf |
| 设置模式 | `c_opmode` | `buildOpMode(mode)` | field 1 varint = mode (2=flush, 3=descale) |
| 取消模式 | `c_opmode` (无 payload) | `buildNormalOpMode()` | 无 (重置为 standby) |
| TARE 归零 | `c_tare_pend` | `buildTareCommand()` | field 2 varint = 1 |
| 请求曲线详情 | `g_prof` | `buildGetProfile(id)` | field 1 varint = profileId — **`g_prof(id)` 按请求 id 精确返回该条 profile 的当前定义**（官方 webui 即以此获取 profile 数据，已确认），故可逐条拉取**非活跃**曲线。注意：返回的 `d_prof` protobuf **不携带 curve 类型**（解码后恒 `FLAT`），真实 curve 字符串需从 **shot 内嵌 `profile.phases`** 叠加（REST `/api/profile/{id}` 在本机固件返回 SPA HTML，dead）(见 `MachineSessionManager.requestProfilePhases` 与 `MachineRepository.fetchProfilePhases`) |
| 请求设置 | `g_settings` | `buildGetSettings()` | 无 |
| 修改温度 | (通过 `c_upd_act_prof`) | `updateProfileTemperature(payload, newTemp)` | 替换 field 4 float |
| 修改目标重量 | (通过 `c_upd_act_prof`) | `updateProfileTargetWeight(payload, newWeight)` | 替换 field 3 → subfield 2 float |

**发送方法** (`MachineSessionManager`):
```kotlin
selectProfile(id) → sendGetProfile(id) → setOpMode(mode)
tareScale() → setNormalMode() → updateActiveProfileTemperature(temp)
updateActiveProfileWeight(weight) → requestSettings()
```

### 3.2 机发往 App (d\_*)

| 命令 | 解析函数 | 消息类型 | 说明 |
|------|---------|---------|------|
| `d_sys_state` | `parseSysState()` | `SystemStateMsg` | 系统状态 (state/mode/uptime/serial/hw) |
| `d_sensor_snap` | `parseSensorSnapshot()` | `SensorSnapshotMsg` | 传感器快照 (温度/目标温度/压力/水位) |
| `d_shot_snap` | `parseShotSnapshot()` | `ShotSnapshotMsg` | 萃取中数据点 |
| `d_prof_dict` | `parseProfileDict()` | `ProfileDictMsg` | 曲线列表 (ID/name/selected) |
| `d_act_prof` / `d_prof` | `parseProfilePhases()` | `ActiveProfileMsg` | 曲线详情 + phase 数据 |
| `d_shot_hist_index` | `parseShotIndex()` | `ShotHistoryIndexMsg` | 萃取历史索引 |
| `d_settings` | `parseSettings()` | `SettingsMsg` | 设置键值对 |
| `d_esp_mem` | (不处理) | (丢弃) | ESP 内存信息 |

**数据流向**:
```
WS BINARY 帧
  → ProtoCodec.decodeResponse() 提取 (cmd, payload)
  → parseProtoMessage() 创建消息对象
  → handleMessage() 更新 StateFlows
  → _messages.tryEmit() 发往 SharedFlow (WsDataOverlay 使用)
```

**Profile protobuf 结构** (payload 内):
```
field 1: string        → profile 名称
field 2: bytes[]       → phases (每个 phase 一个长度分隔消息)
  phase 内:
    field 1: varint    → 类型 (0=FLOW, 1=PRESSURE)  // 注意: 0=FLOW, 1=PRESSURE
    field 2: string    → 阶段名称
    field 3: bytes     → 嵌套 Target 消息 (关键! 设定值在此)
        sub1: float    → start (阶段起始值)
        sub2: float    → end   (阶段目标值)
        sub3: varint   → curve 枚举 (0=FLAT … 6=FAST_IN_OUT)  // 变化模式
        sub4: int32    → time (毫秒)
    field 4: float     → restriction (过渡/限制值)
    field 5: float     → waterTemp (水温)
    field 6: varint    → skip (是否跳过)
  // 解析要点: 旧的 decoder 误把 field 2 (名称) 当成嵌套 Target, 导致所有 phase 内容全 0.
  // 修复后 decodePhaseInfo 从 field 3 读取嵌套 Target (start/end/curve/time).
field 3: bytes         → 设置 (maxTime, targetWeight)

**Profile JSON schema** (`GET /api/profile/{id}` 与 shot 内嵌 `profile` 同构, 已用真实机器日志核对):
```json
{
  "id": 7, "name": "SOE",
  "phases": [
    { "target": {"end": 1.5, "curve": "EASE_OUT", "time": 15000},
      "type": "FLOW", "skip": false, "restriction": 3,
      "stopConditions": {"time": 18000, "pressureAbove": 3, "waterPumpedInPhase": 60} },
    { "target": {"start": 1.5, "end": 6.5, "curve": "EASE_OUT", "time": 10000},
      "type": "PRESSURE", "skip": false },
    { "target": {"start": 6.5, "end": 5, "curve": "EASE_IN_OUT", "time": 35000},
      "type": "PRESSURE", "skip": false, "restriction": 2 }
  ],
  "globalStopConditions": {"time": 65000, "weight": 42},
  "waterTemperature": 95, "recipe": {}
}
```
- `type` 大写 `"FLOW"` / `"PRESSURE"`; `curve` 是**字符串** (`FLAT`/`EASE_IN`/`EASE_OUT`/`EASE_IN_OUT`/`FAST_IN`/`FAST_OUT`/`FAST_IN_OUT`).
- 首阶段 `target` 常**缺 `start`** (从 0 起); `end` 为目标值. `PhaseTarget.start` 为可空 → 缺省按 0.
- 直接映射到 `PhaseV3` + `PhaseTarget` (字段名一致), `toBrewPhase()` 解析正确.
- REST `GET /api/profile/{id}` 在本机固件**已确认返回 SPA index.html（dead）**，不可用作曲线来源。WS `g_prof`→`d_prof`（官方 webui 同方式，已确认按 id 精确返回）提供**实时值**，但 `d_prof` protobuf **不携带 curve 类型**（解码后恒 `FLAT`）。故真实 curve 类型 (`EASE_OUT`/`EASE_IN_OUT`/…) **仅来自 shot 内嵌 `profile.phases`**（日志已核对，含 `EASE_IN`×26、`EASE_OUT`×52）。`fetchProfilePhases` 取数策略：① 曲线来源 = 本地「profile 名（或 id）匹配的最近 shot」的 `embedded_phases`（落库于 `shot_records.embedded_phases_json`，离线可用，见 §6.7）；② WS `requestProfilePhases(id,name)` 取实时值；③ 两者 phase 数一致时**按序叠加 curve 类型**到 WS 值上 → 图表呈现缓动；否则回退 curve 来源。phase 持久化（给 `ProfileEntity.phasesJson`）由 `SyncManager.syncShots` 完成（见 §6.7），`fetchProfilePhases` 不负责落库。
field 4: float         → 温度设定
field 5: string        → (空)
field 6: varint        → 数字
```

### 3.3 JSON Fallback

当 `ProtoCodec.decodeResponse()` 返回 null 时, 二进制帧会尝试以 UTF-8 解码并检查是否包含 `"action"` 关键词. 
如果是, 则走 `handleJsonMessage()` 路径.

JSON 格式:
```json
{"action": "sensor_data_update", "data": {"temperature": 30.79, ...}}
{"action": "shot_data_update", "data": {"timeInShot": 30, ...}}
```

---

### 3.4 Profile 实时取数机制 (g_prof 请求↔响应关联)

WS `g_prof`→`d_prof` 提供「当前」曲线定义的**实时值**，但其 protobuf **不携带 curve 类型**（解码后恒 `FLAT`，见 `ProtoDecoder.decodePhaseInfo`）。真实 curve 类型（`EASE_OUT`/`EASE_IN_OUT`/…）**仅存在于 shot 内嵌 `profile.phases` 的字符串字段**（REST `GET /api/profile/{id}` 在本机固件返回 SPA HTML，已确认 dead），由 `fetchProfilePhases` 叠加（见 §6.3 / §6.7）。WS 本身是异步的：发 `g_prof(id)` 后，机器在将来的某一帧才回 `d_prof`/`d_act_prof`。为让调用方能**同步等待**结果，`MachineSessionManager` 用「按 profile name 关联的 `CompletableDeferred`」做请求/响应配对：

- `pendingProfileDeferreds: MutableMap<String, CompletableDeferred<List<BrewPhase>>>` — 以 **profile name** 为 key (因为 `d_prof` 响应 protobuf 携带的是该 profile 的 name, 而非请求时的 id).
- `requestProfilePhases(id: Int, name: String, timeoutMs = 3500): List<BrewPhase>`:
  1. 若 `!isConnected()` 直接返回空;
  2. 注册 `pendingProfileDeferreds[name] = CompletableDeferred()`;
  3. `sendGetProfile(id)` 发 `g_prof` (二进制帧, field1 = profileId);
  4. `withTimeout(timeoutMs)` 等待 deferred; 超时返回空;
  5. `finally` 清理该 name 的 deferred (防泄漏).
- `ActiveProfileMsg` 处理 (`handleMessage`): phases 非空时 → ① 更新 `_selectedProfilePhases` StateFlow; ② `profileDataReceived.tryEmit(name to phases)`; ③ `pendingProfileDeferreds.remove(name)?.complete(phases)` 完成对应 deferred.

> 关联可靠性建立在「`g_prof(id)` 按 id 精确返回该 profile, 且响应携带其 name」之上 (官方 webui 行为, 已确认). 若固件对别的 id 返回活跃 profile, name 关联会错配; 但本固件已确认按 id 精确返回.

**Proactive 预取**: `d_prof_dict` 选中项到达时 (`ProfileDictMsg`), 立即 `sendGetProfile(selected.id)` 预拉取选中 profile 的 phases + 全局设定值, 即使机器从不主动推 `d_act_prof` 也能拿到.

---

## 4. REST API

**基础 URL**: `http://{host}:{port}`  
**实现**: Retrofit [`GgboardApi`.kt](app/src/main/java/com/gagmate/app/data/api/GgboardApi.kt)  
**客户端**: [`GgboardApiClient.kt`](app/src/main/java/com/gagmate/app/data/api/GgboardApiClient.kt)

| 端点 | 方法 | 返回 | 用途 |
|------|------|------|------|
| `/api/system/status` | GET | `List<MachineState>` | 系统状态 (轮询替代已移除, 现在走 WS) |
| `/api/profiles/all` | GET | `List<ProfileRef>` | 曲线列表 |
| `/api/profile/{id}` | GET | `EmbeddedProfile` | **本机固件已确认返回 SPA index.html（dead）**，不可用作曲线来源；真实 curve 来源改为 shot 内嵌 `profile.phases`（见 §5.3 / §6.3 / §6.7） |
| `/api/profile-select/{id}` | POST | Map | 激活曲线 |
| `/api/profile-select/{id}` | DELETE | Map | 删除曲线 |
| `/api/profile` | POST | Map | 上传曲线 |
| `/api/shots/latest` | GET | `List<LatestShotResponse>` | 最新萃取 ID |
| `/api/shots/{id}` | GET | `ShotRecordApi` | 萃取完整数据 |
| `/api/settings` | GET | Map | 获取设置 |
| `/api/settings/versions` | GET | Map | 版本信息 |

**REST 客户端**: [`MachineRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/MachineRepository.kt)  
封装所有 REST 调用, 提供 `Flow` / `Result` 类型返回值.

**现有 REST 调用入口**:
- `SyncManager` — 同步 profiles 和 shots
- `ProfilesViewModel` — 获取/上传/删除曲线
- `ShotHistoryViewModel` — 获取萃取记录

---

## 5. 数据模型

### 5.1 传感器数据 (WS)

| 模型 | 定义位置 | 字段 |
|------|---------|------|
| `SensorSnapshot` | `ProtoDecoder.kt` | temperature/Float, targetTemperature/Float, pressure/Float, pumpFlow/Float, weight/Float, waterLevel/Int |
| `SystemState` | `ProtoDecoder.kt` | state/Int, mode/Int, uptime/Int, serial/String, hwType/String |
| `ShotSnapshot` | `ProtoDecoder.kt` | timeInShot/Int, pressure/Float, flow/Float, temperature/Float, weight/Float, waterPumped/Float |
| `ShotIndexEntry` | `ProtoDecoder.kt` | id/Int, profileName/String, timestamp/Long |

### 5.2 机器状态 (REST)

| 模型 | 定义位置 | 关键字段 |
|------|---------|---------|
| `MachineState` | `MachineState.kt` | upTime, temperature (parsed from temperatureStr), pressure, waterLevel, weight, profileName, brewSwitchState, steamSwitchState |

### 5.3 Profile

| 模型 | 定义位置 | 用途 |
|------|---------|------|
| `ProfileRef` | `ShotRecord.kt` | REST 曲线列表条目 (id/name/isSelected) |
| `PhaseV3` | `PhaseModels.kt` | REST/JSON API 曲线阶段格式 (target/stopConditions/type/skip/name/restriction/waterTemperature) |
| `PhaseTarget` | `PhaseModels.kt` | 阶段目标值 (start?/end/curve:String/time:Int ms) |
| `PhaseStopConditions` | `PhaseModels.kt` | 阶段停止条件 |
| `GlobalStopConditions` | `PhaseModels.kt` | 全局停止条件 |
| `BrewPhase` | `ShotProfile.kt` | **本地统一阶段格式** (name/type=pressure\|flow/target/start/variation/time/condition/next); `phasesJson` 即 `List<BrewPhase>` 的 JSON |
| `ShotProfile` | `ShotProfile.kt` | 完整曲线 (用于上传/编辑) |
| `ProfileEntity` | `ProfileEntity.kt` | Room DB 实体 (关键字段 `phasesJson: String`) |
| `EmbeddedProfile` | `ShotRecord.kt` | shot 记录内嵌的曲线数据 (phases: List<PhaseV3>)；**是真实 EASE_* curve 类型的唯一可靠来源**，已镜像进 `shot_records.embedded_phases_json` 并用于给 `ProfileEntity.phasesJson` 落库（见 §6.7） |
| `ActiveProfileMsg` | `ProtoMessage.kt` | WS `d_prof`/`d_act_prof` 解析结果: `name: String` + `phases: List<BrewPhase>` + `rawPayload: ByteArray`; 是 request/response 关联的 key |

**格式转换**:
- `PhaseV3.toBrewPhase()` 把 REST/JSON 阶段映射为本地 `BrewPhase`: `type` 归一为小写 `pressure`/`flow`; `target.end`→`target`, `target.start`(可空)→`start`(缺省 0); `target.curve`(字符串)→`variation`(大写, `LINEAR` 兜底); `target.time`(ms)→`time`(秒, 下限 0.1s).
- WS protobuf 的 phase 由 `ProtoDecoder.decodePhaseInfo()` 直接产出 `BrewPhase` (`field 3` 嵌套 Target 的 `start/end/curve/time`), **不经过 `PhaseV3` 中转**.
- `ActiveProfileMsg.phases` 已是 `List<BrewPhase>`, 可直接用于 `CurveChart.generateProfileChartPoints()` 与落库.

### 5.4 Shot (萃取记录)

| 模型 | 定义位置 | 用途 |
|------|---------|------|
| `ShotRecordApi` | `ShotRecord.kt` | REST 返回格式 (带 columnar datapoints) |
| `ShotDataPointsApi` | `ShotRecord.kt` | Columnar 数据点格式 |
| `ShotDataPoint` | `ShotRecord.kt` | 单个数据点 (本地格式) |
| `ShotRecord` | `ShotRecord.kt` | 萃取记录 (本地格式) |
| `ShotEntity` | `ShotEntity.kt` | Room DB 实体 |

### 5.5 图表数据

| 模型 | 定义位置 | 字段 |
|------|---------|------|
| `ChartPoint` | `BrewChartView.kt` | time, pressure, flowRate, weight, weightChangeRate, temperature, targetPressure/FlowRate/Temperature, shotWeight |

---

## 6. Repository 层

### 6.1 AppContainer (Service Locator)

**文件**: [`AppContainer.kt`](app/src/main/java/com/gagmate/app/data/repository/AppContainer.kt)

单例全局对象, 持有:
- `machineSession: MachineSessionManager` — WS 会话
- `localRepo: LocalDataRepository` — 本地 DB
- `machineRepo: MachineRepository` — REST 通信
- `sensorRepo: SensorRepository` — 传感器数据 (订阅 WS)
- `shotRepo: ShotRepository` — 实时萃取数据
- `profileRepo: ProfileRepository` — 曲线管理
- `syncManager: SyncManager` — 单向同步（机器 → 本地镜像）

### 6.2 LocalDataRepository

**文件**: [`LocalDataRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/LocalDataRepository.kt)

封装 Room DB 操作:
- `profilesFlow` / `getAllProfiles()` / `saveProfile()` / `deleteProfile()`
- `shotsFlow` / `getAllShots()` / `insertShot()` / `deleteShot()`
- `settingsFlow` / `saveSetting()`

### 6.3 MachineRepository

**文件**: [`MachineRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/MachineRepository.kt)

REST 调用封装:
- `fetchMachineState()`, `fetchProfiles()`.
- `getProfileDetail(id)` — `GET /api/profile/{id}` → `EmbeddedProfile`. **本机固件已确认返回 SPA HTML（dead）**，调用会失败；保留为通用端点但**不作为曲线来源**（真实 curve 来源见下）.
- `fetchLatestShotId()`, `fetchShotDetail(id)` — shot 记录内嵌 `profile.phases` (PhaseV3 同 schema). 该内嵌 profile 经 `ShotRecordApi.toShotRecord()` 镜像进 `ShotEntity.embeddedPhasesJson`, 是 **`fetchProfilePhases` 的 curve 来源** (按 name 或 `profile.id` 匹配, 离线可用).
- `fetchProfilePhases(id, name): List<BrewPhase>` — **联网实时展示取数入口** (不负责落库):
  1. 取 **curve 来源**: 本地「profile 名（或 id）匹配的最近 shot」的 `embedded_phases` (`resolveShotPhases`, 经 `LocalDataRepository.getLatestShotByProfileName/Id`) — 携带真实 `EASE_*` curve 字符串, **离线可用**;
  2. 取 **WS `g_prof`→`d_prof` 实时值** (`session.requestProfilePhases(id, name)`, 按 name 关联, 带 3.5s 超时) — 但 curve 类型恒为 `FLAT`;
  3. 若 WS 与 curve 来源 phase 数一致 → **按序叠加 curve 类型**到 WS 值上 (图表呈现缓动); 否则回退 curve 来源; 再否则回退 WS.
  落库由 `SyncManager.syncShots` 完成 (见 §6.7).
- `uploadProfile(profile)`, `deleteMachineProfile(id)`
- `selectMachineProfile(id)`

### 6.4 ProfileRepository

**文件**: [`ProfileRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/ProfileRepository.kt)

- `profilesFlow` — 观察本地所有曲线
- 订阅 `session.profileDataReceived` 仅作**日志诊断**：WS `d_prof` 的 curve 类型恒为 `FLAT`/`乱码`，且 REST 详情端点 dead，故 phase 的**持久化已改由 `SyncManager.syncShots` 从 shot 内嵌 profile 完成**（见 §6.7）。本收集器**不再写库**，以免把不可靠的 WS 相位覆盖掉 shot 来源的真实 `EASE_*` 曲线。
- `exportAsJson()` — 导出为 JSON
- **编辑推送已停用**：`pushAndSaveIfConfirmed()` / `saveEditedProfile()` 均为 no-op（返回 `SyncManager.PUSH_DISABLED_MESSAGE`，**本地和机器都不写**）——主控板为唯一权威，本地修改推送暂不支持。详情页编辑入口由 `ProfileDetailScreen` 顶部 `EDIT_ENABLED = false` 隐藏；编辑 UI 代码保留，未来恢复推送时置回 `true` 即可。

### 6.5 SensorRepository

**文件**: [`SensorRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/SensorRepository.kt)

订阅 `session.sensorSnapshot` → 提供 `sensorFlow: SharedFlow<SensorSnapshot>`

### 6.6 ShotRepository

**文件**: [`ShotRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/ShotRepository.kt)

- `chartData: StateFlow<List<ChartPoint>>` — 实时图表数据 (环形缓存, 最多 2000 点)
- `appendPoint(time, pressure, flow, temp, weight)` — 统一的追加入口 (shot 快照 / 传感器回填共用).
- **时间轴**: 优先用固件 `timeInShot` (需单调且 >0); 否则回退到**墙钟时间差** (萃取开始时刻起算), 保证即使固件不报 `timeInShot` 曲线也始终能画.
- **清空时机**: 仅在 `brewActive` **上升沿** (false→true) 清空缓冲区. 旧逻辑在 `timeInShot < 100` 时就清空, 一旦固件 `timeInShot` 被误读为 0 会在每帧把缓冲区清空 → 曲线空白; 已修正.
- **传感器回填**: 萃取期间订阅 `session.sensorSnapshot` (`sensor_data_update`, 含压力/泵流速/温度/液重且置位 `brewActive`) 追加数据点; 若 400ms 内已有 shot 快照到达则以 shot 快照为准 (去重, 避免双画).
- `clearChart()` — 清空
- `SensorSnapshot` 已扩展 `pumpFlow` / `weight` 字段 (由 JSON `sensor_data_update` 填充).

### 6.7 SyncManager

**文件**: [`SyncManager.kt`](app/src/main/java/com/gagmate/app/data/repository/SyncManager.kt)

> **同步原则：Gaggiuino 主控板是唯一权威（single source of truth），本地 DB 是只读单向镜像。本地修改推送暂不支持**（`uploadPendingProfiles()` 为 no-op，`ProfileRepository` 的编辑推送方法已停用，详情页编辑入口由 `EDIT_ENABLED=false` 隐藏）。

- `fullSync()` — 全量同步（单向：机器 → 本地）
- `syncProfiles()` — 同步曲线列表，**机器数据无条件覆盖本地**：
  - 本地行一律镜像机器版本并置为 `SYNCED`；旧的 `MODIFIED`/`CONFLICT` 状态被**直接丢弃**（其 `phasesJson` 清为 `[]`，同一轮 `syncShots()` 会用 shot 内嵌相位重新填充为机器真实配方）；不再有冲突/上传分支。
  - **镜像删除**：本地存在但机器列表中已不存在的机器镜像行（`machineProfileId` 非空且非 `LOCAL_ONLY`）会被删除；仅当机器列表**非空**时执行，防止异常空响应清空整个镜像。
  - **不再**在同步时发 WS `g_prof`，也**不再**调用 dead 的 REST `GET /api/profile/{id}` 去填充相位（见 §6.3：该端点在本机固件返回 SPA HTML）。profile 的相位/曲线来源完全交给 `syncShots()`。
- `uploadPendingProfiles()` — **已停用（no-op）**，返回 `PUSH_DISABLED_MESSAGE` 错误说明；保留方法仅为让"上传待同步"按钮优雅降级。
- `syncShots()` — 同步萃取记录 (REST → 本地 DB)，并**顺带完成 profile 相位的落库**：
  - 每条拉取的 shot 把其内嵌 `profile`（`EmbeddedProfile.phases`，含真实 `EASE_*` curve 字符串）写入 `ShotEntity.embeddedPhasesJson`（`ShotRecordApi.toShotRecord` 捕获 `profile.id` + `profile.phases`，时间戳统一归一化为 epoch 毫秒）；
  - **v5→v6 兼容回填**：迁移前同步的旧 shot 行 `embedded_phases_json='[]'` 且默认不会被重复拉取——若仍有 `SYNCED` profile 缺可用相位（`anyProfileNeedsSeeding()`），会**重新拉取这些空相位旧行**并 REPLACE upsert 回填；一旦全部 profile 已 seed，此回填自动停止（自限流）；
  - 「最近一条」判定在**落库之后**扫描全部本地 shot 行，用统一的归一化毫秒时间戳比较，避免 API 原始秒 vs 本地毫秒错序；
  - **seed 规则（机器权威版）**：对每个 `SYNCED` profile，取「按 name 匹配的最新一条 shot」的内嵌相位，**只要与库内 `phasesJson` 不同即覆盖**（本地编辑已停用，无需保护；相同则跳过避免写抖动）。这保证本地镜像持续收敛到主控板配方——例如用户在机器自带 webui 改配方后拉一杯新 shot，下次同步即更新。`LOCAL_ONLY`（导入/示例，非机器镜像）不触碰。
  - 这是 profile 详情 / 仪表盘激活曲线**离线可绘制且带缓动**的权威来源。
- 注意：`MachineRepository.fetchProfilePhases()` 用于**联网时的实时展示**，把 WS `g_prof` 的 `FLAT` 实时值叠加到 shot 来源的 curve 类型上（按 phase 序）。**离线时详情/图表直接读本地 `phasesJson`**——该字段现在由 `syncShots` 写入真实 `EASE_*` curve（见上文），因此离线也能呈现缓动曲线，不依赖联网叠加。

### 6.8 SettingsRepository

**文件**: [`SettingsRepository.kt`](app/src/main/java/com/gagmate/app/data/repository/SettingsRepository.kt)

DataStore 偏好存储:
- `host` / `port` — 机器连接地址

---

## 7. UI 组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `GaugeView` | `GaugeView.kt` | 圆弧仪表盘 (Canvas 绘制, 动画过渡, 支持颜色配置) |
| `CurveChart` | `CurveChart.kt` | **共用曲线渲染组件** — 单一 Canvas 渲染器 (网格/虚线设定值/实线实际值/末端标记/十字线). `BrewChartView` 与 `ShotChartFullScreen` 均委托给它; 另提供 `ProfileCurveChart` 便捷封装与 `generateProfileChartPoints` (按阶段 variation 缓动生成设定曲线) |
| `BrewChartView` | `BrewChartView.kt` | 5 指标曲线图卡片 (委托 `CurveChart` 绘制, 左轴 0-16, 右轴 0-100, 虚线=设定值/实线=实际值/分色显示) |
| `ShotChartFullScreen` | `ShotChartFullScreen.kt` | 全屏横屏图表 (委托 `CurveChart` 绘制; 触摸交互 — 十字线 + 数据弹出框; 支持缩放/平移) |
| `PhaseIndicator` | `PhaseIndicator.kt` | 阶段指示卡片 (类型/目标值/时间) |
| `PhaseCard` | `ProfilesScreen.kt` | 阶段编辑卡片 (显示类型/目标/停止条件) |
| `ProfileCard` | `ProfileCard.kt` | 曲线列表卡片 (名称/作者/同步状态) |
| `MachineStatusBadge` | `StatusIndicator.kt` | 机器状态徽章 |
| `StatusIndicator` | `StatusIndicator.kt` | 连接状态指示灯 |
| `DebugOverlay` | `DebugOverlay.kt` | 调试日志浮窗 (DebugLogState) |
| `WsDataOverlay` | `WsDataOverlay.kt` | WS 数据半透明浮窗 (实时传感器/消息日志) |

### 7.1 BrewChartView 配色

| 数据指标 | 颜色 | UI 颜色常量 |
|---------|------|-----------|
| 液重 (g) | 褐色 | `weightColor = Color(0xFF8D6E63)` |
| 压力 (bar) | 蓝色 | `pressureColor = Color(0xFF2196F3)` |
| 泵流速 (ml/s) | 黄色 | `flowColor = Color(0xFFFFC107)` |
| 液重变化速度 (g/s) | 绿色 | `weightRateColor = Color(0xFF4CAF50)` |
| 温度 (°C × 10) | 红色 | `temperatureColor = Color(0xFFF44336)` |

Y 轴: 左轴 0-16 (压力/泵流速/液重增速), 右轴 0-100 (温度/液重)

### 7.2 CurveChart 共用曲线组件

为消除原先 `BrewChartView` 与 `ShotChartFullScreen` 中重复的 Canvas 绘制代码, 抽出单一渲染组件 `CurveChart` (`ui/components/CurveChart.kt`):

- **`data class CurveSeries(color, axis, dashed=false, valueAt: (Int)->Float)`** — 一条曲线; `axis` 选左/右轴, `dashed` 画半透明设定线, `valueAt(i)` 返回第 i 点数值 (末位参数以便用尾随 lambda).
- **`enum class ChartAxis { LEFT, RIGHT }`** — 左轴 0–`leftMax` (默认 16), 右轴 0–`rightMax` (默认 100).
- **`@Composable CurveChart(...)`** — 共享 Canvas 渲染器: 网格 + 虚线 target + 实线 actual + 末端圆点标记 + 可选 `crosshairTime` 竖线. 参数 `t0/t1` 支持窗口化 (全屏图表缩放/平移).
- **`fun generateProfileChartPoints(phases, resolution=0.25f): List<ChartPoint>`** — 设定曲线生成器. 每个阶段从 `start` 缓动到 `target`, 缓动方式取 `BrewPhase.variation` (EASE_OUT / EASE_IN_OUT / FAST_IN / …), 前一个阶段的末端值作为下一阶段起点以保证连续. **修复了原先只有 FLAT 阶梯** 的问题.
- **`fun curveVariationEasedProgress(variation, p): Float`** — 各 variation 枚举对应的缓动插值实现.
- **`fun ProfileCurveChart(phases, height=200.dp)`** — 便捷封装, 用于仪表盘"当前曲线"卡片与曲线编辑器预览.
- 共享配色常量 `ChartColorPressure/Flow/Temperature/Weight/WeightRate` 及其 `ChartColorTarget*` (alpha 0.4f) 版本.

> 调用处: `ProfileDetailScreen` 与 `DashboardScreen` 的 `generateProfileChartPoints` 已统一指向 `CurveChart.kt` 中的共享实现 (删除了原先 `ProfileDetailScreen` 里的 FLAT-only 本地副本).

---

## 8. 系统服务

### 8.1 CrashLogger

**文件**: [`CrashLogger.kt`](app/src/main/java/com/gagmate/app/data/system/CrashLogger.kt)

- 注册 `Thread.setDefaultUncaughtExceptionHandler()`
- 崩溃日志写入 `context.cacheDir/`
- 通过 FileProvider 分享

### 8.2 DebugLogState

**文件**: [`DebugLogState.kt`](app/src/main/java/com/gagmate/app/data/system/DebugLogState.kt)

- 内存环形缓存 (500 条) 保存调试日志
- 需要在 Settings 中启用 `enable()`

### 8.3 NetworkLogger / ApiDebugLogger

**文件**: [`NetworkLogger.kt`](app/src/main/java/com/gagmate/app/data/api/NetworkLogger.kt) / [`ApiDebugLogger.kt`](app/src/main/java/com/gagmate/app/data/api/ApiDebugLogger.kt)

- 拦截 OkHttp 请求记录到文件
- 在 Settings 中可导出/清除

### 8.4 SoundManager

**文件**: [`SoundManager.kt`](app/src/main/java/com/gagmate/app/data/system/SoundManager.kt)

- 播放连接成功提示音

### 8.5 ConnectionState (枚举)

**文件**: [`ConnectionState.kt`](app/src/main/java/com/gagmate/app/data/session/ConnectionState.kt)

```kotlin
enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
```

---

## 9. 数据库

**实现文件**: [`AppDatabase.kt`](app/src/main/java/com/gagmate/app/data/local/AppDatabase.kt)

Room 数据库, **版本 6**。`fallbackToDestructiveMigration` 仅作兜底，正式迁移通过 `Migration` 显式定义。

### 迁移 (Migrations)

| 迁移 | 说明 |
|------|------|
| `MIGRATION_3_4` | 归一化 `shots.timestamp`：历史行存在"秒 / 毫秒 / 毫秒又被 ×1000"三种混乱单位，迁移时统一 `UPDATE` 为规范的 **epoch 毫秒**。 |
| `MIGRATION_4_5` | 清除旧版 WS 解码器写入的**损坏 phases_json**：将其 `UPDATE profiles SET phases_json = '[]' WHERE sync_status = 'SYNCED'`，使下次同步能重新取回真实 `EASE_*` 曲线。用户编辑过的 profile（非 SYNCED）不受影响。 |
| `MIGRATION_5_6` | 为 `shot_records` 增加 `profile_id TEXT`（**可空**，与 `ShotEntity.profileId: String?` 严格一致）与 `embedded_phases_json TEXT NOT NULL DEFAULT '[]'`，把 shot 内嵌 profile 的**真实 curve 来源**持久化，供 `fetchProfilePhases` / `syncShots` 离线使用（REST 详情端点在本机固件为 dead，WS `d_prof` 不携带 curve 类型）。⚠️ 教训：迁移 SQL 的可空性/默认值必须与 Entity 声明完全一致，否则 Room 迁移后 schema 校验抛 `Migration didn't properly handle: shot_records`，迁移事务回滚（DB 停留 v5、数据无损），此后**每个触库页面反复闪退**（首版误写 `profile_id NOT NULL DEFAULT ''` 导致历史页与日志导出页闪退）。 |

### 时间戳单位约定 (canonical = epoch ms)

所有 shot 时间戳在**写入 DB 前**必须经 [`TimeUtils.normalizeShotTimestamp(ts)`](app/src/main/java/com/gagmate/app/util/TimeUtils.kt) 归一化为 epoch 毫秒：

- `ts ≤ 1e12` → 视为秒，`×1000`；
- `ts > 1e15` → 视为被多乘了一次 1000 的毫秒，`÷1000`；
- 其余 → 已是毫秒，原样保留。

`ShotRecord.toShotRecord()` 使用该函数（替代原先的裸 `×1000`），杜绝"double ×1000"污染；UI（`ShotHistoryScreen`）直接读取 `Date(shot.timestamp)`，不再本地二次归一化。

> 注意：若 Gaggiuino 板 RTC 未联网同步（NTP），其源头墙钟时间本身就是错的，App 端无法纠正——只有板子 NTP 同步后拉取的新 shot 才会显示正确时间。

### 表

| 表名 | 实体 | DAO |
|------|------|-----|
| `profiles` | `ProfileEntity` | `ProfileDao` |
| `shots` | `ShotEntity` | `ShotDao` |
| `machine_settings` | `MachineSettingsEntity` | `MachineSettingsDao` |

### ProfileEntity

**文件**: [`ProfileEntity.kt`](app/src/main/java/com/gagmate/app/data/local/entity/ProfileEntity.kt)

```kotlin
@Entity(tableName = "profiles")
data class ProfileEntity(
    val id: String,                    // PRIMARY KEY — 本地 UUID 或 machine ID
    val name: String,                  // 曲线名称
    val author: String,                // 作者
    val notes: String,                 // 备注
    val machineProfileId: String?,     // 机器上的数字 ID (null = 纯本地)
    val phasesJson: String,            // JSON 序列化的 List<BrewPhase>
    val syncStatus: SyncStatus,        // 同步状态
    val localUpdatedAt: Long,          // 本地更新时间
    val machineUpdatedAt: Long?,       // 机器更新时间
    val createdAt: Long                // 创建时间
)
```

### ShotEntity

**文件**: [`ShotEntity.kt`](app/src/main/java/com/gagmate/app/data/local/entity/ShotEntity.kt)

```kotlin
@Entity(tableName = "shots")
data class ShotEntity(
    val id: String,                    // PRIMARY KEY
    val timestamp: Long,               // 时间戳
    val profileId: String?,            // 内嵌 profile 的机器 ID (v6 新增)
    val profileName: String,           // 曲线名称
    val duration: Float,               // 时长 (秒)
    val volume: Float,                 // 液重 (g/ml)
    val embeddedPhasesJson: String,    // v6 新增: 内嵌 profile 的真实 EASE_* 相位 (List<BrewPhase> JSON), 是曲线唯一可靠来源
    val dataJson: String,              // 数据点 JSON
    val syncStatus: SyncStatus         // 同步状态
)
```

---

## 附录 A — 数据流场景

### A.1 App 启动 → 显示传感器数据

```
MainActivity.onCreate()
  → AppContainer.init(this)
    → MachineSessionManager() 创建
    → syncManager.fullSync() 启动
  → machineSession.start(appScope)
    → WebSocket 连接到 ws://{host}/ws
    → 机器发送 d_prof_dict (曲线列表)
    → 机器发送 d_sys_state (系统状态)
    → 机器发送 d_sensor_snap (传感器数据, 每秒 2-3 次)
  → setContent { AppNavigation() }
    → DashboardScreen 渲染
    → LaunchedEffect 启动收集 directSensorT 等
    → DBG: t=30.8C P=-0.08bar W=100% MODE=1 PROF=Light Template
```

### A.2 打开曲线详情 → 取 Phase 绘制

```
打开 ProfileDetailScreen (route profile_detail/{id})
  → LaunchedEffect(machineProfileId, name):
      fetchedPhases = machineRepo.fetchProfilePhases(id, name)
        → ① resolveShotPhases(id, name)                   // 离线可用: 本地「profile 名(或 id) 匹配的最近 shot」的 embedded_phases
             → LocalDataRepository.getLatestShotByProfileName/Id(name)
             → ShotEntity.embeddedPhasesJson → List<BrewPhase> (含真实 EASE_* curve)
        → ② session.requestProfilePhases(id, name)        // WS: 发 g_prof(id)，await d_prof 取实时值 (curve 恒 FLAT)
             → sendGetProfile(id) 发送二进制帧 (field1 = profileId)
             → 机器回 d_prof / d_act_prof (protobuf)
             → ProtoDecoder.parseProfilePhases() → ActiveProfileMsg(name, phases: List<BrewPhase>)
             → 完成 pendingProfileDeferreds[name] 的 CompletableDeferred → requestProfilePhases 返回 phases
        → ③ 若 WS 与 shot 来源 phase 数一致 → 按序叠加 shot 的 curve 类型到 WS 实时值上; 否则回退 shot 来源; 再否则回退 WS
  → chartPhases = fetchedPhases ?: phasesFromJson(本地库 phasesJson)
  → CurveChart.generateProfileChartPoints(chartPhases) 绘制设定曲线
```

> 同步阶段 (`syncShots`) 拉取 shot 时把内嵌 `profile.phases` 写入 `shot_records.embedded_phases_json`, 并把每个 profile 名**最近一条** shot 的真实相位写回 `ProfileEntity.phasesJson` (仅 SYNCED); 此后**离线**打开详情直接读本地库, 无需 WS/REST.

### A.3 冲洗 → 反馈

```
点击 "Flush" 按钮
  → viewModel.flush() → session.setOpMode(2)
  → WS 发送 c_opmode (field 1 varint = 2)
  → 机器响应 d_sys_state (mode = 2)
  → handleMessage: _machineMode.value = 2
  → DashboardScreen: flushActive = true
  → 按钮变填色 "Flushing"
```

---

## 附录 B — 已知问题

1. **Dashboard 数据流** (已修复): `d_sensor_snap` 命令名含尾部 `p`, 常量写为 `d_sensor_sna` 导致不匹配
2. **Float 字节序** (已修复): `ByteBuffer.getFloat()` 默认 big-endian, protobuf 要求 little-endian
3. **Phase 名缺失** (已修复): `decodePhaseInfo` 返回 null 当 phase 无 field 6 (name), 导致无名称 phase 被丢弃
4. **Phase end 值** (已修复): 当 segment field 2 无 float 时, 改用 phase field 3 作为备选
5. **历史数据列格式**: `ShotRecordApi.parseDatapoints()` 将 service 端整数除以 10 (时间/压力/流量)
6. **Phase 编辑（已停用）**: 主控板为唯一权威、本地修改推送暂不支持——编辑入口由 `ProfileDetailScreen.EDIT_ENABLED = false` 隐藏，`pushAndSaveIfConfirmed` / `saveEditedProfile` / `uploadPendingProfiles` 均为 no-op。编辑 UI 代码保留，未来恢复推送时翻回开关即可
7. **Shot 时间戳 double ×1000** (已修复): `toShotRecord()` 曾对已是毫秒的值再 ×1000, 现统一经 `TimeUtils.normalizeShotTimestamp()` 归一化 + `MIGRATION_3_4` 修复存量数据 (详见 §9)

