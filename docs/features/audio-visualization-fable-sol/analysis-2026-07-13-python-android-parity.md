# Python 模拟器与 Android FableSol 当前差异分析

> 本文记录 Python 提交 `44ad68bd…` 的实施前基线。用户随后决定保留 Python 320dp 宽度，
> 其余差异已完成首轮回同步；实现结果见 D84～D85 与 2026-07-13 会话记录。未完成的双端
> 确定性截图夹具和桌面系统级 HDR 输出见同功能待办。

## 分析范围

- Android 迁移基线：`f9ad7215c06d5197127362f1654deaf5b0ec1cce`。
- Android 当前版本：`c03b4f7115a11a4818f0df1ecd939cbde8a7cd1a`。
- Python 模拟器当前版本：`44ad68bd29e63994b3a03967b0a2e69606bd2f26`。
- Python 当前提交与 Android 迁移提交的作者时间和提交主题相同，属于同一轮双端同步基线；此后
  Python 没有继续提交。
- Android 在迁移基线后与 FableSol 直接相关的主要提交只有两次：
  - `8eb23c04…`：迁移到统一 GLES 渲染器，并加入新的逐像素材质能力；
  - `c03b4f7…`：加入 FP16/scRGB HDR，并收敛表层光学、颜色策略与亮度行为。
- 本文结论来自提交历史、当前源码、功能决策和测试的静态对照。尚未使用同一组确定性
  `FeatureFrame` 做逐帧双端截图，因此视觉影响是有源码依据的判断，不是像素级实测结论。

## 结论摘要

1. 两端没有从根本上变成两套波面。九层语义、25 行连续曲面、216 点/120Hz 波场、定向谱、
   波包、Gerstner 横纵位移、俯仰软压缩以及大部分音频映射仍然同源。
2. 当前最大差距不是分析器或宏观波动方程，而是 Android 已从 QPainter/Canvas 语义转向
   GLES 的逐顶点、逐像素材质与 HDR 输出；Python 仍停留在 CPU QPainter 路径。
3. Android 当前还收窄了表面反射、薄峰透射和波冠轻纱，取消固定偏色与珍珠/猫爪效果，
   并增加双深度散射、镜面抗锯齿、全局 1/f、风梳微法线、朝阳 SSS 和解析光晕。
4. 除渲染器外，横滚范围、边界重建节奏、最大渲染列数、音频采样率、麦克风启动抑制和录音
   状态亮度也会形成可见或可感知差异。Android 280dp 与 Python 320dp 的宽度差异已决定保留，
   不再作为待修复项。
5. 不建议在 QPainter 中重新近似 Android 的新着色器。最短的一致性路线是保留 QPainter
   作为历史/诊断后端，为 Python 增加 ModernGL 后端并直接消费 Android 的共享 GLSL。

## 两端当前调用链

| 阶段 | Python 模拟器 | Android 当前实现 |
|---|---|---|
| 输入 | 麦克风、音频文件、离线模式、GUI 滑杆 | 录音对话框中的实时麦克风与重力传感器 |
| 分析 | `FeatureMapper` 前的 Python DSP/IPC | `FableSolRealtimeAnalyzer`，录音线程投递帧 |
| 映射 | `FeatureMapper` | `FableSolFeatureMapper` |
| 物理 | `Simulation` + `ContinuousSurfacePrototype` | `FableSolSimulation` + `FableSolContinuousSurface` |
| 渲染 | `VisCanvas` + QPainter | `WaveVisualizerFableSolGl` → 独立 GL 线程 → `FableSolGlRenderer` |
| 输出 | 8 位 SDR 桌面窗口/录屏 | RGBA8 SDR；符合条件时为 FP16 线性 scRGB HDR |
| 回退 | 连续水面与旧九条水带可切换 | GLES 失败时回退旧 Canvas，红色天空用于诊断 |

## 仍然基本一致的部分

### 音频特征和映射合同

- 两端继续围绕相同的响度、频谱质心、低中高频、onset、段落、速度、俯仰等语义工作。
- 核心波包注入、Hero 传播、画外出生、俯仰软压缩和颜色阴影策略仍来自迁移时的同一实现。
- Android 迁移后的映射主差异是删除 `spawnGust`；不是重新定义整套声学响应。
- Android 实时采样率为 44.1kHz，Python 默认是 48kHz；Android 另有录音启动阶段的低频
  自适应抑制。对普通稳定输入影响有限，但启动前 0～4.5 秒和频带边缘不会严格一致。

### 宏观物理与连续曲面

- 仍是 9 个颜色/深度锚层、25 行连续曲面、216 个横向物理点和 120Hz 固定物理子步。
- 定向谱、波包、平流、沉降、Gerstner 横向位移与纵向起伏主体没有被后续 Android 提交重写。
- Android 新增的主要运动差异是全局粉红噪声呼吸；另有边界剖面 30Hz、每帧最多重建 5 层等
  摊销策略。静止或慢变时宏观几何应接近，快速倾斜过程存在短暂数值差异。

## 当前实质差异

### 1. 表层光学参数已经分叉

| 项目 | Python 当前默认值/公式 | Android 当前默认值/公式 | 直接影响 |
|---|---|---|---|
| 表面反射带 | `(2.2 + (10.5 + 4×crest)×facing)×depth`，近层最大约 16.7dp | `(1.2 + (5.8 + 2.4×crest)×facing)×depth`，近层最大约 9.4dp | Android 更窄、更少雾状白带 |
| 薄峰透射厚度 | `(3 + 20×signal)×sqrt(signal)`，最大约 23dp | `(1.6 + 9.4×signal)×sqrt(signal)`，最大约 11dp | Android 的透光更克制 |
| `thin_glow_gain` | 0.55 | 0.38 | Android 更暗 |
| `crest_veil_strength` | 0.32 | 0.14 | Android 波冠轻纱更弱 |
| `body_light_strength` | 0.36 | 0.36 | 当前已重新一致 |
| `pearl_shift_deg` | 6° | 0° | Android 不再周期摆色 |
| `hue_temp_deg` | 5°，渲染时乘 0.6 | 0° | Android 不再固定偏暖/偏冷 |

Android 当前值不是 GLES 迁移误差，而是用户在 Debug `202607130749` 基线上的明确收敛结果；
同步 Python 时应把它们视为当前产品参数。

### 2. 颜色身份策略不同

- Python 高光仍叠加约 ±6° 的珍珠周期摆色和默认约 +3° 的固定冷暖偏移；薄峰透光还向
  165° 青绿色偏移，珍珠斑另有派生色边缘。
- Android 当前将反射、透射和轻纱统一为“Thing 身份色 → 中性白”的 OKLab 轴；深水和
  次表层只调整明度、彩度，不主动更换色相。
- 结果是 Android 更稳定地保留记事颜色，Python 更容易出现固定青色、绿色或周期性珠光偏色。

### 3. Python 仍保留 Android 已删除的效果

- Python 仍会生成和绘制珍珠斑：`_draw_pearls`。
- Python 映射仍会调用 `spawn_gust`，模拟仍维护 `gusts`，画布仍绘制猫爪暗纹：
  `_draw_gusts`。
- Android 已按用户裁决整体删除珍珠和猫爪，不应在新的 Python Android-parity 模式中保留。
  如有研究价值，可只留在旧 QPainter/legacy 模式。

### 4. 闪点与流光的几何不同

- Python 闪点上限为近两层各 3 个、其余近层各 2 个，最小间隔 46dp；使用直线方向的
  `QRadialGradient` 椭圆。流光也以直切椭圆表达，在曲面附近可能露出水体外侧。
- Android 近两层各 4 个、第 2～5 层各 3 个，最小间隔 34dp；闪点和流光都沿曲线分段，
  且只绘制到水体内侧。
- Android 还把核心闪点与解析外围光晕分开：当前光晕强度 0.10、长度 1.18 倍、厚度 2.25 倍、
  alpha 系数 0.18，并在 shader 中使用更快的径向衰减。
- 因此 Android 通常呈现更多、更窄、贴合曲面的亮片；Python 是更少、更宽的直椭圆光斑。

### 5. Android 独有的逐像素材质

这些能力都没有可靠的纯 QPainter 等价物：

- **双深度散射，0.21**：由 Thing 色派生 deep/subsurface 两组同色相颜色，并按浪峰收拢、
  视角和光向混合。
- **镜面足迹抗锯齿，1.0**：按每个光学波的实际采样足迹带限高频，并把被滤除的坡度/曲率方差
  卷回高光宽度，减少倾斜和运动时闪烁。
- **全局 1/f 呼吸，1.0**：同一慢呼吸调制环境波幅，另以独立种子调制稀有波包间隔和新生
  闪点频率；Python 只有局部光学 alpha 的粉红噪声调制。
- **风梳微法线，0.16**：三倍频解析噪声导数沿水面流向形成纹理，并按距离/采样足迹带限。
- **朝阳次表面散射，0.16，falloff 6**：按光向、浪峰收拢和深度形成局部透亮。
- **解析光晕，0.10**：与镜面亮核解耦，避免把高光整体做宽。
- **逐像素抖动和连续插值**：天空、水体、光学边缘不再依赖 8 位 QPainter 渐变，banding 和
  边缘观感自然不同。

`absorption_gain=0.35` 虽仍在 Android 参数中，但当前正常 GLES 主路径没有读取它，仅旧 Canvas
诊断回退继续使用；它不应被误列为当前 Android 主画面的新增效果。

### 6. HDR 输出是能力差异，不只是参数差异

- Android 在 API 34+ 且显示能力允许时使用浮点 EGL、RGBA16F 线性场景缓冲和 linear scRGB；
  任一环节不可用就自动回退 RGBA8 SDR。
- 只有录音状态允许局部超白，准备/停止状态保持 SDR；启停过渡约 0.36 秒。
- 超白只分配给近中层闪点核心、受光浪峰和少量薄峰透射，环境、体光、光晕、流光、羽化与
  远层仍保持 SDR。闪点核心峰值由近至中层约为 2.0、1.9、1.75、1.5、1.35、1.2。
- Python 目前是 8 位 SDR QPainter，没有亮度余量、录音状态门控或 FP16 中间结果。即使颜色数值
  相同，也无法在普通 SDR 桌面窗口中复现 Android 的真实峰值亮度。

### 7. 容器和倾斜行为不同

- Android 实际水体容器为 280dp×420dp，并把测得宽度传给模拟；Python 固定为
  320dp×420dp。宽度参与体积守恒、墙面、倾斜几何、事件位置和可见波长数量，所以不只是窗口
  缩放差异。
- 用户已明确决定 Python 保持 320dp 宽度。后续实现必须让 Python 的物理和渲染继续在 320dp
  容器内自洽，不改成 280dp，也不使用内部 280dp 加外部拉伸。双端测试按归一化横向坐标比较，
  接受宽度导致的墙面位置和可见波长数量差异。
- Android 横滚由完整 `atan2` 输入连续展开，支持跨 ±180° 和完整 360° 翻转；Python GUI/文档
  仍以 ±90° 为主要范围。
- Android 使用投影安全采样窗口，并把渲染列固定在最多 120；Python 用 QPainter 启发式采样，
  没有相同的固定列预算。

### 8. 帧调度和实时性能不同

- Android：Choreographer 锁 60Hz、独立 GL HandlerThread、输入 latest-value 合并、离散事件有界，
  物理子步仍为固定 120Hz；当前设备已经验证持续倾斜时稳定。
- Python：Qt 定时器、QPainter 和交互大体处于同一桌面进程路径。已有基准中连续水面平放约
  16.6ms/帧，倾斜约 24.1ms/帧，不能稳定保证 60fps。
- 丢帧不会改变固定子步的理论方程，但会改变视觉采样、输入延迟和光学闪烁，因此属于实际观感
  差异。ModernGL 后端同时也是一致性和性能需求。

### 9. 产品生命周期与模拟器能力不同

- Android 在准备/停止状态仍以约 0.16 presentation alpha 展示水面，录音时过渡到 1.0；Python
  没有相同状态机。
- Android 背景来自任意 Thing 纯色/渐变和多种方向；Python 主要使用固定调色板与有限背景选项。
- Python 的文件输入、离线导出、参数面板和旧/连续水面切换是有价值的研究工具，不需要为了
  Android 一致性删除；应把 Android parity 做成明确的运行模式，而不是收窄模拟器全部能力。

## 应同步、应适配和应保留的边界

### 必须同步的产品语义

- 完整横滚输入；Python 容器宽度明确排除在同步范围外；
- 当前表层光学参数和 Thing 身份色轴；
- 删除珍珠/猫爪的当前产品裁决；
- 全局 1/f 对波幅、波包节奏和闪点出生率的语义；
- 双深度散射、镜面抗锯齿、微法线、SSS、解析光晕；
- Android 当前闪点数量、间隔、覆盖层、曲面内侧裁切；
- 共享 GLSL 和 SDR/HDR 两条渲染合同。

### 按桌面平台适配，不应机械照搬

- SurfaceView、EGL window surface、Android 生命周期和红色 Canvas 回退；
- 麦克风权限、`AudioRecord` 线程及 Android 特有的启动抑制接线；
- 真实 HDR window 的能力查询和 headroom API。Python 可先提供 FP16 离屏结果、SDR 预览、
  false-color/峰值诊断，桌面真 HDR 另立验收项。

### 建议继续保留的模拟器能力

- 音频文件和离线可重复输入；
- 参数 GUI、录屏、确定性种子和调试可视化；
- QPainter legacy 后端，作为迁移历史对照和无 GL 环境的研究回退。

## 推荐更新顺序

### 阶段 0：先建立可比较基线

1. 增加 `android-parity` 运行模式：保持 320dp×420dp，使用 Android 当前默认参数、完整横滚和
   Thing 背景输入；宽度差异通过归一化坐标进入验收规则。
2. 固定随机种子，并让两端可读取或导出同一组 `FeatureFrame`/离散事件脚本。
3. 生成至少四个标准场景：静水、稳定人声、强 onset、持续倾斜；固定每个场景的时间点和截图。
4. 记录 Python 当前 QPainter 基线，避免迁移时失去问题定位参照。

### 阶段 1：同步非渲染核心

1. 保持现有 320dp 容器及其体积、墙面和事件坐标语义。
2. 同步当前参数、颜色策略、珍珠/猫爪删除和全局 1/f 行为。
3. 同步 Android 的闪点出生容量/间隔语义和镜面足迹统计；暂不在 QPainter 中追求最终画法。
4. 用双端确定性轨迹按归一化横向坐标比较波高、坡度、曲率、波包、事件和映射输出，先证明
   除宽度外的宏观运动一致。

### 阶段 2：增加 ModernGL，并直接复用共享 GLSL

1. 以 `EverythingDone/shared/fablesol/glsl/` 为当前产品 shader 的唯一来源；Python 开发环境直接
   加载这些文件，并记录 shader 内容哈希，避免静默漂移。
2. 在桌面 GL 中建立与 Android 相同的 environment → water → optical → present passes，复用
   相同顶点布局、uniform 合同、混合方式和颜色空间。
3. 仅为 GLSL ES 与桌面 GLSL 的版本声明/精度限定做薄适配，不复制两套材质公式。
4. QPainter 留作 legacy 后端，不再承担当前 Android 像素级复刻目标。

### 阶段 3：处理 HDR 和验收

1. 先让 FP16 离屏渲染、HDR eligibility、分层峰值和录音状态门控在数值上与 Android 一致；
   SDR 显示时提供可选 tone-map/false-color 诊断。
2. 桌面真实 HDR window 输出单独验证；它受 Qt/窗口系统、显卡、显示器和 OS HDR 设置影响，
   不应阻塞 SDR 行为与材质一致性。
3. 对相同脚本比较：几何轨迹、uniform 快照、线性 FP16 像素、SDR 截图和性能。允许驱动级微小
   像素差异，但颜色轴、效果出现条件、层级范围和能量峰值必须一致。

## 建议的第一轮实现范围

第一轮建议只做“阶段 0 + 阶段 1 + ModernGL 的 SDR 主链”。这能解决当前绝大多数肉眼差距，
同时建立以后 Android shader 更新可持续同步的结构。真实桌面 HDR 放在第二轮，不应先投入大量
时间攻克窗口系统差异。

## 校验状态

- Python：`conda run -n everythingdone python -m unittest discover -s tests -p 'test_*.py'`，
  80 项测试通过。
- Python 环境未安装 `pytest`；仓库测试实际使用 `unittest`，没有为本次分析修改环境。
- 两个仓库 `git diff --check` 均通过。
- 未使用 adb，未运行真机或模拟器，也未改动两端产品实现。
