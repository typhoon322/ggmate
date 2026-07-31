# GagMate 项目长期记忆

## 工作方式约定（用户明确要求）
- **所有修改必须同步更新文档**（根目录 `AGENTS.md` Rule 1）：改动完成后，在汇报前更新对应文档并更新其顶部日期——`README.md`（用户功能）/`GAGMATE_REFERENCE.md`（架构·协议·DB·组件）/`GagMate_DesignSystem.md`（UI·token·布局）/`CODE_REVIEW.md`（已知问题）。
- **每次改完代码都必须打包验证**：改动任何源码后，运行 `./gradlew :app:assembleDebug`（离线可加 `--offline`）确认 BUILD SUCCESSFUL，再向用户汇报完成。不要用 "UP-TO-DATE" 当作没验证。
- **非代码类内容用中文回复**，代码/标识符/技术术语保持英文。
- 根目录 `AGENTS.md` = 项目根级协作规则（人可读，非 WorkBuddy 自动读取）。

## 架构要点速记
- **数据权威原则（用户拍板 2026-07-29）**：Gaggiuino 主控板 = 唯一权威，本地 DB 是单向只读镜像；本地修改推送暂不做（`ProfileDetailScreen.EDIT_ENABLED=false` 隐藏编辑入口，push 方法全 no-op）。**曲线相位来源（2026-07-31 更正）**：WS `d_prof`/`d_act_prof` 携带真实 `EASE_*` curve 枚举（ProtoDecoder 解析 0–6），是**首选权威**；但本机固件 `g_prof(id)` **只回当前活跃 profile**，故 `requestProfilePhases` 必须先 `selectProfile(id)` 设为活跃再拉取。shot 内嵌 `profile.phases` 仅作离线回退 / WS 为 FLAT 时的 curve 叠加；REST `/api/profile/{id}` 在本机 dead（返回 SPA HTML）。Room 已到 v6（`shot_records.profile_id` 可空 + `embedded_phases_json`）。
- WS 全局单例 = `MachineSessionManager`，自带指数退避重连（连失败 6 次后转 ERROR）。
- 曲线数据缓冲在 `ShotRepository.start(scope)`（AppContainer.appScope，进程级），不在 ViewModel，避免离开页面冻结。
- 萃取只能由机器触发，仪表盘只显示状态；`brewActive` 上升沿自动跳转 `livecurve`。
- 历史横屏图表是独立 NavHost 目标 `history_chart/{shotId}`（支持缩放/平移/拖拽游标）。
- profile 详情取数：`MachineRepository.fetchProfilePhases` 优先 WS 真实曲线；`ProfileRepository.mirrorProfilePhases(id, phases)` 在本地 `phasesJson` 空白时把 WS 真实相位写回库（只读镜像安全补全，不覆盖已有数据）。打开任意详情会 `selectProfile` 把机器当前活跃 profile 设为该曲线（原生 UX）。
- 网络日志已去重：`NetworkLogger`/`ApiDebugLogger` 用 `LinkedHashMap` 哈希缓存跳过连续重复响应 + 跳过 SPA-HTML shell；`ApiDebugLogger` 仅记 `/api/system/`。
- 设计系统基础：`theme/Tokens.kt`（`GagMateExtendedColors` 语义色 + 间距/形状/海拔 token），经 `LocalGagMateColors` 注入；仪表盘已重构为 token 驱动、无障碍优先。组件内禁止硬编码 `Color(0xFF…)`，统一用 `gagMateColors()`。

## 已知待办（优先级 4-6，尚未处理）
- 删除死代码 `GaggiuinoV3Client.kt`（337 行，与 MachineSessionManager/ProtoDecoder 重复）。
- NEW-D：steam 温度/流量仪表硬编码 0f，brew 卡片硬编码 "--"/0。

## 已完成（补记）
- Room 已升到 v4，`MIGRATION_3_4` 归一化 shot 时间戳；统一入口 `TimeUtils.normalizeShotTimestamp()`（≤1e12→×1000，>1e15→÷1000，否则原样）。`fallbackToDestructiveMigration` 仅兜底。
- 仪表盘：连接状态并入 `MachineStatusBadge`（offline/connecting/reconnecting，`stateOffline` token）；无数据显示 `—`（`GaugeView.showDash`/`metricOrDash()`）；"曲线全局设定"改为"当前曲线"图表（`selectedProfilePhases` StateFlow）。
