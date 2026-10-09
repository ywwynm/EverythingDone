# 首页列表滚动掉帧：三星 SM-S9180 实测结论（2026-09-26）

数据来源：`collection-report.md`（会话 scratchpad `samsung/run1/`，含逐帧 framestats、三份 atrace、设备端 logcat、
SurfaceFlinger 转储；采集方法与偏离见其第 0 节）。被测对象是设备上现装的调试包（16:11 安装，pid 6064），
没有改任何设备或应用设置，只发竖向手势。关键数字由主代理用独立脚本（`samsung/check_framestats.py`）
逐文件复核，与代理报告一致。

## 一、结论

**滚动开始那一下的掉帧，原因是滑动完成动画（粒子）的按下预热。** 手指落在任何一张卡片上（`ACTION_DOWN`），
应用就对这张卡做一次整卡离屏渲染并在 RenderThread 上把像素读回来；快照若在手指越过滑动阈值之前完成，
还会立刻建动画层、建材、起 GL 线程。代价与手指下那张卡的可见面积成正比。导入的两张卡片是列表里最大的：
通栏视频卡在列表顶部时占 1440×2334 像素，起手在它上面时读回要 17–23 ms，一次达到 189 ms。

导入的记事本身带来的第二项代价是**两张动图封面**（视频派生 GIF 与动态照片派生 GIF）：静止时应用持续按约
24.5 fps 出帧，两条 `glide-animation` 线程合计占用每秒 370–693 ms CPU，RenderThread 每秒上传 24 张 1320×742
的纹理（单张最长 6 ms），封面已经滚出屏幕时也照常上传。它们在每次手势开始后 60–110 ms 才被停掉，正好与按下
预热的突发负载重叠；但对照组证明它们**单独不产生**起手那一帧的掉帧。

另一项与导入无关、但每次滚动都在的事实：**应用被粒子 SurfaceView 的 60 Hz 投票压在 60 fps**
（SurfaceFlinger `FrameRateOverrides` 对本应用 uid 为 60 Hz），而面板在触摸后切到 120 Hz；全部 4670 帧的帧间隔
都是 16.67 ms，面板处于 120 Hz 的那段里耗时超过 8.33 ms 的帧会晚一个 120 Hz 周期上屏（一次惯性滚动里 25 帧）。
这个 SurfaceView 在应用启动时就由新建动画的空闲预算建好了。

静态分析里排在第二、三位的「绑定期测量」「停下时 Glide 集中回调」在这台设备、这份列表上**没有发生**：
三份 trace 里 `RV OnBindView`、`RV OnLayout`、`inflate` 都是 0 次，`RV Scroll` 最长 2.4 ms；两张封面都是动图，
不走静态封面的烘焙路径。列表只有 6 张卡，滚动时没有任何卡片重绑。

## 二、证据

### 1. 起手点决定有没有掉帧（framestats，干净组，不开 trace）

| 起手点 | 手势数 | 起手 300 ms 内 total > 16.7 ms 的帧 | 那一帧的主因 |
|---|---|---|---|
| 两列之间的空隙（不在任何卡片上，set7_gap） | 4 | 0 | 最大 startDelay 3.4 ms |
| 小标题卡 630×655（set3、set8） | 8 | 6 | startDelay 13–32 ms（主线程晚开帧） |
| 7 图卡 630×1318（set2） | 4 | 4 | startDelay 12–23 ms |
| 通栏视频卡 1320 宽（set1） | 4 | 3 | startDelay 19.5 ms；syncWait 15.7 ms；issue 11.4 ms |

空隙起手时 logcat 没有 `抓图路径：硬件离屏`，也没有 `HWUI: CFMS:: SetUp`；落在卡片上的 27 次手势每次都有一条抓图记录。

### 2. 两种代价（atrace，pid 6064）

**只做快照**（快照在越过阈值前没完成，随后被取消；每次一个 `DrawFrames -1`）：

| 手势下的卡 | 离屏渲染尺寸 | RenderThread `copyImageInto` | 对应的帧 |
|---|---|---|---|
| 通栏视频卡（set5_tvideo，4 次） | 1440×2020–2333 | 23.2 / **189.4** / 17.3 / 18.9 ms | syncWait 14.5 / **180.9** / 2.7 / 13.3 ms，帧长 29.3 / **187.3** / 16.0 / 18.7 ms |
| 小标题卡（set4_tsmall，4 次） | 743×786–807 | 4.4 / 4.5 / 3.4 / 5.3 ms | — |

读回由 `ParticleSnapshotCopy` 线程发起（`RenderProxy#copyHWBitmapInto readback`），实际在 RenderThread 上执行
（`copyImageInto`），主线程的下一帧在 sync 处等它。

**快照完成后建层**（27 次卡片起手里 16 次；小卡快速起手 4 次里 2–3 次，7 图卡 4 次全部，按住 270 ms 再滑 3 次全部）：

- 再做 7 个 `DrawFrames -1`（前景控件各一套 ImageReader + HardwareRenderer），主线程 7 次 `postAndWait` 合计 10–12 ms；
- DOWN 后 300 ms 内的 CPU（按住再滑 g1，ms）：ParticleModelWa（5 条原生建材线程）102、ParticleGl 116、RenderThread 105、
  ParticleMateria 52、Jit 44、HeapTaskDaemon 67、glide-animation 58、main 49；
- 结果是 DOWN 后 33–67 ms 的那一帧主线程晚开 12–32 ms，帧长 20–40 ms。16 次里没有例外。

原生建材线程在 trace 里的名字是 `ParticleModelWa`（5 个 tid，`particle_material.cpp` 的静态池），材料线程
`ParticleMateria`（2 个），快照拷贝 `ParticleSnapsho`。

### 3. 动图封面的常驻负载（静止对照 + trace 空闲段）

| 项 | 数值 |
|---|---|
| 静止 5 s 渲染帧数 | 136 / 135（约 24.5 fps），每帧 total p50 4.0 / 2.8 ms，无超时 |
| `Texture upload 1320x742` | 23.5–24.7 次/s，四种列表状态都一样，封面滚出屏幕时也一样；单次最长 6.2 ms |
| `Texture upload 630x472`（7 图卡） | 该卡可见时 23.9–24.2 次/s |
| `glide-animation` 两条线程 | 整份 19–20 s 的 trace 合计 6.7–9.3 s CPU；每次手势前 1 s 内 370–693 ms |
| 手势里停掉的时刻 | DRAG 时停：DOWN 后 +63…+112 ms；IDLE 恢复：+1252…+1402 ms（`animated-scroll-control`，只记 thingId=1259） |

### 4. 帧率上限（SurfaceFlinger 转储 + framestats）

- 图层树：`ThingsActivity` 窗口 `requestedFrameRate 0 Hz / NoPreference`；其下 `SurfaceView[…ThingsActivity]`
  `requestedFrameRate 60.00 Hz Default`；`FrameRateOverrides setFrameRate (uid 10193) = 60.00 Hz`。
- 全部 8 组 4670 帧 `FrameInterval` = 16.67 ms（另有 7 帧 8.33、2 帧 33.3）。
- VRRInfo：23 次测量手势开始时面板都在 60 Hz，20 次在开始后 212–966 ms 切到 120 Hz（Touch=1），1.9–2.8 s 后回到 60 Hz。
  处于 120 Hz 的区间里 framestats 的 deadline 变成 8.33 ms，set5_tvideo.g2 有 25 帧 total 8.4–13.4 ms 被判超时。
- logcat 在 `am start` 后 0.1 s 即有 `粒子 Surface 就绪`，随后 `创建页轨迹已提前算好`：SurfaceView 从启动起常驻。

### 5. 没有出现的项

- `RV OnBindView` / `RV OnLayout` / `inflate`：三份 trace 都是 0；`RV Scroll` 311 / 304 / 9 次，最长 2.4 ms；
  `RV Prefetch` 最长 0.9 ms。
- `Davey`、`Choreographer: Skipped frames`：整个采集期间任何进程都没有。GC：logcat 只有启动时一条；trace 里
  `HeapTaskDaemon` 只在建层窗口活跃。
- `glide-source`、`WorkManager`、`MediaMetadataRetriever` 线程：滚动期间没有出现（派生 GIF 早已生成）。
- `alpha caused saveLayer`：5 次、合计 0.04 ms，可忽略。

### 6. 环境观察（与掉帧无关，记录备查）

- 设备上另有一个进程在每次手势后约 3.08 s 对本应用执行 `dumpsys gfxinfo`（48 次，发起进程每次不同、未查明来源），
  偶尔造成 `FastPrintWriter … EPIPE`。它离下一次手势 0.38–0.46 s，附近保留下来的逐帧数据里没有超时帧。
- 已启用的无障碍服务：三星 sreminder 两项、`hello.litiaotiao.app`。
- `Thermal Status: 0`，MemAvailable 3.2 GB。

## 三、这条记事为什么让问题变得明显

1. 通栏视频卡在列表顶部时占满屏幕（804 到 3088 像素），几乎每次起手都落在它上面，每次都付 17–23 ms 的读回，
   偶尔一次 189 ms；导入之前列表里只有 630×380–655 的小卡，读回只要 3–5 ms。
2. 快照小、完成得快，反而更容易在越过阈值前建层（小卡 8 次里 6 次），主线程被延迟 13–32 ms；大卡快速起手多半来不及
   建层，但人手起步比 `input swipe` 慢，按住 270 ms 的用例显示大卡同样会建层并延迟主线程 20–31 ms。
3. 两张动图封面是列表里仅有的动图，静止时持续占着两条解码线程与 RenderThread 上传，起手后前 100 ms 仍在跑。

## 四、可选的改动方向（未裁定）

- 按下预热：抓图与建层推迟到横向位移越过阈值之后，或纵向手势一旦识别就取消尚未提交的读回；`swipeMaterialsOnDown`
  接上真实读取点；建材结果按卡片内容缓存，避免每次起手重建。
- 动图封面：封面滚出屏幕后停掉动画（现在只按滚动状态停）；限制帧率或按屏幕像素缩小派生 GIF；两条 `glide-animation`
  线程的持续负载是导入这两条记事之后新增的常驻开销。
- 帧率：粒子 SurfaceView 空闲时不投 60 Hz（或空闲时移除），让列表滚动跟随面板的 120 Hz。
