# 技术方案

> 技术细节权威来源：仓库根目录 [DESIGN.md](../DESIGN.md) 第 5 节。
> 本文记录工程层面的技术选型与关键约束，随开发补充落地细节。

## 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 | Kotlin 2.2.10 | |
| UI | Jetpack Compose + Material3 + Compose BOM 2026.02.01 | 单 Activity |
| 导航 | Compose Navigation | Home → Player |
| 播放 | Media3 ExoPlayer **1.5.1**（本机已缓存） | 双 Player（视频 / 配音） |
| 数据库 | Room（KSP 编译） | Video / Segment / RecordingChunk |
| 录音 | MediaRecorder（AAC → .m4a） | 输出 App 私有目录 |
| 构建 | AGP 9.3.2 / Gradle 9.5 | Kotlin Compose 插件已配 |

## 工程环境（已确认）

- compileSdk = targetSdk = **37**，minSdk = **26**
- 命名空间 / applicationId：`com.example.dance`
- AGP 9 新式写法（`compileSdk { version = ... }`、`optimization` 块）正在使用

## 关键依赖（里程碑 1 需接入）

- `androidx.media3:media3-exoplayer:1.5.1`、`media3-ui:1.5.1`、`media3-common:1.5.1`
- Room：`room-runtime` / `room-ktx` / `room-compiler`（**KSP**）
- `androidx.compose.navigation:navigation-compose`
- `androidx.lifecycle:lifecycle-viewmodel-compose`

## 播放模型：双 Player 同步（DESIGN 5.2）

- **主 Player**：视频 + 原声，主时钟。
- **配音 Player**：audio-only，与主 Player 位置对齐。
- 配音源按当前块 `[blockStart, blockEnd)` 构造：ClippingMediaSource 裁片段 + SilenceMediaSource 补静音 + MergingMediaSource 合成 + LoopingMediaSource 循环。
- 同步：侦听主 Player `onPositionDiscontinuity` / 位置回卷 → 配音 Player 在块循环起点 `seekTo(0)` 并 `play()`。
- 倍速与音量：两 Player 倍速设为相同值；主 `volume`=原声滑条，配音 `volume`=配音滑条；配音开关 = 停/释放配音 Player 或置其 volume=0。

> 注意（已核对源码）：Media3 **1.5.1 不含 `OverlayMediaSource`**（更晚版本才有），不依赖它。1.5.1 含 Clipping/Looping/Silence/Merging/Progressive。

### 配音源构造的落地细节（里程碑 6 实现决策）

- **整视频顺序播放列表，而非按块 Merge+Loop**：`MergingMediaSource` 只能把多个源**起点对齐**并行合并，无法把片段放到任意偏移处，故 DESIGN 5.2 的「按绝对时间对齐合成一轨」实际用**顺序播放列表**实现（`ExoPlayer.setMediaSources`）：按片段绝对起点排序，空隙插 `SilenceMediaSource`，片段音频用 `MediaItem.ClippingConfiguration` 裁剪（`DefaultMediaSourceFactory` 自动套 ClippingMediaSource）。语义与 DESIGN 相同（缺块静音、片段锚定绝对时间）。
- **配音轨覆盖整视频**而非仅当前块：配音时间轴偏移 == 主 Player 位置，换块 / 取消块**无需重建**配音源；仅 chunk 集合或时长变化时重建。不用 `LoopingMediaSource`：块循环由主 Player 回卷驱动。
- **同步**：主 Player `onPositionDiscontinuity`（seek / 块循环回卷 / 整视频循环）即时把配音 Player seek 到主位置（自维护「播放列表项起点表」把绝对位置映射为 项index+项内偏移）；200ms ticker 镜像播放/暂停并纠偏（漂移 > 300ms 才回 seek，两次纠偏至少间隔 500ms 防缓冲抖动）。倍速在每个设置点同时写两个 Player。
- 倒放手势 / 录音期间配音 Player 暂停（录音时避免旧配音串进麦克风）。

## 数据模型（Room）

Video(id, title, filePath, durationMs, thumbnailPath?, sourceType, createdAt)
Segment(id, videoId FK, index, startMs, endMs)
RecordingChunk(id, videoId FK, audioPath, blockStartMs, blockEndMs, recordedMs, createdAt)

- 段由分割点切分：段 i = [split[i-1], split[i])；段 0 起点为 0。
- 录音音轨是**概念整体**：不物理拼接文件，回放时按绝对时间组合音源，缺块补静音。
- 块内 `[recordedMs, blockEnd)` 剩余区间回放时由 SilenceMediaSource 补静音。
- **录音片段替换语义（里程碑 5 实现决策）**：新录片段入库时，删除**所有与其块范围相交**的旧片段（含同块重录），同事务完成，旧音频文件随后删除。这样分割点变动后重录也不会留下重叠片段，保证配音时间轴无重叠。
- 录音实际时长 `recordedMs` 用 `MediaMetadataRetriever` 读回（等价于 DESIGN 5.3 所述 MediaExtractor 方案，更简），并 clamp 到块长；录音文件在 `filesDir/recordings/`。

## 网络视频（里程碑 7 实现决策）

- **元信息探测**：`MediaMetadataRetriever.setDataSource(url, headers)` 远程读标题（无内嵌标题时取 URL 文件名）、时长、中间帧封面。**仅支持渐进式直链**（mp4 等）；HLS/DASH 清单不支持（探测即失败，UI 提示检查链接）。封面帧远程提取依赖服务器 Range 支持，失败则卡片无封面（可选项）。
- **下载**：`HttpURLConnection` 流式拷到 `filesDir/videos/`（原样保存，不转码），进度按整百分比节流回调（Content-Length 未知时为不定进度）；失败删除半成品文件。下载完成后用本地文件重读时长（比远程值可靠），封面帧存 `filesDir/thumbnails/` 并写 `thumbnailPath`，入库 `sourceType=NETWORK_DOWNLOADED`，此后与本地视频完全同权（分段/录音/配音）。
- **不引入新依赖**：用平台 `HttpURLConnection`，不加 OkHttp。
- **Manifest**：`INTERNET` 权限；`android:usesCleartextTraffic="true"`——练习用 App 需兼容 http 直链，且模拟器测试用宿主机 HTTP 服务（`python -m http.server` + `http://10.0.2.2:8000/...`）必须放行明文流量。

## 权限

- `RECORD_AUDIO`（录音）
- `READ_MEDIA_VISUAL_USER_SELECTED` / `READ_EXTERNAL_STORAGE`（选本地视频，视系统文件选择器策略）
- `INTERNET`（网络视频元信息与下载）

## 待定 / 待验证项

- ~~系统文件选择器在当前 targetSdk 37 + minSdk 26 下读视频 URI 的具体权限策略。~~ 已验证：`PickVisualMedia`（照片选择器）无需任何存储权限即可读所选 URI。
- ~~Room + KSP 与 Kotlin 2.2.10 / AGP 9.3.2 的插件版本兼容性。~~ 已验证：KSP 需用 **2.3.x**（当前 2.3.11）；旧的 Kotlin 前缀版本（2.2.10-2.0.2）与 AGP 9 内置 Kotlin 冲突（报 `kotlin.sourceSets DSL not allowed`）。

## 与 DESIGN.md 的差异（用户后续变更）

- **横竖屏策略（覆盖 DESIGN 2.9 / 5.7）**：2026-09-03 用户要求——识别视频源方向，横屏视频自动将 Activity 锁为横屏播放（画面相对竖屏机身顺时针旋转 90°），UI 随之横屏布局；竖屏视频保持方向跟随设备。实现：`Player.Listener.onVideoSizeChanged`（用 `unappliedRotationDegrees` 修正宽高）→ `activity.requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE / UNSPECIFIED`，退出播放页恢复 UNSPECIFIED。
- **倍速控件形态（细化 DESIGN 2.3）**：右下角单按钮显示当前倍速（如「1.0×」），点击弹出菜单选择 0.5×–1.0×。

## 开发环境备忘（已踩坑记录）

- 本机 JDK：`D:\Android Studio\jbr`（OpenJDK 25），CLI 构建需先设 `JAVA_HOME`；`gradle.properties` 已加 `org.gradle.java.installations.paths`。
- adb 完整路径：`D:\AndroidSDK\platform-tools\adb.exe`（不在系统 PATH）。
- **模拟器测试视频入库（MediaStore）**：adb push 的媒体文件 `is_pending=1`，照片选择器会隐藏它。触发索引：`adb shell content call --uri content://media/ --method scan_file --arg <路径>`（或 `--method scan_volume --arg external_primary`）。`cmd media scan` 在 API 36 上无此服务；`MEDIA_SCANNER_SCAN_FILE` 广播自 Android 10 起被系统忽略。
- SQLite 保留字：`Segment.index` 列在 Room `@Query` 中必须写成 `"index"`（带引号）。
- Media3 `AspectRatioFrameLayout.RESIZE_MODE_FIT` 等属 `@UnstableApi`，使用处需 `@OptIn(UnstableApi::class)`。
- **模拟器音频两大坑**：① 播放无声且 logcat 刷 `ranchu pcm_writei failed: I/O error` = 模拟器音频 HAL 整体失效，冷启动（Cold Boot Now）恢复；② 录音文件无声 = 模拟器没接宿主机麦克风，需开 Extended Controls → Microphone → 「Virtual microphone uses host audio input」。
