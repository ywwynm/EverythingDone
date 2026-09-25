# 碰一碰动画模拟器

在 PC 上调碰一碰的发送与接收动画。画面由真机录下的**整段会话**重放，与手机同源：同一份着色器原文、
同一套材料模型、同一份参数文件。

- 发送从打开分享界面开始：记事抬起成卡片、黑色罩层与底部面板、等待被碰时天线处的扩散圈、碰上后压下投影、
  卡片化成颗粒流进天线（天线处的波纹随之收缩）、「已发送」停留、流回拼成卡片、关闭时落回。
- 接收从进入接收状态开始：页面罩层与屏幕边框粒子、碰上后边框换成来件颜色、列表插入与让位、颗粒从天线与
  屏幕四边流进空位、拼好后留出空洞、退出。

**2026-09-25 起模拟器里是第三版动画**（整团滑行与光尘带，设计见
[../design-2026-09-25-particle-motion.md](../design-2026-09-25-particle-motion.md)），Android 还是上一版，
尚未移植。环境变量 `TAP_FLOW` 切换终点模型：缺省 `field` 是第三版；`legacy` 是上一版的终点牵引（接收的
边框这时仍画成新的光尘带）；`dialog` 只按弹窗消散朝天线方向走。

## 运行

Python 用 conda 环境 `everythingdone`（NumPy、SciPy、Pillow、ModernGL、PySide6、Matplotlib），在本目录执行：

```powershell
& C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe -X utf8 viewer.py
```

- 左上选会话（`scenes/` 下的四段：三星、魅族各一段接收、一段发送）。「对照参数文件」打开后左边是参数文件里
  的值、右边是当前改动，同一种子、同一时刻。
- 「随机记事颜色」把收发的记事换成随机的纯色或渐变（应用的 10 色调色板、8 个渐变方向，每张卡片各自
  随机）：详情页、卡片、面板图标、粒子快照、碰上后光尘带的颜色一起换，卡片里的图片保持原样；
  「原色」恢复素材录下时的颜色。命令行的 `look.py`、`export.py` 用 `--recolor <种子>`。
- 时间轴下方是各阶段的起点；「片段」选一段后跳到它的开头，播放时只在这一段里循环（前后各多放一点）。
- 右侧是 `shared/tap-transfer/particles.properties` 的全部参数，只显示当前会话用得到的几组。改动的代价：
  - 终点一组、接收时冒出的颗粒、光尘带、交接与流入时长：重算粒子轨迹（接收时连同冒出的颗粒与交接一起，
    约 0.5–0.8 秒）；
  - 接收的罩层、流入时长：重算舞台（几毫秒；光尘带按时间求值，不在舞台里推进）；
  - 其余（发送的各种时长、缓动、罩层、扩散圈、天线光）：只换时间线，立即生效。

  算过的轨迹与舞台按参数缓存，来回改不重算。与参数文件不同的格子有橙框；参数组合违反
  `TapParticleParams` 的校验时，下方给出原因，画面不更新，也不能写回。
- 「写回参数文件」只改动过的那几行（保留注释、顺序与原来的写法），重新编译 debug 包即在手机上生效：
  Android 端的 `TapParticleParams` 读的就是这一份。
- 「边框推进」：接收舞台按设备刷新率逐帧推进，拖尾长度与帧间隔有关（`trail_s / dt`，夹在 1–6 之间），
  三星与魅族都是 120 Hz。
- 在画面上点一下可以把天线挪到那里。接收完全等价；发送的卡片不会像真机那样为天线让位，只作流向参考。
- 「导出视频」出 1 倍与 0.5 倍两段、全分辨率：选了片段只导出那一段，打开对照时导出左右并排的对照视频，
  写到 `out/`。
- 空格播放／暂停，← → 逐帧（1/60 秒），Home 回到开头（或当前段的开头）；「换一种效果」换粒子的随机种子，
  「素材里的种子」换回录下时的种子。

命令行：

```powershell
# 联系表 / 视频（--params file 为参数文件，scene 为录下时手机上的参数，也可以给一个参数文件的路径）
python -X utf8 export.py --scene scenes/samsung-receive --sheet out/sheet.png --times 2 4 6.5 7.5 9
python -X utf8 export.py --scene scenes/samsung-send --video out/send.mp4 --width 720
python -X utf8 export.py --scene scenes/meizu-receive --video out/inflow.mp4 --start 5.5 --end 8 --rate 0.5
# 两组参数左右并排
python -X utf8 export.py --scene scenes/samsung-send --video out/ab.mp4 --params file --compare <另一份参数文件>
# 几段会话同一时刻按网格并排（这里是录下的接收 + 两种合成场景）
python -X utf8 export.py --scene scenes/samsung-receive --grid scenes/samsung-receive-one-full scenes/samsung-receive-pinned-2 --video out/grid.mp4 --start 3.3 --end 8.9 --rate 0.5 --scale 0.5
# 整段会话与真机录屏对齐，输出同步并排视频与逐时刻差异
python -X utf8 compare_session.py --scene scenes/samsung-receive --video out/device/receive-R5CW20BLNKL.mp4 --out out/compare-samsung-receive
# 现行版本（真机录屏）与参数文件里的新动画并排：跳过对齐，用上面量出的偏移
python -X utf8 compare_session.py --scene scenes/samsung-send --video out/device/send-R5CW20BLNKL.mp4 --out out/newlook-samsung-send --params file --offset 1.3 --captions 现行版本 新动画
# 看画面：联系表与轨迹图
python -X utf8 look.py --scene scenes/samsung-send --times 4.6 4.9 5.2 --crop 0 600 1440 2000 --out out/look/x.png
python -X utf8 paths.py --scene scenes/meizu-send --out out/look/paths.png --at 0.5 0.75
# 只看粒子飞行那一段（会话里的 flight-<n>/），与录屏按覆盖曲线逐帧对照
python -X utf8 compare_device.py --scene scenes/meizu-receive/flight-1 --video <录屏.mp4> --out out/compare
# 改了终点整形算法之后重新生成跨语言对照数据
python -X utf8 make_sink_fixture.py
```

## 文件

| 文件 | 内容 |
| --- | --- |
| `sim_model.py` | 参数读写与校验、飞行素材加载、建材（带释放起点）、终点整形 |
| `sim_renderer.py` | 粒子层（GPU）：预算 240 步轨迹并存 61 份采样、按进度取帧、按手机的方式累积与 resolve |
| `sim_stage.py` | 接收舞台：`PerimeterParticles`、`FlowNoise`、`RoundRectPerimeter`、罩层与那圈光的逐帧推进（上一版） |
| `sim_dust.py` | 第三版的光尘带：屏幕四周的微片，传输时贴着边缘持续向内扩散，状态是时间的闭式函数；流入开始后被接手的照常运动、其余向外退去 |
| `sim_emerge.py` | 接收进度期间的那一层：预计落点、代理卡片（代表色圆角矩形）的倒放 `ProxyFlight`，两层共用的物理参数 `receive_params` |
| `sim_list.py` | 首页列表的瀑布流布局：插在置顶记事之后、逐张放进较短的一列、通栏放在两列之后；预计落点与合成场景共用 |
| `make_layout_scenes.py` | 从录下的接收会话合成其他列表状态（一普通、一通栏、三条、一个置顶、两个置顶），写成 `scenes/<设备>-receive-<场景>/` |
| `sim_handoff.py` | 接收的交接：拼卡那一层的逐片淡入（与代理那层交叉淡化）、扩散进来的光尘就近接手流向四边的颗粒 |
| `sim_recolor.py` | 给会话里的记事换颜色（应用的调色板，纯色或 8 个方向的渐变），预览窗口的「随机记事颜色」用它 |
| `sim_timeline.py` | 会话的时间线：录下的外部时刻 + 参数给的时长与缓动 → 每一时刻各层的状态 |
| `sim_layers.py` | 页面、罩层（带空洞）、面板、卡片与投影、扩散圈、光、边框粒子的绘制与合成 |
| `sim_session.py` | `SessionPlayer`：按手机的层次把以上几层与粒子层合成一帧；`make_flight` 建一次粒子飞行（材料、渲染器、冒出的颗粒与交接） |
| `viewer.py` | 桌面预览与调参 |
| `export.py` | 联系表、视频、左右对照视频、多段会话网格并排的视频（`--grid`） |
| `compare_session.py` | 整段会话与真机录屏对齐、逐时刻量化差异、输出同步并排视频 |
| `compare_device.py` | 只对粒子飞行那一段：按覆盖曲线对齐并逐帧量化 |
| `look.py` | 看画面：指定会话、若干时刻、裁剪区域，出全分辨率联系表 |
| `paths.py` | 轨迹诊断图：抽样颗粒的路径（被吸入 / 途中消散 / 流向边框三色），可叠加某一时刻的位置 |
| `make_sink_fixture.py`、`fixtures/sink-shape.txt` | 终点整形的跨语言对照数据，Kotlin 端 `ParticleSinkCrossLanguageTest` 读它 |
| `scenes/<会话>/` | 见下节 |
| `device/` | 真机驱动：`capture.py` 一条命令抓一套，其余是它用到的 adb 工具与清理脚本 |

## 会话素材

debug 包里，`tap_transfer_debug.xml` 的 `dump_fixtures` 为真时，每次打开分享界面、每次进入接收状态都会把
整段会话写到应用外部私有目录的 `tap-fixtures/<时刻>-<send|receive>-session/`（`TapSessionRecorder`）：

- `session.json`：设备、屏幕、密度、本机「粒子大小 / 流动精细度 / 颜色精度」、当时的参数，以及按时间排列的
  事件（界面打开、罩层与面板的动画、抬起与投影、传输阶段、碰上与接管、列表插入与逐帧的卡片位置、舞台的
  各个动作、列表下移量、关闭或退出……），时间单位毫秒，0 是会话开始；
- 截图：发送的 `page`（分享界面盖上之前的窗口）、`card-<n>`（卡片）、`panel-<n>`（面板，按变化节流截取）；
  接收的 `page-enter` / `page-before` / `page-slots` / `page-done`（隐去舞台与按钮的列表）、`pull-<n>`（按钮与
  提示）。截图用离屏重绘，不闪屏；
- `flight-<n>/`：这次会话里的粒子飞行（`TapFixtureDump`）：`scene.json`（几何、天线、边框、种子）、
  `snapshot.png`（交给粒子层的快照）、`background*.png`（粒子层底下的窗口画面）。

`scenes/` 里的截图含设备上的真实列表内容，只留本地（`.gitignore` 排除），`session.json` 与 `scene.json` 入库。
`session.json` 开头加一个 `title` 字段作为界面上的名字。

### 合成的场景

录下的接收只有「收到两条、没有置顶」。其他列表状态由 `make_layout_scenes.py` 从录下的两段合成（要先有
`samsung-receive`、`meizu-receive` 两个目录的截图）：

```bash
python -X utf8 make_layout_scenes.py                      # 全部 10 段
python -X utf8 make_layout_scenes.py --only samsung-receive-three
```

五种场景：`one-normal`（收到一条普通宽度的记事）、`one-full`（一条通栏记事）、`three`（三条）、`pinned-1` /
`pinned-2`（列表里有一条 / 两条置顶记事，收到两条）。合成的目录是生成的产物，整个不入库（`.gitignore`），
换一台电脑要先有录下的两段，再跑一遍脚本。时间线与原会话相同，只换列表与来件；做法见设计文档
「合成的场景」一节。合成场景的 `session.json` 比真机素材多两项：`stage-touched` 的 `spans` / `aspects`（握手里
发送方告知的是否通栏与高宽比）、第一帧列表的 `pinned`（置顶记事的 id）。

## 抓新素材

```bash
cd device
SERIAL=<序列号> python -X utf8 capture.py <拉回的目录> [top,plain]
```

它写调试开关（`simulate`、`dump_fixtures`、`simulate_samples`）→ 录一次接收、一次详情页发送（录屏在
`out/device/`）→ 拉回素材并逐个比 md5 → 把本轮收到的样例移入回收站（三星用选择模式批量删，魅族逐条从详情页
删）→ 删掉调试开关文件与设备上的素材。设备须已解锁、已装当前的 debug 包；清理出错时不猜测，停下来提示人工
核对。拉回来之后把会话目录改名放进 `scenes/`。驱动时的注意事项见 `.claude/rules/adb.md`。

## 与手机的对应关系

粒子层逐项与 Android 相同：

- 建材：GPT6 桌面模拟器的共同模型（`dialog-particle-dismiss/tuning-0909-gpt6/unified_model.py`）；碰一碰给定
  释放起点时的两处不同（第一个局部起点移到起点、延迟为 0，局部权重至少 0.90）按 `ParticleReleaseTopology`
  补上；「粒子大小」按素材里记下的档位给格子下限。
- 终点：`ParticleSink.shape` / `target` 的逐行移植，单精度计算，跨语言单测逐位核对。
- 积分：`shared/particle-dismiss/step.comp` 原文，打开 `SINK`，按 `ParticleReverseShader.integration` 插入
  「超过寿命不再积分」；240 Hz，压力每 4 步一次，迭代次数取素材里的「流动精细度」（0 即不做压力求解）。
- 回放：61 份采样（每 4 步一份）。发送按进度 × 60 在相邻两份之间线性插值（`history.comp` 的 fraction），
  接收取 `round((1 − 已播时间 / 时长) × 60)`、不插值（`ParticleReversePlan.frame`）。
- 绘制：`material.vert` / `material.frag` 的默认路径与 `resolve.frag`；颜色精度「标准」时前景与累积目标都是
  SRGB8_ALPHA8，否则线性 RGBA16F；粒子层按 ONE / ONE_MINUS_SRC_ALPHA 叠到 sRGB 画面上（SurfaceFlinger 的方式）。

粒子层之外：

- 接收舞台逐行移植 `TapReceiveStageView`、`PerimeterParticles`、`FlowNoise`、`RoundRectPerimeter`：
  `java.util.Random` 的 48 位线性同余、同样的填充顺序与取数顺序，罩层空洞与那圈光的透明度按 Kotlin 的整数截断。
- 发送舞台按 `TapShareOverlay` / `TapShareCardStage` 的编排：抬起的逐张错开、淡入、投影高度、罩层与面板的
  插值器、扩散圈的相位、天线处的高斯光点。
- 时间线：外部决定的时刻（打开、碰上、各传输阶段、列表插入、首帧上屏、关闭或退出）取录下的值，应用自己控制
  的时长与缓动取参数；改了时长，后面的时刻跟着移（例如流出变长，「已发送」与流回随之推后）。
- 合成顺序：发送是页面 → 黑色罩层 → 面板 → 舞台（投影、卡片、条数、扩散圈、光）→ 粒子层；接收是页面（列表）
  → 舞台（罩层与空洞、那圈光、边框粒子）→ 按钮与提示 → 粒子层。

近似或不模拟的：

- 发送开头溢出菜单的淡出没有重放（三星那段前 0.2 秒的差异就是它）；接收开头新建按钮的收起没有重放。
- 面板、按钮与提示按截图重放，两次截图之间是静止的；列表让位按逐帧记下的卡片位置从截图里搬运，
  卡片投影按四周各留 3 dp 近似。
- 卡片投影是环境光 + 点光源两层的近似（光源高 600 dp、半径 800 dp），不是 Skia 的阴影算法。
- 边框的拖尾与设备的真实帧间隔有关，模拟器按「边框推进」给的固定刷新率算。
- 设备上的性能宏（`MEDIUMP_*`、`PEEL_FIELD_HALF`、硬件三线性等）与各家 GPU 的精度差异。

## 验证记录

2026-09-25，三星 SM-S9180（本机粒子设置为默认：流动精细度 100、颜色精度「高精度」）与魅族 20 Infinity
（粒子大小 1.6 dp、流动精细度 0、颜色精度「标准」）各一段接收、一段发送，同一份会话记录、同一种子，与同时
录下的录屏整屏逐帧对照（`compare_session.py`，去掉状态栏与导航条，小图口径）：

| 会话 | 整屏平均差 | 差异最大的时段与原因 |
| --- | --- | --- |
| 三星接收 | 2.78 | 流入后段（7.8–8.0 秒，流入 7.0–8.3 秒）：细颗粒的高频细节与录屏压缩 |
| 三星发送 | 3.04 | 开头 0–0.2 秒：溢出菜单淡出，模拟器没有重放 |
| 魅族接收 | 3.13 | 退出时（10.8 秒）列表收回的几像素位移；流入中后段（7.7–7.9 秒） |
| 魅族发送 | 2.59 | 关闭后（12.2–12.3 秒）罩层、面板退场与卡片落回那几帧 |

同步并排视频在 `out/compare-<会话>/side-by-side.mp4`，逐时刻差异在同目录的 `diff.csv`。

更早的一轮（2026-09-24，魅族，只有粒子飞行）：接收时录屏每一帧都唯一对应模拟器的同一份采样，中段逐像素差
14–20，与把模拟器的帧按录屏码率压一遍得到的 15–25 同量级；发送流出段与流回段分开对齐后，覆盖曲线均方差
分别为 0.00001、0.00002。
