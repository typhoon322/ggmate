# GagMate 项目长期记忆

## 工作方式约定（用户明确要求）
- **所有修改必须同步更新文档**（根目录 `AGENTS.md` Rule 1）：改动完成后，在汇报前更新对应文档并更新其顶部日期——`README.md`（用户功能）/`GAGMATE_REFERENCE.md`（架构·协议·DB·组件）/`GagMate_DesignSystem.md`（UI·token·布局）/`CODE_REVIEW.md`（已知问题）。
- **每次改完代码都必须打包验证**：改动任何源码后，运行 `./gradlew :app:assembleDebug`（离线可加 `--offline`）确认 BUILD SUCCESSFUL，再向用户汇报完成。不要用 "UP-TO-DATE" 当作没验证。
- **非代码类内容用中文回复**，代码/标识符/技术术语保持英文。
- 根目录 `AGENTS.md` = 项目根级协作规则（人可读，非 WorkBuddy 自动读取）。

## 架构要点速记
- WS 全局单例 = `MachineSessionManager`，自带指数退避重连（连失败 6 次后转 ERROR）。
- 曲线数据缓冲在 `ShotRepository.start(scope)`（AppContainer.appScope，进程级），不在 ViewModel，避免离开页面冻结。
- 萃取只能由机器触发，仪表盘只显示状态；`brewActive` 上升沿自动跳转 `livecurve`。
- 历史横屏图表是独立 NavHost 目标 `history_chart/{shotId}`（支持缩放/平移/拖拽游标）。
- 设计系统基础：`theme/Tokens.kt`（`GagMateExtendedColors` 语义色 + 间距/形状/海拔 token），经 `LocalGagMateColors` 注入；仪表盘已重构为 token 驱动、无障碍优先。组件内禁止硬编码 `Color(0xFF…)`，统一用 `gagMateColors()`。

## 已知待办（优先级 4-6，尚未处理）
- 删除死代码 `GaggiuinoV3Client.kt`（337 行，与 MachineSessionManager/ProtoDecoder 重复）。
- NEW-D：steam 温度/流量仪表硬编码 0f，brew 卡片硬编码 "--"/0。

## 已完成（补记）
- Room 已升到 v4，`MIGRATION_3_4` 归一化 shot 时间戳；统一入口 `TimeUtils.normalizeShotTimestamp()`（≤1e12→×1000，>1e15→÷1000，否则原样）。`fallbackToDestructiveMigration` 仅兜底。
- 仪表盘：连接状态并入 `MachineStatusBadge`（offline/connecting/reconnecting，`stateOffline` token）；无数据显示 `—`（`GaugeView.showDash`/`metricOrDash()`）；"曲线全局设定"改为"当前曲线"图表（`selectedProfilePhases` StateFlow）。
