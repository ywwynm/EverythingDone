# 桌面实时预览

## 固定入口

用户始终使用 **<http://localhost:8666/latest.html>**。默认选择最新已发布到预览的模型，顶部显示版本、更新时间和验收状态；「打开最新版预渲染对照」从同一端口进入对应成片。「切回最新版」可从基准对照返回。

## Android 移植口径（2026-10-05 夜起为最新版）

模型 `harmony-android`（`../android_drift.py`）：Android「飘散」样式在模拟器里的样子。输入按 Android 的规则生成（参考样式按方向与概率、
左上更常是旧钢铁侠一族、风转到消散方向、粒子数按面积、内容强调、背景亮度定加光或 src-over），是 Kotlin 源码的逐项移植，与设备上的正式代码
逐位相同；渲染仍是 `harmony_native.NativeRenderer`（`android_extras()` 接上湍流换算、内容强调与风向），同一输入下与 Android 离屏出帧逐帧一致。
播放按 Android：设备时刻 startSeconds → totalSeconds 压进 1 秒，弹窗暗层按 Android 的曲线淡出；随机播放即返回键关闭（左上 135°），
点击按触点方向；参考复刻仍按原片输入播放华为原生实现。核对脚本与结果见 `docs/features/harmony-particle-android-port/parity/`。

2026-10-06 同步：朝上（竖直 ±35° 以内）更常出现朝上的几段参考及其变体；点击时风力按触点远近取 0.55–1.45 倍，颗粒朝终点偏转
（与流体同口径）；噪声值域改为只算格点单元的角点；粒子数量与大小按 Android 默认取 1 倍（`android_drift.random_model(amount=…, size=…)`
可改）。重启了 8653 后台并重新发布，与设备 1404 组输入逐位相同。

## 华为原生逐项等价实现（2026-10-05 起；同日夜改为对照）

模型 `harmony-native`（`../harmony_native.py`）按镜像宿主机器码逐项实现粒子消蚀；“复刻华为参考”使用 `../native_reference_inputs.json`
里按原片估计的一次动画输入（消蚀格点值、横风、设备比例、扩展尺寸、进度跨度与偏移）。该表在每次建渲染器时重新读取，
**改表无需重启后台**；改 `harmony_native.py` / `desktop_inputs.py` / `live_server.py` 后要重启 8653 后台
（`.claude/launch.json` 的 `dialog-dismiss-renderer`）。随机播放与点击按种子生成横风（±60000）与遮罩随机数，从动画首帧播放到 1.5 秒。

预渲染对照：`../analysis/export_harmony_native_review.py [--scenes ...]`（只重导给定序列，其余保留）。
发布（Git Bash 下以 `/` 开头的参数会被改写，要加 `MSYS_NO_PATHCONV=1`）：

```bash
MSYS_NO_PATHCONV=1 python -X utf8 live/preview_gateway.py --publish-port 8653 --model harmony-native --title '华为原生 · 逐项等价' --status '…' --review '/review/harmony-native/index.html'
```

方法与各段输入的来历见 `../../native-replica-2026-10-04.md`。

固定入口由 `preview_gateway.py` 提供，读取 `latest.json`；目前转发至 8653 渲染服务，默认是 `harmony-origins` 起点与方向核对版。十二段参考起点实际进入同一传播函数；运动和寿命保留华为核心，传播与初期尺寸交接为共同扩展。十二段早期画面已核对，尚未达到视觉一致；原生粒子栅格化后端缺失，使用圆片替代绘制。原生公式 `harmony-core`、Android 同规则基准及较少颗粒版保留为对照。前台每三秒检查发布编号，发现新版本后自动刷新并保留所选序列。所有页面、API 和视频均留在 8666，更新后台端口不改变用户地址。6666 在 Chromium 的受限端口列表中，因此使用 8666。

从本目录的上一级 `tuning-0909-gpt6` 启动固定入口：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 live/preview_gateway.py
```

它是持续运行的前台服务，需保持终端会话运行；后台渲染服务也需要保持运行。`.claude/launch.json` 中 `dialog-dismiss-live` 启动固定入口，`dialog-dismiss-renderer` 启动 8653 后台。入口不创建额外 GPU 上下文，不终止现有预览进程。

### 后续算法发布必须更新同一个入口

1. 修改模型后在后台进程加载新代码，完成实际渲染验证；若需要另用后台端口，仍保持 8666 不变。
2. 生成对应预渲染对照，再发布新配置。下面命令中的模型、标题、验收状态和对照路径必须对应当次实际算法；未验收实验须明确标注。

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 live/preview_gateway.py --publish-port 8653 --model harmony-origins --title '华为核心 · 起点与方向核对' --status '十二段参考输入已接入；传播、卷曲与材质仍在调校' --review '/review/harmony-origins/index.html'
```

发布命令先验证后台模型列表、状态及对照页面，再原子替换配置；不重启固定入口。它不代替视觉验收，也不自动重新加载后台 Python 代码。失败时保留上一份配置；后台失联时明确返回不可用，不悄悄展示旧算法。每次发布生成新编号，旧页面发出的跨版本命令会被拒绝并触发刷新。

验证转发、视频拖动、相同地址切换后台及失败发布保护：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 live/test_gateway.py
```

六项测试使用临时端口与模拟后台，不接触当前 GPU 会话。用户看到的实际播放仍需在固定入口验证。

## 核心公式验证

### 三种播放方式与背景点击

- “复刻华为参考”：恢复预设种子、观测起点数量／位置／启动时刻和参考方向，不受种子锁定影响；左侧原片、右侧所选模型实时计算。左右编号分别表示参考观测与实际模型输入，负时刻表示首帧前预演。预设种子并非恢复出的原片种子，部分位置受遮挡而有误差，此入口目前用于复刻调校，不能声称已经一致。
- “固定种子对照”：使用当前种子框的值，从共同分布重复生成相同的随机起点和方向；没有值时使用素材预设种子。与参考观测输入分开，不要求额外勾选锁定。
- “随机播放”：重新生成随机输入；勾选“锁定种子”时保留该值。背景点击从控件中心到实际点击位置确定方向，自动重放，并显示绿色触点与箭头；左侧原片、控件内部和手机外部区域不触发。

参考预设由 `analysis/export_reference_inputs.py` 从十二段 `scene.json` 及 `reference_initial_conditions.py` 导出到 `analysis/match-cache/takeover-20261004/reference-inputs.json`。它是当前预览依赖，清理缓存时须保留；新启动后台会自动生成。仅修改网页时可先运行该脚本，再刷新固定入口；修改起点或 Python 模型时须重新启动渲染服务。原生公式对照仍不使用显式起点，网页会说明这一限制；起点核对版无需辅助触点即可设置方向。真实触点位置未知。

从 `tuning-0909-gpt6` 运行：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 analysis/check_harmony_core.py
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 analysis/check_harmony_origins.py --out 'E:/projects/EverythingDone/tmp/dialog-takeover-20261004/harmonyos7/origin-review'
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 analysis/export_harmony_core_review.py --model harmony-origins --no-baseline
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 analysis/check_harmony_live.py --model harmony-origins
```

最后一项要求 8653 后台已经运行。全部十二段的在线／离线中间帧须逐字节一致；最终画面仍须目视核对。核心版默认固定 120 Hz 计算步长，实时 1× 播放按 1.17 秒推进；暂停、回退和随机重放共用同一实现。候选数量与当前活动数量分别显示。

`export_harmony_core_review.py --page-only` 只更新页面，保留已生成成片及其身份。每段记录已观察到的差异，原片截断后不再计为有效比较。完整结果见[核心桌面验证](../../desktop-core-validation-2026-10-04.md)。

## 后台渲染与历史入口

从 `tuning-0909-gpt6` 目录运行：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 live/live_server.py 8652
```

- 实时页面：<http://localhost:8652/>。
- 预渲染对照：<http://localhost:8652/review/>。
- 本次核心复刻在确认端口空闲后启动 8653 与 8666；此前进程已退出。早期实验曾因停止进程的自动审批拒绝另开 8653，该记录不表示当前仍有旧服务在运行。
- 页面提供核心公式、Android 同规则基准、只减少粒子的消融、多起点实验及保色材质实验。固定入口默认以 `latest.json` 为准；后台初始选择也为核心版，所有实验的相似度限制须保留。
- 材质四栏对照：<http://localhost:8652/review/material/index.html>。材质实验只改变采样、颜色与寿命，沿用候选的同一释放和运动；不读取逐段观测场。
- 场景首帧用源图合成，不创建 GPU 模型；选择后立即发布。画面使用带请求编号的有限 JPEG 响应，不依赖 MJPEG 下一帧刷新。
- 仅渲染线程创建和释放 GPU 资源；新输入到达后丢弃旧帧。暂停、回退、同输入重放复用正式固定步长积分器。
- 起点核对版及旧候选共用 `reference_initial_conditions.SITES`；原生公式对照只使用固定种子和调用方随机规则，不接收这些起点。随机输入不抽取参考“样本段”，不读取速度表。
- `desktop_inputs.make_renderer()` 是实时与预渲染的共同入口；修改 Python 后重启服务，修改 HTML 后刷新网页。
- 旧 `live_inputs.py` 和 `spec-live.txt` 留作历史记录，当前入口不读取它们。接手前的服务与页面另存于仓库 `tmp/dialog-takeover-20261004/`。

导出全部十二段原片／Android 基准／候选的 120 Hz 对照：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 analysis/export_takeover_review.py
```

附加 `--material-study` 可独立导出包含材质实验的四栏对照，保存于原目录的 `material/` 子目录，不覆盖三栏成片。

附加 `--baseline-recovery` 导出原片／Android 基准／较少颗粒基准／此前保色实验四栏对照，保存于 `recovery/`。它与 `--material-study` 互斥，可和 `--pages-only` 合用。这组保留为历史回归对照。

附加 `--pages-only` 可从现有报告更新网页和 `reference-limits.json`，无需 GPU，不重渲染视频，也不改写原成片的生成身份。新 2／3 的有效参考仅到约 0.895／0.752；实时标题与预渲染提示均标出截断，之后的冻结末帧不用于评价相似度。导出脚本的页面逻辑更新后，现有报告中的源码身份仍表示原导出时的版本。

验证独立端口的场景首帧、快速切换、重放、模型切换、在线／离线 JPEG 一致性及无效输入：

```powershell
& 'C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe' -X utf8 live/test_preview.py
```

测试先在独立子进程生成候选与材质实验的离线帧，退出该进程后再启动独立端口的实时服务，核对 JPEG 完全一致。测试结束时关闭子进程，不改变用户正在使用的实时会话。没有调用 ADB，也不修改 Android 共享资源。

2026-10-04 的七项测试曾完整通过；随后系统显存接近满载时，独立进程仍在 `wglCreateContextAttribsARB not found` 处失败，故进程隔离不能视为已解决此环境问题。失败输出现在完整保留，不会跳过。已运行的 8653 服务仍能渲染，并与导出的两个无损 0.50 帧编码逐字节一致；复核脚本为仓库 `tmp/dialog-takeover-20261004/verify_material_live.py`。
