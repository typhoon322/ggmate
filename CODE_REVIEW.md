# GagMate — 架构梳理与问题清单（2026-07-23 更新版）

> **状态说明**：本文档是对 `app/src/main/**`（54 个 Kotlin 文件，约 7,000 LOC）的重新梳理。
> 早先（03:06）的初版 Code Review 报了一个「启动即崩溃」的致命 bug，但**过去 24 小时内代码已被修改**，
> 其中多处问题已经修复（见 §2）。本文档反映**当前真实状态**，并补充了本次新发现的问题。
> 配套设计文档见 `GAGMATE_REFERENCE.md`。

---

## 0. 本轮修复记录（2026-07-20 第 2 次开发）

按用户要求 **修复优先级 1–3 + 将 WS 连接重构成一个健壮的全局单例**。已全部通过 `assembleDebug` 编译验证。

### WS 全局单例（`MachineSessionManager`）
- `ConnectionState` 新增 `RECONNECTING`，状态机更清晰：`DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING / ERROR`。
- 断联后**自动重连**：`onFailure`/`onClosed(非1000)` 进入 `RECONNECTING` 并重试；指数退避（`INITIAL_RECONNECT_MS`→`MAX_RECONNECT_MS`，上限封顶），**重试 6 次仍失败则置 `ERROR`**（UI 显示「无法连接」引导去设置），避免无限重试。
- `start()` 现在**必须传入 `Context`**（`MainActivity` 已改为 `start(appScope, this)`），连接成功提示音现在能正常播放。
- host 解析统一为 `normalizeHost()`（同时剥离 `http://` 与 `https://`、去尾斜杠），`start`/`restart` 不再各写一套；`SettingsViewModel` 改为 `restart(_host.value)`，由单例内部归一化。
- 复用单个 `OkHttpClient`；`connect()` 前先 `webSocket?.cancel()` 防止旧 socket 泄漏；详细调试日志用 `BuildConfig.DEBUG` 守卫（已开启 `buildFeatures.buildConfig`）。
- 新增 `startBrew()`（`c_opmode`→NORMAL）/ `stopBrew()`（`c_opmode`→STANDBY）真实命令。

### NEW-B（Dashboard 错误通道污染）— 已修复
- `DashboardViewModel` 新增独立的 `_message`（瞬时成功/提示）通道；控制命令成功只写 `_message`，**不再污染 `_error`**。
- 仅当 WS 处于终端 `ERROR` 才显示整屏「无法连接」接管；`RECONNECTING` 期间只显示非阻断的「重连中…」横幅（含「设置」按钮），仪表盘保持可用。
- 屏幕新增 `message` 的 Snackbar（2.5s 自动消失）。

### NEW-C（控制命令空壳）— 已修复
- `startBrew()`/`stopBrew()` 发送真实 opmode 命令；`flush`/`tare`/`setSetpoint`/`setWeight` 成功改为提示消息；`toggleSteam`/`primePump` 无对应 WS 命令，改为诚实的提示而非假错误。

### NEW-A（曲线详情自动拉取不显示）— 已修复
- 新增 `ProfileDao.observeById` → `LocalDataRepository.observeProfile` → `ProfilesViewModel.profileFlow`。
- `ProfilesScreen` 改为用 `selectedProfileId` + 实时 `liveProfile` 流驱动 `ProfileDetailDialog`；WS 返回的 phase 写入 Room 后对话框自动重组显示。修复了 `LaunchedEffect` 中 `loadingPhases` 被瞬间置 false 的问题（现在等 phase 真正到达或 8s 超时）。顺手修了粘贴 JSON 对话框重复 `showPasteDialog=false` 与删除用错 ID 的 bug。

### NEW-E（syncProfiles 虚报上传数）— 已修复
- `syncProfiles()` 中 `LOCAL_ONLY`/`MODIFIED` 的本地曲线现在**真正调用 `uploadProfile()` 上传**，成功后标记 `SYNCED` 并正确累加 `profilesUploaded`，不再虚报。

### NEW-F（syncShots 逐条串行全量拉取）— 已修复
- 先读本地已有 shot ID 集合**跳过已同步项**；缺失项用 `Dispatchers.IO.limitedParallelism(4)` **有界并发**拉取，避免 `1..latestId` 无脑全量串行。

### NEW-L（生产加固）— 部分修复
- 声音：`start()` 传 Context 后连接提示音已恢复。
- cleartext：`AndroidManifest` 移除冗余的 `usesCleartextTraffic="true"`，改为在 `network_security_config` 的 `base-config` 单一声明 `cleartextTrafficPermitted="true"`（机器是 LAN 无 TLS 设备，IP 可配，故全局允许；已在配置中注释说明）。
- 日志：Dashboard/MachineSessionManager 的噪声 `Log.d` 用 `BuildConfig.DEBUG` 守卫。
- **未做**：Room `fallbackToDestructiveMigration()` 仍会在 schema 升级时清空本地数据。需在掌握历史 schema 后补正式 `Migration`（本轮未改，避免误写迁移导致数据损坏）。

### 未在本轮处理（属优先级 4–6）
- 🟠 NEW-D：蒸汽温度表 / 流速表仍硬编码 `0f`（Gen3 JSON 无对应字段，需扩展数据模型）。
- 死代码 `GaggiuinoV3Client.kt`（337 行重复实现）仍待删除。
- UI 诚实性清理（萃取信息卡恒显 `--`/`0`）。

---

## 0b. 本轮改动（2026-07-20 第 3 次开发：萃取交互体验）

用户要求：① 移除仪表盘「开始萃取」按钮（萃取只能由机器触发，仪表盘只展示状态；萃取开始后要**自动跳转曲线模式**实时画曲线）；② 重做萃取历史**横屏图表查看页**；③ 非代码内容用中文回复。

### 实时曲线数据流下沉（关键架构修正）
- 之前 `ShotRepository.chartData` 的填充逻辑放在 `DashboardViewModel` 里，导致**跳转到曲线页后 ViewModel 被销毁、曲线停止更新**。
- 改为 `ShotRepository.start(scope)` 自订阅 `session.shotSnapshot`，在 `AppContainer.init()` 里用新增的 `appScope`（进程级 `CoroutineScope`）启动。曲线缓冲现在**与界面生命周期解耦**，跳转后仍持续累积。
- `appendShotPoint()` 现在同时写入 **温度 / 重量**（之前只填了压力 / 流速），实时曲线可同时显示 P / PF / T / W 四条线。
- `DashboardViewModel` 移除了重复的 chart 收集器与 `startBrew()`/`stopBrew()` 命令（只保留 `_message` 提示通道）。

### 仪表盘：移除开始萃取按钮，保留状态展示
- 删除 FAB（start/stop brew）；`MachineStatusBadge` 仍根据 `brewActive` 展示「萃取中 / 待机」。
- 新增 `LiveCurveScreen`（横屏、`BrewChartView` 全屏、顶部实时读数、返回按钮）。`BrewChartView` 增加 `height = Dp.Unspecified` 以填满父容器。

### 自动跳转曲线模式
- `AppNavigation` 监听 `machineSession.brewActive` 上升沿：萃取开始时**从任意 Tab 自动跳转** `navigate("livecurve")`（launchSingleTop）；仅当用户已在全屏图表（`livecurve` / `history_chart/*`）上时跳过，避免把人从正在看的图上拽走。用 `navController.currentDestination` 实时读路由，避免闭包捕获旧值；返回仪表盘后若仍在萃取不会再次误跳。
- **`brewActive` 来源修复（关键）**：原实现只有 JSON `sensor_data_update.brewActive` 会置位，protobuf 固件（只发 `ShotSnapshot`/`SystemState`）永不触发，导致萃取开始 App 不唤醒曲线。现改为 OR 两个信号：`brewActiveFromJson`（JSON）与 `brewActiveFromProtobuf`（`ShotSnapshot.timeInShot > 0`）；后者由 4s 看门狗兜底——若一段时间无 in-shot 快照则自动清零（机器可能不发结束帧就停发快照）。详见 `MachineSessionManager`。

### 萃取历史横屏图表页（重做 `ShotChartFullScreen`）
- 改为 NavHost 目的地 `history_chart/{shotId}`，由 `ShotHistoryScreen` 通过 `onOpenChart(id)` 导航（原先是覆盖层，无法盖住底部导航栏）。
- **强制横屏**；真实左右坐标轴 + 刻度标签（左轴 P/PF 0–12，右轴 T/W 0–100）；时间轴按实际时长自适应（不再固定 60s）。
- **拖动擦洗**：手指在图上拖动即时移动十字准线并刷新 P/PF/T/W 读数卡片（原实现只能点一下）。
- **缩放 + 平移**：放大/缩小按钮（1×→20×），放大后底部出现滑条做水平平移；目标曲线以虚线显示。
- 通过 `produceState` 按 shotId 从 `localRepo` 加载，不再依赖跨页面对象传递。

全部通过 `./gradlew :app:assembleDebug` 编译验证。

---

## 0c. 本轮改动（2026-07-20 第 4 次开发：曲线组件抽取 + 萃取唤醒 + 阶段解析）

用户反馈（基于日志 `gagmate_combined(2).log`）四个问题，已全部修复并通过 `./gradlew :app:assembleDebug --offline` 编译验证。

### ① 萃取开始 App 不唤醒实时曲线
- **根因**：`MachineSessionManager._brewActive` 只从 JSON `sensor_data_update.brewActive` 置位；protobuf 固件走 `ShotSnapshotMsg` 路径从不置位。
- **修复**：`brewActive` 改为 JSON 与 protobuf 两个信号之 OR（`brewActiveFromJson` / `brewActiveFromProtobuf`）。protobuf 侧以 `ShotSnapshot.timeInShot > 0` 判定萃取中，`System.currentTimeMillis()` 记录最近活跃时刻；`launchBrewWatchdog()` 每 4s 检查，若超时无 in-shot 快照则清零（兜底机器不发结束帧的情况）。`start()` 启动看门狗、`stop()` 取消。
- **导航放松**：`AppNavigation` 自动跳转不再要求「当前在仪表盘」，改为从任意 Tab 跳转（仅跳过已处全屏图表时），确保萃取一开始就能看到实时曲线。

### ② 很多 profile 阶段数正确但内容全 0
- **根因**：`ProtoDecoder.decodePhaseInfo()` 误把 phase 的 **名称字段（field 2）** 当作嵌套 Target 消息解析，从未读到 start/end/curve/time。
- **修复**：改为从 **field 3 嵌套 Target** 读取 sub1=start、sub2=end、sub3=curve 枚举（新增 `curveEnumToString` 映射 0–6 到 FLAT/EASE_IN/…/FAST_IN_OUT）、sub4=time(ms→s)；名称从 field 2、类型从 field 1 读取。`parseProfilePhases()` 用 `BrewPhase(start, target=end, variation, time)` 重建。配套：`BrewPhase` 增加 `start`/`variation` 字段（默认 `"LINEAR"`），`PhaseV3.toBrewPhase()` 携带 `start` 与 `variation=curve.uppercase()`。

### ③ 曲线绘制应支持变化模式（EASE_OUT / EASE_IN_OUT …）
- **根因**：原先 `generateProfileChartPoints()` 只有 FLAT 阶梯（直接把 target 赋给整段），没有缓动。
- **修复**：在共享组件 `CurveChart.kt` 实现 `curveVariationEasedProgress(variation, p)`，覆盖 FLAT/LINEAR、EASE_IN(t²)、EASE_OUT(1−√(1−t))、EASE_IN_OUT(t²(3−2t))、FAST_IN(√t)、FAST_OUT(1−(1−t)²)、FAST_IN_OUT(smoothstep)。`generateProfileChartPoints()` 现在按阶段 `start→target` 用对应 variation 缓动生成曲线，并以上一阶段末端值作为本阶段起点保证连续。

### ④ 曲线图抽出共用组件，替换各处单独实现
- **新增** `ui/components/CurveChart.kt` 作为唯一 Canvas 渲染源：
  - `data class CurveSeries(color, axis, dashed=false, valueAt)`、`enum ChartAxis{LEFT,RIGHT}`、`@Composable CurveChart(...)`（网格/虚线 target/实线 actual/末端标记/十字线/窗口化 `t0..t1`）、`ProfileCurveChart(phases)` 便捷封装。
  - `generateProfileChartPoints()` 与 `curveVariationEasedProgress()` 也在此集中。
- **重构** `BrewChartView` 与 `ShotChartFullScreen`：删除各自重复的 Canvas 绘制代码，改为构造 `List<CurveSeries>` 并委托 `CurveChart` 渲染（保留各自的交互：缩放/平移/擦洗/十字线、图例、坐标轴标签）。
- **迁移**：移除 `ProfileDetailScreen` 里 FLAT-only 的本地 `generateProfileChartPoints`，`ProfileDetailScreen` 与 `DashboardScreen` 的统一改用 `CurveChart.kt` 中的共享实现（`DashboardScreen.ProfileGlobalsCard` 的"当前曲线"卡片即受益）。

---

## 0d. 本轮改动（2026-07-23：实时曲线空白 + profile 曲线 0 值 + 仪表盘布局）

基于上一轮后仍存在的四个问题做**二次修复**（结合用户提供的真实机器 `gagmate_combined(3).log` 网络日志核对了数据契约），全部通过 `./gradlew :app:assembleDebug --offline` 编译验证。关键发现：本机 `GET /api/profile/{id}` 独立端点未被调用，但 shot 记录稳定内嵌完整 `profile.phases`（schema 与 `PhaseV3` 一致）；实时曲线空白的真正原因是 `timeInShot < 100` 触发每帧清空缓冲区。

### ① 开始萃取后实时图表页空白、不画图
- **根因（经日志核对）**：`ShotRepository` 旧逻辑在每次收到 shot 快照且 `timeInShot < 100` 时就 `clearChart()`。一旦固件的 `timeInShot` 字段名不匹配（被 Gson 解析为 0），每一帧都会把缓冲区清空 → 曲线永远空白。萃取期间机器持续推 `sensor_data_update`（含压力/温度/液重，且 `brewActive=true`），但时间轴处理有缺陷导致画不出。
- **修复**：
  - 清空时机改为 **`brewActive` 上升沿**（false→true）一次性清空，不再依赖单帧 `timeInShot` 阈值。
  - 时间轴改为**墙钟时间差**兜底：优先用固件 `timeInShot`（需单调且 >0），否则用「萃取开始时刻起算的秒数」，保证即使固件不报 `timeInShot` 曲线也始终能画。
  - 保留传感器流回填（`session.sensorSnapshot` 在 `brewActive` 期间追加），并与 shot 快照去重（400ms 内以 shot 快照为准）。

### ② profile 详情图仍全 0 bar / 0 流速
- **根因（经日志核对）**：本机 `GET /api/profile/{id}` **独立端点未被调用**，且 WS protobuf `parseProfilePhases` 在特定固件下解析为 0。但日志证实：机器在 **shot 记录内稳定内嵌完整 `profile.phases`**（`api/shots/{id}` → `profile`，schema 见 GAGMATE_REFERENCE §Profile JSON schema），且字段名与 `PhaseV3`/`PhaseTarget` 完全一致（`type` 大写 `FLOW`/`PRESSURE`、`curve` 为字符串、首阶段常缺 `start`）。
- **修复**：新增 `MachineRepository.fetchProfilePhases(id, name)` —— **优先** `GET /api/profile/{id}`（Gen3 REST），**回退**到最近一条 shot 内嵌的 `profile.phases`（按 `name` 匹配）。详情页与仪表盘均改用此聚合入口，确保总能拿到真实设定曲线。

### ③ 仪表盘：底部数据块移到顶部，仅保留温度与压力
- **修复**：`DashboardScreen` LazyColumn 顺序调整 —— 机器读数仪表（仅 **温度** + **压力** 两个，使用 `directSensorT` / `directSensorP`）移到 Hero 卡片之后置顶；移除原先置底的蒸汽温度 / 泵流速仪表（其值硬编码 0）。

### ④ 仪表盘"当前激活 profile"曲线未绘制
- **根因**：`ProfileGlobalsCard` 用 WS `_selectedProfilePhases`，而该流在本固件下为空/不可赖。
- **修复**：`DashboardScreen` 在 `selectedProfileId` / `selectedProfileName` 变化时调用 `fetchProfilePhases(id, name)`（REST detail → 回退 shot 内嵌 profile，按名匹配），优先用其结果绘制；仍失败再回退 WS 流。`AppContainer` 暴露 `machineRepo`。

> 注：②/④ 在 §0d 中采用的「REST `GET /api/profile/{id}` 优先」策略**经用户确认在本机固件上并不成立**——该端点实际不支持。以下 §0e 为最终落地方案。

---

## 0e. 本轮改动（2026-07-23 续：REST 不支持 → 改用 WebSocket `g_prof` 取当前曲线定义）

用户确认本机固件**不支持 REST `GET /api/profile/{id}`**，并纠正：shot 是萃取历史快照，不能当作离线权威的 profile 数据（机器上改了配方后 shot 里的还是旧的）。故把「当前曲线定义」的唯一权威来源改为 **WebSocket `g_prof` → `d_prof`/`d_act_prof`**，并在同步时落库。

### 取数架构最终定稿
- **当前定义（权威、实时）**：`MachineSessionManager.requestProfilePhases(id, name)` —— 发 `g_prof(id)`，await `d_prof` 响应并按 **profile name** 关联（用 `pendingProfileDeferreds` 完成 `CompletableDeferred`）。带超时（默认 3.5s），WS 未连返回空。
- **离线落库**：`SyncManager.syncProfiles()` 同步时为每条曲线发 `sendGetProfile(id)`，`ProfileRepository` 的 WS→Room 收集器（`profileDataReceived`）把 `d_prof` 按 name 写回 `ProfileEntity.phasesJson`（**仅覆盖 `SYNCED`**，永不覆盖本地已编辑；带全零保护，避免本固件偶发的全 0 推送覆盖正确数据）。同步一次后即可离线看图。
- **实时展示入口** `MachineRepository.fetchProfilePhases(id, name)`：① 优先 WS `requestProfilePhases`（当前定义）；② 失败回退最近 shot 内嵌 profile（**仅展示、不落库**，因是历史快照）；③ 空则返空。落库由上面的 sync 路径负责，fetch 不再落库。
- **详情页**：删除了「发 `g_prof` 后 `delay(8000)` 却不消费响应」的死代码（旧 `hasNoPhases` 分支），改为直接走 `fetchProfilePhases`（WS 实时）并统一在取数后收尾 `loadingPhases=false`。

### 撤掉的方案
- **REST 详情落库（§0d 尝试）**：`syncProfiles` 内对每条 profile 调 `getProfileDetail(id)` 落库 —— 因端点不支持，必然取空，已撤。
- **shot 内嵌回填落库（更早尝试）**：把 shot 内嵌 `profile.phases` 按 name 回写本地库 —— 用户指出 shot 是历史快照，改配方后失效，已撤。

### 已知固件风险
- ~~`g_prof` 是否按请求 id 精确返回该 profile~~ **已确认**：`g_prof(id)` 按请求 id 精确返回该条 profile 的当前定义（官方 webui 即以此获取 profile 数据）。因此同步时逐条 `g_prof(id)` 可正确回填**每条**非活跃曲线，离线/无 WS 时也能绘制；`requestProfilePhases(id,name)` 的 name 关联亦可靠。
- 验证：`SyncManager` 日志 `fullSync: ADDED profile ... (phases filled via WS g_prof)` 表示已建行；WS→Room 日志 `WROTE phasesJson (N B) for name='X'` 表示已落库（N>2 即有效）。

---

## 1. 项目架构（整理）

### 1.1 分层结构

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层 (Jetpack Compose)                                      │
│   DashboardScreen / ProfilesScreen / ShotHistoryScreen /       │
│   SettingsScreen  →  各自 ViewModel                            │
└───────────────┬──────────────────────────────────────────────┘
                │ collect / invoke
┌───────────────▼──────────────────────────────────────────────┐
│  Repository 层                                                │
│   LocalDataRepository(Room) · MachineRepository(REST) ·       │
│   SensorRepository · ShotRepository · ProfileRepository ·      │
│   SyncManager · SettingsRepository(DataStore)                 │
└───┬───────────────────────┬──────────────────────┬──────────┘
    │                        │                      │
┌───▼─────────┐   ┌──────────▼─────────┐   ┌───────▼──────────────┐
│ Room (本地) │   │ Retrofit + OkHttp  │   │ MachineSessionManager│
│ profiles/   │   │ REST API          │   │ (WebSocket 单例)     │
│ shots/      │   │ /api/system/...   │   │ 暴露 StateFlow/      │
│ settings    │   │ /api/profile...   │   │ SharedFlow           │
└─────────────┘   └──────────────────┘   └───────────────────────┘
                                              ↑ 实时数据
                                        Protobuf(BINARY) + JSON(TEXT)
                                        ws://{host}/ws
```

- **服务定位器**：`AppContainer`（object 单例）在 `MainActivity.onCreate` 中 `init()`，持有全部仓储与 `machineSession`。
- **WebSocket 会话**：`MachineSessionManager` 是**应用生命周期级单例**，暴露 9 个 `StateFlow`/`SharedFlow`（`sensorSnapshot`/`machineState`/`shotSnapshot`/`currentProfiles`/`brewActive`/`machineMode`/`selectedProfileName`/`connectionState`/`messages` + `profileDataReceived`）。
- **协议解析**：`ProtoCodec`（编解码）→ `parseProtoMessage()` → `handleMessage()` 更新 StateFlow；二进制帧失败再尝试按 JSON（`"action"` 关键字）解析（Gen3 固件文本格式）。

### 1.2 启动流程（`MainActivity.onCreate`）

1. `NetworkLogger.init` / `ApiDebugLogger.init` / `CrashLogger.init`
2. `AppContainer.init(this)` —— 建 DB、仓储、`machineSession`
3. `appScope.launch { delay(500); syncManager.fullSync() }` —— 后台静默全量同步
4. `AppContainer.machineSession.start(appScope)` —— 建立 WS 连接
5. `setContent { GagMateTheme { AppNavigation() } }`

### 1.3 关键模块职责

| 模块 | 文件 | 职责 | 状态 |
|------|------|------|------|
| AppContainer | `repository/AppContainer.kt` | 全局单例容器 | ✅ 已修 |
| MachineSessionManager | `session/MachineSessionManager.kt` | WS 连接/重连/命令/解析 | ✅ 已修 |
| ProtoCodec / Decoder / Commands / Message | `protocol/*` | 自定义 protobuf 编解码 | ✅ 健壮 |
| GgboardApi / Client | `api/*` | Retrofit REST + 动态 baseUrl | ✅ 日志已修 |
| Repository 群 | `repository/*` | 数据编排 | ⚠️ 见 §3 |
| LocalDataRepository / AppDatabase | `local/*` | Room | ⚠️ 破坏性迁移 |
| Dashboard/Profiles/History/Settings | `ui/*` | 4 个页面 | ⚠️ 见 §3 |
| CrashLogger / NetworkLogger / DebugLogState / SoundManager | `data/system`,`data/api` | 系统服务 | ⚠️ 见 §3 |

### 1.4 与参考文档（`GAGMATE_REFERENCE.md`）的差异

- 文档描述的是「意图架构」，主体已落地。
- Dashboard 确实按文档所说**直连 `machineSession` 的 StateFlow**（绕过 ViewModel 中转），但 ViewModel 里仍保留了一份没人读到的 `_machineState` 收集（见 §3 NEW-I）。
- `README.md` 的 REST 端点（`/api/state`、`/api/command` 等）已过时；代码实际用 `/api/system/status`、`/api/profile-select/{id}` 等，以 `GAGMATE_REFERENCE.md` + `GgboardApi.kt` 为准。

---

## 2. 前期问题修复状态

| # | 问题 | 状态 |
|---|------|------|
| 1 | `AppContainer.init()` 在赋值前读取 `machineSession`（启动崩溃） | ✅ **已修复** —— 现顺序正确 |
| 2 | `GaggiuinoV3Client.kt`（337 行）是 `MachineSessionManager` 的重复死代码 | ❌ **仍在** —— 从未被实例化 |
| 3 | 改 Settings 后 WS 不刷新 host | ✅ **已修复** —— 新增 `restart()` 且 `saveAndApply()` 调用 |
| 4 | `syncShots()` 循环 `1..latestId` 逐条 HTTP | ❌ **仍在**（见 NEW-F） |
| 5 | `GgboardApiClient` 日志误用转义 `$` | ✅ **已修复** —— 现正确输出方法名 |
| 6 | WS host 仅剥离 `http://` | 🟡 **部分修复** —— `start()` 已支持 `https://`，但 `restart()`/`saveAndApply()` 仍只剥离 `http://`（见 NEW-H） |
| 7 | `onClosed` 不重连 | ✅ **已修复** —— 非 1000 关闭会重连 |
| 8 | 重连每次新建 OkHttpClient 泄漏 | ✅ **已修复** —— client 复用 |
| 9 | `SettingsRepository.getConnectionUrl()` 返回硬编码且未使用 | ✅ **已移除** |
| 10 | profile 导入换行解析 | 🟡 **部分修复** —— 主路径 `Regex("\\}\\s*\\{")` 正确；CRLF 兜底路径仍坏（低） |
| 11 | `deleteProfile` 无意义 `coroutineScope` | ❌ 仍在（低） |
| 12 | 示例曲线逻辑重复两处 | ❌ 仍在（低） |
| 13 | `importProfileFromJson` 未用 `use{}` 泄漏流 | ❌ 仍在（低） |
| 14 | `fallbackToDestructiveMigration()` 上线风险 | ❌ 仍在（中/低，生产前必须加迁移） |
| 15 | `ProfileDao` 硬编码 `"sync_status"` 字面量 | ❌ 仍在（低） |
| 16 | 死代码 `ProfilesResponse`/`ApiResponse` 等 | ❌ 部分仍在 |
| 17 | UI 收集生命周期 | 🟡 设计异味仍在（低） |
| 18 | `network_security_config` 未真正限定域名 | ❌ **确认全局放行**（见 NEW 安全项） |

---

## 3. 当前仍存在的问题（已逐项核实）

### 🔴 高

**NEW-A · 曲线详情「自动拉取 phase」结果不显示（核心功能坏掉）**
`ProfilesScreen.ProfileDetailDialog` 用 `profile = selectedProfile!!`（`mutableStateOf`，仅在点击时赋值）。
- `loadingPhases` 初始为 `hasNoPhases`，但在 `LaunchedEffect` 里发完 `sendGetProfile` 后**立即** `loadingPhases = false`（第 569 行），加载圈瞬间消失；
- WS 返回 `d_prof` → `ProfileRepository` 写入 Room，但 `selectedProfile` **不会被重新赋值**，对话框拿到的还是旧（空 phase）对象，不会重组出新数据。
- 结果：点一个尚未缓存 phase 的曲线，对话框显示空 phase 列表，看起来「自动请求」毫无作用。
- **修复**：按 id 从 `profiles` 流里取最新实体给对话框（或让 VM 暴露 `getProfileByIdFlow(id)`）；并修正 `loadingPhases` 逻辑——在数据到达/有 phase 前保持 true。

**NEW-B · Dashboard 的「开始/停止萃取」「冲洗」会触发全屏错误覆盖**
`DashboardViewModel.flush()` 成功时 ` _error.value = "Flush started"`；`startBrew()/stopBrew()/toggleSteam()/primePump()` 把 `_error` 设成占位提示字符串。
而 `DashboardScreen` 的 `when { ... error != null -> 错误布局 ... }` 在 `_error` 非空时**用「无法连接」整屏替换仪表盘**。
- **后果**：任何一次成功的控制操作都会把整个仪表盘变成错误页。
- **修复**：新增独立的 `infoMessage`/Snackbar 通道，`_error` 只用于真正的失败。

### 🟠 中

**NEW-C · 开始/停止萃取、蒸汽、注水是空壳**
`startBrew()/stopBrew()/toggleSteam()/primePump()` 只把 `_error` 设成「requires WebSocket command」，**不发任何 WS 命令**。FAB（App 主按钮）点了只弹错误。若固件支持 WS 启动萃取，应补上命令；否则禁用 FAB 并在文档说明。README/参考文档都宣传了 start/stop brew。

**NEW-D · 两个仪表盘恒为 0；萃取信息卡显示 "--"/0**
`DashboardScreen` 把**蒸汽温度表**（`value = 0f`，第 444 行）和**流速表**（`value = 0f`，第 466 行）硬编码为 0；`SensorSnapshot`/WS JSON 也未把 steam-temp、pump-flow 映射进来。
「当前萃取」信息卡（time/volume/pump%）硬编码 `"--"`/`"0 ml"`/`"0%"`（第 500–509 行）。
- **修复**：要么接真实数据，要么移除这些元素，避免暗示不存在的功能。

**NEW-E · `syncProfiles` 上报虚假上传数且从不真正上传本地修改**
`SyncManager.syncProfiles()`（第 157–169 行）对 `LOCAL_ONLY`/`MODIFIED` 直接 `profilesUploaded++`，**却没调用 `uploadProfile`**（注释称「REST 不可用」——但 `MachineRepository.uploadProfile` 存在，`saveEditedProfile` 也在用）。
- 后果：点 Sync 后摘要谎称「N uploaded」，实际什么都没推。
- 同时每次同步都把 `MODIFIED`/`CONFLICT` 全部标成 `CONFLICT`。
- **修复**：像 `saveEditedProfile` 那样对 pending 修改真正调用 `uploadProfile`；停止虚报。

**NEW-F · `syncShots()` 仍是 N 次顺序 HTTP（重确认 #4）**
`for (shotId in 1..latestIdInt)` 每个 id 一次 REST 调用；已删除 id 返回 404 静默跳过；无上限。机器上 shot 多时会慢且猛打设备。应使用 shots 索引/分页，或至少限流+并发上限。

### 🟡 低 / 清理

**NEW-G · `MainActivity` 没给 `session.start()` 传 Context → 连接成功音永不播放**
`machineSession.start(appScope)` 省略了 `context` 参数 → `appContext` 为 null → `onOpen` 里 `appContext?.let { SoundManager.playConnectionSuccess(it) }` 被跳过。修复：`start(appScope, this)`。

**NEW-H · host 解析在 `start`/`restart`/`saveAndApply` 间不一致**
`start()` 剥离 `https://` 和 `http://`；`restart()` 与 `SettingsViewModel.saveAndApply()` 只剥离 `http://`；`MachineRepository.updateConnection` 强制 `http://`。若用户输入 `https://host`，`restart` 会拼出 `ws://https://host/ws`（畸形）。建议统一一个 `normalizeHost()` 并明确 http-only 策略。

**NEW-I · Dashboard 冗余/死亡数据流**
`DashboardViewModel.init` 把 `session.*` 收集进 `_machineState`（一个 `MachineState`），但屏幕根本不读它（屏幕直连 `directSensorT` 等）；还有**两个**独立的 `shotSnapshot` 收集器（都对新 shot 调 `clearChart`）。清理：删掉没人读的 `_machineState` 收集，或按参考文档意图统一走 VM。

**NEW-J · DashboardScreen 里的死代码 / 调试残留**
- `LaunchedEffect(false) { steamOn = false == true }`：常量 key，把一个从不使用的 `steamOn` 设成恒 false —— 纯残留，删除。
- `SideEffect { Log.d(...) }` 每次重组都打日志（刷屏）。
- 非空 `Float`/`String` 状态变量用了多余的 `?:`/`?.`（`directSensorTT ?: 0f`、`directProfileName?.isNotBlank()`）—— 整理。

**NEW-K · 曲线编辑对话框保存时丢失调件条件/下一阶段**
`ProfileEditDialog` 只编辑 name/type/target/time；保存时序列化 `editedPhases`（`BrewPhase` 默认 `condition="time"`、`nextPhase=""`），原曲线的 `condition`/`nextPhase`（及 PhaseV3 专属字段）被静默丢掉。若曲线有实际 stop condition，编辑+保存会悄悄损坏它。修复：暴露这些字段，或编辑时保留原值。

**NEW-L · 每次打开「历史」页都触发一次全量同步**
`ShotHistoryViewModel.loadShots()` 每次调用都 `syncManager.fullSync()`（每次切到该 Tab），叠加 NEW-F 的成本。应做去抖或仅在显式刷新时同步。

**安全 · `usesCleartextTraffic="true"` 让 `network_security_config` 失效**
`AndroidManifest.xml` 第 14 行 `android:usesCleartextTraffic="true"` 会**全局**放行明文，覆盖 `res/xml/network_security_config.xml` 里 `<base-config cleartextTrafficPermitted="false">` 的域名限定。局域网 ESP 设备需要 http，但应只在 `domain-config` 内放行，而不是全局。低危（LAN 咖啡机），但发布前应收紧。

**其他低项（继承自初版）**：`deleteProfile` 无用 `coroutineScope`（#11）、示例曲线重复定义（#12）、`importProfileFromJson` 流未 `use{}`（#13）、`fallbackToDestructiveMigration()`（#14）、`ProfileDao` 硬编码字面量（#15）、死代码 `ProfilesResponse`/`ApiResponse`/`GaggiuinoV3Client`（#2/#16）、`HttpLoggingInterceptor.Level.BODY` + 全量 body 落盘应包在 `BuildConfig.DEBUG` 后（#18）。

**O8（低）· 同步结果丢弃 shots 计数**
`SyncManager.fullSync()` 第 58 行 `profilesAdded += r.profilesAdded` 用的是 shots 结果的 `profilesAdded`（恒为 0），且返回的 `SyncResult` 根本不含 `shotsAdded` —— shot 同步数量被静默丢弃。

---

## 4. 亟待优化 / 技术债清单

1. **同步性能与正确性**（NEW-F / NEW-E / NEW-L）：批量/分页拉 shots；对 pending 修改真正上传；历史页同步去抖。
2. **死代码清理**：删除 `GaggiuinoV3Client.kt`（~337 行）、未使用的 REST `selectProfile`、可能的 `ProfilesResponse`/`ApiResponse`。
3. **单一数据源**：统一 Dashboard 数据通路（要么全走 VM，要么删掉 VM 里没人读的收集），消除直连 session + VM 双份收集的混乱。
4. **生产加固**：Room 真实迁移（替代 destructive）、`BuildConfig.DEBUG` 守卫网络日志、`network_security_config` 收紧为域名级。
5. **资源释放**：profile 导入用 `use {}`；确认 WS/文件流无泄漏。
6. **国际化**：`ProfilesScreen` 里 `"Profiles"`、`"Paste Profile JSON"`、`"Fetching phase data..."` 等硬编码英文绕过 `stringResource`，与 zh/en 切换不一致 —— 应外置。
7. **UI 诚实性**：恒为 0 的仪表、假的萃取信息卡要么接数据要么移除（NEW-D）。

---

## 5. 建议修复顺序

| 优先级 | 项 | 影响 |
|--------|----|------|
| 1 | **NEW-B + NEW-C**：拆分 `_error`/新增 info 通道；FAB/控制按钮要么接 WS 要么禁用 | 让 App 操作不再「一点就崩成错误页」，且主按钮可用 |
| 2 | **NEW-A**：曲线详情拉取结果正确显示 | 修复核心功能（曲线 phase 查看） |
| 3 | **NEW-E + NEW-F + NEW-L**：同步正确性与性能 | 数据一致 + 不猛打机器 |
| 4 | 死代码：`GaggiuinoV3Client` 等 | 减少 337 行混淆 |
| 5 | **NEW-D / NEW-I / NEW-J / NEW-K**：UI 诚实性与清理 | 体验与可维护性 |
| 6 | 加固：迁移、日志守卫、cleartext、声音 Context（NEW-G） | 发布前必做 |
| 7 | 低项清理（#11–#16、O8、国际化） | 收尾 |

---

## 6. 结论

架构分层清晰（UI→VM→Repository→Room/REST/WS），实时链路（protobuf+JSON over WS）和 `CrashLogger` 是亮点；
**初版的致命启动崩溃与 WS 重连问题已被修复**。当前最大风险是**交互层**：Dashboard 控制操作会错误触发整屏错误覆盖（NEW-B）、曲线详情自动拉取结果不显示（NEW-A）、同步逻辑虚报上传且逐条猛打 REST（NEW-E/F）、以及若干「永远为 0 / 占位」的 UI 元素（NEW-C/D）。

下一步建议从 **NEW-B + NEW-C** 开始动手修（用户体验影响最大、风险低），需要我继续时直接说「修 NEW-B/C」或「按优先级 1–3 全修」即可。
