# CLAUDE.md — 工作说明

本文件为开发会话的**全局约定**。进入工作前先读本文件，再按标准文件路径定位上下文。

## 标准文件路径

| 用途 | 路径 |
|------|------|
| 会话交接说明（新会话先读） | [HANDOFF.md](HANDOFF.md) |
| 方案设计文档（**权威**，功能与技术已定稿） | [DESIGN.md](DESIGN.md) |
| 开发需求（分层索引 + 补充说明） | [docs/requirements.md](docs/requirements.md) |
| 技术方案（选型/约束/依赖，含待验证项） | [docs/technical.md](docs/technical.md) |
| 编码规范（Kotlin/Compose/Room/Git） | [docs/coding-standard.md](docs/coding-standard.md) |
| 执行步骤（里程碑推进计划，带验收点） | [docs/execution-steps.md](docs/execution-steps.md) |
| 开发日志索引 | [devlog/INDEX.md](devlog/INDEX.md) |
| 开发日志（按日） | [devlog/](devlog/YYYY-MM-DD.md) |

## 工作流程约定

1. **开工先读状态**：读 `devlog/INDEX.md` 与最新一日日志，了解进行到哪、下一步做什么；再读 `docs/execution-steps.md` 对应里程碑。
2. **按里程碑推进**：严格按 `docs/execution-steps.md` 的先后顺序。**一个里程碑一个里程碑地做**，每步结束都必须：构建通过、可运行、可人工验收该步功能，再进入下一步。不一口气做大量改动。
3. **小步验证**：遇到未知（依赖版本、权限、API 行为），先做一个最小验证（build 一下 / 单独试一个功能），确认后再扩展；出错时先定位根因，不靠重试。
4. **自动记日志**：每完成或新增一件可交付的事，立即在该日日志文件追加一条（移到「已完成」，并更新「待办」）；日志是**追加式**，不重写历史。新增日志文件后在 `devlog/INDEX.md` 加一行索引。
5. **文档随开发更新**：技术决策 / 依赖 / 待验证项有变化，同步更新 `docs/technical.md`；需求理解变化先对照 `DESIGN.md`，冲突时以 `DESIGN.md` 为准。
6. **遵守编码规范**：见 [docs/coding-standard.md](docs/coding-standard.md)（包结构、Kotlin/Compose/Room 约定、UI 文案中文、代码注释英文等）。

## 关键技术要点（备忘，细节见 docs/technical.md）

- 技术栈：Kotlin + Compose + Media3 ExoPlayer **1.5.1** + Room（KSP）。
- 双 Player 同步模型：主 Player（视频/原声，主时钟）+ 配音 Player（audio-only）。
- Media3 **1.5.1 不含 `OverlayMediaSource`**，不依赖；用 Clipping/Looping/Silence/Merging/Progressive。
- 全部数据存 App 私有目录（filesDir）与 Room 数据库，不写系统公共媒体库。
- UI 文案中文字符串资源；代码与注释英文。

## 当前状态快照

- 项目：跳舞练习视频播放器（见 [DESIGN.md](DESIGN.md)）。
- 环境：Gradle 9.5 / AGP 9.3.2 / Kotlin 2.2.10 / compileSdk 37 / minSdk 26。
- 进度：**里程碑 1–7 已验收通过**（里程碑 7 形态：B 站缓存 m4s 导入，URL 直链下载已按用户要求移除）。下一步：里程碑 8（打磨）。
