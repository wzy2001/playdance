# HANDOFF — 新会话交接（2026-09-05）

> 给下一个开发会话的交接说明。开工顺序：先读 [CLAUDE.md](CLAUDE.md)（全局约定），再读本文件，然后按需查 [devlog/INDEX.md](devlog/INDEX.md) 与 [docs/execution-steps.md](docs/execution-steps.md)。

## 一、项目状态

- 项目：跳舞练习视频播放器（权威规格 [DESIGN.md](DESIGN.md)，**不要改它**；已确认的偏差记录在 [docs/technical.md](docs/technical.md)「与 DESIGN.md 的差异」）。
- **里程碑 1–7 已全部用户验收通过**。用户明确表示：**需要的功能都已实现，剩下的是细节优化**（= 里程碑 8「打磨」）。
- 每次改动后必须 `assembleDebug` 构建通过，功能交由用户在 Android Studio + Pixel_8 模拟器（API 36）人工验收；验收通过才算完成。
- 注意：**本项目目前不是 git 仓库**（无版本历史可回退，删代码前想清楚；被移除功能的实现思路都记在 devlog）。

## 二、已实现功能一览（全部验收通过）

1. 视频库主页：本地导入（照片选择器，无需权限）、**B 站缓存导入**（选 video.m4s + audio.m4s，remux 合并为 mp4）、删除、有配音标记。
2. 播放器（单页合并所有功能）：播放/拖动/倍速 0.5–1.0/循环/沉浸全屏/控件层显隐/横屏视频自动锁横屏（用户变更，见差异节）。
3. 分段：分割点增删 + ±10ms/±100ms 微调；连续段选块；块循环。
4. 手势：长按右 0.5×、长按左 2× 模拟倒放、横滑跳转；录制态屏蔽。
5. 录音：块录音 @1x，MediaRecorder → m4a，重叠块替换语义。
6. 配音回放：双 Player（主视频 + audio-only 配音），整视频顺序播放列表（Clipping 裁剪 + Silence 补隙），discontinuity 即时对齐 + 200ms ticker 纠偏，双音量滑条 + 配音开关，倍速同变。
7. 网络视频**最终形态 = B 站 m4s 导入**；URL 直链下载曾实现，2026-09-05 按用户要求**整体移除**（连同 `INTERNET` 权限）。

## 三、剩余工作：里程碑 8（打磨），逐项做、逐项验收

按 [docs/execution-steps.md](docs/execution-steps.md) 里程碑 8，已知的具体缺口：

- **封面缩略图**：`Video.thumbnailPath` 字段存在但**目前没有任何代码写入**，主页 `VideoCard` 也未显示图片。需要：导入（本地 + m4s）时提取中间帧存 `filesDir/thumbnails/`，主页展示。
- **删除视频不清理文件**：`HomeViewModel.deleteVideo` 只删数据库行（Room 级联删 Segment/Chunk 行），但**视频文件、录音 m4a、缩略图都留在 filesDir**。需要级联删文件。
- **录音中断恢复**：录音中退出/切后台的状态处理未打磨。
- **后台/切前台**恢复播放状态。
- 中文文案与 UI 风格统一走查。
- 小尾巴：`HomeScreen.kt` 有一个未使用 import（`rememberModalBottomSheetState`）；`PlayerViewModel.kt:167-168` 有 `unappliedRotationDegrees` 弃用警告（可继续无视）。

## 四、关键代码地标

- [PlayerViewModel.kt](app/src/main/java/com/example/dance/ui/player/PlayerViewModel.kt) — 全部播放/分段/录音/配音逻辑。**两个已修的坑别再踩**：
  1. 配音时间轴字段必须声明在 init 块**之前**（viewModelScope 是 Main.immediate，init 里的 ticker 协程在构造完成前就跑第一轮，引用类型字段晚声明 = NPE）。
  2. 配音轨重建的判据是 `dubTimelineDurationMs`（配音轨自身构建时长），**不是** UI 的 durationMs（DB 与 ExoPlayer 的 MP4 时长都来自 mvhd、可能恰好相等，会吞掉重建时机）。
  - Logcat tag `PlayerViewModel`：「Dubbing timeline rebuilt: N items」/「no usable chunks」/ 配音 Player 错误，验收出问题先看这个。
- [M4sVideoImporter.kt](app/src/main/java/com/example/dance/util/M4sVideoImporter.kt) — m4s 合并导入（剥 ftyp 前混淆字节 + MediaExtractor/MediaMuxer 交错 remux）。
- [LocalVideoImporter.kt](app/src/main/java/com/example/dance/util/LocalVideoImporter.kt) — 本地导入。
- [HomeViewModel.kt](app/src/main/java/com/example/dance/ui/HomeViewModel.kt) / [HomeScreen.kt](app/src/main/java/com/example/dance/ui/home/HomeScreen.kt) — 主页与两条导入流。
- 数据层：[Entities.kt](app/src/main/java/com/example/dance/data/db/Entities.kt) / [Daos.kt](app/src/main/java/com/example/dance/data/db/Daos.kt) / [VideoRepository.kt](app/src/main/java/com/example/dance/data/VideoRepository.kt)。改表结构必须写 Migration（编码规范）。

## 五、环境与工具备忘（细节见 technical.md「开发环境备忘」）

- CLI 构建：`$env:JAVA_HOME = "D:\Android Studio\jbr"; $env:Path = "$env:JAVA_HOME\bin;" + $env:Path; ./gradlew assembleDebug`（PowerShell 5.1，**没有 `&&`**）。
- adb：`D:\AndroidSDK\platform-tools\adb.exe`（不在 PATH）。拉二进制文件要用 `cmd /c "adb exec-out ... > file"`（PowerShell `>` 会损坏二进制）。
- 应用私有文件：`adb shell run-as com.example.dance`；Room 表名小写复数（`videos`/`segments`/`recording_chunks`），`Segment.index` 在 SQL 里要写 `"index"`。
- 模拟器音频两大坑：播放无声 + logcat 刷 `ranchu pcm_writei failed` → Cold Boot Now；录音无声 → Extended Controls → Microphone → 开「Virtual microphone uses host audio input」。
- 模拟器测试 m4s：`adb push xx.m4s /sdcard/Download/`；测试视频入 MediaStore 需 `content call --method scan_file` 触发索引。
- 用户偏好：改变设备/模拟器状态的操作（冷启动、设置、重录）由**用户自己做**；验收清单用中文给出。
