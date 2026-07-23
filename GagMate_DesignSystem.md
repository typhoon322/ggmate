# GagMate — Dashboard Redesign & Design System (v1)

**Designer:** UI Designer · **Date:** 2026-07-23 · **Status:** Implemented · `assembleDebug` ✅

This document is the developer handoff for the redesigned Dashboard (the first screen
of the full-app redesign) and the foundational design system it introduces. The goal:
a beautiful, habit-friendly, accessible UI that coffee enthusiasts understand at a glance.

---

## 1. What changed

| Area | Before | After |
|------|--------|-------|
| Design tokens | Ad-hoc `4/8/12/16/72.dp`, hardcoded `Color(0xFF…)` in components | Centralsied tokens in `theme/Tokens.kt` (spacing, shape, elevation, semantic colours) |
| Status colours | Hardcoded inside `StatusIndicator` / `MachineStatusBadge` | Driven by semantic `GagMateExtendedColors` (light + dark) |
| Debug leak | A `DBG: t=… P=…` text shown to users | Removed; debug logging kept behind `BuildConfig.DEBUG` only |
| Dashboard layout | Loose `LazyColumn`, inconsistent spacing, 72dp tail spacer | Token-driven sections, `16.dp` rhythm, hero card, loading skeleton |
| Accessibility | Gauges had no screen-reader label | Merged `contentDescription` on gauges, badges, metric tiles; 48dp touch targets |

---

## 2. Design Foundations (tokens)

All tokens live in **`app/src/main/java/com/gagmate/app/theme/Tokens.kt`**.

### Spacing (4dp base)
`xs=4` · `sm=8` · `md=12` · `lg=16` · `xl=24` · `xxl=32` (dp)
Use `GagMateSpacing.lg` everywhere a card gap or outer padding is needed.

### Shape
`sm=8` · `md=12` · `lg=16` · `xl=24` · `pill=999` (dp, used as `RoundedCornerShape(GagMateShape.*)`)

### Elevation (tonal)
`none=0` · `sm=1` · `md=3` · `lg=6` (dp) — applied via `Surface(tonalElevation = …)`

### Semantic colours (light + dark) — `GagMateExtendedColors`
| Role | Light | Dark | Use |
|------|-------|------|-----|
| `success` | `#2E7D32` | `#81C784` | Connected dot, idle state |
| `warning` | `#B45309` | `#F0B36B` | Flushing button |
| `info` | `#1565C0` | `#90CAF9` | Informational |
| `stateBrewing` | `#C2410C` | `#FF8A65` | Brew badge |
| `stateHeating` | `#B45309` | `#FFB74D` | Heating badge |
| `stateIdle` | `#2E7D32` | `#81C784` | Idle badge |
| `stateSteam` | `#455A64` | `#90A4AE` | Steam badge |
| `stateOffline` | — | — | Offline / connecting / reconnecting badge |
| `gaugeTrack` / `gaugeTemperature` / `gaugePressure` / `gaugeFlow` / `gaugeSteam` | — | — | Gauge fills |
| `divider` / `disabled` | — | — | Hairlines, skeletons |

All badge colours meet **WCAG AA** (≥4.5:1 for normal text) with their `onState` text.
Access the set with `gagMateColors()` inside any composable, or `LocalGagMateColors.current`.

### Typography
Kept the existing `GagMateTypography` (M3 scale). Gauge values use `displayLarge` scaled to the gauge size.

---

## 3. Component Library (now token-driven & accessible)

| Component | File | Notes |
|-----------|------|-------|
| `StatusIndicator` | `ui/components/StatusIndicator.kt` | Pulsing connection dot; colour from `success`/`disabled` tokens |
| `MachineStatusBadge` | `ui/components/StatusIndicator.kt` | Localised label — now also covers **offline / connecting / reconnecting** (new `stateOffline` token) in addition to Brewing/Heating/Idle/Steam. Connection state is merged into this single badge (no separate connection row). |
| `GaugeView` | `ui/components/GaugeView.kt` | Arc gauge; track + fill from tokens; merged `contentDescription`. New `showDash` flag renders `—` instead of `0` when there is no data (e.g. disconnected). |
| `CurveChart` | `ui/components/CurveChart.kt` | **Shared curve renderer** (single `Canvas` source of truth, replaces duplicated drawing in `BrewChartView`/`ShotChartFullScreen`). `CurveSeries(color, axis, dashed, valueAt)` + `ChartAxis{LEFT,RIGHT}`; `generateProfileChartPoints()` applies per-phase variation easing (EASE_OUT/EASE_IN_OUT/…). Colours use shared `ChartColor*` constants. |

These are reused by the dashboard and are ready for other screens (profiles, history).

**Dashboard gauge change (2026-07-23):** the machine readout gauges were moved from the bottom of the dashboard to directly under the hero card, and now show **only the current machine temperature and pressure** (using `directSensorT` / `directSensorP`). The previously hard-coded-0 steam-temperature and pump-flow gauges were removed.

---

## 4. Redesigned Dashboard (`ui/dashboard/DashboardScreen.kt`)

> **布局演进 (2026-07-20)**：移除了官方 TopAppBar / 独立标题控件，refresh & settings 动作内联到 Hero 卡片头部；顶部的独立"未连接"提示行/浮窗已删除，连接状态并入 Hero 的 `MachineStatusBadge`；底部冗余的"机器状态"块已删除。

Sections, top to bottom:
1. **Hero status card** — active profile name (长名 `maxLines=2 + Ellipsis`)、machine-state badge（含 未连接/连接中/重连中/萃取中/空闲）、setpoint、uptime；refresh & settings 图标按钮内联在卡片头部。
2. **Machine controls card** — Flush / Tare（48dp 触达区），setpoint stepper（±1°C），液量 stepper（− / +，标签为 **设定液量**；已移除多余的 "T" 按钮）。
3. **Live brew chart** — 仅在 `chartData` 流式推送时显示。
4. **Metric gauges (2×2)** — Boiler T、Steam T、Pressure、Flow Rate；未连接时显示 `—` 而非 `0`（`showDash`）。
5. **Profile curve card（当前曲线）** — 渲染当前选中 profile 的压力/流量目标曲线（`ProfileCurveChart` / `BrewChartView` 委托 `CurveChart`，设定曲线按阶段 variation 缓动）；无 phases 时降级为 温度/液量/时间 数值行，完全无数据时显示"无曲线"。
6. **Active brew card** — 萃取中出现；volume 显示实时秤重。

**States**
- *Connecting / Reconnecting* → 状态并入 Hero `MachineStatusBadge`（连接中/重连中），页面不再闪空白、无独立横幅。
- *Error / offline* → badge 显示"未连接"，各数值/gauge 显示 `—`；不再有独立错误横幅与按钮（配置入口在设置页）。
- *Transient command result* → auto-dismissing `Snackbar`。

**Responsive**
Phone-first `LazyColumn`. On wide screens (tablet/landscape, `maxWidth > 720.dp`) the
column is capped at `720.dp` and centred via `BoxWithConstraints` — no new dependencies.

**Accessibility**
- Gauges, badges, and metric tiles expose merged `contentDescription`s.
- Primary/stepper actions use `defaultMinSize(minHeight = 48.dp)` (≥44dp touch target).
- Honours system font scaling via M3 `MaterialTheme.typography`.

---

## 5. How to extend (new screen)

```kotlin
import com.gagmate.app.theme.GagMateSpacing
import com.gagmate.app.theme.GagMateShape
import com.gagmate.app.theme.gagMateColors

Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(GagMateShape.lg)
) {
    Column(Modifier.padding(GagMateSpacing.lg)) {
        Text("Title", style = MaterialTheme.typography.titleSmall)
        // …use gagMateColors().success / .stateBrewing etc.
    }
}
```

Add new semantic colours to `GagMateExtendedColors` (both light & dark) — never hardcode hex in a component.

---

## 6. Build & verification
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (clean build verified).
- New files: `theme/Tokens.kt`.
- Modified: `theme/Theme.kt` (provides `LocalGagMateColors`), `ui/components/StatusIndicator.kt`,
  `ui/components/GaugeView.kt`, `ui/dashboard/DashboardScreen.kt`, `values/strings.xml`, `values-zh/strings.xml`.

## 7. Known data gaps (not design — wiring)
Per project notes, Steam-T and Flow-Rate gauges currently render `0f` (no live source yet),
and the Active-Brew Time/Pump tiles show `--`. These are data-source TODOs, not visual defects.

## 8. 后续迭代 (2026-07-20)
- **标题固定在状态栏下方、滚动体有界 (2026-07-23)**：上一轮「一刀切清零所有 inset」导致内容穿到系统状态栏里重叠。现改为：每个页面根容器加 `Modifier.statusBarsPadding()`，标题/页眉作为**固定（不滚动）兄弟节点**置于状态栏下方，**滚动容器（`LazyColumn`/滚动 `Column`）作为其下方的独立兄弟** `weight(1f)`，因此滚动内容永远不会越过标题栏。各页实现：
  - 列表页（Profiles / History）：`Scaffold` → `Column(statusBarsPadding)` → 固定 `PageHeader` + `when { 空态 Box(weight1f) | LazyColumn(weight1f) }`。
  - Settings：`Column(statusBarsPadding)` → 固定 `PageHeader` + 滚动 `Column(weight1f, verticalScroll)`。
  - Dashboard：`Column(statusBarsPadding)` → 固定 `HeroStatusCard` + `BoxWithConstraints(weight1f)` 内含 `LazyColumn`。
  - LiveCurve / ProfileDetail / ShotChart 全屏图：根 `Column` 加 `statusBarsPadding()`，标题行固定、内容区 `weight` 填充。
  - `PageHeader` 自身不带 status-bar padding，由使用页的根容器负责留白。
- **移除官方 TopAppBar**：所有页面标题+动作内联到页面内容；修正 `statusBarsPadding()` 只作用于首个可见项，消除顶部空白。
- **连接状态合并进 badge**：删除独立"未连接"行/浮窗；`MachineStatusBadge` 新增 offline/connecting/reconnecting 状态（`stateOffline` token）。
- **无数据显示 `—`**：`GaugeView.showDash` + `metricOrDash()` helper，未连接时不再显示 `0`。
- **当前曲线卡片**：仪表盘用图表展示当前选中 profile 的目标曲线（`MachineSessionManager.selectedProfilePhases: StateFlow<List<BrewPhase>>` 持有 phases）。
- **控制卡**：移除多余 "T" 按钮，液量标签改为"设定液量"；删除底部冗余"机器状态"块。
- 所有改动均 `assembleDebug` → **BUILD SUCCESSFUL**。
