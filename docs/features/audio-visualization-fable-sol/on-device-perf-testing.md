# FableSol 实机性能测量方法（平板 9018f404 / OPD2515，2026-07-17 实测）

本文件收录 FableSol 专用的实机性能测量手段；通用 adb 调用规范见
`.claude/rules/adb.md`（本内容不属于通用规则，故放在功能目录）。

## 锁屏 cpuset 限制（测量前必读）

平板设有安全锁屏，`wm dismiss-keyguard` 无效，adb 无法也不得代输凭据。
锁屏时应用进程进入 `/background` cpuset（仅 0-5 号核），CPU 阶段耗时约为
前台的 2~6 倍——**锁屏下测得的绝对帧耗时不代表前台性能**，只能做同条件
A/B 相对比较。正式帧率数据必须解锁后在前台采集。

## 离屏基准（debug 构建内置，锁屏可用）

debug 构建含 `FableSolBenchmarkReceiver`（`app/src/debug/`，EGL pbuffer
离屏跑完整四 pass 管线，demo_mode 固定种子确定性驱动）：

```powershell
& $adb -s 9018f404 shell am broadcast --include-stopped-packages `
    -a com.ywwynm.everythingdone.FABLESOL_BENCH `
    -n com.ywwynm.everythingdone/.views.recording.fablesol.FableSolBenchmarkReceiver `
    --ei frames 600 --ei fps 120 --ez hdr true --es dump <tag>
```

结果看 `logcat -s FableSolBench:*`（drain/physics/build/draw/finish/frame 的
p50/p95/p99）；`--es dump <tag>` 把 present 输出按帧落盘到
`/sdcard/Android/data/com.ywwynm.everythingdone/files/fablesol_frames_<tag>/`，
供跨构建逐字节像素对照（优化改动的零视觉变化证据）。

## 前台正式测量

1. 打开录音 Dialog（debug 构建自动挂 `FableSolPerformanceMonitor`），电脑
   ffplay 播放 `E:\projects\audioVisualizerSimulator\assets` 音频驱动真实链路；
2. 读 `files/debug_logs/fablesol_frame_perf.log`：`glFrame` 为 GL 线程分阶段
   （build/physics/compose/steps 等），`frame` 为窗口 FrameMetrics；实际渲染
   帧率可由相邻 `glFrame` 摘要时间戳换算（每 120 帧一条）；
3. release 构建无 debug 日志，用 atrace 数水面 SurfaceView 的 setBuffer 事件：
   `atrace -t 6 gfx -b 30000`，统计层名含 `SurfaceView[...DetailActivity](BLAST)`
   的 setBuffer 条数除以时间跨度即呈现帧率（2026-07-17 release 实测 120.4fps）。

## 已知结论（D163）

debuggable 构建约半数 CPU 周期是 ART 运行时开销（Mutex/CAS/JNI 蹦床/JIT
反复编译），`pm compile` 对 debuggable 应用无效（ART 忽略 AOT 代码）；平板
上 debug 构建水面约 30fps 为该运行时税所致，release 同场景满帧 120.4fps。
