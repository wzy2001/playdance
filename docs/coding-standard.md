# 编码规范

> 本文件定义本项目代码规范，供所有会话遵守。

## 通用

- **包结构**：按功能分包，与界面结构对应。
  - `com.example.dance.ui` — 界面（Home / Player / theme）
  - `com.example.dance.data` — 数据库（Room entity / dao / database）
  - `com.example.dance.player` — 播放 / 录音 / 配音逻辑
  - `com.example.dance.util` — 通用工具
- **语言**：代码与注释用英文书写；面向用户的 UI 文案（string 资源）用中文。
- **命名**：Kotlin 官方风格（类 PascalCase，函数/变量 camelCase，常量大写+下划线）；数据库实体用单数名词。
- **格式**：4 空格缩进（项目默认），kotlin.code.style=official。

## Kotlin

- 优先 `val`，尽量不可变；用 `data class` 做纯数据模型。
- 协程（Coroutine/Flow）用 `viewModelScope`；`Room` 查询返回 `Flow` 以自动感知数据变化。
- 避免在 Composable 中做耗时/IO；用 ViewModel + StateFlow 管理状态。
- 只对确有其存的约束写注释；不写「见 xxx」「为什么这样是对的」这类给评审看的注释。

## Compose

- 单 Activity，页面用 Compose Navigation，导航路由命名常量。
- 状态上提到 ViewModel（StateFlow），Composable 只读并响应；UI 事件通过回调/VM 方法下发。
- 通用子组件放 `ui/components`。
- 主题统一用 `DanceTheme`；颜色/字体尽量走 MaterialTheme，不硬编码。

## Room / 数据库

- Entity 用 `@Entity`，主键 `id` Long 自增；FK 用 `ForeignKey` 且 `onDelete` 视数据语义决定（Video 删除时级联删除其 Segment / RecordingChunk）。
- 所有 DAO 查询返回 `Flow` 或以 `suspend` 函数暴露。
- 数据库版本号改动须同步迁移逻辑（`Migration`），生产不删库。

## Android / Manifest

- 需要的权限在 manifest 声明并注明用途。
- 所有视频/录音/数据库文件位于 App 私有目录（filesDir），不写入系统公共媒体库。

## Git

- 遵循「小步、可构建」提交：每完成一个里程碑或一个可独立验证的改动，提交一次。
- 提交信息用英文、简洁、描述「做了什么 + 为什么」。
