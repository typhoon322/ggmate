# GagMate — 项目协作规则 (Project Rules)

> 本文件是本仓库的根级协作规则，适用于所有开发者与 AI 编码助手。修改代码时必须遵守。

---

## Rule 1 — 所有修改必须同步更新文档 (Docs must follow code)

**任何代码改动，都必须同步更新对应文档。** 这是强制要求，不是可选项。

- 改动完成后，在提交/汇报前，先检查并更新以下相关文档：
  - `README.md` — 面向用户的功能说明（新增/变更功能时）。
  - `GAGMATE_REFERENCE.md` — 架构、协议、数据模型、数据库、Repository、组件参考（改动架构/协议/DB/组件时）。
  - `GagMate_DesignSystem.md` — 设计系统与仪表盘布局（改动 UI 布局、design token、组件交互时）。
  - `CODE_REVIEW.md` — 已知问题清单（修复问题或引入新已知问题时）。
- 更新文档时同步更新文档顶部的"文档版本 / Date"日期。
- 若改动引入新的已知问题或修复了旧问题，更新对应文档的"已知问题"章节。
- 判断标准：如果别人只读文档就会对当前代码产生误解，就必须更新文档。

---

## 工程约定速记 (Engineering conventions)

- **每次改完代码都必须打包验证**：运行 `./gradlew :app:assembleDebug`（离线可加 `--offline`），确认 `BUILD SUCCESSFUL` 后再汇报完成。
- **禁止在组件内硬编码颜色** `Color(0xFF…)`：统一用 `gagMateColors()` / `GagMateExtendedColors` 语义色（见 `theme/Tokens.kt`）。
- **间距/圆角/海拔**统一使用 token：`GagMateSpacing.*` / `GagMateShape.*`。
- **字符串**必须双语落地：同时更新 `values/strings.xml` 与 `values-zh/strings.xml`。
- 非代码类沟通用中文；代码、标识符、技术术语保持英文。
