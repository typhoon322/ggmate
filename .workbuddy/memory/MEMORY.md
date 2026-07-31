# GagMate 项目长期记忆

## 工作方式约定（用户明确要求）
- **所有修改必须同步更新文档**（根目录 `AGENTS.md` Rule 1）：改动完成后，在汇报前更新对应文档并更新其顶部日期——`README.md`（用户功能）/`GAGMATE_REFERENCE.md`（架构·协议·DB·组件）/`GagMate_DesignSystem.md`（UI·token·布局）/`CODE_REVIEW.md`（已知问题）。
- **每次改完代码都必须打包验证**：改动任何源码后，运行 `./gradlew :app:assembleDebug`（离线可加 `--offline`）确认 BUILD SUCCESSFUL，再向用户汇报完成。不要用 "UP-TO-DATE" 当作没验证。
- **非代码类内容用中文回复**，代码/标识符/技术术语保持英文。
- 根目录 `AGENTS.md` = 项目根级协作规则（人可读，非 WorkBuddy 自动读取）。

## 架构要点速记
- **数据权威原则（用户拍板 2026-07-29）**：Gaggiuino 主控板 = 唯一权威，本地 DB 是单向只读镜像；本地修改推送暂不做（`ProfileDetailScreen.EDIT_ENABLED=false` 隐藏编辑入口，push 方法全 no-op）。**曲线相位来源（2026-07-31 再更正 ×2）**：本机固件 `GET /api/profiles/all` **只返回 `[{id,name,selected}]`，不含 `phases`**（已用 `gagmate_combined(7).log` 行 234 实测证伪早前的"全量相位"推断）。相位真实来源只有两条：(1) 活跃 profile 的 WS `g_prof`→`d_prof`/`d_act_prof` 实时增强；(2) 同步期由 shot 内嵌 `profile.phases` seed 进 `ProfileEntity.phasesJson`（`seedProfilePhasesFromShots`）。**因此「不切活跃」前提下，未实际萃取过的非活跃 profile 没有本地曲线缓存，打开详情会空白**——这是用户 2026-07-31 要求「打开详情绝不 `selectProfile`」后的真实能力边界。**打开任意 profile 详情不再向机器推送 `c_upd_act_prof_id`**（用户明确要求，零副作用）。Room 已到 v6（`shot_records.profile_id` 可空 + `embedded_phases_json`）。
- WS 全局单例 = `MachineSessionManager`，自带指数退避重连（连失败 6 次后转 ERROR）。
- 曲线数据缓冲在 `ShotRepository.start(scope)`（AppContainer.appScope，进程级），不在 ViewModel，避免离开页面冻结。
- 萃取只能由机器触发，仪表盘只显示状态；`brewActive` 上升沿自动跳转 `livecurve`。
- 历史横屏图表是独立 NavHost 目标 `history_chart/{shotId}`（支持缩放/平移/拖拽游标）。
- profile 详情取数：本地 `ProfileEntity.phasesJson` 由 `SyncManager.seedProfilePhasesFromShots` 在同步期从 **shot 内嵌 `profile.phases`** 写入（REST `GET /api/profiles/all` 本机固件仅返回 id/name/selected，不含相位，已实测）；详情页优先读本地 `phasesJson`，为空才 `fetchProfilePhases` 兜底（WS `g_prof` 仅对当前活跃 profile 有效 + shot 内嵌回退）。`ProfileRepository.mirrorProfilePhases(id, phases)` 仅当 `phasesJson` 空白时补全。**打开任意详情不再 `selectProfile`**（用户 2026-07-31 明确要求，避免把机器活跃 profile 切走）。
- 网络日志已去重：`NetworkLogger`/`ApiDebugLogger` 用 `LinkedHashMap` 哈希缓存跳过连续重复响应 + 跳过 SPA-HTML shell；`ApiDebugLogger` 仅记 `/api/system/`。
- 设计系统基础：`theme/Tokens.kt`（`GagMateExtendedColors` 语义色 + 间距/形状/海拔 token），经 `LocalGagMateColors` 注入；仪表盘已重构为 token 驱动、无障碍优先。组件内禁止硬编码 `Color(0xFF…)`，统一用 `gagMateColors()`。
- **协议取证工具（调试专属）**：`设置`(仅 debug) → `WebSocket 协议实验` → `运行实验`：`DebugWsExperimentScreen` 内藏 `1×1` 不可见 `WebView` 加载 `assets/ws_experiment.html`，其 JS 逐字节复刻 `ProtoCodec`/`ProtoCommands` 线协议，连机器实测 `d_prof_dict` + `g_prof(id)` 是否按 id 返回，日志回传 App 内面板。用于取代第三方库 schema 推断，坐实 WebUI 取数机制（详见 `GAGMATE_REFERENCE.md` 附录 C / `CODE_REVIEW.md` §0s）。

## 已知待办（优先级 4-6，尚未处理）
- 删除死代码 `GaggiuinoV3Client.kt`（337 行，与 MachineSessionManager/ProtoDecoder 重复）。
- NEW-D：steam 温度/流量仪表硬编码 0f，brew 卡片硬编码 "--"/0。

## 已完成（补记）
- Room 已升到 v4，`MIGRATION_3_4` 归一化 shot 时间戳；统一入口 `TimeUtils.normalizeShotTimestamp()`（≤1e12→×1000，>1e15→÷1000，否则原样）。`fallbackToDestructiveMigration` 仅兜底。
- 仪表盘：连接状态并入 `MachineStatusBadge`（offline/connecting/reconnecting，`stateOffline` token）；无数据显示 `—`（`GaugeView.showDash`/`metricOrDash()`）；"曲线全局设定"改为"当前曲线"图表（`selectedProfilePhases` StateFlow）。
