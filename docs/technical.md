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

- **最终形态：B 站缓存导入（m4s 合并）**。B 站等站点的页面链接非直链，App 内不做站点解析；用户用 B 站客户端下载视频，得到 video.m4s + audio.m4s 两个 DASH 分离文件，在 App 内导入合并。
- `M4sVideoImporter` 用 `MediaExtractor` + `MediaMuxer` 按显示时间交错重封装为单个标准 mp4（纯 remux 不转码、无画质损失）；B 站会在 ftyp box 前塞若干混淆字节，拷贝时扫描头部剥掉。文件用 `OpenMultipleDocuments`（SAF）选取，轨道按 MIME 识别不依赖文件名，支持仅视频单文件。入库为 `sourceType=LOCAL` 的普通本地视频，与其余功能同权。
- **注意**：Android 11+ 第三方 App 无法读取其他 App 的 `Android/data`，m4s 文件需先经 PC（USB/adb）复制到 Download 等可访问目录再选取。
- ~~URL 直链下载（元信息探测 + HttpURLConnection 下载）~~：曾实现并构建通过，2026-09-05 经用户确认**移除**——m4s 导入已覆盖实际需求（B 站为唯一网络来源，且客户端下载画质更高）。`NetworkVideoImporter`、URL 对话框、`INTERNET` 权限与 `usesCleartextTraffic` 均已删除；实现思路记录在 devlog/2026-09-05，如未来需要可照此重做。

## 打磨（里程碑 8 实现决策）

- **缩略图**：`ThumbnailGenerator` 用 `MediaMetadataRetriever.getFrameAtTime`（中间帧、OPTION_CLOSEST_SYNC，参数为微秒）取帧，等比缩到最长边 ≤640、JPEG 80 存 `filesDir/thumbnails/`；导入（本地 + m4s）时生成,生成失败不影响导入（留 null）。存量视频由 `HomeViewModel` 启动时后台补齐。主页不引 Coil，用 `produceState` + `BitmapFactory`（两遍解码 inSampleSize）自行加载。
- **⚠ 更新 videos 行必须用 `@Update`，禁止 REPLACE insert**：Room 开外键时 SQLite 的 `INSERT OR REPLACE` 是先 DELETE 再 INSERT，会级联删光该视频的 segments/recording_chunks。
- **删除级联**：删行前先收集文件路径（视频/缩略图/全部 chunk 音频），先删 DB 行（Room 级联删子表）再删文件；文件删失败只留孤儿，由清扫兜底。删除前有中文确认对话框。
- **生命周期**：`PlayerScreen` 把 `LifecycleEventObserver` 注册在 **Activity** 的 lifecycle（不能用 `LocalLifecycleOwner` = NavBackStackEntry，否则按返回键也触发 ON_STOP、与 onCleared 竞态）；ON_STOP 且 `!activity.isChangingConfigurations`（旋转 / 进页面横屏锁定也会触发 ON_STOP，必须排除）时调 `onHostStopped()`：录音中则按手动停止语义保存入库，随后暂停主 Player（配音 Player 由 ticker 镜像跟随）。回前台不自动播放，位置保留。按返回键退出时录音仍是丢弃语义（onCleared）。
- **孤儿录音清扫**：`HomeViewModel` 启动时（进程内仅一次）扫 `filesDir/recordings/`，删除不被任何 chunk 行引用且 `lastModified` 距今 >60s 的文件（年龄阈值防与 stopRecording 异步落库竞态）。
- **本地导入文件名**：目标文件加时间戳前缀 `<ts>_<displayName>`，防同名二次导入覆盖旧视频源文件。
- **横屏视频进页体验优化**（2026-09-08 用户反馈）：原实现 `init` 里 `prepare()` 后立即 `play()`，而横竖屏判定依赖播放器回调 `onVideoSizeChanged`（晚于开播），导致横屏视频先在竖屏播几帧、再锁横屏触发 Activity 重建。改为：进页前用 `MediaMetadataRetriever` 读文件元数据提前判定方向（`isLandscapeFile` 读 VIDEO_WIDTH/HEIGHT/ROTATION），方向已知后写入 `uiState.isLandscapeVideo/orientationKnown`；`init` 只 `prepare()` 不 `play()`，播放由屏幕在「方向已知 + (横屏视频需等当前已转到横屏 | 竖屏视频无等待)」时通过 `startPlaybackWhenOriented(currentOrientation)` 一次性触发。横屏视频因此在 Activity 转到横屏后才开播,不再有竖屏播出画面。`onHostStopped` 消耗守卫防回前台自动播放。
- **播放界面视觉重做**（2026-09-08，参考 B 站播放器风格，纯视觉、不改功能）：顶/底栏改渐变遮罩(`Brush.verticalGradient`)；时间轴进度条改自绘 `Canvas`（`TimelineSlider`：细圆角轨 3dp + 粉色已播放段 `TimelineAccent #FB7299` + 圆形滑块 + 分段刻度），**不用** Material `Slider` 的自定义 track/thumb（M3 1.4.0 该重载为实验 API，本工程 `@OptIn` 在 AGP 9 内置 Kotlin 下未生效）；时间显示移至进度条上方左对齐，录制计时留在按钮行；音量/配音区改半透明圆角面板。色调常量集中在 `PlayerScreen.kt` 顶部（`TimelineAccent`/`ScrimTop`/`ScrimBottom`/`ControlIdle`），不影响全局主题。**系统栏内缩**：遮罩必须 edge-to-edge（不能在控件层根 `Box` 上加 `systemBarsPadding()`，否则底部露黑带）；顶栏用 `windowInsetsPadding(WindowInsets.statusBars)`；底栏**不能**直接套 `navigationBars` 内边距——那会把控件顶到 16:9 视频的黑边之上、控件下露出黑带，令控件「浮」在屏幕中部；改为 `WindowInsets.navigationBars.getBottom()` 取实际值、**封顶 16dp** 后再作为底部 padding，使控件贴近屏幕下沿（参考图样式）同时不被手势条压住。
- **音量与「节拍」控件**（2026-09-08 用户要求）：视频声跟随**系统媒体音量**——主 `player.volume = 1f`，删除了 App 内的「原声」滑条（`originalVolume`/`setOriginalVolume` 一并移除）。原「配音」回放在界面改称**「节拍」**（内部字段仍名 `dubbing*`，功能不变）：主页标记文案「有节拍」；播放页控件为底栏按钮行的「节拍」按钮（分段、倍速之间），点开是含开关 + 音量滑条的面板。`dubbingVolume` 语义改为**相对视频音量的倍率**，范围 `0..2`（默认 1，>1 可放大偏小的节拍声），常量 `PlayerViewModel.MAX_DUBBING_VOLUME`、`PlayerScreen.MAX_BEAT_VOLUME`。
- **统一蓝色系**（2026-09-08 用户要求）：`Color.kt`/`Theme.kt` 全局改为蓝系（`Blue80/Blue40` + `BlueGrey` + `Sky`），**关闭动态取色**（`dynamicColor` 会用壁纸色覆盖，已移除该参数）；播放页覆盖层的强调色统一为 `AccentBlue (#4A90E2)`（时间轴已播放段、循环/节拍/分段编辑激活态），原粉 `#FB7299` 不再使用。**例外**：录音指示保留红色 `#FF5252`（`PlayerScreen.RecordingAccent`，用户要求与蓝色激活态区分）；`colorScheme.error`（删除类语义色）与白/黑/遮罩色保留。
- **应用图标与开屏**（2026-09-08 用户要求，素材 `icon.jpg` / `figure.jpg` 在工程根目录）：
  - 图标：自适应图标（`mipmap-anydpi/ic_launcher.xml`），前景 `ic_launcher_foreground.png`（各密度，白色舞蹈小人抠图、缩放居中于安全区），背景色 `values/colors.xml` 的 `ic_launcher_background (#A4D1F0)`；另有各密度位图 `ic_launcher(_round).png` 兼容旧启动器。旧的模板矢量图标（`ic_launcher_background/foreground.xml`）与 webp 已删。
  - 开屏：**不引第三方库**（未缓存 `androidx.core:core-splashscreen`，避免拉依赖风险），纯主题实现——`drawable/splash_background.xml`（米黄 `#FDF5D8` + 居中 `splash_logo`）设为 `android:windowBackground`；`values-v31/themes.xml` 复用 `windowSplashScreenBackground`/`windowSplashScreenAnimatedIcon`（Android 12+ 系统 SplashScreen）。logo 为 `figure.jpg` 裁出的猫，各密度 `drawable-*/splash_logo.png`。

## 权限

- `RECORD_AUDIO`（录音）
- `READ_MEDIA_VISUAL_USER_SELECTED` / `READ_EXTERNAL_STORAGE`（选本地视频，视系统文件选择器策略）
- ~~`INTERNET`（网络视频元信息与下载）~~ 已随 URL 直链下载功能移除（2026-09-05）

## 待定 / 待验证项

- ~~系统文件选择器在当前 targetSdk 37 + minSdk 26 下读视频 URI 的具体权限策略。~~ 已验证：`PickVisualMedia`（照片选择器）无需任何存储权限即可读所选 URI。
- ~~Room + KSP 与 Kotlin 2.2.10 / AGP 9.3.2 的插件版本兼容性。~~ 已验证：KSP 需用 **2.3.x**（当前 2.3.11）；旧的 Kotlin 前缀版本（2.2.10-2.0.2）与 AGP 9 内置 Kotlin 冲突（报 `kotlin.sourceSets DSL not allowed`）。

## 与 DESIGN.md 的差异（用户后续变更）

- **横竖屏策略（覆盖 DESIGN 2.9 / 5.7）**：2026-09-03 用户要求——识别视频源方向，横屏视频自动将 Activity 锁为横屏播放（画面相对竖屏机身顺时针旋转 90°），UI 随之横屏布局；竖屏视频保持方向跟随设备。实现：`Player.Listener.onVideoSizeChanged`（用 `unappliedRotationDegrees` 修正宽高）→ `activity.requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE / UNSPECIFIED`，退出播放页恢复 UNSPECIFIED。
- **倍速控件形态（细化 DESIGN 2.3）**：右下角单按钮显示当前倍速（如「1.0×」），点击弹出菜单选择 0.5×–1.0×。
- **视频来源扩展（覆盖 DESIGN 2.1 网络视频）**：2026-09-05 用户决定——不做 URL 元信息 + 直链下载（已实现后按用户要求移除），改为**导入 B 站客户端缓存的 m4s 文件对**（合并为普通本地视频），详见「网络视频」一节。

## 开发环境备忘（已踩坑记录）

- 本机 JDK：`D:\Android Studio\jbr`（OpenJDK 25），CLI 构建需先设 `JAVA_HOME`；`gradle.properties` 已加 `org.gradle.java.installations.paths`。
- adb 完整路径：`D:\AndroidSDK\platform-tools\adb.exe`（不在系统 PATH）。
- **模拟器测试视频入库（MediaStore）**：adb push 的媒体文件 `is_pending=1`，照片选择器会隐藏它。触发索引：`adb shell content call --uri content://media/ --method scan_file --arg <路径>`（或 `--method scan_volume --arg external_primary`）。`cmd media scan` 在 API 36 上无此服务；`MEDIA_SCANNER_SCAN_FILE` 广播自 Android 10 起被系统忽略。
- SQLite 保留字：`Segment.index` 列在 Room `@Query` 中必须写成 `"index"`（带引号）。
- Media3 `AspectRatioFrameLayout.RESIZE_MODE_FIT` 等属 `@UnstableApi`，使用处需 `@OptIn(UnstableApi::class)`。
- **模拟器音频两大坑**：① 播放无声且 logcat 刷 `ranchu pcm_writei failed: I/O error` = 模拟器音频 HAL 整体失效，冷启动（Cold Boot Now）恢复；② 录音文件无声 = 模拟器没接宿主机麦克风，需开 Extended Controls → Microphone → 「Virtual microphone uses host audio input」。
