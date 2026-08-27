# -*- coding: utf-8 -*-
"""历史粒子消散蓝本，仅用于复查第 1–41 轮参数，不再作为当前模型验收依据。

当前多帷幔模型请运行 render_curtain_model.py；该脚本直接读取 Android canonical Shader，
避免本文件这种人工复制 GLSL 的同步偏差。
"""
import os

import moderngl
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "frames")

DENSITY = 2.75
CELL_PX = 1.1 * DENSITY         # 第二十五轮：1.5 -> 1.1，细密化（薄纱而非沙粒）
DRIFT_PX = 210 * DENSITY        # 第二轮：130 -> 210，位移加大才有"飘走"感
NOISE_SCALE_PX = 120 * DENSITY  # 第二轮：96 -> 120，缕更大块，像烟团不像碎絮
SWIRL = 0.75                    # 第十二轮：0.6 -> 0.75，用户要求加大随机（方向整形仍在）
VIRTUAL_TOUCH_FACTOR = 1.1      # 第五轮：虚拟远触点距离 = 因子 x 快照对角线
PINCH = 0.96                    # 第十轮：0.91 -> 0.96（用户指定）
FLARE_PX = 90 * DENSITY         # 第十三轮：外扩羽流幅度——部分区段粒子超出 dialog 宽度
PINCH_MAX_PX = 240 * DENSITY    # 第十四轮：收拢位移绝对上限——宽面板边缘不被勒到中轴
# 第二十九轮（华为量化对照）：单向揭开——delay 主项为沿飞行方向的投影
# （揭开线垂直于飞行方向、从飞行反侧边缘 proj=0 扫到前侧 proj=1，"像揭开
# 一张便利贴"，用户模型）。量化目标（华为清空段实测）：浓带宽 ≈ 跨度 30%、
# 峰/尾密度比 4–8、扫完跨度 ≈ 0.44s。锋线波浪与抖动收小保带形。
# delay 上限 = 0.40+0.14+0.08 = 0.62 精确守恒
SWEEP_TIME = 0.24               # 揭开线扫完卡片跨度的时长（大弧线延展后视觉 ≈0.57s）
ARC_BOW = 0.20                  # 揭开线大弧：两侧比中间晚 0.16s（夸张波峰/波谷）
WAVE_WARP = 0.14                # 大振幅弧线（华为截图：锋线是夸张弧线，振幅 ~40% 跨度）
KICK = 0.02                     # 第十轮：激活即刻的起飞冲量（ease 占比），消除原地点阵带
FLOW_EVOLVE = 0.5
TURB_PX_NEW = CELL_PX * 1.4     # 第十二轮：0.8 -> 1.4，湍流加大
SPREAD_TIME_NEW = 0.0           # 触点距离项废弃（凝聚场景仍用 spread 参数）
DELAY_JITTER = 0.04             # 弥散收窄；delay 上限 0.51+0.06+0.05 = 0.62 守恒
LIFETIME_NEW = 0.58
LIFETIME_OLD = 0.55
TILT_DEG = -12.0  # 新模型主方向随机倾斜的一个示例取值

VIEW_W, VIEW_H = 1960, 1700  # 第十四轮加宽：容纳 560dp 宽面板场景及其外扩
WAVE_ORIGIN_UV = (0.5, 0.78)

# 内容色增强（第二十四轮）：副本扩倍——每 cell 发 REPLICAS 个顶点，
# replica 0 为主粒子（行为与无副本时逐位一致），彩色格的副本真实增加数量
REPLICAS = 3
PANEL_COLOR = (245 / 255.0, 246 / 255.0, 248 / 255.0)

COMMON_HEADER = """#version 330

uniform sampler2D uSnapshot;
uniform vec2 uViewportPx;
uniform vec2 uOriginPx;
uniform float uCellPx;
uniform ivec2 uGrid;
uniform float uTime;
uniform vec2 uWaveOriginUv;
uniform float uSpreadTime;
uniform float uDelayJitter;
uniform float uLifetime;
uniform float uDriftPx;
uniform float uTurbPx;
uniform float uMaxPointPx;
uniform vec2 uNoiseSeed;
uniform uint uHashSeed;
uniform vec3 uPanelColor;
uniform int uReplicas;
uniform float uContentBoost;
uniform float uSweepTime;
uniform vec2 uSweepDir;
uniform float uArcBow;
uniform float uShatterLead;

out vec4 vColor;
out float vActivation;
out float vGlow;
"""

GRID_AND_WAVE = """
    // 副本扩倍：每 cell 发 uReplicas 个顶点。replica 0 = 主粒子（hash 输入
    // 与静止层完全一致，行为与无副本时逐位相同）；replica > 0 = 彩色格的
    // 增量副本（起飞时与主粒子重叠在同一格、随轨迹随机分开）
    int replica = gl_VertexID % uReplicas;
    int cellId = gl_VertexID / uReplicas;
    int ix = cellId % uGrid.x;
    int iy = cellId / uGrid.x;
    vec2 cell = vec2(float(ix), float(iy));
    vec2 uv = (cell + 0.5) / vec2(uGrid);
    vec4 color = textureLod(uSnapshot, uv, 0.0);

    // 主 hash：输入 = cellId，与静止层逐位一致——delay 由它派生，主粒子
    // 起飞与静止层擦除严格对齐
    uint hm = uint(cellId) ^ uHashSeed;
    hm = hm * 747796405u + 2891336453u;
    hm = ((hm >> ((hm >> 28u) + 4u)) ^ hm) * 277803737u;
    hm = (hm >> 22u) ^ hm;
    float h1m = float(hm & 1023u) * 0.0009775171;
    float h2m = float((hm >> 10u) & 1023u) * 0.0009775171;
    // 自身 hash：副本的输入偏移出主空间（轨迹独立）；replica 0 时与主
    // hash 相同，主粒子的全部随机行为不变
    uint h = (uint(cellId) + uint(replica) * uint(uGrid.x * uGrid.y)) ^ uHashSeed;
    h = h * 747796405u + 2891336453u;
    h = ((h >> ((h >> 28u) + 4u)) ^ h) * 277803737u;
    h = (h >> 22u) ^ h;
    float h1 = float(h & 1023u) * 0.0009775171;
    float h2 = float((h >> 10u) & 1023u) * 0.0009775171;
    float h3 = float((h >> 20u) & 1023u) * 0.0009775171;
    float h4 = fract(h1 + h2 * 0.618034);
    float h5 = fract(h2 + h3 * 0.618034);

    // 内容色权重（第二十四轮）：与面板本体色的色距为主（黑/灰字中档）、
    // 饱和度加成（彩色最高档）。颜色本身逐位不变，只做重加权——白底粒子
    // 是烟云质感载体，内容色粒子更大、更持久、真实更多
    float colorDist = length(color.rgb - uPanelColor) * 0.5774;
    float sat = max(color.r, max(color.g, color.b))
            - min(color.r, min(color.g, color.b));
    float wDist = smoothstep(0.08, 0.42, colorDist);
    float wSat = smoothstep(0.18, 0.42, sat);
    vec2 snapshotPx = vec2(uGrid) * uCellPx;
    vec2 basePx = uOriginPx + (cell + 0.5) * uCellPx;
    // 边缘并入内容权重（第三十三轮，用户模型）：浓带 = 边缘与内容色
    // 转换出的密集粒子群本身——边缘粒子享受全保留/尺寸/长寿/副本全套
    // 增强，成为掀起时随波浪变形飘动的亮弧线（华为截图的银河带）
    float edgeDistW = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    // 边缘带的弯曲与断续（第三十四轮，用户反馈）：边界线位置受噪声摆动
    // （带呈波浪而非直线）、沿边强度低频变化（同一条边有的段浓有的段稀
    // 甚至断开；四条边对噪声采样不同、彼此表现各异）
    float edgeWave = (vnoise(basePx / (uNoiseScalePx * 0.45) + 210.7) - 0.5) * 0.08;
    float edgeMod = smoothstep(0.22, 0.72, vnoise(basePx / (uNoiseScalePx * 0.7) + 123.4));
    float edgeBoost = smoothstep(0.06, 0.015, edgeDistW + edgeWave) * edgeMod;
    float w = min(wDist * 0.62 + wSat * 0.6 + 0.10 * edgeBoost, 1.0) * uContentBoost;

    float waveDist = distance(uv * snapshotPx, uWaveOriginUv * snapshotPx)
            / length(snapshotPx);
    // 锋线扭曲（第二十五轮重构，第二十六轮双尺度）：单侧 [0, uWaveWarp]
    // 噪声主导——溶解顺序由噪声等值线推进（华为式侵蚀）。低频项撑起锋线
    // 大波浪形态；中频项（约 66dp 波长）在面板内制造多个**互不连通**的
    // 局部谷，多处同时起碎、随扩张合并（单一大波长时整个面板只有一两个
    // 谷，起碎聚在一处——2026-08-26 用户指出）。触点距离只保留整体先后
    // 趋势。静止层用完全同款公式保持擦除对齐
    // 大振幅弧线锋线（第三十一轮，按华为截图钉死）：低频大波长噪声——
    // 横跨整卡一两个起伏、振幅 ~40% 跨度，弧线一侧先掀开一大片、另一侧
    // 滞后；起始只是边缘的一段（弧线谷的先头），不是整条边
    float lowN = vnoise(basePx / (uNoiseScalePx * 2.2) + 7.7);
    float waveWarp = lowN * uWaveWarp;
    // 单向揭开（第二十九轮，用户模型）：delay 主项 = 沿飞行方向的投影。
    // 揭开线垂直于飞行方向、从飞行反侧边缘（proj=0）扫到前侧（proj=1）
    // ——往上飞从下缘揭开、往右上飞浓带呈左上-右下走向，像揭一张便利贴。
    // 凝聚传 uSweepTime=0 退回噪声主导斑块凝实
    float sweepBase = min(0.0, snapshotPx.x * uSweepDir.x)
            + min(0.0, snapshotPx.y * uSweepDir.y);
    float sweepSpan = abs(snapshotPx.x * uSweepDir.x)
            + abs(snapshotPx.y * uSweepDir.y);
    float proj = (dot(basePx - uOriginPx, uSweepDir) - sweepBase) / max(sweepSpan, 1.0);
    // delay 用主 h1（静止层对齐）；副本在主粒子之后小的正偏移起飞——
    // 绝不早于格子擦除，"凭空多一颗"不会发生（凝聚倒放同理：副本先落）
    // 次级边缘起碎（仅消散）：其他边缘（含前侧）的噪声谷段也同时开始
    // 局部粒子化（华为截图：上缘左侧同步掀起），各自形成小浓带
    float edgeDist = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    // 四周边缘同时向内侵蚀（第三十九轮，用户反馈"轮廓一直方方正正"）：
    // 距边缘 13% 以内的格子大幅提前碎化，侵蚀边界受噪声摆动（±10%）、
    // 强度沿边低频起伏——四条边同时开始破碎成弯曲曲线并向内推进，
    // 轮廓在动画早期就不再方正；揭开线只保留整体推进与浓带
    float erodeWave = (vnoise(basePx / (uNoiseScalePx * 0.4) + 300.7) - 0.5) * 0.10;
    float erodeMod = 0.45 + 0.55 * vnoise(basePx / (uNoiseScalePx * 0.62) + 412.3);
    float localEdge = smoothstep(0.13, 0.0, edgeDist + erodeWave) * erodeMod
            * step(0.001, uSweepTime);
    // 揭开线的夸张大弧（第四十轮，用户要求"波峰/波谷"）：中间比两侧
    // 早揭开 uArcBow 秒——揭开边界沿飞行方向凸出成一道大弧（往上飞是
    // 波峰、往下飞是波谷），弧心每次动画随机偏移，不完全对称
    vec2 perpDir = vec2(-uSweepDir.y, uSweepDir.x);
    float perpSpan = abs(snapshotPx.x * perpDir.x) + abs(snapshotPx.y * perpDir.y);
    float perpBase = min(0.0, snapshotPx.x * perpDir.x)
            + min(0.0, snapshotPx.y * perpDir.y);
    float lateral = (dot(basePx - uOriginPx, perpDir) - perpBase)
            / max(perpSpan, 1.0);
    float bowCenter = 0.5 + (fract(uNoiseSeed.x * 0.0137) - 0.5) * 0.16;
    float lat = (lateral - bowCenter) / max(max(bowCenter, 1.0 - bowCenter), 0.001);
    float arcBow = uArcBow * lat * lat * step(0.001, uSweepTime);
    // 帷幔浓带（第四十一轮，用户要求"一条边上的一部分粒子形成帷幔"）：
    // 沿揭开起始边用低频噪声选出局部区段（约占边长 1/3），该区段全保留
    // 且副本全开——形成一道浓密的下垂弧带；其余部分（含边缘）照常抽稀，
    // 轮廓因此不再方正
    float curtainCenter = fract(uNoiseSeed.y * 0.0091) * 0.6 + 0.2;
    float curtainHalf = 0.13 + 0.07 * fract(uNoiseSeed.x * 0.0233);
    float curtainSeg = smoothstep(1.0, 0.25,
            abs(lateral - curtainCenter) / curtainHalf);
    float curtainBand = smoothstep(0.34, 0.0, proj);
    float curtain = curtainSeg * curtainBand * step(0.001, uSweepTime);
    // 帷幔自身的下垂弧：段中心先掀、两端拖后，掀起的一角呈幕布弧
    float curtainArc = (1.0 - curtainSeg) * 0.07 * curtainBand
            * step(0.001, uSweepTime);
    float delayMain = max(
        waveDist * uSpreadTime + proj * uSweepTime + arcBow + waveWarp + curtainArc
                - localEdge * 0.55 * uSweepTime, 0.0
    ) + h1m * uDelayJitter;
    float delay = delayMain + float(replica) * (0.3 + 0.7 * h2) * 0.5 * uDelayJitter;
    // 两锋线（第三十轮，用户模型修正）：碎化锋线领先起飞锋线 0.14s
    // （≈27% 跨度的起沙带）——内容先变为**原位颗粒态**（轻微错位起沙、
    // 仍可辨），起飞锋线随后把颗粒掀起飞走；未到区域保持逐像素清晰。
    // 浓带 = 起飞线附近刚掀起、未散开的**纯粒子**聚集（不是残余的卡片
    // 内容），与揭开同时出现、随线移动
    // 掀起点更厚：揭开起始段（proj 小）连白色副本也激活，起点更浓
    float originBoost = 1.0 - smoothstep(0.08, 0.30, delayMain);
    // 白底抽稀（第三十二轮，用户提议）：华为通知卡毛玻璃底不透明度实测
    // 仅 0.26——粒子化时透明底区的粒子淡到不可见，可见粒子天然集中在
    // 内容与边缘，浓稀对比是内容透明度分布的直接映射。对不透明控件的
    // 等效：本体色格子只保留约 30% 转化为粒子（其余碎化瞬间直接消失
    // 露背景），几何边缘带与内容色格子全保留——浓（边缘/内容密集带）
    // 与稀（白底零星）的对比内生。凝聚不抽稀（要凝实成完整面板）
    // 白底抽稀率也随空间起伏（稀疏场浓淡不均，不是均匀撒点）
    float keepBase = 0.03 + 0.06 * vnoise(basePx / (uNoiseScalePx * 0.8) + 87.1);
    float keep = max(max(keepBase, w), curtain);
    bool culled = step(0.001, uSweepTime) > 0.5 && h2m > keep;
    // 全局快速碎化（第三十三轮，用户模型澄清）：动画开始 ~0.15s 内整卡
    // 零星碎化为悬浮粒子场——白底 70% 格子直接消失、露出 **dialog 底下
    // 的界面**（不是 dialog 内部的白底），30% 化为原位悬浮稀粒；边缘与
    // 内容色全保留（轮廓可辨）。随后揭开线扫过把悬浮粒子掀起飞散。
    // 凝聚无此阶段（tShatter = delay）
    float shatterN = vnoise(basePx / (uNoiseScalePx * 0.6) + 77.7);
    float tShatter = mix(delay, shatterN * 0.10 + h1m * 0.05, step(0.001, uSweepTime));
    // 寿命两极分化（第二十九轮量化调参）：78% 粒子短寿（0.22–0.40）——
    // 只活在揭开线的浓带里（带宽 = 平台期/扫速 ≈ 跨度 25%，对齐华为 30%），
    // 死亡后已扫区快速清空；22% 长寿（0.70–1.25）稀疏飘完全程。峰/尾
    // 密度比目标 ≥4（华为 4–8，上一版实测仅 1.1——数量存量过大）。
    // 内容色长寿概率更高（余缕以内容色为主）；上限 1.25、总时长不变
    float lifeMix = 0.5 * vnoise(basePx / (uNoiseScalePx * 0.9) + 67.9) + 0.5 * h5;
    float h6 = fract(h3 + h4 * 0.618034);
    float shortLife = 0.16 + 0.12 * lifeMix;
    float longLife = 0.70 + 0.55 * lifeMix;
    float longGate = step(0.96 - 0.49 * w, h6);
    // 两极寿命仅消散（sweep>0）生效；凝聚用连续寿命（倒放下短寿会造成
    // 大量格子既无粒子也无静止层的黑洞）
    float bipolar = step(0.001, uSweepTime);
    float lifeMod = mix(
        0.55 + 0.70 * lifeMix,
        mix(0.55 + 0.70 * lifeMix, mix(shortLife, longLife, longGate), bipolar),
        uContentBoost
    );
    // 抽稀格子的粒子在揭开线扫到的**瞬间闪现**（极短寿命 0.09）——
    // 浓带因此是全格子密度、带外只剩保留粒子（约 1/6），浓稀对比来自
    // 数量本身；它们的静止层早在 delay 的 1/4 就擦除了（露背景），
    // 闪现前那段时间该格子确实是空的
    lifeMod = mix(lifeMod, 0.07, culled ? 1.0 : 0.0);
    float tl = clamp((uTime - delay) / (uLifetime * lifeMod), 0.0, 1.0);
"""

TAIL = """
    vActivation = smoothstep(0.0, 0.05, tl);
    vec2 ndc = posPx / uViewportPx * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
"""

# 新模型：curl 流场（空间连贯烟缕）+ 前慢后快 ease + 浓淡调制
# （旧模型 A/B 对比使命已完成，第十轮起删除——锋线扭曲依赖 vnoise，共享块不再兼容）
VERT_NEW = COMMON_HEADER + """
uniform float uNoiseScalePx;
uniform float uSwirl;
uniform float uFlowEvolve;
uniform float uWaveWarp;

// 整数 hash：sin-hash 的 dot 参数达 1e3 弧度量级，移动 GPU 的 sin 是低阶
// 多项式近似、大参数严重失真，噪声在真机上退化（桌面蓝本与真机不一致的
// 根因，2026-08-26 用户发现）。整数运算跨平台逐位一致
float hash21(vec2 p) {
    uvec2 q = uvec2(ivec2(p)) * uvec2(1597334673u, 3812015801u);
    uint n = (q.x ^ q.y) * 1597334673u;
    return float(n) * (1.0 / 4294967296.0);
}

// uNoiseSeed：Android 端每次动画随机（蓝本固定 0 便于对比）
float vnoise(vec2 p) {
    p += uNoiseSeed;
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
        mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

float fbm(vec2 p) {
    return vnoise(p) * 0.667 + vnoise(p * 2.03 + 11.3) * 0.333;
}

vec2 curlField(vec2 p) {
    float e = 0.12;
    float dy = fbm(p + vec2(0.0, e)) - fbm(p - vec2(0.0, e));
    float dx = fbm(p + vec2(e, 0.0)) - fbm(p - vec2(e, 0.0));
    return vec2(dy, -dx) / (2.0 * e);
}

uniform vec2 uVirtualTouchPx;
uniform float uPinch;
uniform float uKick;
uniform float uFlarePx;
uniform float uPinchMaxPx;

void main() {
""" + GRID_AND_WAVE + """
    // 副本三类：彩色（全程）、起碎源头区、以及**带内副本**（第三十一轮，
    // 浓带的数量来源）——所有粒子刚起飞段（前 30% 寿命）额外激活副本，
    // 数量 x3 聚在揭开线附近成浓带，随后淡出还回 1 颗
    bool bandReplica = tl > 0.0 && tl < 0.38;
    // 高权重（边缘 w=0.85 / 彩色 w=1.0，黑字 0.62 除外——用户裁定黑字
    // 不增量）副本全程激活：浓带三倍密度贯穿动画
    bool replicaDead = replica != 0 && (culled || uContentBoost < 0.5 ||
            (curtain < 0.5 &&
            wSat < 0.5 && w < 0.78 && originBoost < 0.4 && !bandReplica));
    // 碎化即飞散（第三十五轮，用户报"两段感"）：粒子在自己的 delay
    // 时刻碎化并**立即**起飞，无悬浮等待阶段——碎化与消逝同步（华为
    // 同款）。"开始就露背景"由被抽稀的白底格子提前消失承担（见静止层）
    if (uTime - delay <= 0.0 || replicaDead) {
        vColor = vec4(0.0);
        vActivation = 0.0;
        vGlow = 0.0;
        gl_PointSize = 1.0;
        gl_Position = vec4(2.0, 2.0, 0.0, 1.0);
        return;
    }
    // 激活即刻的小冲量：几帧内滑出约 4dp，消除"点阵化但原地不动"的假
    // dialog 带（锋线后的粒子立即离位，实体边缘直接起沙散开）
    float ease = pow(tl, 1.6) + uKick * smoothstep(0.0, 0.06, tl);
    // 气流略早于主位移，但不再抢跑（第六轮 0.8 -> 1.15：早期以主方向为主）
    float flowEase = pow(tl, 1.15);
    float riseMod = 0.45 + 1.2 * vnoise(basePx / uNoiseScalePx * 0.55 + 3.7);

    // 逐粒子主方向：指向虚拟远触点（触点沿其方向推远到约 1.1 倍对角线）。
    // 方向随粒子位置平滑渐变——触点在左上时右侧粒子更偏左、左侧粒子更偏上；
    // 远点保证触点贴近快照（点按钮）时两侧粒子不对冲
    vec2 dir = normalize(uVirtualTouchPx - basePx);

    // 低频弯曲场（第七轮）：指向远点的方向场是线性收敛场，整团像做线性
    // warp、两侧呈一致斜边。大尺度噪声给每个区域一个转向角（左右弯向不同，
    // 打破对称直边），转角随时间平滑增长——起飞时仍朝触点，随后弯出弧线
    float bendNoise = vnoise(basePx / (uNoiseScalePx * 1.8) + 27.4) - 0.5;
    float bend = (bendNoise * 1.6 + (h2 - 0.5) * 0.3) * smoothstep(0.0, 1.0, tl);
    float cb = cos(bend);
    float sb = sin(bend);
    vec2 dirBent = vec2(dir.x * cb - dir.y * sb, dir.x * sb + dir.y * cb);

    vec2 samplePos = (basePx + dirBent * ease * uDriftPx * 0.4) / uNoiseScalePx;
    vec2 flow = curlField(samplePos + vec2(0.0, uTime * uFlowEvolve));

    // 流场方向整形：剔除逆行进方向的分量（没有粒子往回跑），横摆全保留
    // （成缕与云宽靠它），顺向分量减弱——整团烟保持朝触点的整体流动
    float along = dot(flow, dirBent);
    vec2 flowShaped = (flow - dirBent * along) + dirBent * max(along, 0.0) * 0.4;

    // Genie 收拢：粒子相对"过触点、沿主方向"轴线的横向偏移随飞行进度收回。
    // 配合波前（近触点侧先飞、收得多；远侧后激活、仍全宽），整团呈漏斗形。
    // 收拢量做双层调制（第十一轮）：均匀线性收缩会让快照左右边缘的粒子收缩后
    // 仍然共线，云的侧边像刀切的直线——大尺度噪声让不同区段收得多收得少
    // （边缘波浪化），逐粒子随机再加毛糙羽化；均值 1 保持整体漏斗力度，
    // 封顶防止过冲穿轴
    vec2 wavePx = uOriginPx + uWaveOriginUv * snapshotPx;
    vec2 rel = basePx - wavePx;
    vec2 lateralVec = rel - dir * dot(rel, dir);
    float pinchMod = (0.55 + 0.9 * vnoise(basePx / (uNoiseScalePx * 0.7) + 41.7))
            * (0.75 + 0.5 * h3);

    // 外扩羽流（第十三轮）：低频噪声门控约一半区段，边缘粒子向外推出、
    // 可超过原 dialog 宽度；外扩区收拢同时打折——主体被吸走、边缘流苏
    // 逸散，云宽不再被收拢锁死
    float flare = max(vnoise(basePx / (uNoiseScalePx * 0.8) + 91.3) - 0.55, 0.0)
            / 0.45;
    float lateralLen = length(lateralVec);
    vec2 lateralDir = lateralLen > 1.0 ? lateralVec / lateralLen : vec2(0.0);
    vec2 flareDrift = lateralDir * flare * uFlarePx * flowEase * (0.5 + h5);

    // 收拢位移设绝对上限：收拢按比例作用于初始横向偏移，宽 Dialog 边缘的
    // 绝对收拢量线性放大、会被一口气拉向中轴——封顶后漏斗保留但不勒死
    float pinchAmount = min(uPinch * ease * pinchMod, 0.98) * (1.0 - 0.7 * flare);
    vec2 pinchVec = -lateralVec * pinchAmount;
    float pinchLen = length(pinchVec);
    if (pinchLen > uPinchMaxPx) {
        pinchVec *= uPinchMaxPx / pinchLen;
    }

    vec2 pinch = pinchVec + flareDrift;

    // 方向插值（第十五轮，替换爆散位移段）：运动方向从"随机偏侧向"平滑
    // 过渡到主流方向，位移曲线不变——轨迹呈先斜出再拐向主流的连续弧线。
    // 无额外速度段（不急、不加时长）；早期 ease 小、散开量天然温和；中部
    // 粒子早期方向以纯随机为主，不会集体撤离中轴形成空洞
    float randAng = h5 * 6.2831853;
    vec2 randDir = vec2(cos(randAng), sin(randAng));
    vec2 earlySum = lateralDir * (0.4 + 0.5 * h4) + randDir * 0.8;
    vec2 earlyDir = length(earlySum) > 0.05 ? normalize(earlySum) : randDir;
    float dirBlend = smoothstep(0.0, 0.55, tl);
    vec2 moveSum = mix(earlyDir, dirBent, dirBlend);
    vec2 moveDir = length(moveSum) > 0.05 ? normalize(moveSum) : dirBent;

    float speedJitter = 0.6 + 0.9 * h3;
    vec2 drift = (moveDir * riseMod * ease + flowShaped * uSwirl * flowEase)
            * uDriftPx * speedJitter + pinch;

    // 逐粒子幅度随机的湍流抖动
    vec2 jitter = vec2(
        sin(uTime * 6.0 + h1 * 41.0),
        cos(uTime * 5.1 + h2 * 37.0)
    ) * uTurbPx * tl * (0.5 + h4);

    vec2 posPx = basePx + drift + jitter;

    // 浓淡对比加强（第十二轮）：0.4~1.0；背景减密（第二十四轮，轻度）：
    // 低权重（白底）粒子在飞散途中更早变稀薄，内容色不减
    float density = min(0.4 + 0.75 * vnoise(basePx / uNoiseScalePx * 0.8 + 17.3), 1.0)
            * (1.0 - 0.28 * (1.0 - w) * uContentBoost);
    // 平台式衰减（短寿=浓带成员：平台满亮后快谢）；长寿粒子早衰渐隐
    // （飘着的淡纱，已扫区不再满亮压底——华为 tail 0.14 的构成）
    float fadeShort = 1.0 - smoothstep(0.50, 0.80, tl);
    float fadeLong = pow(1.0 - smoothstep(0.03, 0.65, tl), 2.2);
    float fade = mix(
        pow(1.0 - smoothstep(0.02, 0.92, tl), 1.7),
        mix(fadeShort, fadeLong, longGate), bipolar
    );
    // 浓淡噪声的介入延后到锋线带之后（0.05->0.30 起），带内不被打薄
    float alphaMul = mix(1.0, density, smoothstep(0.30, 0.60, tl));
    // 锋线辉光（第二十五轮）：起碎瞬间为粒子原色的发光版本——亮度增益后
    // 截断，深色内容变亮色、白色保持纯白（用户裁定：不固定成银色），随
    // 寿命前段衰减回真实色；锋线密集带叠加成亮纱
    float glowEase = pow(max(1.0 - tl * 2.2, 0.0), 1.5);
    vec3 glowRgb = min(color.rgb * 2.2, vec3(1.0));
    vec3 litRgb = mix(color.rgb, glowRgb, 0.75 * glowEase);
    // 白色带内副本在 tl 0.18-0.30 淡出（浓带随锋线移动、身后还回单颗）
    float bandReplicaFade = (replica != 0 && wSat < 0.5 && originBoost < 0.4)
            ? (1.0 - smoothstep(0.18, 0.30, tl)) : 1.0;
    vColor = vec4(litRgb, color.a * fade * alphaMul * bandReplicaFade);
    vGlow = glowEase;

    // 粒子大小不均（第十二轮）：低频区域差 x 逐粒子随机（平方偏斜：多数小、
    // 偶有大颗粒），静止拼图由静止层负责后尺寸已无约束。内容色放大
    // （第二十四轮）：高权重粒子最大 +70%；副本略缩一档保持主次层次
    float sizeMod = (0.7 + 0.6 * vnoise(basePx / (uNoiseScalePx * 0.5) + 53.1))
            * (0.55 + 0.95 * h4 * h4)
            * (1.0 + 0.7 * w) * (replica != 0 ? 0.8 : 1.0)
            * (1.0 + 0.6 * (1.0 - smoothstep(0.0, 0.35, tl)))
            * (1.0 + 1.0 * curtain);
    // 第二十五轮：起步再收一档（1.0/0.34 -> 0.9/0.32，叠加 cell 细化共约
    // -34%）——细密成纱
    gl_PointSize = clamp(
        mix(uCellPx * 0.9, uCellPx * 0.32, tl) * sizeMod, 1.0, uMaxPointPx
    );
""" + TAIL + """
}
"""

FRAG = """#version 330

in vec4 vColor;
in float vActivation;
in float vGlow;
out vec4 outColor;

void main() {
    float r = length(gl_PointCoord - 0.5);
    // 辉光期软边内径放宽（0.30 -> 0.12）：光晕更柔更晕，硬边消失成纱
    float inner = mix(0.30, 0.12, vGlow);
    float circle = 1.0 - smoothstep(inner, 0.5, r);
    float shape = mix(1.0, circle, vActivation);
    float a = vColor.a * shape;
    outColor = vec4(vColor.rgb * a, a);
}
"""

# 静止层：波前未扫到的区域直接以逐像素原图绘制（粒子拼图是 cellPx 网格的
# 重采样近似，文字会糊）；按与粒子完全同款的波前公式逐格擦除
STILL_VERT = """#version 330

uniform vec2 uViewportPx;
uniform vec2 uOriginPx;
uniform float uCellPx;
uniform ivec2 uGrid;

out vec2 vUv;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vUv = corner;
    vec2 posPx = uOriginPx + corner * vec2(uGrid) * uCellPx;
    vec2 ndc = posPx / uViewportPx * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
}
"""

STILL_FRAG = """#version 330

uniform sampler2D uSnapshot;
uniform ivec2 uGrid;
uniform float uCellPx;
uniform vec2 uOriginPx;
uniform vec2 uWaveOriginUv;
uniform float uSpreadTime;
uniform float uDelayJitter;
uniform float uNoiseScalePx;
uniform float uWaveWarp;
uniform float uTime;
uniform vec2 uNoiseSeed;
uniform uint uHashSeed;
uniform float uSweepTime;
uniform vec2 uSweepDir;
uniform float uArcBow;
uniform vec3 uPanelColor;

in vec2 vUv;
out vec4 outColor;

// 整数 hash：与粒子层同款（sin-hash 在移动 GPU 大参数失真）
float hash21(vec2 p) {
    uvec2 q = uvec2(ivec2(p)) * uvec2(1597334673u, 3812015801u);
    uint n = (q.x ^ q.y) * 1597334673u;
    return float(n) * (1.0 / 4294967296.0);
}

// 种子与粒子层一致，擦除边界精确对齐
float vnoise(vec2 p) {
    p += uNoiseSeed;
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
        mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}

void main() {
    vec2 cell = floor(vUv * vec2(uGrid));
    uint id = uint(cell.y) * uint(uGrid.x) + uint(cell.x);
    uint h = (id ^ uHashSeed) * 747796405u + 2891336453u;
    h = ((h >> ((h >> 28u) + 4u)) ^ h) * 277803737u;
    h = (h >> 22u) ^ h;
    float h1 = float(h & 1023u) * 0.0009775171;
    vec2 snapshotPx = vec2(uGrid) * uCellPx;
    vec2 cellUv = (cell + 0.5) / vec2(uGrid);
    vec2 basePx = uOriginPx + (cell + 0.5) * uCellPx;
    float waveDist = distance(cellUv * snapshotPx, uWaveOriginUv * snapshotPx)
            / length(snapshotPx);
    // 与粒子层完全同款的锋线扭曲与单向揭开投影，擦除边界精确对齐
    // 大振幅弧线锋线（第三十一轮，按华为截图钉死）：低频大波长噪声——
    // 横跨整卡一两个起伏、振幅 ~40% 跨度，弧线一侧先掀开一大片、另一侧
    // 滞后；起始只是边缘的一段（弧线谷的先头），不是整条边
    float lowN = vnoise(basePx / (uNoiseScalePx * 2.2) + 7.7);
    float waveWarp = lowN * uWaveWarp;
    float sweepBase = min(0.0, snapshotPx.x * uSweepDir.x)
            + min(0.0, snapshotPx.y * uSweepDir.y);
    float sweepSpan = abs(snapshotPx.x * uSweepDir.x)
            + abs(snapshotPx.y * uSweepDir.y);
    float proj = (dot(basePx - uOriginPx, uSweepDir) - sweepBase) / max(sweepSpan, 1.0);
    float edgeDist = min(min(cellUv.x, 1.0 - cellUv.x), min(cellUv.y, 1.0 - cellUv.y));
    // 四周边缘同时向内侵蚀（第三十九轮，用户反馈"轮廓一直方方正正"）：
    // 距边缘 13% 以内的格子大幅提前碎化，侵蚀边界受噪声摆动（±10%）、
    // 强度沿边低频起伏——四条边同时开始破碎成弯曲曲线并向内推进，
    // 轮廓在动画早期就不再方正；揭开线只保留整体推进与浓带
    float erodeWave = (vnoise(basePx / (uNoiseScalePx * 0.4) + 300.7) - 0.5) * 0.10;
    float erodeMod = 0.45 + 0.55 * vnoise(basePx / (uNoiseScalePx * 0.62) + 412.3);
    float localEdge = smoothstep(0.13, 0.0, edgeDist + erodeWave) * erodeMod
            * step(0.001, uSweepTime);
    // 揭开线的夸张大弧（第四十轮，用户要求"波峰/波谷"）：中间比两侧
    // 早揭开 uArcBow 秒——揭开边界沿飞行方向凸出成一道大弧（往上飞是
    // 波峰、往下飞是波谷），弧心每次动画随机偏移，不完全对称
    vec2 perpDir = vec2(-uSweepDir.y, uSweepDir.x);
    float perpSpan = abs(snapshotPx.x * perpDir.x) + abs(snapshotPx.y * perpDir.y);
    float perpBase = min(0.0, snapshotPx.x * perpDir.x)
            + min(0.0, snapshotPx.y * perpDir.y);
    float lateral = (dot(basePx - uOriginPx, perpDir) - perpBase)
            / max(perpSpan, 1.0);
    float bowCenter = 0.5 + (fract(uNoiseSeed.x * 0.0137) - 0.5) * 0.16;
    float lat = (lateral - bowCenter) / max(max(bowCenter, 1.0 - bowCenter), 0.001);
    float arcBow = uArcBow * lat * lat * step(0.001, uSweepTime);
    // 帷幔浓带（第四十一轮，用户要求"一条边上的一部分粒子形成帷幔"）：
    // 沿揭开起始边用低频噪声选出局部区段（约占边长 1/3），该区段全保留
    // 且副本全开——形成一道浓密的下垂弧带；其余部分（含边缘）照常抽稀，
    // 轮廓因此不再方正
    float curtainCenterS = fract(uNoiseSeed.y * 0.0091) * 0.6 + 0.2;
    float curtainHalfS = 0.13 + 0.07 * fract(uNoiseSeed.x * 0.0233);
    float curtainSegS = smoothstep(1.0, 0.25,
            abs(lateral - curtainCenterS) / curtainHalfS);
    float curtainBandS = smoothstep(0.34, 0.0, proj);
    float curtainS = curtainSegS * curtainBandS * step(0.001, uSweepTime);
    float curtainArcS = (1.0 - curtainSegS) * 0.07 * curtainBandS
            * step(0.001, uSweepTime);
    float delay = max(
        waveDist * uSpreadTime + proj * uSweepTime + arcBow + waveWarp + curtainArcS
                - localEdge * 0.55 * uSweepTime, 0.0
    ) + h1 * uDelayJitter;
    // 抽稀格子提前擦除（第三十五轮）：白底大多数格子不产生粒子，在
    // delay 的 1/4 时刻就消失——动画一开始整卡快速变稀薄、露出 dialog
    // 底下的界面；保留的格子（边缘/内容色/少量白粒）仍以原图显示，直到
    // 自己的 delay 被粒子层接管（碎化即飞散，无静止分割线）。
    // 判定必须与粒子层逐位一致
    float h2s = float((h >> 10u) & 1023u) * 0.0009775171;
    vec4 cs = texture(uSnapshot, cellUv);
    float colorDistS = length(cs.rgb - uPanelColor) * 0.5774;
    float satS = max(cs.r, max(cs.g, cs.b)) - min(cs.r, min(cs.g, cs.b));
    float edgeDistWS = min(min(cellUv.x, 1.0 - cellUv.x),
            min(cellUv.y, 1.0 - cellUv.y));
    float edgeWaveS = (vnoise(basePx / (uNoiseScalePx * 0.45) + 210.7) - 0.5) * 0.08;
    float edgeModS = smoothstep(0.22, 0.72,
            vnoise(basePx / (uNoiseScalePx * 0.7) + 123.4));
    float edgeBoostS = smoothstep(0.06, 0.015, edgeDistWS + edgeWaveS) * edgeModS;
    float wS = min(smoothstep(0.08, 0.42, colorDistS) * 0.62
            + smoothstep(0.18, 0.42, satS) * 0.6 + 0.10 * edgeBoostS, 1.0);
 float keepBaseS = 0.03
            + 0.06 * vnoise(basePx / (uNoiseScalePx * 0.8) + 87.1);
    bool culledS = step(0.001, uSweepTime) > 0.5
            && h2s > max(max(keepBaseS, wS), curtainS);
    if (uTime > (culledS ? delay * 0.15 : delay)) discard;
    vec4 c = texture(uSnapshot, vUv);
    outColor = vec4(c.rgb * c.a, c.a);
}
"""


def make_snapshot(width_dp=320, height_dp=190):
    """PIL 画一张假 Dialog 快照：白色圆角卡片 + 文字 + 彩色按钮。"""
    w, h = int(width_dp * DENSITY), int(height_dp * DENSITY)
    radius = int(16 * DENSITY)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=(245, 246, 248, 255))

    def font(size, bold=False):
        name = "msyhbd.ttc" if bold else "msyh.ttc"
        try:
            return ImageFont.truetype(name, int(size * DENSITY))
        except OSError:
            return ImageFont.load_default()

    draw.text((24 * DENSITY, 26 * DENSITY), "删除这条记事？",
              font=font(18, bold=True), fill=(30, 33, 38, 255))
    draw.text((24 * DENSITY, 68 * DENSITY), "删除后会进入回收站，可在 30 天内恢复。",
              font=font(13), fill=(95, 102, 114, 255))
    draw.text((24 * DENSITY, 94 * DENSITY), "包含 2 张图片附件。",
              font=font(13), fill=(95, 102, 114, 255))
    draw.text((196 * DENSITY, 148 * DENSITY), "取消",
              font=font(15, bold=True), fill=(47, 127, 224, 255))
    draw.text((258 * DENSITY, 148 * DENSITY), "删除",
              font=font(15, bold=True), fill=(232, 84, 77, 255))
    return img


def make_colorful_snapshot(width_dp=330, height_dp=300):
    """模拟改记事颜色面板：白底 + 两行彩色圆钮 + 色相条——彩色增强的主验证场景。"""
    w, h = int(width_dp * DENSITY), int(height_dp * DENSITY)
    radius = int(16 * DENSITY)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=(245, 246, 248, 255))

    def font(size, bold=False):
        name = "msyhbd.ttc" if bold else "msyh.ttc"
        try:
            return ImageFont.truetype(name, int(size * DENSITY))
        except OSError:
            return ImageFont.load_default()

    draw.text((24 * DENSITY, 22 * DENSITY), "调整颜色",
              font=font(17, bold=True), fill=(233, 90, 40, 255))
    swatches = [
        (109, 128, 145), (0, 172, 193), (49, 94, 128), (38, 50, 66), (141, 110, 99),
        (21, 101, 152), (16, 150, 105), (103, 88, 174), (207, 129, 57), (183, 82, 82),
    ]
    r = int(21 * DENSITY)
    for i, c in enumerate(swatches):
        cx = int((44 + (i % 5) * 61) * DENSITY)
        cy = int((92 + (i // 5) * 62) * DENSITY)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c + (255,))
    # 色相条
    bar_y0, bar_y1 = int(196 * DENSITY), int(212 * DENSITY)
    bar_x0, bar_x1 = int(28 * DENSITY), int(240 * DENSITY)
    import colorsys
    for x in range(bar_x0, bar_x1):
        hue = (x - bar_x0) / (bar_x1 - bar_x0)
        rgb = tuple(int(v * 255) for v in colorsys.hsv_to_rgb(hue, 0.95, 0.92))
        draw.rectangle([x, bar_y0, x + 1, bar_y1], fill=rgb + (255,))
    draw.text((26 * DENSITY, 232 * DENSITY), "R 98   G 242   B 166   #62F2A6",
              font=font(12), fill=(95, 102, 114, 255))
    draw.text((176 * DENSITY, 264 * DENSITY), "取消",
              font=font(15, bold=True), fill=(120, 126, 134, 255))
    draw.text((248 * DENSITY, 264 * DENSITY), "确定",
              font=font(15, bold=True), fill=(233, 90, 40, 255))
    return img


def render_sequence(ctx, program, snapshot, spread_time, lifetime, extra_uniforms, tag,
                    drift_angle_deg=None, still_program=None, condense_from=None):
    # 寿命调制上限 1.25：最长寿粒子决定动画总时长。第二十九轮时间预算：
    # sweep 0.40 + warp 0.14 + jitter 0.08 = 0.62，总时长精确守恒 1.345
    sweep_time = extra_uniforms.get("uSweepTime", SWEEP_TIME)
    total = spread_time + DELAY_JITTER + WAVE_WARP + ARC_BOW + sweep_time + lifetime * 1.25
    cols = -(-snapshot.width // int(CELL_PX))  # ceil
    rows = -(-snapshot.height // int(CELL_PX))
    origin_x = (VIEW_W - snapshot.width) / 2.0
    origin_y = VIEW_H - snapshot.height - int(120 * DENSITY) / 2.0

    tex = ctx.texture((snapshot.width, snapshot.height), 4, snapshot.tobytes())
    tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
    tex.repeat_x = tex.repeat_y = False
    tex.use(0)

    def set_uniform(name, value):
        if name in program:
            program[name].value = value

    set_uniform("uSnapshot", 0)
    set_uniform("uViewportPx", (float(VIEW_W), float(VIEW_H)))
    set_uniform("uOriginPx", (origin_x, origin_y))
    set_uniform("uCellPx", CELL_PX)
    set_uniform("uGrid", (cols, rows))
    set_uniform("uWaveOriginUv", WAVE_ORIGIN_UV)
    set_uniform("uSpreadTime", spread_time)
    set_uniform("uDelayJitter", DELAY_JITTER)
    set_uniform("uLifetime", lifetime)
    set_uniform("uDriftPx", DRIFT_PX)
    set_uniform("uMaxPointPx", 256.0)
    set_uniform("uPanelColor", PANEL_COLOR)
    set_uniform("uReplicas", REPLICAS)
    set_uniform("uContentBoost", 1.0)
    set_uniform("uSweepTime", sweep_time)
    set_uniform("uArcBow", extra_uniforms.get("uArcBow", ARC_BOW))
    set_uniform("uShatterLead", extra_uniforms.get("uShatterLead", 0.14))
    # 揭开推进方向 = 飞行方向（指向虚拟触点）
    import math as _m
    _rad = _m.radians(drift_angle_deg if drift_angle_deg is not None else -90.0)
    set_uniform("uSweepDir", (_m.cos(_rad), _m.sin(_rad)))
    if drift_angle_deg is not None:
        import math
        rad = math.radians(drift_angle_deg)
        reach = VIRTUAL_TOUCH_FACTOR * math.hypot(snapshot.width, snapshot.height)
        center_x = origin_x + snapshot.width / 2.0
        center_y = origin_y + snapshot.height / 2.0
        set_uniform("uVirtualTouchPx",
                    (center_x + math.cos(rad) * reach, center_y + math.sin(rad) * reach))
    for name, value in extra_uniforms.items():
        set_uniform(name, value)

    still_vao = None
    if still_program is not None:
        def set_still(name, value):
            if name in still_program:
                still_program[name].value = value
        set_still("uSnapshot", 0)
        set_still("uViewportPx", (float(VIEW_W), float(VIEW_H)))
        set_still("uOriginPx", (origin_x, origin_y))
        set_still("uCellPx", CELL_PX)
        set_still("uGrid", (cols, rows))
        set_still("uWaveOriginUv", extra_uniforms.get("uWaveOriginUv", WAVE_ORIGIN_UV))
        # 静止层的 delay 参数必须与粒子层完全一致（含场景覆盖值），否则
        # 擦除边界错位——此前 warp/jitter 读全局常量，凝聚场景两层不齐
        set_still("uSpreadTime", extra_uniforms.get("uSpreadTime", spread_time))
        set_still("uDelayJitter", extra_uniforms.get("uDelayJitter", DELAY_JITTER))
        set_still("uNoiseScalePx", NOISE_SCALE_PX)
        set_still("uWaveWarp", extra_uniforms.get("uWaveWarp", WAVE_WARP))
        set_still("uNoiseSeed", (0.0, 0.0))
        set_still("uHashSeed", 0)
        set_still("uSweepTime", sweep_time)
        set_still("uArcBow", extra_uniforms.get("uArcBow", ARC_BOW))
        set_still("uPanelColor", PANEL_COLOR)
        set_still("uSweepDir", (_m.cos(_rad), _m.sin(_rad)))
        still_vao = ctx.vertex_array(still_program, [])

    fbo = ctx.simple_framebuffer((VIEW_W, VIEW_H), components=4)
    fbo.use()
    vao = ctx.vertex_array(program, [])

    frames = []
    for i in range(1, 10):
        fraction = i / 10.0
        # 凝聚模式：uTime 从 condense_from 倒放到 0
        t = condense_from * (1.0 - fraction) if condense_from is not None \
            else fraction * total
        set_uniform("uTime", t)
        fbo.clear(0.1255, 0.1333, 0.1490, 1.0)
        if still_vao is not None:
            still_program["uTime"].value = t
            still_vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        vao.render(moderngl.POINTS, vertices=cols * rows * REPLICAS)
        data = fbo.read(components=3)
        frame = Image.frombytes("RGB", (VIEW_W, VIEW_H), data).transpose(
            Image.FLIP_TOP_BOTTOM
        )
        frame.save(os.path.join(OUT_DIR, f"{tag}_t{int(fraction * 100):02d}.png"))
        frames.append((fraction, frame))

    fbo.release()
    tex.release()
    vao.release()
    return frames


def make_grid(frames, tag):
    cell_w, cell_h = 440, 534
    grid = Image.new("RGB", (cell_w * 3, cell_h * 3), (18, 19, 22))
    draw = ImageDraw.Draw(grid)
    for index, (fraction, frame) in enumerate(frames):
        thumb = frame.resize((cell_w, cell_h - 24), Image.LANCZOS)
        x = (index % 3) * cell_w
        y = (index // 3) * cell_h
        grid.paste(thumb, (x, y + 24))
        draw.text((x + 8, y + 4), f"t = {fraction:.1f} x total", fill=(154, 160, 166))
    grid.save(os.path.join(OUT_DIR, f"grid_{tag}.png"))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    ctx = moderngl.create_context(standalone=True)
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
    ctx.enable_direct(0x8642)  # GL_PROGRAM_POINT_SIZE：由 vertex shader 控制点大小

    snapshot = make_snapshot()

    program_new = ctx.program(vertex_shader=VERT_NEW, fragment_shader=FRAG)
    program_still = ctx.program(vertex_shader=STILL_VERT, fragment_shader=STILL_FRAG)
    base_uniforms = {
        "uTurbPx": TURB_PX_NEW,
        "uNoiseScalePx": NOISE_SCALE_PX,
        "uSwirl": SWIRL,
        "uFlowEvolve": FLOW_EVOLVE,
        "uPinch": PINCH,
        "uKick": KICK,
        "uWaveWarp": WAVE_WARP,
        "uFlarePx": FLARE_PX,
        "uPinchMaxPx": PINCH_MAX_PX,
        "uNoiseSeed": (0.0, 0.0),
        "uHashSeed": 0,
    }
    # 第五轮：逐粒子指向虚拟远触点。最常见路径 = 点下中部按钮（方向 ≈ 78°）
    frames_new = render_sequence(
        ctx, program_new, snapshot, SPREAD_TIME_NEW, LIFETIME_NEW,
        dict(base_uniforms), "new", drift_angle_deg=78.0,
        still_program=program_still
    )
    make_grid(frames_new, "new")

    # 用户举例场景：点击弹窗左上方外部——重点看右侧粒子方向是否更偏左
    frames_ul = render_sequence(
        ctx, program_new, snapshot, SPREAD_TIME_NEW, LIFETIME_NEW,
        dict(base_uniforms, uWaveOriginUv=(-0.15, -0.25)),
        "touch_upper_left", drift_angle_deg=-135.0,
        still_program=program_still
    )
    make_grid(frames_ul, "touch_upper_left")

    # 触点场景：点击弹窗下方外部（波前从底边咬入、粒子向下朝触点飞）
    frames_below = render_sequence(
        ctx, program_new, snapshot, SPREAD_TIME_NEW, LIFETIME_NEW,
        dict(base_uniforms, uWaveOriginUv=(0.5, 1.30)),
        "touch_below", drift_angle_deg=90.0,
        still_program=program_still
    )
    make_grid(frames_below, "touch_below")

    # 内容色增强主验证场景（第二十四轮）：彩色面板 A/B——boost 关（旧观感）
    # 对比 boost 开（色钮/色相条粒子更大更多更持久、白底先散尽）
    snapshot_colorful = make_colorful_snapshot()
    frames_cb = render_sequence(
        ctx, program_new, snapshot_colorful, SPREAD_TIME_NEW, LIFETIME_NEW,
        dict(base_uniforms, uWaveOriginUv=(0.5, 1.25)),
        "colorful_boost", drift_angle_deg=90.0,
        still_program=program_still
    )
    make_grid(frames_cb, "colorful_boost")
    frames_cp = render_sequence(
        ctx, program_new, snapshot_colorful, SPREAD_TIME_NEW, LIFETIME_NEW,
        dict(base_uniforms, uWaveOriginUv=(0.5, 1.25), uContentBoost=0.0),
        "colorful_plain", drift_angle_deg=90.0,
        still_program=program_still
    )
    make_grid(frames_cp, "colorful_plain")

    # 彩色面板的凝聚（出现）：机制自动继承——彩色更早凝实、副本多颗飞回聚格
    frames_cc = render_sequence(
        ctx, program_new, snapshot_colorful, 0.06, LIFETIME_NEW,
        dict(base_uniforms, uSpreadTime=0.06, uDelayJitter=0.20, uWaveWarp=0.30,
             uDriftPx=DRIFT_PX * 0.55, uWaveOriginUv=(0.5, 0.5), uSweepTime=0.0, uShatterLead=0.0),
        "colorful_condense", drift_angle_deg=-90.0,
        still_program=program_still, condense_from=0.55
    )
    make_grid(frames_cc, "colorful_condense")

    # 凝聚出现场景（问题1重做）：波前权重压到极低（spread 0.06）、区域噪声
    # 与逐粒子抖动大幅加强（warp 0.30 / jitter 0.20）——凝实顺序呈斑块状
    # "一缕缕"随机浮现而非从四周向中心收拢；位移减半（就近轻盈飘入）。
    # 帧序为倒放：t=0.1 是起点散云、t=0.9 接近完整
    frames_cond = render_sequence(
        ctx, program_new, snapshot, 0.06, LIFETIME_NEW,
        dict(base_uniforms, uSpreadTime=0.06, uDelayJitter=0.20, uWaveWarp=0.30,
             uDriftPx=DRIFT_PX * 0.55, uWaveOriginUv=(0.5, 0.5), uSweepTime=0.0, uShatterLead=0.0),
        "condense", drift_angle_deg=-90.0,
        still_program=program_still, condense_from=0.55
    )
    make_grid(frames_cond, "condense")

    # 宽面板场景（第十四轮）：模拟 DateTime/BottomSheet 级宽 Dialog，验证
    # 收拢封顶与初期爆散在大宽度下的表现
    snapshot_wide = make_snapshot(width_dp=560, height_dp=240)
    frames_wide = render_sequence(
        ctx, program_new, snapshot_wide, SPREAD_TIME_NEW, LIFETIME_NEW,
        dict(base_uniforms, uWaveOriginUv=(0.5, 1.20)),
        "wide_below", drift_angle_deg=90.0,
        still_program=program_still
    )
    make_grid(frames_wide, "wide_below")

    print("done:", OUT_DIR)


if __name__ == "__main__":
    main()
