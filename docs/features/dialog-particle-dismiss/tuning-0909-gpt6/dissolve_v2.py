"""第二版共同模型：任意起点的羽化解除 + 保持源色的颗粒 + 结构化的输运。

所有素材共用 V2Rules 一套参数；差异只来自控件尺寸、方向、触点远近、源图、随机种子以及
粒子化起点的数量、位置与启动时刻。接口不接收素材名称相关的参数，也不读取参考帧。

运动由几项叠加（均以短边/进度为单位，最终由拟合与目视共同决定取值）：
- 沿前沿推进方向（远离起点）的初速，可快速衰减，也可持续（径向扩张）；
- 沿前沿来源方向的剥离冲量（边缘起点处朝控件外），去掉逆风分量；
- 主方向风：随颗粒年龄与全局时间增强，方向可从上风某点呈扇形发散；单片速度倍率不同；
- 旧模型已认可的共同流场模板（按方向旋转、按控件尺寸缩放），带增益；
- 随风迁移的无散卷流；随年龄增大的独立离散。
"""
from dataclasses import dataclass,asdict,replace,fields
from pathlib import Path
import json,math
import numpy as np
import moderngl
from PIL import Image
from scipy.ndimage import gaussian_filter

HERE=Path(__file__).resolve().parent
STEP=1/240


@dataclass(frozen=True)
class Site:
    """控件归一化坐标中的起点、启动时刻（进度）与推进速率倍数。

    rate 为 None 时按种子在 [exp(-rate_variation), exp(rate_variation)] 内抽取；参考重放可记录观测到的倍数，
    但必须落在同一范围内（它相当于选定了随机量，而不是另设运动参数）。edge 同理：这个起点的前沿沿控件边缘
    加速的程度（0～1，乘在 edge_speed 上），为 None 时按种子在 [1-edge_variation, 1] 内抽取。
    length>0 时是线段起点：把 (x,y) 投到最近的一条边上，沿这条边取 length×边长 的一段同时开始解除，
    之后前沿沿法向推进（速度律与点起点相同）。length=0 即点起点。
    """
    x: float
    y: float
    start: float=0.
    rate: float|None=None
    edge: float|None=None
    length: float=0.


@dataclass(frozen=True)
class V2Rules:
    cell: float=1.65              # 颗粒网格（场景像素）；按原片放大后的颗粒尺寸与密度目视核对取值
    panel_cell: float=1.8        # 大面积纯色面板材质（自有白底弹窗）的颗粒网格：细颗粒，按面板占比与 cell 连续混合
    panel_jitter: float=0.04      # 面板材质额外的单片解除时刻扰动：解除带更松散，不显出规则圆形
    panel_speed: float=1.        # 面板材质颗粒的运动幅度增量（倍数 1+panel_speed×面板权重）：纯色面板化成轻雾随风流走
    panel_life: float=1.         # 面板材质颗粒寿命的倍数（按面板权重混合）
    panel_front_noise: float=0.05  # 面板材质额外的前沿低频起伏，纯色面上不显出规则圆弧
    panel_vanish_alpha: float=0.8 # 面板材质颗粒消失中由透明度完成的额外比例（纯色轻雾以淡出为主）
    panel_template: float=1.5     # 面板材质额外的共同流场模板增益（纯色面缺少纹理，靠成股流动显出层次）
    panel_size: float=0.75         # 面板材质颗粒释放后的粒径倍数（纯色面颗粒更细）
    panel_alpha: float=0.65        # 面板材质颗粒的透明度倍数（纯色面颗粒更通透，文字、图标等非面板色不受影响）
    panel_diffuse: float=0.       # 面板材质额外的独立离散（纯色面后释放的一片不聚成亮团）
    panel_var: float=0.           # 面板材质额外的顺风速度离散
    # 解除前沿：到达时刻 = 启动 + duration·(距离/短边)^exponent / 速率
    duration: float=1.605
    exponent: float=1.23
    rate_variation: float=0.4997
    late_bump: float=0.           # 最晚解除处的平滑隆起高度（进度）：最后解除的区域收成一整块
    late_bump_radius: float=0.22  # 隆起的半径（短边）
    bulk_time: float=0.           # 整体溶解时刻：>0 时前沿到这个时刻还没扫到的区域在此附近整体解除
    bulk_spread: float=0.1        # 整体溶解时刻的空间起伏幅度（进度）
    bulk_noise_scale: float=0.35  # 整体溶解起伏的空间尺度（短边）
    sweep_radius: float=0.        # 两段式推进：起点附近快速扫过的半径（短边，按起点速率换算）；0 为单段幂律
    sweep_time: float=0.1         # 扫过这一半径所用的进度
    creep_speed: float=0.3        # 半径之外的慢爬速度（短边/进度）
    front_step: float=0.          # 前沿台阶的高度（进度）：离起点超过 front_knee 的材料整体推迟这么多
    front_knee: float=0.35        # 台阶所在的距离（短边，按起点速率换算）
    front_step_width: float=0.1   # 台阶的过渡宽度（短边）
    content_edge: float=0.        # 前沿在画面颜色边界处变慢的强度（速度倍数 1/(1+强度×归一化颜色梯度^幂)）；0 为与内容无关
    content_edge_power: float=1.  # 上式中颜色梯度的幂（>1 只让强边界起作用）
    edge_ring: float=1.           # 加速带内侧慢环的速度倍数（1 为没有慢环）：边缘起点的前沿沿边成带后在此停一停
    edge_ring_width: float=0.     # 慢环宽度（短边）
    edge_site_margin: float=0.    # 离边界超过这个距离（短边）的起点按内部起点处理：圆形扩张，不沿边加速、不过慢环；0 为都按边缘起点
    edge_lead: float=0.6864           # 前缘（外法向与消逝方向同向的边）上沿边加速的削减比例：0 四边相同，1 前缘完全不加速
    edge_plateau: float=0.        # 沿边加速带的形状：0 按离边距离指数衰减，1 为平顶带（带内满速、带外不加速）
    edge_variation: float=0.      # 逐起点的沿边加速程度在 [1-edge_variation, 1] 内随机：有的起点沿边缘成带解除，有的只是圆形扩张
    front_aniso: float=0.         # 前沿推进随消逝方向的各向异性：>0 时顺风方向走得慢、逆风方向走得快
    front_noise: float=0.0008286
    front_noise_scale: float=0.28
    softmin: float=4.443
    round_front: float=0.05686         # 到达时刻场的空间平滑（短边）：两支前沿相遇处的残余表面收成圆角，不留尖角
    aspect_scale: float=0.6        # 长条控件的前沿按长宽比加快：距离除以 1+aspect_scale×(长宽比−1)
    meeting: float=0.             # 两支前沿相遇处的额外延迟（进度）。原片解除时刻图里没有这一结构，且它会在相遇线上形成细亮脊线，取 0
    edge_speed: float=1.448          # 前沿沿控件边缘推进的额外速度倍数（原片常先沿边缘解除出一条带）
    edge_depth: float=0.1         # 边缘加速带的深度（短边）：约 0.3 的宽带；0.02 的窄通道同样能对上时刻，但会沿边拉出细线
    meeting_width: float=0.1485      # 相遇判定的到达时刻差尺度
    jitter: float=0.03           # 单片开始运动时刻的独立扰动（表面按平滑场交出，不受它影响）
    surface_lead: float=0.03676     # 原表面在到达前开始变淡
    surface_tail: float=0.05501      # 到达后完全交出
    surface_ghost: float=0.       # 残影：主交接之后原图留在原位的不透明度（0 为没有残影）
    surface_ghost_tail: float=0.2 # 残影从解除时刻起淡去所用的进度
    # 颗粒外观
    size_birth: float=1.2       # 出生粒径：原片刚解除的区域仍遮得住背景，颗粒先稍大、再收到正常粒径
    size_settled: float=1.436
    settle: float=0.1461
    shrink_late: float=0.4342       # 年龄后段继续变细的比例
    size_sigma: float=0.4        # 颗粒大小的对数正态离散（释放后逐渐建立，平均面积不变）
    alpha_in: float=0.01581         # 颗粒自身出现的时间
    surface_grain: float=0.15       # 原表面按每格自己的解除时刻整格交出的份额（其余平滑渐隐）
    panel_surface_grain: float=0.66 # 面板材质额外的整格交出份额：白底弹窗的前沿是一粒粒脱落，不是一片渐隐
    panel_look_power: float=4.    # “整块面板”外观（整格交出、较实的轻雾）按面板权重的这个幂次生效：照片里的大块暗区不当成弹窗面板
    panel_full_alpha: float=1.385    # 整块面板颗粒透明度的额外倍数（乘在 panel_alpha 之上）
    panel_full_size: float=1.133     # 整块面板颗粒粒径的额外倍数（乘在 panel_size 之上）
    particle_follow: float=0.977    # 颗粒透明度跟随原表面交出的比例（1 为与原表面交叉渐变，0 为立即实心）：原片前沿是柔和的交叉渐变，不是一圈实心亮点
    vanish_alpha: float=0.997       # 消失中由透明度完成的比例，其余由颗粒缩小完成（原片颗粒消失前保持实心）
    roundness: float=1.          # 颗粒形状：0 为方片，1 为圆点
    # 颗粒光照（线性空间）：原片颗粒整体比源色亮 10%～20%，少数颗粒转到迎光面时有高光。
    light: float=0.3375             # 释放后颗粒的整体增亮
    light_early: float=0.5198       # 释放初段额外增亮（刚离开表面、仍成片时最亮），随年龄衰减
    light_early_tau: float=0.1804
    glint: float=0.01551             # 高光强度（加白）
    glint_power: float=21.44       # 高光的锐度：越大，同一时刻亮起的颗粒越少
    glint_rate: float=26.31        # 颗粒转动引起的明暗变化频率（弧度/进度）
    softness: float=0.4          # 圆点边缘的柔化宽度（占半径比例）；原片颗粒是边缘柔和的小圆斑
    # 运动（短边 / 进度）
    spread: float=0.            # 沿前沿推进方向（远离起点）的初速
    spread_tau: float=0.2987
    peel: float=0.4              # 沿前沿来源方向剥离（边缘起点处朝控件外）的初速
    peel_along: float=0.2        # 剥离冲量在顺风方向上保留的比例；逆风分量始终去掉
    peel_tau: float=0.08
    front_slide: float=0.         # 新材料不朝未解除区运动的程度（去掉沿解除场梯度的正分量，1 为完全去掉）
    front_slide_tau: float=0.15   # 上述约束随颗粒年龄放开的时间（进度）
    var_tail: float=0.            # 单片速度倍率的慢尾（>0 时一部分颗粒明显更慢；数值越大慢尾越集中在少数颗粒）
    motion_legacy: float=1.       # 1 时改用此前认可模型的运动规则（COMPUTE_LEGACY）；下面几项只在此时起作用
    legacy_touch_gap: float=0.65  # 旧运动的触点距离（短边）：决定朝触点的轻微偏转
    legacy_peel: float=1.         # 旧运动剥离冲量的倍数
    legacy_template: float=1.     # 旧运动模板流场的倍数
    legacy_separate: float=1.     # 旧运动初段独立分离的倍数
    legacy_peel_var: float=1.     # 旧运动剥离强度的逐片随机程度（1 为旧规则 0.1～1.9 倍，0 为全部取均值）
    legacy_wind: float=1.        # 旧运动恒定风的倍数（所有已释放颗粒共有的均匀漂移）
    legacy_deficit: float=0.16     # 旧运动的轴向速度平滑下限（短边/进度）
    legacy_forward: float=1.      # 旧运动迎风前沿初段减速的倍数
    legacy_indep: float=1.        # 旧运动单片独立离散（速度倍率与卷流）的倍数
    # 以下六项把此前认可模型的规则逐项接回（默认 0 不改变现有路径），用于与旧模型逐项对照。
    legacy_pressure: float=1.     # 旧运动的压力网格（>0 且 motion_legacy=1）：每 4 步按当前分布解一次单侧压力，抵消剥离造成的汇聚；数值为修正的倍数
    legacy_life: float=0.         # >0 时寿命用旧公式：出生时刻增益渐变、上限 .865+.115×random.z−出生、内容色延寿 30%、下限 .11
    legacy_normals: float=1.      # >0 时剥离法向（及压缩估计）用旧平滑：σ=3 → 归一 → σ=7，不再归一，前沿相遇处法向变短
    legacy_light: float=0.        # >0 时颗粒明暗用旧片元规则（暗部提亮、斜向明暗条纹、初段增亮、加性高光、彩色去灰、近白限幅），不用 light/glint
    legacy_alpha: float=0.        # >0 时颗粒透明度用旧规则：.001～.022 淡入、.018～.12 内降到 .92、寿命最后 .075 只靠透明度淡出（不缩小）
    legacy_clock: float=0.        # >0 且 motion_legacy=1 时模板时钟用旧定义 t=time−offset（只含种子形变的偏移，逐片取值），与解除场 T 无关；按起点解除时模板相位因此是全局时间（A/B 选项）
    motion_lag: float=0.          # 运动时钟相对解除时刻的比例延迟：颗粒在 解除时刻×(1+motion_lag) 才开始运动
    onset: float=0.002886               # 起动延迟（进度）：全部运动分量乘 smoothstep(0, onset, 年龄)
    spread_downwind: float=0.     # 外鼓只保留顺主方向推进的前沿（按前沿方向与主方向夹角的余弦加权）
    back_flow: float=0.           # 颗粒逆着消逝主方向运动的分量保留多少（1 全保留，0 去掉）：原片的运动基本顺主方向，不回流
    shelter: float=0.             # 风影强度（仅旧运动路径）：上风方向仍有未解除表面时，风与模板分量削减的比例
    shelter_step: float=0.06      # 风影向上风方向取样的步长（短边，共 4 步）
    shelter_rest: float=0.15      # 完全处在风影里时风与模板分量的残余比例（云仍缓慢漂移）
    shelter_soft: float=0.02      # 判定「上风仍未解除」的软阈值宽度（进度）
    shield: float=0.01129              # 遮挡强度（仅旧运动路径）：顺风前方仍有材料（未解除或悬停中）时颗粒减速的比例
    shield_linger: float=0.1255      # 解除后的材料在原处悬停、仍能挡住后方颗粒的时长（进度）
    shield_reach: float=0.08871      # 向顺风前方检查遮挡的距离（短边）
    obstacle: float=0.1016            # 尚未解除的原表面挡住颗粒：去掉颗粒朝未解除区域内部的速度分量（1 为完全挡住），颗粒沿其边界滑过
    obstacle_depth: float=0.06782    # 挡住的强度随“该处还要多久才解除”在这个进度范围内升到满值
    carry: float=0.               # 前沿顺风推进时带走刚解除的材料：初速沿主方向，大小按前沿推进方向与主方向的一致程度（逆风、横风的前沿不带）
    carry_tau: float=0.15         # 带走初速的衰减时间（进度）
    escape: float=0.              # 靠近控件边缘的材料解除后朝该边外逸出的初速（逆风分量去掉）；不依赖起点，不会向一点汇聚
    escape_depth: float=0.08      # 逸出随离边距离衰减的尺度（短边）
    escape_tau: float=0.2         # 逸出初速的衰减时间（进度）
    wind0: float=0.1187
    wind1: float=0.7096
    wind_tau: float=0.3668
    wind_global: float=0.004756       # 全局时间上的整体加速（所有已释放颗粒一起）
    wind_global_start: float=0.6938
    wind_global_span: float=0.4531
    wind_var: float=0.2          # 单片顺风速度倍率的离散（形成顺风细缕）
    var_tau: float=0.1           # 速度离散随年龄建立的时间：刚释放的颗粒成片同速，之后才逐渐拉开
    turb_tau: float=0.2904          # 卷流随年龄建立的时间
    fan: float=0.178                # 0 为平行风；增大时方向从上风一点呈扇形发散
    converge: float=0.1334           # 向触点一侧的目标点收拢的程度（0 为平行风）；目标点随触点远近前后移动
    target_near: float=0.3869        # 触点最近时目标点在控件边缘外的距离（短边）
    target_far: float=2.253        # 触点最远时目标点在控件边缘外的距离（短边）
    gain_near: float=0.3         # 触点最近时的运动幅度倍数
    gain_far: float=1.4         # 触点最远时的运动幅度倍数
    fan_distance: float=2.356      # 扇形源点在控件上风侧的距离（短边）
    template: float=1.031           # 旧共同流场模板的增益（0 为不用）
    template_div: float=1.           # 模板无旋（汇聚／发散）部分保留的比例；0 只用无散部分，不产生点状汇聚
    template_clock: float=0.         # 模板时钟随材料对齐（与旧模型相同）：材料按新解除场释放，模板时间按与模板原生解除时刻的差值平移
    turb: float=0.6236
    turb_scale: float=0.6556
    turb_rate: float=0.4999
    diffuse: float=0.05105
    separate: float=0.05935          # 释放初段各颗粒的独立分离速度（短边/进度），打散规则网格
    separate_tau: float=0.1332
    inflate: float=0.0008862            # 整团以控件中心为原点等比外扩的速率（1/进度）：边缘颗粒向外、中心基本不动
    inflate_tau: float=0.08609       # 外扩随颗粒年龄建立的时间
    grow: float=0.               # 外扩同时颗粒按同一比例变大的程度（0 为不变）
    diffuse_tau: float=0.3539
    inertia: float=0.07125
    touch_default: float=0.5586      # 没有真实触点（返回键、代码关闭）时的触点远近；取参考重放细化值的典型水平
    # 寿命
    life0: float=0.1018
    life1: float=0.3098
    life_born: float=1.633e-05          # 寿命随解除时刻增长的系数（寿命 += 系数×解除时刻）
    life_shape: float=1.176
    fade: float=0.388
    end: float=0.92


# 默认值为 2026-09-27 傍晚的采用值 E2（在 E 的基础上把触点远近对运动幅度的调节放宽到 0.3～1.4 倍，没有真实触点时的默认幅度不变；参考重放的触点远近按实测颗粒速度标定）。E：运动改用此前认可模型的规则（含压力网格与旧法向平滑、完整流场模板），颗粒外观按华为九段重新拟合（analysis/match-cache/fit/P6.json）；D2 见 adopted-20260927d2.json。
RULES=V2Rules()


def _mix32(v):
    v=np.uint32(v&0xffffffff)
    v=(v^(v>>np.uint32(16)))*np.uint32(0x7feb352d)
    v=(v^(v>>np.uint32(15)))*np.uint32(0x846ca68b)
    return int(v^(v>>np.uint32(16)))


def hash_uniform(count,seed,salt):
    """整数散列的均匀随机数，跨端可复现。种子先整体散列一次：相邻种子得到互不相关的序列，
    而不是同一序列错开几位（否则种子 0 与种子 4 会给出相同的起点）。"""
    with np.errstate(over='ignore'):
        s=_mix32((int(seed)&0xffffffff)^(int(salt)&0xffffffff))
        x=(np.arange(count,dtype=np.uint64)+np.uint64(s))&np.uint64(0xffffffff)
        x=x.astype(np.uint32)
        x=(x^(x>>16))*np.uint32(0x7feb352d)
        x=(x^(x>>15))*np.uint32(0x846ca68b)
        x=x^(x>>16)
    return (x>>8).astype('float32')/np.float32(16777216)


def sample_sites(width,height,seed,direction=90.):
    """一般播放的起点（初始条件的随机采样），分布取自十段原片的观测：

    - 数量 1～4 个（约 10%／45%／35%／10%），长条控件多一个；
    - 首个起点约 20% 在内部，其余在边缘；边缘位置按边长加权，并偏向顺风侧的边
      （十段原片的首个起点：顺风边 5、逆风边 2、内部 2、侧边 1）；
    - 首个起点在 0 时刻启动，其余在 0～0.2 之间；速率倍数由种子在全局随机范围内抽取；
    - 沿边长度（线段起点）取 0.6×u³：中位数约 0.075、约三成超过 0.19，与九段参考细化后的长度分布
      （中位数 0.08、四分之一超过 0.19）一致。
    """
    a=hash_uniform(32,seed,0x1f83d9ab);lengths=hash_uniform(16,seed,0x2545f491)
    u=float(a[0]);count=1 if u<.10 else 2 if u<.55 else 3 if u<.90 else 4
    aspect=max(width,height)/min(width,height)
    # 长条控件沿长轴多一到两个起点，否则远端要等很久才解除。
    if aspect>1.2:count+=int((aspect-1.2)/.5+.5)
    ang=math.radians(direction);wx,wy=math.cos(ang),-math.sin(ang)
    # 四条边：上、右、下、左；外法向与主方向的点积决定顺风偏好。
    edges=[((0,-1),width),((1,0),height),((0,1),width),((-1,0),height)]
    weight=np.array([length*(1+.7*(nx*wx+ny*wy)) for (nx,ny),length in edges])
    cdf=np.cumsum(weight)/weight.sum()
    out=[]
    for i in range(count):
        e,v,w,t=(float(q) for q in a[1+4*i:5+4*i])
        if i==0 and w<.20:
            x,y=.2+.6*e,.2+.6*v
        else:
            k=int(np.searchsorted(cdf,e));jitter=(w-.5)*.06
            x,y=[(v,jitter),(1-jitter,v),(v,1-jitter),(jitter,v)][k]
        out.append(Site(float(x),float(y),0. if i==0 else .2*t,length=float(.6*lengths[i]**3)))
    return tuple(out)


_GRAPH_CACHE={}
_EDGE_SPEED={}


def _edge_key(gx,gy,width,height,edge_speed,edge_depth,plateau=0.,ring=1.,ring_width=0.,lead=None):
    return (gx,gy,round(width,3),round(height,3),round(edge_speed,6),round(edge_depth,6),round(plateau,4),round(ring,4),round(ring_width,4),lead)


def _edge_graph(gx,gy,width,height,edge_speed,edge_depth,plateau=0.,ring=1.,ring_width=0.,lead=None):
    """16 邻域网格图：边权 = 步长 / 两端平均速度。速度在控件边缘附近更快。

    plateau=0 时加速随离边距离按指数衰减；plateau=1 时是一条深 edge_depth 的平顶带（带内满速、带外不加速，
    边界约 0.2×深度宽的平滑过渡）：前沿沿边成带之后，向内按正常速度推进。两者按 plateau 线性混合。
    lead 为上、右、下、左四条边各自的加速倍数（按消逝方向算出，见 release_field），None 时四条边相同。"""
    key=_edge_key(gx,gy,width,height,edge_speed,edge_depth,plateau,ring,ring_width,lead)
    if key in _GRAPH_CACHE:return _GRAPH_CACHE[key]
    from scipy.sparse import coo_matrix
    span=min(width,height)
    x=(np.arange(gx)+.5)/gx*width/span;y=(np.arange(gy)+.5)/gy*height/span
    X,Y=np.meshgrid(x,y)
    e=np.minimum(np.minimum(X,width/span-X),np.minimum(Y,height/span-Y))
    ed=max(edge_depth,1e-3);em=np.maximum(e,0)
    def band(v):
        v=np.maximum(v,0)
        return (1-plateau)*np.exp(-v/ed)+plateau*(1-np.clip((v-.9*ed)/(.2*ed),0,1)**2*(3-2*np.clip((v-.9*ed)/(.2*ed),0,1)))
    if lead is None:shape=band(em)
    else:shape=np.max([f*band(v) for f,v in zip(lead,(Y,width/span-X,height/span-Y,X))],axis=0)
    speed=1+edge_speed*shape
    if ring<1. and ring_width>0.:
        # 加速带内侧一圈慢环：沿边成带之后停一停，再以正常速度向内推进（解除时刻出现“台阶”）。
        def ss(a,b,v):
            t=np.clip((v-a)/max(b-a,1e-6),0,1);return t*t*(3-2*t)
        inring=ss(.9*ed,1.1*ed,em)*(1-ss(ed+ring_width-.1*ed,ed+ring_width+.1*ed,em))
        speed=speed*(1-(1-ring)*inring)
    _EDGE_SPEED[key]=speed.copy()
    speed=speed.ravel()
    dx=width/span/gx;dy=height/span/gy
    idx=np.arange(gx*gy).reshape(gy,gx);rows=[];cols=[];vals=[]
    for sx,sy in [(1,0),(0,1),(1,1),(1,-1),(2,1),(1,2),(2,-1),(1,-2)]:
        a=idx[max(0,-sy):gy-max(0,sy),max(0,-sx):gx-max(0,sx)]
        b=idx[max(0,sy):gy-max(0,-sy)+(0 if sy<=0 else 0),max(0,sx):gx-max(0,-sx)]
        b=idx[max(0,sy):max(0,sy)+a.shape[0],max(0,sx):max(0,sx)+a.shape[1]]
        a=a.ravel();b=b.ravel();step=math.hypot(sx*dx,sy*dy)
        w=step*2/(speed[a]+speed[b])
        rows+=[a,b];cols+=[b,a];vals+=[w,w]
    g=coo_matrix((np.concatenate(vals),(np.concatenate(rows),np.concatenate(cols))),shape=(gx*gy,gx*gy)).tocsr()
    if len(_GRAPH_CACHE)>32:_GRAPH_CACHE.clear()
    _GRAPH_CACHE[key]=(g,x,y)
    return _GRAPH_CACHE[key]


def content_speed(image,gx,gy,strength,power=1.):
    """画面内容对前沿速度的影响：颜色边界处变慢（前沿在物体轮廓处停一停，一块一块地解除）。

    image 为控件前景 RGB(A)；按网格求 Lab 颜色梯度，按本图 90 分位归一化后，速度倍数 = 1/(1+strength×梯度^power)。"""
    import cv2
    rgb=np.asarray(image)[...,:3].astype(np.uint8)
    small=cv2.resize(rgb,(gx*4,gy*4),interpolation=cv2.INTER_AREA)
    lab=cv2.cvtColor(cv2.GaussianBlur(small,(0,0),1.2),cv2.COLOR_RGB2LAB).astype(np.float32)
    gxl=cv2.Sobel(lab,cv2.CV_32F,1,0,ksize=3);gyl=cv2.Sobel(lab,cv2.CV_32F,0,1,ksize=3)
    mag=np.sqrt((gxl**2+gyl**2).sum(2))
    mag=cv2.resize(mag,(gx,gy),interpolation=cv2.INTER_AREA)
    mag=mag/max(float(np.percentile(mag,90)),1e-6)
    return 1./(1.+strength*np.clip(mag,0,2.)**power)


def _speed_graph(gx,gy,width,height,speed):
    """任意逐格速度图的 16 邻域网格图：边权 = 步长 / 两端平均速度。"""
    from scipy.sparse import coo_matrix
    span=min(width,height)
    x=(np.arange(gx)+.5)/gx*width/span;y=(np.arange(gy)+.5)/gy*height/span
    dx=width/span/gx;dy=height/span/gy;speed=np.asarray(speed,np.float64).ravel()
    idx=np.arange(gx*gy).reshape(gy,gx);rows=[];cols=[];vals=[]
    for sx,sy in [(1,0),(0,1),(1,1),(1,-1),(2,1),(1,2),(2,-1),(1,-2)]:
        a=idx[max(0,-sy):gy-max(0,sy),max(0,-sx):gx-max(0,sx)]
        b=idx[max(0,sy):max(0,sy)+a.shape[0],max(0,sx):max(0,sx)+a.shape[1]]
        a=a.ravel();b=b.ravel();step=math.hypot(sx*dx,sy*dy)
        w=step*2/(speed[a]+speed[b])
        rows+=[a,b];cols+=[b,a];vals+=[w,w]
    g=coo_matrix((np.concatenate(vals),(np.concatenate(rows),np.concatenate(cols))),shape=(gx*gy,gx*gy)).tocsr()
    return g,x,y


def edge_segment(s,width,height):
    """线段起点（短边单位的两端点）：穿过 (x,y)、平行于最近的一条边，长度为 length×该边边长，
    超出控件时整段平移回控件内（length=1 即与整条边等长）。起点在边上时就是那条边的一段；
    length 从 0 连续变化，点起点是它的特例。"""
    span=min(width,height);W=width/span;H=height/span
    x=min(max(s.x,0.),1.)*W;y=min(max(s.y,0.),1.)*H
    k=int(np.argmin([y,W-x,H-y,x]))          # 上、右、下、左
    L=W if k in (0,2) else H;c=x if k in (0,2) else y
    l=min(max(s.length,0.),1.)*L;a=min(max(c-l/2,0.),L-l);b=a+l
    return (a,y,b,y) if k in (0,2) else (x,a,x,b)


def _segment_distance(px,py,seg):
    x0,y0,x1,y1=seg;dx,dy=x1-x0,y1-y0;L2=dx*dx+dy*dy
    t=np.clip(((px-x0)*dx+(py-y0)*dy)/max(L2,1e-12),0,1)
    return np.hypot(px-(x0+t*dx),py-(y0+t*dy))


def site_distances(nx,ny,width,height,sites,rules=RULES,grid=72,edge=None,image=None,lead=None):
    """每个起点到各颗粒的距离（短边）。edge_speed×该起点的沿边程度 >0 时为带边缘加速的测地距离（粗网格求解再插值）；
    content_edge>0 且给了控件图像时，前沿在颜色边界处变慢（所有起点都受影响）。"""
    span=min(width,height)
    yy,xx=np.mgrid[:ny,:nx].astype('float64')
    px=(xx+.5)/nx*width/span;py=(yy+.5)/ny*height/span
    edge=[1.]*len(sites) if edge is None else edge
    from scipy.sparse.csgraph import dijkstra
    from scipy.ndimage import map_coordinates
    gx=grid if width>=height else max(8,round(grid*width/height));gy=grid if height>=width else max(8,round(grid*height/width))
    content=None
    if rules.content_edge>0 and image is not None:
        content=content_speed(image,gx,gy,rules.content_edge,rules.content_edge_power)
    out=[]
    for s,f in zip(sites,edge):
        speed=rules.edge_speed*max(f,0.)
        line=s.length>0
        seg=edge_segment(s,width,height) if line else None
        # 离控件边界较远的起点是内部起点：只做圆形扩张，不沿边加速，也不经过慢环。
        margin=min(s.x*width,(1-s.x)*width,s.y*height,(1-s.y)*height)/span
        interior=rules.edge_site_margin>0 and margin>rules.edge_site_margin and not line
        if (speed<=1e-6 or interior) and content is None:
            out.append(_segment_distance(px,py,seg) if line else np.hypot(px-s.x*width/span,py-s.y*height/span));continue
        if content is not None:
            base=np.ones((gy,gx))
            if not (speed<=1e-6 or interior):
                g0,_,_=_edge_graph(gx,gy,width,height,round(speed,4),rules.edge_depth,rules.edge_plateau,rules.edge_ring,rules.edge_ring_width,lead)
                base=_EDGE_SPEED[_edge_key(gx,gy,width,height,round(speed,4),rules.edge_depth,rules.edge_plateau,rules.edge_ring,rules.edge_ring_width,lead)]
            g,x,y=_speed_graph(gx,gy,width,height,base*content)
        else:
            g,x,y=_edge_graph(gx,gy,width,height,round(speed,4),rules.edge_depth,rules.edge_plateau,rules.edge_ring,rules.edge_ring_width,lead)
        if line:
            # 线段起点：线段经过的所有格同时作为源点（多源最短路）。
            x0,y0,x1,y1=seg;m=max(2,int(math.ceil(math.hypot(x1-x0,y1-y0)/min(width/span/gx,height/span/gy)*2))+1)
            ts=np.linspace(0,1,m);ii=np.clip(np.floor((y0+ts*(y1-y0))/(height/span)*gy),0,gy-1).astype(int)
            jj=np.clip(np.floor((x0+ts*(x1-x0))/(width/span)*gx),0,gx-1).astype(int)
            d=dijkstra(g,indices=np.unique(ii*gx+jj),min_only=True).reshape(gy,gx)
        else:
            sx=s.x*width/span;sy=s.y*height/span
            i=int(np.clip(np.floor(sy/(height/span)*gy),0,gy-1));j=int(np.clip(np.floor(sx/(width/span)*gx),0,gx-1))
            d=dijkstra(g,indices=i*gx+j).reshape(gy,gx)
            d=d+math.hypot(x[j]-sx,y[i]-sy)          # 起点到所在格中心的直线段
        # 插值回颗粒网格（格中心坐标系）
        cy=(py/(height/span)*gy-.5);cx=(px/(width/span)*gx-.5)
        out.append(map_coordinates(d,[cy,cx],order=1,mode='nearest'))
    return out


def release_field(nx,ny,width,height,sites,seed,rules=RULES,panel=0.,direction=None,image=None):
    """任意起点的到达时刻场（进度）。相邻起点的前沿以软最小值连续相遇。"""
    span=min(width,height)
    y,x=np.mgrid[:ny,:nx].astype('float64')
    x=(x+.5)/nx*width/span;y=(y+.5)/ny*height/span
    wind=None
    if direction is not None and rules.front_aniso!=0:
        a=math.radians(direction);wind=(math.cos(a),-math.sin(a))
    rates=hash_uniform(max(1,len(sites)),seed,0x3c6ef372);edges=hash_uniform(max(1,len(sites)),seed,0x1b873593)
    edge=[1.-rules.edge_variation*float(edges[i]) if s.edge is None else float(s.edge) for i,s in enumerate(sites)]
    potential=[]
    # 长条控件的前沿按长宽比加快，整体解除节奏与方形控件相近（华为参考都接近方形，此项不改变它们）。
    stretch=1+rules.aspect_scale*(max(width,height)/span-1)
    lead=None
    if rules.edge_lead>0 and direction is not None:
        # 沿边加速按边的朝向区分：外法向与消逝方向同向的边（前缘、顺风侧）加速减弱，背风的后缘与侧边照常。
        a=math.radians(direction);wx,wy=math.cos(a),-math.sin(a)
        lead=tuple(round(1-rules.edge_lead*max(nx_*wx+ny_*wy,0.),4) for nx_,ny_ in ((0,-1),(1,0),(0,1),(-1,0)))
    for i,(s,d) in enumerate(zip(sites,site_distances(nx,ny,width,height,sites,rules,edge=edge,image=image,lead=lead))):
        d=d/stretch
        if wind is not None:
            # 前沿随消逝方向有快慢：沿主方向（顺风）推进的距离按 1+front_aniso 计，逆风按 1-front_aniso 计。
            dx=x-s.x*width/span;dy=y-s.y*height/span;r=np.maximum(np.hypot(dx,dy),1e-6)
            d=d*(1+rules.front_aniso*(dx*wind[0]+dy*wind[1])/r*np.minimum(r/.08,1.))
        rate=math.exp(2*rules.rate_variation*(float(rates[i])-.5)) if s.rate is None else float(s.rate)
        u=d/rate
        t=rules.duration*u**rules.exponent
        if rules.sweep_radius>0:
            # 两段式推进：起点附近 sweep_radius 以内在 sweep_time 内快速扫过，之外以 creep_speed（短边/进度）慢爬。
            R=rules.sweep_radius
            t=np.where(u<=R,rules.sweep_time*(np.minimum(u,R)/R)**rules.exponent,rules.sweep_time+(u-R)/max(rules.creep_speed,1e-3))
        if rules.front_step>0:
            # 前沿在离起点 front_knee（短边）处明显放慢：该距离之外的材料整体推迟 front_step，形成台阶与晚解除的平台；
            # 几个起点各自的“台阶圆”之外就是晚区，晚区的边界由圆弧组成。
            k=np.clip((u-rules.front_knee)/max(rules.front_step_width,1e-3),0,1)
            t=t+rules.front_step*k*k*(3-2*k)
        potential.append(.004+s.start+t)
    stack=np.maximum(np.stack(potential),1e-4)
    arrival=np.power(np.sum(stack**-rules.softmin,axis=0),-1/rules.softmin)
    if rules.bulk_time>0:
        # 整体溶解：前沿在 bulk_time 之前没有扫到的区域在 bulk_time 附近整体解除（带低频起伏），
        # 不再等各起点的前沿慢慢推进过去、在相遇处留下一条最后解除的脊。
        ph2=hash_uniform(4,seed,0x6a09e667)*6.2831853;k2=1/max(rules.bulk_noise_scale,1e-3)
        nb=.5+.25*(np.sin(x*k2*1.1+y*k2*.7+ph2[0])+np.sin(y*k2*1.3-x*k2*.4+ph2[1]))
        B=np.maximum(rules.bulk_time+rules.bulk_spread*(nb-.5),1e-3)
        arrival=np.power(arrival**-rules.softmin+B**-rules.softmin,-1/rules.softmin)
    if rules.meeting>0 and len(stack)>1:
        # 两支前沿相遇的地带稍晚解除：到达时刻差越小，延迟越大。
        part=np.sort(stack,axis=0);gap=part[1]-part[0]
        arrival=arrival+rules.meeting*np.exp(-gap/max(rules.meeting_width,1e-4))
    if rules.late_bump>0:
        # 最后解除的区域收成一块：以当前到达时刻最晚处为中心加一个平滑隆起。多起点的前沿相遇线（沿起点间的分界）
        # 本来是一条条最晚解除的细脊，会带出交叉的细带；隆起把最晚区变成一整块，晚区边界随之成为圆弧。
        a=gaussian_filter(arrival,max(1.,.04*span/(width/nx)))
        iy,ix=np.unravel_index(int(np.argmax(a)),a.shape)
        du=x-x[iy,ix];dv=y-y[iy,ix];r=max(rules.late_bump_radius,1e-3)
        arrival=arrival+rules.late_bump*np.exp(-(du*du+dv*dv)/(2*r*r))
    if rules.round_front>0:
        # 相遇处的等时线会形成尖角；对到达时刻做空间平滑，使残余表面保持圆滑轮廓。
        sig=rules.round_front*span/(width/nx)
        arrival=gaussian_filter(arrival,sig,mode='nearest')
    # 低频空间噪声改变局部推进速度，前沿不再是规则圆弧。
    ph=hash_uniform(6,seed,0x5be0cd19)*6.2831853
    k=1/rules.front_noise_scale
    noise=(np.sin(x*k*1.3+y*k*.5+ph[0])*np.sin(y*k*1.1-x*k*.3+ph[1])
           +.45*np.sin(x*k*2.7-y*k*1.9+ph[2])*np.sin(y*k*2.3+x*k*1.2+ph[3]))
    arrival=arrival+(rules.front_noise+rules.panel_front_noise*panel)*noise
    # 长条控件或起点离得远时，整个解除场按比例加快（保持先后顺序与相对速度），
    # 让 97% 的表面在约 0.78 之前解除，而不是最后一大片在上限处同时解除。
    q=float(np.percentile(arrival,97))
    if q>.66:arrival=arrival*(.66+.12*math.tanh((q-.66)/.12))/q
    # 全程结束前解除完毕：平滑上限。
    arrival=.80-.03*np.logaddexp(0,(.80-arrival)/.03)
    return np.maximum(arrival,.002).astype('float32')


def build_materials(width,height,foreground,direction,seed,sites,rules=RULES):
    from unified_model import panel_material
    # 材质：面板色占比高的控件（白底弹窗）用细颗粒；照片类内容用 cell。两者按面板权重连续混合。
    panel_color,panel,_=panel_material(foreground)
    cell=rules.cell+(rules.panel_cell-rules.cell)*panel
    nx=max(2,math.ceil(width/cell));ny=max(2,math.ceil(height/cell))
    cx=width/nx;cy=height/ny;n=nx*ny
    T=release_field(nx,ny,width,height,sites,seed,rules,panel,direction,foreground)
    sx,sy=cx,cy
    if rules.legacy_normals>0:
        # 旧模型的法向（unified_model.materials）：float32 场、σ=3 平滑 → 归一 → σ=7 平滑，不再归一。
        sx,sy=np.float32(cx),np.float32(cy)
        gy,gx=np.gradient(gaussian_filter(T,3),sy,sx)
        length=np.maximum(np.hypot(gx,gy),1e-6)
        nxv=gaussian_filter(gx/length,7);nyv=gaussian_filter(gy/length,7)
    else:
        gy,gx=np.gradient(gaussian_filter(T.astype('float64'),2.),cy,cx)
        length=np.maximum(np.hypot(gx,gy),1e-9)
        nxv=gaussian_filter(gx/length,3);nyv=gaussian_filter(gy/length,3)
        ln=np.maximum(np.hypot(nxv,nyv),1e-6);nxv/=ln;nyv/=ln
    r=hash_uniform(n*4,seed,0x9e3779b9).reshape(n,4)
    yy,xx=np.mgrid[:ny,:nx]
    base=np.zeros((n,12),dtype='float32')
    base[:,0]=((xx.ravel()+.5)*cx);base[:,1]=((yy.ravel()+.5)*cy)
    born=T.ravel()+(rules.jitter+rules.panel_jitter*panel)*(r[:,0]-.5)*2
    base[:,2]=np.maximum(born,.001);base[:,3]=np.arange(n)
    base[:,4]=nxv.ravel();base[:,5]=nyv.ravel()
    # 寿命用独立散列，避免与离散速度、剥离强度相关（相关会把长寿命颗粒集中到同类轨迹上）。
    q=hash_uniform(n,seed,0x7f4a7c15)
    life=rules.life0+rules.life1*(-np.log(np.maximum(q,1e-3)))**(1/rules.life_shape)/1.2
    # 寿命随解除时刻增长（此前认可模型的做法）：早解除的颗粒很快散尽、把最早的区域清空，晚解除的（弧形亮带）留得更久。
    life=life+rules.life_born*np.maximum(born,0.)
    life=life*(1+(rules.panel_life-1)*panel)
    life=np.minimum(life,rules.end-base[:,2]);base[:,6]=np.maximum(life,.06)
    base[:,7]=T.ravel()          # 平滑到达时刻：原表面据此逐像素交出
    base[:,8:]=r
    ph,pw=foreground.shape[:2]
    px=np.clip((base[:,0]/width*pw).astype(int),0,pw-1);py=np.clip((base[:,1]/height*ph).astype(int),0,ph-1)
    keep=foreground[py,px,3]>8
    if rules.legacy_life>0:
        # 旧寿命公式（unified_model.materials）。随机量用本模型自己的：寿命项用独立散列 q，上限用 random.z（与旧模型同一分量），
        # 只求分布一致，不求逐片相同；内容色权重按旧规则在格心取样。
        from unified_model import RULES as OLD,content_weights,smooth
        born=base[:,2]
        content=content_weights(foreground[py,px,:3].astype('float32')/255,np.asarray(panel_color,'float32'),panel)*foreground[py,px,3].astype('float32')/np.float32(255)
        cap=.865+.115*base[:,10]-born
        early=OLD['life_early_gain']
        gain=early+(OLD['life_gain']-early)*smooth((born-OLD['life_birth_start'])/OLD['life_birth_span'])
        life=np.maximum(np.minimum((.10+.22*(-np.log(np.maximum(q,.004)))**.85+.20*born)*gain,cap),.11)
        base[:,6]=np.maximum(np.minimum(life*(1+.30*content),cap),.11)
    # 模板原生解除时刻（旧共同模型从同一模板导出的时序）：只在模板时钟对齐打开时计算，否则与 T 相同（差值为零）。
    native=T
    compression=np.zeros(n,np.float32)
    if rules.motion_legacy>0:
        from unified_model import peel_compression
        compression=peel_compression(nxv,nyv,sx,sy,min(width,height),direction).ravel()
    clock_offset=np.zeros(0,np.float32)
    if rules.template_clock>0 or rules.motion_legacy>0:
        from unified_model import release_components
        released,offset=release_components(nx,ny,direction,width,height,seed)
        native=(released-offset).astype('float32')
        # 旧模型逐片的模板时钟偏移（materials 的 physical.w），供 legacy_clock 使用。
        if rules.motion_legacy>0:clock_offset=offset.ravel()[keep].astype('float32')
    return dict(base=base[keep],nx=nx,ny=ny,cell=(cx,cy),release=T,native=native,compression=compression[keep].astype('float32'),clock_offset=clock_offset,count=int(keep.sum()),panel=float(panel),panel_color=[float(v) for v in panel_color])


COMPUTE=r'''#version 430
layout(local_size_x=256) in;
struct Material{vec4 src;vec4 physical;vec4 random;};
struct State{vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A{Material particles[];};
layout(std430,binding=1) buffer B{State state[];};
uniform int count;
uniform float time,dt,span,distance_gain;
uniform vec2 wind,card;
uniform float spread,spread_tau,peel,peel_along,peel_tau,wind0,wind1,wind_tau,wind_global,wind_global_start,wind_global_span;
uniform float escape,escape_depth,escape_tau,carry,carry_tau,obstacle,obstacle_depth,back_flow,spread_downwind,onset,motion_lag;
uniform float front_slide,front_slide_tau,var_tail;
uniform sampler2D obstacle_map;
uniform sampler2D native_map;
uniform float template_clock;
uniform float wind_var,var_tau,turb_tau,fan,fan_distance,template_gain,turb,turb_scale,turb_rate,diffuse,diffuse_tau,inertia,inflate,inflate_tau,separate,separate_tau;
uniform float converge,target_distance;
uniform float panel_template,panel_weight,panel_diffuse,panel_var;
uniform vec4 phase;
// 旧共同流场模板（与 renderer.py 相同的显式插值与坐标变换）。
uniform sampler3D guide_field;
uniform sampler3D confidence_field;
uniform vec2 guide_rotation;
uniform float guide_gain;
uniform vec4 field_geometry;
uniform vec4 variation_affine,variation_bend,variation_wave;
uniform vec2 variation_clock;
uint hash32(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float unit(uint x){return float(hash32(x)>>8u)*(1./16777216.);}
vec2 local_anchor(float index){
    float along=index*span*.96*field_geometry.z;
    float across=span*.16*field_geometry.z*sin(index*2.4+variation_wave.z);
    vec2 axis=mix(vec2(1,0),vec2(0,1),field_geometry.w);
    return axis*along+vec2(-wind.y,wind.x)*across;
}
vec3 field_cell(ivec3 c){return vec3(texelFetch(guide_field,c,0).xy,texelFetch(confidence_field,c,0).r);}
vec3 sample_field(vec3 uvw){
    ivec3 size=textureSize(guide_field,0);
    vec3 q=clamp(uvw*vec3(size)-.5,vec3(0),vec3(size-1));
    ivec3 a=ivec3(floor(q)),b=min(a+1,size-1);vec3 f=fract(q);
    vec3 z0=mix(mix(field_cell(a),field_cell(ivec3(b.x,a.y,a.z)),f.x),mix(field_cell(ivec3(a.x,b.y,a.z)),field_cell(ivec3(b.x,b.y,a.z)),f.x),f.y);
    vec3 z1=mix(mix(field_cell(ivec3(a.x,a.y,b.z)),field_cell(ivec3(b.x,a.y,b.z)),f.x),mix(field_cell(ivec3(a.x,b.y,b.z)),field_cell(b),f.x),f.y);
    return mix(z0,z1,f.z);
}
vec2 guided_flow(vec2 p,vec2 anchor,float t){
    vec2 cq=(p-card*.5-anchor)/field_geometry.xy;
    float ca=guide_rotation.x,sa=guide_rotation.y;
    vec2 local=vec2(ca*cq.x-sa*cq.y,sa*cq.x+ca*cq.y);
    vec2 wave=vec2(variation_wave.y*local.y+variation_wave.z,variation_wave.x*local.x+variation_wave.w);
    vec2 lookup=vec2(.5)+variation_affine.xy*local+variation_affine.zw*local.yx+variation_bend.xy+variation_bend.zw*sin(wave);
    float field_time=t+t*(1.-t)*(variation_clock.x+variation_clock.y*(2.*t-1.));
    float clock_rate=1.+variation_clock.x*(1.-2.*t)+variation_clock.y*(-6.*t*t+6.*t-1.);
    vec2 guide=sample_field(vec3((lookup+vec2(.45))/1.9,field_time)).xy;
    vec2 cross_terms=variation_affine.zw+variation_bend.zw*variation_wave.yx*cos(wave);
    float determinant=variation_affine.x*variation_affine.y-cross_terms.x*cross_terms.y;
    guide=vec2(variation_affine.y*guide.x-cross_terms.x*guide.y,-cross_terms.y*guide.x+variation_affine.x*guide.y)/determinant*clock_rate;
    return vec2(ca*guide.x+sa*guide.y,-sa*guide.x+ca*guide.y)*field_geometry.xy;
}
// 势函数取旋度：无散、空间平滑的卷流；三个频段幅值递减。
vec2 curl(vec2 q,float t){
    vec2 v=vec2(0.);
    for(int k=0;k<3;k++){
        float s=k==0?1.:k==1?.53:.27;
        float a=phase[k]+float(k)*1.7;
        vec2 u=vec2(cos(a),sin(a)),z=vec2(-u.y,u.x);
        float A=dot(q,u)/s*6.2831853+t*turb_rate*(1.+.4*float(k))+phase.w;
        float B=dot(q,z)/s*6.2831853-t*turb_rate*(.7+.3*float(k));
        vec2 g=(cos(A)*cos(B)*u-sin(A)*sin(B)*z)/s;
        v+=vec2(g.y,-g.x)*(k==0?1.:k==1?.45:.2);
    }
    return v*.16;
}
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=uint(count))return;
    // 运动时钟：颗粒解除（点亮、可见）后，按 motion_lag×到达时刻 再开始运动——越晚解除的区域在原位停留越久，
    // 同一条运动前沿上的颗粒同时起动，保持成线。
    Material m=particles[i];float age=time-m.src.z-motion_lag*m.physical.w;
    if(age<=0.){state[i].pos=vec4(m.src.xy,0.,0.);state[i].vel=vec4(0.);return;}
    float h=min(dt,age);
    vec2 p=state[i].pos.xy;
    vec4 r=m.random;
    uint id=floatBitsToUint(m.src.w)^floatBitsToUint(r.x);
    float u_peel=unit(id^0x68e31da4u),u_wind=unit(id^0xb5297a4du);
    // 远离起点方向（前沿推进方向）的初速。
    // spread_downwind=1 时只有顺主方向推进的前沿带着颗粒走（逆风、横风的前沿不推），两支前沿相遇处不再对推。
    vec2 v_spread=m.physical.xy*span*spread*(.35+1.3*r.z)*exp(-age/spread_tau)*mix(1.,max(dot(m.physical.xy,wind),0.),spread_downwind);
    // 沿前沿来源方向剥离：边缘起点处朝控件外；去掉逆风分量，顺风分量按比例保留。
    vec2 pd=-m.physical.xy;float pa=dot(pd,wind);
    pd-=wind*(pa<0.?pa:pa*(1.-peel_along));
    vec2 v_peel=pd*span*peel*(.30+1.4*u_peel)*exp(-age/peel_tau)*smoothstep(0.,.012,age);
    // 主方向风：方向可从上风一点呈扇形发散；随年龄与全局时间增强；单片速度倍率不同。
    float edge=min(card.x/(2.*max(abs(wind.x),1e-6)),card.y/(2.*max(abs(wind.y),1e-6)));
    vec2 source=card*.5-wind*(edge+span*fan_distance);
    vec2 dir=normalize(mix(wind,normalize(p-source),fan));
    // 向触点一侧的目标点收拢：离目标越近越偏向它，远处仍以主方向为主。
    vec2 goal=card*.5+wind*(edge+span*target_distance);
    dir=normalize(mix(dir,normalize(goal-p+wind*1e-4),converge));
    float gust=wind0+wind1*smoothstep(0.,wind_tau,age)+wind_global*smoothstep(wind_global_start,wind_global_start+wind_global_span,time);
    // 单片速度倍率：均匀离散；var_tail>0 时另有一条慢尾（少数颗粒明显更慢，留到后段才离开）。
    float mult=1.+(wind_var+panel_var*panel_weight)*(2.*u_wind-1.);
    if(var_tail>0.)mult*=mix(1.,pow(u_wind,var_tail),.5);
    vec2 v_wind=dir*span*gust*distance_gain*mix(1.,mult,smoothstep(0.,var_tau,age));
    // 旧共同流场模板：按出生位置固定局部锚点权重。
    vec2 v_template=vec2(0.);
    float tg=template_gain+panel_template*panel_weight;
    if(tg>0.){
        vec2 src=m.src.xy-card*.5;
        float coordinate=mix(src.x,src.y,field_geometry.w)/(span*.96);
        float index=floor(coordinate),weight=smoothstep(0.,1.,coordinate-index);
        float tt=time;
        if(template_clock>0.){
            vec2 su=m.src.xy/card;
            tt=mix(time,clamp(time-(texture(obstacle_map,su).r-texture(native_map,su).r),0.,1.),template_clock);
        }
        vec2 g=guided_flow(p,local_anchor(index),tt);
        if(field_geometry.z>.00001)g=mix(g,guided_flow(p,local_anchor(index+1.),tt),weight);
        v_template=g*(guide_gain/.9)*1.08*tg*distance_gain;
    }
    // 大尺度卷流在随风迁移的坐标里取样，保持相干。
    vec2 q=(p-card*.5)/(span*turb_scale)-wind*time*.35;
    vec2 v_turb=curl(q,time)*span*turb*smoothstep(0.,turb_tau,age);
    float ang=r.w*6.2831853;
    vec2 v_rand=vec2(cos(ang),sin(ang))*span*(diffuse+panel_diffuse*panel_weight)*sqrt(-2.*log(max(r.y,.002)))*smoothstep(0.,diffuse_tau,age);
    // 以控件中心为原点的等比外扩：位移与出生位置到中心的距离成正比。
    vec2 v_inflate=(m.src.xy-card*.5)*inflate*smoothstep(0.,inflate_tau,age);
    // 释放初段的独立分离：方向与强度来自独立散列，随年龄衰减，只用来打散规则网格。
    float sa=unit(id^0x2c1b3c6du)*6.2831853,sm=sqrt(-2.*log(max(unit(id^0x297a2d39u),.002)));
    vec2 v_separate=vec2(cos(sa),sin(sa))*span*separate*sm*exp(-age/separate_tau);
    // 边缘逸出：离边越近越强，方向为所在边的外法向，按该边朝向顺风的程度加权（逆风的边不外逸）；
    // 与起点无关，平行外逸，不会向一点汇聚。
    vec2 v_escape=vec2(0.);
    if(escape>0.){
        vec2 ed=min(m.src.xy,card-m.src.xy);
        vec2 sx=vec2(m.src.x<card.x*.5?-1.:1.,0.),sy=vec2(0.,m.src.y<card.y*.5?-1.:1.);
        vec2 outw=sx*exp(-ed.x/(span*escape_depth))*max(dot(sx,wind),0.)+sy*exp(-ed.y/(span*escape_depth))*max(dot(sy,wind),0.);
        v_escape=outw*span*escape*(.3+1.4*unit(id^0x5f356495u))*exp(-age/escape_tau)*smoothstep(0.,.012,age);
    }
    // 前沿带走：只沿主方向，只在前沿本身顺风推进处；不会把两侧材料推向相遇线。
    vec2 v_carry=wind*max(dot(m.physical.xy,wind),0.)*span*carry*(.5+r.z)*exp(-age/carry_tau)*smoothstep(0.,.012,age);
    vec2 target=v_spread+v_peel+v_carry+v_escape+v_wind+v_template+v_turb+v_rand+v_inflate+v_separate;
    // 逆主方向的分量按 back_flow 保留（平滑的 min(·,0)，不产生折点）；横向与顺向分量不变。
    if(back_flow<1.){
        float ax=dot(target,wind)/span;
        float neg=.5*(ax-sqrt(ax*ax+.0004));
        target-=wind*span*neg*(1.-back_flow);
    }
    // 未解除的原表面是障碍：颗粒不能飞进（压到）仍完整的区域，只保留沿其边界的分量。
    if(obstacle>0.){
        vec2 uvp=p/card;
        if(all(greaterThan(uvp,vec2(0.)))&&all(lessThan(uvp,vec2(1.)))){
            float depth=texture(obstacle_map,uvp).r-time;
            if(depth>0.){
                vec2 texel=1./vec2(textureSize(obstacle_map,0));
                vec2 g=vec2(texture(obstacle_map,uvp+vec2(texel.x,0.)).r-texture(obstacle_map,uvp-vec2(texel.x,0.)).r,
                            texture(obstacle_map,uvp+vec2(0.,texel.y)).r-texture(obstacle_map,uvp-vec2(0.,texel.y)).r)/(2.*texel*card);
                float gl=length(g);
                if(gl>1e-8){
                    vec2 n=g/gl;
                    target-=n*max(dot(target,n),0.)*obstacle*smoothstep(0.,obstacle_depth,depth);
                }
            }
        }
    }
    // 起动延迟：颗粒解除后先在原位停留、再逐渐起动（原片刚解除的区域仍成片、遮得住背景）。
    if(onset>0.)target*=smoothstep(0.,onset,age);
    // 新材料不朝未解除的区域运动：去掉沿解除场梯度（指向更晚解除处）的正分量，只沿前沿切向或向已解除侧滑动；
    // 随年龄在 front_slide_tau 内放开。不会把颗粒推向前沿相遇线（与“远离起点外鼓”相反，它只去掉分量、不加推力）。
    if(front_slide>0.){
        vec2 nf=m.physical.xy;
        target-=nf*max(dot(target,nf),0.)*front_slide*(1.-smoothstep(0.,front_slide_tau,age));
    }
    vec2 v=state[i].vel.xy;
    if(age<=dt)v=target;
    v=mix(v,target,1.-exp(-h/inertia));
    state[i].pos.xy=p+v*h;state[i].vel.xy=v;
}
'''

# 此前认可模型（renderer.py）的运动规则移植：模板为主、在“还原距离增益后”的坐标里取样模板、模板时钟按材料对齐、
# 置信度门控卷流、轴向速度平滑下限、逐片惯性。没有压力网格（消融只差 0.015）。motion_legacy=1 时替换 COMPUTE。
COMPUTE_LEGACY=r'''#version 430
layout(local_size_x=256) in;
struct Material{vec4 src;vec4 physical;vec4 random;};
struct State{vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A{Material particles[];};
layout(std430,binding=1) buffer B{State state[];};
layout(std430,binding=3) readonly buffer PeelCompression{float peel_compression[];};
layout(std430,binding=2) readonly buffer ClockOffset{float clock_offset[];};
uniform int count;
uniform float time,dt,span,distance_gain;
uniform vec2 wind,card;
uniform float obstacle,obstacle_depth,onset,shield,shield_linger,shield_reach;
#ifdef SHELTER
uniform float shelter,shelter_step,shelter_rest,shelter_soft;
#endif
uniform float legacy_wind;
#ifdef PEEL_VAR
uniform float legacy_peel_var;
#endif
uniform float wind_gain,curl_gain,guide_gain,legacy_touch_gap,legacy_peel,legacy_template,legacy_separate,legacy_deficit,legacy_forward,legacy_indep;
uniform float legacy_pressure,legacy_clock;
// 旧模型的压力修正场（pressure_grid.py 解出；与 renderer.py 相同的显式双线性插值）。只在 legacy_pressure>0 时取样。
uniform sampler2D peel_field;
uniform vec4 peel_bounds;
vec2 sample_peel(vec2 p){
    ivec2 size=textureSize(peel_field,0);
    vec2 q=clamp((p-peel_bounds.xy)/peel_bounds.zw*vec2(size)-.5,vec2(0),vec2(size-1));
    ivec2 a=ivec2(floor(q)),b=min(a+1,size-1);vec2 f=fract(q);
    return mix(mix(texelFetch(peel_field,a,0).xy,texelFetch(peel_field,ivec2(b.x,a.y),0).xy,f.x),
               mix(texelFetch(peel_field,ivec2(a.x,b.y),0).xy,texelFetch(peel_field,b,0).xy,f.x),f.y);
}
uniform sampler3D guide_field;
uniform sampler3D confidence_field;
uniform sampler2D obstacle_map;
uniform sampler2D native_map;
uniform vec2 guide_rotation;
uniform vec4 field_geometry;
uniform vec4 variation_affine,variation_bend,variation_wave;
uniform vec2 variation_clock;
uint transport_hash(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float transport_unit(uint x){return float(transport_hash(x)>>8u)*(1./16777216.);}
vec2 curl(vec2 p,float t){
    vec2 v=vec2(0);
    for(int k=0;k<3;k++){
        float scale=span*(k==0?1.12:k==1?.51:.23);
        float angle=atan(wind.y,wind.x)+.61+float(k)*1.71;
        vec2 u=vec2(cos(angle),sin(angle)),z=vec2(-u.y,u.x);
        float a=dot(p,u)/scale*6.283+t*(1.2+float(k)*.5)+variation_wave.z;
        float b=dot(p,z)/scale*6.283-t*(.8+float(k)*.43)+variation_wave.w;
        vec2 grad=(cos(a)*cos(b)*u-sin(a)*sin(b)*z);
        v+=vec2(grad.y,-grad.x)*(k==0?1.:k==1?.38:.12);
    }
    return v;
}
vec2 local_anchor(float index){
    float along=index*span*.96*field_geometry.z;
    float across=span*.16*field_geometry.z*sin(index*2.4+variation_wave.z);
    vec2 axis=mix(vec2(1,0),vec2(0,1),field_geometry.w);
    return axis*along+vec2(-wind.y,wind.x)*across;
}
vec3 field_cell(ivec3 c){return vec3(texelFetch(guide_field,c,0).xy,texelFetch(confidence_field,c,0).r);}
vec3 sample_field(vec3 uvw){
    ivec3 size=textureSize(guide_field,0);
    vec3 q=clamp(uvw*vec3(size)-.5,vec3(0),vec3(size-1));
    ivec3 a=ivec3(floor(q)),b=min(a+1,size-1);vec3 f=fract(q);
    vec3 z0=mix(mix(field_cell(a),field_cell(ivec3(b.x,a.y,a.z)),f.x),mix(field_cell(ivec3(a.x,b.y,a.z)),field_cell(ivec3(b.x,b.y,a.z)),f.x),f.y);
    vec3 z1=mix(mix(field_cell(ivec3(a.x,a.y,b.z)),field_cell(ivec3(b.x,a.y,b.z)),f.x),mix(field_cell(ivec3(a.x,b.y,b.z)),field_cell(b),f.x),f.y);
    return mix(z0,z1,f.z);
}
vec3 guided_flow(vec2 p,vec2 anchor,float t){
    vec2 cq=(p-card*.5-anchor)/field_geometry.xy;
    float ca=guide_rotation.x,sa=guide_rotation.y;
    vec2 local=vec2(ca*cq.x-sa*cq.y,sa*cq.x+ca*cq.y);
    vec2 wave=vec2(variation_wave.y*local.y+variation_wave.z,variation_wave.x*local.x+variation_wave.w);
    vec2 lookup=vec2(.5)+variation_affine.xy*local+variation_affine.zw*local.yx+variation_bend.xy+variation_bend.zw*sin(wave);
    float field_time=t+t*(1.-t)*(variation_clock.x+variation_clock.y*(2.*t-1.));
    float clock_rate=1.+variation_clock.x*(1.-2.*t)+variation_clock.y*(-6.*t*t+6.*t-1.);
    vec3 sampled=sample_field(vec3((lookup+vec2(.45))/1.9,field_time));
    vec2 guide=sampled.xy;
    vec2 cross_terms=variation_affine.zw+variation_bend.zw*variation_wave.yx*cos(wave);
    float determinant=variation_affine.x*variation_affine.y-cross_terms.x*cross_terms.y;
    guide=vec2(variation_affine.y*guide.x-cross_terms.x*guide.y,-cross_terms.y*guide.x+variation_affine.x*guide.y)/determinant*clock_rate;
    guide=vec2(ca*guide.x+sa*guide.y,-sa*guide.x+ca*guide.y)*field_geometry.xy;
    return vec3(guide,sampled.z);
}
void main(){
    uint i=gl_GlobalInvocationID.x;if(i>=uint(count))return;
    Material m=particles[i];
    float age=time-m.src.z;
    if(age<=0.){state[i].pos=vec4(m.src.xy,0,0);state[i].vel=vec4(0);return;}
    float h=min(dt,age);
    vec2 p=m.src.xy+(state[i].pos.xy-m.src.xy)/distance_gain;
    vec2 su=m.src.xy/card;
    // legacy_clock：旧模型的模板时钟 t=time−offset（逐片偏移），与本模型的解除场无关。
    float t;
    if(legacy_clock>0.)t=clamp(time-clock_offset[i],0.,1.);
    else t=clamp(time-(texture(obstacle_map,su).r-texture(native_map,su).r),0.,1.);
    vec2 source=m.src.xy-card*.5;
    float coordinate=mix(source.x,source.y,field_geometry.w)/(span*.96);
    float index=floor(coordinate);
    float weight=smoothstep(0.,1.,coordinate-index);
    vec3 sample0=guided_flow(p,local_anchor(index),t);
    if(field_geometry.z>.00001)sample0=mix(sample0,guided_flow(p,local_anchor(index+1.),t),weight);
    float reach=1.-exp(-.65/.85);
    float carried=smoothstep(.012,.16,age);
    vec2 q=p-card*.5-wind*span*(.43*time+.16*time*time);
    vec2 flow=curl(q,time)*span*curl_gain;
    float missing=1.-smoothstep(.12,.85,sample0.z);
    vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)*legacy_template;
    target+=flow*mix(.018,.44+.06*reach,missing)*smoothstep(.01,.09,age);
    target+=wind*span*(.12+.50*reach)*carried*wind_gain*legacy_wind;
    // （可选）风影：上风方向 shelter_step×1..4 短边处若仍是未解除的原表面，这颗颗粒处在它的风影里，
    // 风与模板分量只剩 shelter_rest 的比例（原地悬停、按寿命变暗）；上风侧解除后才随风走。卡外记为无遮挡。
    // 剥离冲量与随机分离不受影响。只用解除时刻图、时间与颗粒当前位置，因果、各素材共用。
#ifdef SHELTER
    float sheltered=1.;
    if(shelter>0.){
        vec2 here=state[i].pos.xy;float sh=0.;
        for(int k=1;k<=4;k++){
            vec2 qs=(here-wind*span*shelter_step*float(k))/card;
            if(all(greaterThan(qs,vec2(0.)))&&all(lessThan(qs,vec2(1.))))
                sh+=smoothstep(-shelter_soft,shelter_soft,texture(obstacle_map,qs).r-time);
        }
        sheltered=1.-shelter*(1.-shelter_rest)*sh*.25;
        target*=sheltered;
    }
#endif
    float edge=min(card.x/(2.*max(abs(wind.x),.000001)),card.y/(2.*max(abs(wind.y),.000001)));
    vec2 destination=card*.5+wind*(edge+span*legacy_touch_gap);
    vec2 bearing=normalize(destination-m.src.xy);
    float turn=.13*atan(dot(bearing,vec2(-wind.y,wind.x)),dot(bearing,wind));
    target=mat2(cos(turn),sin(turn),-sin(turn),cos(turn))*target;
    float random_angle=m.random.w*6.28318;
    float radius=sqrt(-2.*log(max(m.random.z,.015)));
    float separate=smoothstep(.008,.045,age)*(1.-smoothstep(.16,.32,age));
    target+=vec2(cos(random_angle),sin(random_angle))*radius*span*.045*separate*legacy_separate;
    uint peel_seed=floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au;
    float peel_response=transport_unit(peel_seed);
#ifdef PEEL_VAR
    // （可选）剥离强度的逐片随机程度：1 为旧规则（0.1～1.9 倍），0 为全部取均值（颗粒一起走、不散开）。
    peel_response=mix(.5,peel_response,legacy_peel_var);
#endif
    vec2 peel=-m.physical.xy;
    peel-=wind*min(dot(peel,wind),0.);
    peel-=wind*max(dot(peel,wind),0.)*.82;
    vec2 free_edge=min(m.src.xy,card-m.src.xy);
    vec2 free_out=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.)*exp(-free_edge/(span*.13));
    float eject=max(dot(normalize(free_out+vec2(.000001)),normalize(peel+vec2(.000001))),0.);
    peel*=1.-.95*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18));
    float peel_pressure=2.*peel_compression[i]*.16*1.2141309*(.10+1.80*peel_response);
    peel/=1.+peel_pressure*peel_pressure/(1.+peel_pressure);
    // 压力修正与剥离同一分组相加（与旧模型逐位一致）；关闭时保持原式。
    if(legacy_pressure>0.)target+=(peel*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age)*legacy_peel+sample_peel(p)*legacy_pressure)*span;
    else target+=(peel*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age))*span*legacy_peel;
    vec2 edge_dist=min(m.src.xy,card-m.src.xy);
    vec2 outward=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.);
    vec2 edge_weight=exp(-edge_dist/(span*.075));
    vec2 outward_spread=outward*edge_weight;
    float trailing_edge=smoothstep(0.,.6,dot(normalize(outward_spread+vec2(.000001)),m.physical.xy));
    float spread_age=smoothstep(.002,.014,age)*exp(-age/.080)*mix(.12,1.,trailing_edge);
    target+=outward_spread*span*.4*(.05+2.4*m.random.z*m.random.z)*spread_age;
    target+=vec2(-wind.y,wind.x)*span*.12*(m.random.w-.5)*length(edge_weight)*spread_age;
    float forward_front=smoothstep(.20,.85,dot(-m.physical.xy,wind));
    target*=1.-.45*legacy_forward*forward_front*(1.-smoothstep(.10,.25,age));
    float independent=1.-.65*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18))*(1.-smoothstep(.10,.25,age));
    uint transport_seed=floatBitsToUint(m.random.y)^floatBitsToUint(m.random.w);
    vec2 transport_random=vec2(transport_unit(transport_seed),transport_unit(transport_seed^0x9e3779b9u));
    target*=1.+0.6496880*legacy_indep*(transport_random.x-.5)*smoothstep(.025,.14,age)*independent;
    target+=curl(p+transport_random*span*.4,time)*span*.075*legacy_indep*smoothstep(.04,.16,age)*independent;
    float axial=dot(target,wind)/span;
#ifdef SHELTER
    float deficit=legacy_deficit*sheltered-axial;
#else
    float deficit=legacy_deficit-axial;
#endif
    target+=wind*span*.5*(deficit+sqrt(deficit*deficit+.0064));
    // （可选）未解除的原表面是障碍：颗粒不能飞进仍完整的区域，只保留沿其边界的分量，于是在前沿处堆积；
    // 起动延迟：解除后先在原位停留、再逐渐起动。两者默认关闭（与此前认可模型一致）。
    if(obstacle>0.){
        vec2 qo=state[i].pos.xy/card;
        if(all(greaterThan(qo,vec2(0.)))&&all(lessThan(qo,vec2(1.)))){
            float depth=texture(obstacle_map,qo).r-time;
            if(depth>0.){
                vec2 texel=1./vec2(textureSize(obstacle_map,0));
                vec2 g=vec2(texture(obstacle_map,qo+vec2(texel.x,0.)).r-texture(obstacle_map,qo-vec2(texel.x,0.)).r,
                            texture(obstacle_map,qo+vec2(0.,texel.y)).r-texture(obstacle_map,qo-vec2(0.,texel.y)).r)/(2.*texel*card);
                float gl=length(g);
                if(gl>1e-8){vec2 n=g/gl;target-=n*max(dot(target,n),0.)*obstacle*smoothstep(0.,obstacle_depth,depth);}
            }
        }
    }
    if(onset>0.)target*=smoothstep(0.,onset,age);
    // （可选）遮挡：顺风前方 shield_reach 以内的材料如果还没解除、或解除不到 shield_linger（仍悬停在原处），
    // 颗粒就走不动；前方在控件外或早已离开则不挡。于是控件边缘、顺风一侧的材料先流走，内部悬停成雾，
    // 从顺风侧一层层剥离。只用解除时刻图，不依赖其他颗粒的即时状态。
    if(shield>0.){
        vec2 here=state[i].pos.xy;float blocked=0.;
        for(int k=1;k<=4;k++){
            vec2 qs=(here+wind*span*shield_reach*float(k)*.25)/card;
            if(all(greaterThan(qs,vec2(0.)))&&all(lessThan(qs,vec2(1.)))){
                float Td=texture(obstacle_map,qs).r;
                blocked+=1.-smoothstep(0.,.03,time-Td-shield_linger);
            }
        }
        target*=1.-shield*blocked*.25;
    }
    float response=1.-exp(-h/(.018+.036*m.random.z));
    vec2 old_v=state[i].vel.xy/distance_gain;
    if(age<=dt)old_v=target*.85;
    vec2 v=mix(old_v,target,response);
    state[i].pos.xy=m.src.xy+(p+v*h-m.src.xy)*distance_gain;
    state[i].vel.xy=v*distance_gain;
}
'''

VERTEX=r'''#version 430
struct Material{vec4 src;vec4 physical;vec4 random;};
struct State{vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A{Material particles[];};
layout(std430,binding=1) readonly buffer B{State state[];};
uniform vec2 frame,card,offset,cell;
uniform float time,extrapolate;
uniform int material_pass;
uniform float surface_lead,surface_tail,size_birth,size_settled,settle,shrink_late,alpha_in,fade,grow,inflate,particle_follow,vanish_alpha,size_sigma,panel_vanish_alpha,panel_weight,panel_size,panel_alpha;
uniform float surface_grain,panel_surface_grain,panel_look_power,panel_full_alpha,panel_full_size;
uniform float legacy_alpha;
uniform vec3 panel_color;
uniform sampler2D foreground_tex;
out vec2 uv,local;
flat out vec2 src_uv;
flat out float born_out;
flat out float grain_out;
flat out float alpha_out;
flat out float round_out;
flat out float age_out;
flat out float phase_out;
flat out vec2 legacy_out;
const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));
void main(){
    Material m=particles[gl_InstanceID];State s=state[gl_InstanceID];
    vec2 c=corners[gl_VertexID];local=c;
    float T=m.physical.w;float age=time-m.src.z;
    src_uv=m.src.xy/card;
    // 颗粒状交接：原表面有 grain 的份额按本格自己的（带随机差的）解除时刻整格交出，其余按平滑到达时刻渐隐。
    // 大面积纯色面板（白底弹窗）以颗粒状交接为主，与此前认可模型一致；照片类内容以平滑渐隐为主。
    // “整块面板”程度：纯色面板占比越接近整张控件越接近 1；照片里的大块暗区只有很小的份额（幂次压低）。
    float panel_look=pow(clamp(panel_weight,0.,1.),panel_look_power);
    float grain=clamp(surface_grain+panel_surface_grain*panel_look,0.,1.);
    born_out=m.src.z;grain_out=grain;
    vec2 vertex;
    if(material_pass==0){
        // 原表面：按网格原样铺满；透明度在片元中按平滑到达时刻逐像素计算。
        vertex=m.src.xy+(c-.5)*cell;
        uv=vertex/card;
        alpha_out=1.;round_out=0.;age_out=0.;phase_out=0.;legacy_out=vec2(0.);
    }else{
        float life=m.physical.z;
        float size=mix(size_birth,size_settled,smoothstep(0.,settle,age));
        // 对数正态粒径（平均面积不变），释放后随“松开”逐渐建立；随机量取自与运动无关的分量。
        float g=sqrt(-2.*log(max(m.random.y,.004)))*cos(6.2831853*m.random.z);
        size*=mix(1.,exp(size_sigma*g-size_sigma*size_sigma),smoothstep(0.,settle,age));
        size*=1.-shrink_late*smoothstep(.1,life,age);
        // 面板材质：与面板主色相近的颗粒更细、更通透；文字、图标等内容色保持原样。
        vec3 own=texture(foreground_tex,src_uv).rgb;
        float match=1.-smoothstep(.06,.25,length(own-panel_color));
        float is_panel=panel_weight*match;        // 纯色材质：颗粒更细、更通透（按面板权重线性）
        float is_full=panel_look*match;           // 整块面板（白底弹窗）：在此基础上恢复为较实的轻雾
        float loosened=smoothstep(0.,settle,age);
        size*=mix(1.,panel_size,is_panel*loosened)*mix(1.,panel_full_size,is_full*loosened);
        float ending=smoothstep(life*(1.-fade),life,age);
        float va=clamp(vanish_alpha+panel_vanish_alpha*panel_weight,0.,1.);
        // legacy_alpha：旧规则只靠透明度消失，不在寿命末段缩小。
        if(legacy_alpha<=0.)size*=1.-(1.-va)*ending;
        size*=1.+grow*inflate*max(age,0.);
        float ang=(m.random.x-.5)*6.2831853*smoothstep(0.,.25,age);
        mat2 rot=mat2(cos(ang),sin(ang),-sin(ang),cos(ang));
        vec2 center=s.pos.xy+s.vel.xy*extrapolate;
        vertex=center+rot*((c-.5)*cell*size);
        uv=src_uv;
        // 颗粒透明度跟随原表面平滑交出的部分；整格交出的部分由颗粒立即补上（覆盖连续）。
        float appear=mix(1.,smoothstep(T-surface_lead,T+surface_tail,time),particle_follow*(1.-grain));
        float vanish=1.-va*ending,fade_in=smoothstep(0.,alpha_in,age);
        if(legacy_alpha>0.){
            // 旧模型（renderer.py FRAGMENT）：寿命最后 .075（至多后 45%）内淡出；.001～.022 淡入，.018～.12 内降到 .92。
            vanish=1.-smoothstep(max(life-.075,life*.55),life,age);
            fade_in=smoothstep(.001,.022,age)*mix(1.,.92,smoothstep(.018,.12,age));
        }
        alpha_out=age<=0.?0.:appear*vanish*fade_in*mix(1.,panel_alpha,is_panel*loosened)*mix(1.,panel_full_alpha,is_full*loosened);
        round_out=smoothstep(0.,.03,age);age_out=age;phase_out=m.random.w*6.2831853;
        // 旧模型的逐片明暗输入（renderer.py VERTEX 的 light_out）与高光相位随机量，供 legacy_light 使用。
        float a0=max(age,0.);
        legacy_out=vec2(1.+(.17*sin(dot(m.src.xy,vec2(.014,.021))+a0*11.)+.09)*smoothstep(.015,.09,a0)*exp(-max(a0-.25,0.)*2.),m.random.z);
    }
    vec2 world=offset+vertex;
    gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0.,1.);
}
'''

FRAGMENT=r'''#version 430
uniform sampler2D foreground;
uniform sampler2D release_map;
uniform int material_pass;
uniform float roundness,softness,time,surface_lead,surface_tail;
uniform float surface_ghost,surface_ghost_tail;
uniform float light,light_early,light_early_tau,glint,glint_power,glint_rate;
uniform float legacy_light,legacy_light_gain;
in vec2 uv,local;
flat in vec2 src_uv;
flat in float born_out;
flat in float grain_out;
flat in float alpha_out;
flat in float round_out;
flat in float age_out;
flat in float phase_out;
flat in vec2 legacy_out;
out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){
    vec4 src=texture(foreground,uv);
    float a=src.a*alpha_out;
    if(material_pass==0){
        // 解除带逐像素平滑，不出现网格方块。
        float T=texture(release_map,uv).r;
        float keep=mix(1.-smoothstep(T-surface_lead,T+surface_tail,time),time<=born_out?1.:0.,grain_out);
        // （可选）残影：主交接之后，原图以 surface_ghost 的不透明度留在原位，在 surface_ghost_tail 内慢慢淡去
        // （原片刚解除的区域仍看得见原图的淡影，颗粒从上面散开）。默认 0 时与此前完全相同。
        if(surface_ghost>0.)keep=max(keep,surface_ghost*(1.-smoothstep(T,T+surface_ghost_tail,time)));
        a*=keep;
    }else{
        // 颗粒逐渐由方片变圆，边缘在超采样下自然柔化。
        float d=length(local-.5)*2.;
        a*=mix(1.,1.-smoothstep(1.-softness,1.,d),roundness*round_out);
    }
    if(a<.002)discard;
    vec3 color=linear(src.rgb);
    if(material_pass==1&&legacy_light>0.){
        // 旧模型的颗粒明暗（renderer.py FRAGMENT）：暗部幂次提亮、斜向明暗条纹与初段增亮、加性高光、彩色去灰、近白限幅；不截到 1。
        // 颗粒取格心颜色，uv 为常量，近白限幅的小面闪烁因此逐片而非逐像素。
        float ol=smoothstep(.005,.095,age_out);
        color=mix(color,pow(color,vec3(.86)),ol*.85);
        float lighting=mix(1.,legacy_out.x,legacy_light_gain);
        lighting*=1.+.32*legacy_light_gain*smoothstep(.006,.045,age_out)*(1.-smoothstep(.11,.28,age_out));
        float facing=.5+.5*sin(age_out*16.+legacy_out.y*6.28318);
        color=color*lighting*(1.+.2493839*ol*legacy_light_gain)+vec3(.012+.18*pow(facing,12.))*ol*legacy_light_gain;
        float low=min(min(color.r,color.g),color.b),high=max(max(color.r,color.g),color.b);
        color=max(vec3(0.),color-vec3(low)*.44*smoothstep(.15,.65,high-low)*ol);
        float facet=.80+.20*(.5+.5*sin(age_out*11.+dot(uv,vec2(7.,11.))));
        color=mix(color,min(color,vec3(mix(1.,facet,ol))),smoothstep(.80,.98,min(src.r,min(src.g,src.b))));
    }else if(material_pass==1){
        // 释放后的颗粒：整体增亮，初段更亮；转动到迎光面时出现高光。
        float lit=smoothstep(0.,.03,age_out);
        color*=1.+(light+light_early*exp(-age_out/max(light_early_tau,1e-3)))*lit;
        float facing=.5+.5*sin(age_out*glint_rate+phase_out);
        color+=vec3(glint*pow(facing,glint_power))*lit;
        color=min(color,vec3(1.));
    }
    frag=vec4(color*a,a);
}
'''

FULLVERT=r'''#version 430
out vec2 uv;
const vec2 v[3]=vec2[3](vec2(0,0),vec2(2,0),vec2(0,2));
void main(){uv=v[gl_VertexID];gl_Position=vec4(uv.x*2.-1.,1.-uv.y*2.,0,1);}
'''
BACKGROUND=r'''#version 430
uniform sampler2D bg;
uniform float dim;
in vec2 uv;out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){frag=vec4(linear(texture(bg,uv).rgb*(1.-dim)),1.);}
'''
OUTPUT=r'''#version 430
uniform sampler2D screen;
in vec2 uv;out vec4 frag;
vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
void main(){frag=vec4(srgb(texture(screen,vec2(uv.x,1.-uv.y)).rgb),1);}
'''


_SOLENOIDAL=None


def solenoidal_guidance():
    """共同流场模板的无散部分（逐时间层 Helmholtz 投影，Neumann 边界，离散余弦变换求解 Poisson 方程）。

    模板从华为参考光流提炼，含有与旋度同量级的汇聚／发散：汇聚区会把颗粒压成孤立的亮团与细缕。
    去掉无旋部分后，卷曲、剪切结构保留，点状汇聚消失。结果与原模板同尺寸，可预先生成为资源。
    """
    global _SOLENOIDAL
    if _SOLENOIDAL is not None:return _SOLENOIDAL
    from scipy.fft import dctn,idctn
    from unified_model import guidance
    g=guidance().astype('float64');T,H,W,_=g.shape
    ky=2*np.cos(np.pi*np.arange(H)/H)-2;kx=2*np.cos(np.pi*np.arange(W)/W)-2
    lam=ky[:,None]+kx[None,:];lam[0,0]=1.
    out=np.empty_like(g)
    for t in range(T):
        vx,vy=g[t,...,0].copy(),g[t,...,1].copy()
        # 中心差分与余弦变换的离散算子不完全一致，重复投影几次把残余散度压下去。
        for _ in range(6):
            div=np.gradient(vx,axis=1)+np.gradient(vy,axis=0)
            ph=dctn(div,type=2,norm='ortho')/lam;ph[0,0]=0.
            phi=idctn(ph,type=2,norm='ortho')
            vx=vx-np.gradient(phi,axis=1);vy=vy-np.gradient(phi,axis=0)
        out[t,...,0]=vx;out[t,...,1]=vy
    _SOLENOIDAL=out.astype('float32')
    return _SOLENOIDAL


def touch_strength(meta,direction,touch_gap):
    """触点在该方向可用背景中的相对位置（与旧模型一致），默认 0.5。"""
    if touch_gap is None:return .5
    from unified_model import touch_strength_from_point
    x,y,x1,y1=meta['rect'];cw,ch=x1-x,y1-y;span=min(cw,ch)
    ang=math.radians(direction);ux,uy=math.cos(ang),-math.sin(ang)
    edge=min(cw/(2*max(abs(ux),1e-9)),ch/(2*max(abs(uy),1e-9)))
    length=edge+span*touch_gap
    l,t,r,b=meta.get('touch_rect',[0,0,meta['frame'][0],meta['frame'][1]])
    return touch_strength_from_point(cw*.5+ux*length,ch*.5+uy*length,cw,ch,[l-x,t-y,r-x,b-y])


class V2Renderer:
    MATERIAL_KEYS=('motion_legacy','cell','panel_cell','panel_jitter','panel_life','panel_front_noise','edge_speed','edge_depth','edge_lead','edge_plateau','edge_ring','edge_ring_width','edge_site_margin','content_edge','content_edge_power','front_step','front_knee','front_step_width','bulk_time','bulk_spread','bulk_noise_scale','sweep_radius','sweep_time','creep_speed','late_bump','late_bump_radius','round_front','aspect_scale','duration','exponent','rate_variation','edge_variation','front_aniso','front_noise','front_noise_scale','softmin','meeting','meeting_width','jitter',
                   'life0','life1','life_shape','life_born','end','legacy_life','legacy_normals')
    COMPUTE_KEYS=('spread','spread_tau','peel','peel_along','peel_tau','escape','escape_depth','escape_tau','carry','carry_tau','obstacle','obstacle_depth','shield','shield_linger','shield_reach','shelter','shelter_step','shelter_rest','shelter_soft','template_clock','back_flow','spread_downwind','onset','motion_lag','legacy_touch_gap','legacy_peel','legacy_template','legacy_separate','legacy_deficit','legacy_wind','legacy_peel_var','legacy_forward','legacy_indep','front_slide','front_slide_tau','var_tail','wind0','wind1','wind_tau','wind_global','wind_global_start',
                  'wind_global_span','wind_var','var_tau','turb_tau','fan','fan_distance','converge','inflate','inflate_tau','separate','separate_tau','turb','turb_scale','turb_rate','diffuse','diffuse_tau','inertia','legacy_pressure','legacy_clock')
    DRAW_KEYS=('surface_lead','surface_tail','surface_ghost','surface_ghost_tail','surface_grain','panel_surface_grain','panel_look_power','panel_full_alpha','panel_full_size','size_birth','size_settled','settle','shrink_late','alpha_in','fade','grow','inflate','particle_follow','vanish_alpha','size_sigma','legacy_alpha','legacy_light')

    def __init__(self,name,direction=None,seed=None,sites=None,touch_gap=None,rules=RULES,ctx=None,quality=2,view_bounds=None,meta=None,touch_strength=None):
        from unified_model import guidance,flow_confidence,field_geometry,field_rotation,variation,RULES as OLD
        self.ctx=ctx or moderngl.create_standalone_context(require=430)
        self.directory=HERE/'assets'/name
        self.meta=meta or json.loads((self.directory/'scene.json').read_text('utf-8'))
        self.rules=rules
        self.direction=float(self.meta['direction'] if direction is None else direction)
        self.seed=int(self.meta['seed'] if seed is None else seed)
        self.w,self.h=self.meta['frame'];x,y,x1,y1=self.meta['rect'];self.cw,self.ch=x1-x,y1-y
        self.span=min(self.cw,self.ch)
        if sites is None:sites=sample_sites(self.cw,self.ch,self.seed,self.direction)
        self.sites=tuple(Site(**s) if isinstance(s,dict) else s for s in sites)
        fg=np.array(Image.open(self.directory/'foreground.png').convert('RGBA'))
        bg=np.array(Image.open(self.directory/'background.png').convert('RGB'))
        if view_bounds is not None:
            vx,vy,vx1,vy1=map(int,view_bounds);vw,vh=vx1-vx,vy1-vy
            expanded=np.full((vh,vw,3),[14,20,30],dtype=np.uint8)
            l,t=max(0,vx),max(0,vy);r,b=min(self.w,vx1),min(self.h,vy1)
            if r>l and b>t:expanded[t-vy:b-vy,l-vx:r-vx]=bg[t:b,l:r]
            bg=expanded;self.w,self.h=vw,vh;x-=vx;y-=vy
        built=build_materials(self.cw,self.ch,fg,self.direction,self.seed,self.sites,rules)
        self.built=built;self.base=built['base'];self.n=len(self.base)
        self.material=self.ctx.buffer(self.base.tobytes());self.state=self.ctx.buffer(reserve=self.n*32)
        self.compute=None
        self.pressure=None          # 旧模型的压力网格（legacy_pressure），由 _sync_pressure 建立
        self.compression=self.ctx.buffer(np.ascontiguousarray(built['compression']).tobytes()) if len(built['compression']) else self.ctx.buffer(reserve=4)
        self.clock_offset=self.ctx.buffer(np.ascontiguousarray(built['clock_offset']).tobytes()) if len(built['clock_offset']) else self.ctx.buffer(reserve=4)
        self.program=self.ctx.program(vertex_shader=VERTEX,fragment_shader=FRAGMENT)
        self.vao=self.ctx.vertex_array(self.program,[])
        self.bg_program=self.ctx.program(vertex_shader=FULLVERT,fragment_shader=BACKGROUND);self.bg_vao=self.ctx.vertex_array(self.bg_program,[])
        self.out_program=self.ctx.program(vertex_shader=FULLVERT,fragment_shader=OUTPUT);self.out_vao=self.ctx.vertex_array(self.out_program,[])
        self.fg_tex=self.ctx.texture((self.cw,self.ch),4,fg.tobytes());self.fg_tex.repeat_x=self.fg_tex.repeat_y=False
        self.release_tex=self.ctx.texture((built['nx'],built['ny']),1,built['release'].astype('float32').tobytes(),dtype='f4')
        self.release_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.release_tex.repeat_x=self.release_tex.repeat_y=False
        self.native_tex=self.ctx.texture((built['nx'],built['ny']),1,built['native'].astype('float32').tobytes(),dtype='f4')
        self.native_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.native_tex.repeat_x=self.native_tex.repeat_y=False
        self.bg_tex=self.ctx.texture((self.w,self.h),3,bg.tobytes());self.bg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST)
        self.tex=self.ctx.texture((round(self.w*quality),round(self.h*quality)),4,dtype='f2');self.tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.fbo=self.ctx.framebuffer([self.tex])
        self.out_tex=self.ctx.texture((self.w,self.h),4);self.out_fbo=self.ctx.framebuffer([self.out_tex])
        ang=math.radians(self.direction);self.wind=(math.cos(ang),-math.sin(ang))
        # 触点远近：真实触点按可用背景换算；参考重放可直接给出相对强度（0～1，原片触点未知时的输入）。
        self.touch_explicit=touch_strength is not None or touch_gap is not None
        if touch_strength is not None:self.touch_strength=float(np.clip(touch_strength,0,1))
        elif touch_gap is not None:self.touch_strength=globals()['touch_strength'](self.meta,self.direction,touch_gap)
        else:self.touch_strength=float(rules.touch_default)
        # 旧共同流场模板：与旧模型相同的资源、坐标变换与随机形变。
        # 面板材质（白底弹窗）始终只用模板的无散部分：汇聚部分会在纯色面上压出彗星头（第 5m 节）。
        keep=self.effective_div(rules);flow=self.template_field(keep);self.flow_div=keep
        self.flow_tex=self.ctx.texture3d(tuple(flow.shape[2::-1]),2,flow.tobytes(),dtype='f4')
        self.flow_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.flow_tex.repeat_x=self.flow_tex.repeat_y=self.flow_tex.repeat_z=False
        conf=flow_confidence();self.conf_tex=self.ctx.texture3d(tuple(conf.shape[::-1]),1,conf.tobytes(),dtype='f1')
        self.conf_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.conf_tex.repeat_x=self.conf_tex.repeat_y=self.conf_tex.repeat_z=False
        self._make_compute(rules)
        self.apply_rules(rules)
        p=self.program
        p['frame']=(self.w,self.h);p['card']=(self.cw,self.ch);p['offset']=(x,y);p['cell']=built['cell']
        p['foreground']=0;p['release_map']=6;self.bg_program['bg']=1;self.out_program['screen']=2
        if 'legacy_light_gain' in p:p['legacy_light_gain']=OLD['light_gain']
        self.reset()
        self._sync_pressure()

    def _make_compute(self,rules):
        """按规则选用运动着色器（第二版运动或旧运动路径 COMPUTE_LEGACY）并设置与规则无关的常量。

        构造时调用一次；之后 set_rules 切换 motion_legacy 时重建（此前只在构造时选定，先用默认规则构造、
        再 set_rules 的拟合与比较工具因此一直跑的是第二版运动，2026-09-27 修正）。"""
        from unified_model import field_geometry,field_rotation,variation,RULES as OLD
        if self.compute is not None:self.compute.release()
        self.legacy=rules.motion_legacy>0
        # 风影（shelter>0）编译成单独的着色器变体，默认路径的着色器源码保持不变（逐像素一致）。
        self.shelter_on=self.legacy and rules.shelter>0
        src=COMPUTE_LEGACY if self.legacy else COMPUTE
        if self.shelter_on:src=src.replace('#version 430','#version 430\n#define SHELTER 1',1)
        self.peelvar_on=self.legacy and rules.legacy_peel_var!=1.
        if self.peelvar_on:src=src.replace('#version 430','#version 430\n#define PEEL_VAR 1',1)
        self.compute=c=self.ctx.compute_shader(src)
        c['count']=self.n;c['dt']=STEP;c['span']=self.span;c['wind']=self.wind;c['card']=(self.cw,self.ch)
        c['distance_gain']=self.gain(rules)
        ph=hash_uniform(4,self.seed,0x6a09e667)*6.2831853
        if 'phase' in c:c['phase']=tuple(float(v) for v in ph)
        geometry=field_geometry(self.cw,self.ch);delta=field_rotation(self.direction,*geometry[:2]);v=variation(self.seed)
        c['field_geometry']=geometry;c['guide_rotation']=(math.cos(delta),math.sin(delta));c['guide_gain']=OLD['guide_gain']
        c['variation_affine']=tuple(v[:4]);c['variation_bend']=tuple(v[4:8]);c['variation_wave']=tuple(v[8:12]);c['variation_clock']=tuple(v[12:14])
        if 'guide_field' in c:c['guide_field']=3
        if 'confidence_field' in c:c['confidence_field']=4
        if 'obstacle_map' in c:c['obstacle_map']=6
        if 'native_map' in c:c['native_map']=7
        if self.legacy:
            c['wind_gain']=OLD['wind_gain'];c['curl_gain']=OLD['curl_gain']

    def _sync_pressure(self,rebuild=False):
        """旧模型的压力网格（pressure_grid.py）：只在旧运动规则且 legacy_pressure>0 时存在；材料重建后随之重建。"""
        want=self.legacy and self.rules.legacy_pressure>0
        if self.pressure is not None and (rebuild or not want):
            self.pressure.close();self.pressure=None
        if want and self.pressure is None:
            from pressure_grid import PressureGrid
            b=self.built;self.nx,self.ny=b['nx'],b['ny'];self.cell=b['cell'];self.peel_compression=self.compression
            self.pressure=PressureGrid(self)
        if self.pressure is not None:
            # 喷溅着色器按 .55+.90×触点远近 还原材料坐标（旧模型的距离增益）；这里换算成本模型实际的距离增益。
            self.pressure.splat['touch_strength']=(self.gain(self.rules)-.55)/.9
            self.pressure.splat['wind']=self.wind
            self.pressure.reset()

    def effective_div(self,rules):
        return float(rules.template_div)*(1.-float(self.built.get('panel',0.)))

    @staticmethod
    def template_field(keep_div):
        """模板 = 无散部分 + keep_div × 无旋部分。"""
        from unified_model import guidance
        full=guidance();sol=solenoidal_guidance()
        return np.ascontiguousarray(sol+float(keep_div)*(full-sol)).astype('float32')

    def gain(self,rules):
        """运动幅度：触点远近在 gain_near～gain_far 之间线性变化，面板材质再乘 1+panel_speed×面板权重。"""
        g=rules.gain_near+(rules.gain_far-rules.gain_near)*self.touch_strength
        if 'target_distance' in self.compute:
            self.compute['target_distance']=rules.target_near+(rules.target_far-rules.target_near)*self.touch_strength
        return g*(1+rules.panel_speed*self.built.get('panel',0.))

    def set_direction(self,direction):
        """更换消逝方向（输入）：只更新与方向有关的着色器常量。"""
        from unified_model import field_geometry,field_rotation
        self.direction=float(direction);ang=math.radians(self.direction);self.wind=(math.cos(ang),-math.sin(ang))
        c=self.compute;c['wind']=self.wind
        geometry=field_geometry(self.cw,self.ch);delta=field_rotation(self.direction,*geometry[:2])
        c['guide_rotation']=(math.cos(delta),math.sin(delta));self._sync_pressure();self.reset()

    def set_touch_strength(self,strength):
        """更换触点远近（输入，0～1）。"""
        self.touch_explicit=True
        self.touch_strength=float(np.clip(strength,0,1));self.compute['distance_gain']=self.gain(self.rules);self._sync_pressure();self.reset()

    def set_sites(self,sites):
        """更换起点（初始条件）并重新建材；着色器与纹理不重建。"""
        self.sites=tuple(Site(**q) if isinstance(q,dict) else q for q in sites)
        self._rebuild(self.rules);self._sync_pressure(True);self.reset()

    def _rebuild(self,rules):
        fg=np.array(Image.open(self.directory/'foreground.png').convert('RGBA'))
        built=build_materials(self.cw,self.ch,fg,self.direction,self.seed,self.sites,rules)
        self.built=built;self.base=built['base']
        if len(self.base)!=self.n:
            self.n=len(self.base);self.material.release();self.state.release()
            self.material=self.ctx.buffer(self.base.tobytes());self.state=self.ctx.buffer(reserve=self.n*32)
            self.compute['count']=self.n
        else:self.material.write(self.base.tobytes())
        self.compression.release();self.clock_offset.release()
        self.compression=self.ctx.buffer(np.ascontiguousarray(built['compression']).tobytes()) if len(built['compression']) else self.ctx.buffer(reserve=4)
        self.clock_offset=self.ctx.buffer(np.ascontiguousarray(built['clock_offset']).tobytes()) if len(built['clock_offset']) else self.ctx.buffer(reserve=4)
        self.release_tex.release();self.native_tex.release()
        self.release_tex=self.ctx.texture((built['nx'],built['ny']),1,built['release'].astype('float32').tobytes(),dtype='f4')
        self.release_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.release_tex.repeat_x=self.release_tex.repeat_y=False
        self.native_tex=self.ctx.texture((built['nx'],built['ny']),1,built['native'].astype('float32').tobytes(),dtype='f4')
        self.native_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.native_tex.repeat_x=self.native_tex.repeat_y=False
        self.program['cell']=built['cell']

    def set_rules(self,rules):
        """更换整套规则；影响建材的项重新生成颗粒，其余只改着色器常量。"""
        keep=self.effective_div(rules)
        if keep!=getattr(self,'flow_div',None):
            self.flow_tex.write(self.template_field(keep).tobytes());self.flow_div=keep
        rebuilt=any(getattr(rules,k)!=getattr(self.rules,k) for k in self.MATERIAL_KEYS)
        if rebuilt:
            self._rebuild(rules)
        switched=(rules.motion_legacy>0)!=self.legacy or (rules.motion_legacy>0 and rules.shelter>0)!=self.shelter_on or (rules.motion_legacy>0 and rules.legacy_peel_var!=1.)!=self.peelvar_on
        if switched:self._make_compute(rules)
        self.apply_rules(rules);self._sync_pressure(rebuilt or switched);self.reset()

    def apply_rules(self,rules):
        """只更新着色器常量（材料不变）。"""
        self.rules=rules;c=self.compute;p=self.program
        if not getattr(self,'touch_explicit',True):
            self.touch_strength=float(rules.touch_default)
        c['distance_gain']=self.gain(rules)
        for k in self.COMPUTE_KEYS:
            if k in c:c[k]=getattr(rules,k)
        if 'template_gain' in c:c['template_gain']=rules.template
        pw=float(self.built.get('panel',0.)) if hasattr(self,'built') else 0.
        for prog in (c,p):
            if 'panel_weight' in prog:prog['panel_weight']=pw
        if 'panel_template' in c:c['panel_template']=rules.panel_template
        for k in ('panel_diffuse','panel_var'):
            if k in c:c[k]=getattr(rules,k)
        if 'panel_vanish_alpha' in p:p['panel_vanish_alpha']=rules.panel_vanish_alpha
        for k in ('panel_size','panel_alpha'):
            if k in p:p[k]=getattr(rules,k)
        if 'panel_color' in p and hasattr(self,'built'):p['panel_color']=tuple(float(v) for v in self.built.get('panel_color',(1.,1.,1.)))
        if 'foreground_tex' in p:p['foreground_tex']=0
        for k in self.DRAW_KEYS:
            if k in p:p[k]=getattr(rules,k)
        if 'roundness' in p:p['roundness']=rules.roundness
        for k in ('light','light_early','light_early_tau','glint','glint_power','glint_rate'):
            if k in p:p[k]=getattr(rules,k)
        if 'softness' in p:p['softness']=rules.softness

    def reset(self):
        a=np.zeros((self.n,8),dtype='float32');a[:,:2]=self.base[:,:2]
        self.state.write(a.tobytes());self.step=0
        if getattr(self,'pressure',None) is not None:self.pressure.reset()

    def seek(self,time):
        target=int(max(time,0)/STEP+1e-6)
        if target<self.step:self.reset()
        self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1)
        self.flow_tex.use(3);self.conf_tex.use(4);self.release_tex.use(6);self.native_tex.use(7)
        if self.legacy:self.compression.bind_to_storage_buffer(3);self.clock_offset.bind_to_storage_buffer(2)
        if self.pressure is not None:self.pressure.texture.use(5)
        while self.step<target:
            self.step+=1;self.compute['time']=self.step*STEP
            self.compute.run(group_x=(self.n+255)//256)
            self.ctx.memory_barrier()
            if self.pressure is not None and self.step%4==0:
                # 与旧渲染器相同：每 4 步按当前分布重解一次压力；网格占用了存储绑定，解完重新绑定。
                self.pressure.update(self.step*STEP)
                self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1);self.compression.bind_to_storage_buffer(3);self.clock_offset.bind_to_storage_buffer(2)

    def render(self,time):
        time=float(np.clip(time,0,1.05));self.seek(time)
        self.fbo.use();self.ctx.disable(moderngl.BLEND)
        # 弹窗暗层与旧渲染器相同：随动画淡出（参考素材的 dim_alpha 为 0）。
        self.bg_program['dim']=float(self.meta.get('dim_alpha',0.))*(1.-float(np.clip((time-.18)/.55,0,1))**2)
        self.bg_tex.use(1);self.bg_vao.render(mode=moderngl.TRIANGLES,vertices=3)
        self.ctx.enable(moderngl.BLEND);self.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
        self.program['time']=time;self.program['extrapolate']=max(0.,time-self.step*STEP)
        self.fg_tex.use(0);self.release_tex.use(6)
        self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1)
        for material_pass in [0,1]:
            self.fg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST) if material_pass==0 else (moderngl.LINEAR,moderngl.LINEAR)
            self.program['material_pass']=material_pass
            self.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=self.n)
        self.ctx.disable(moderngl.BLEND);self.out_fbo.use();self.tex.use(2);self.out_vao.render(mode=moderngl.TRIANGLES,vertices=3)
        return np.frombuffer(self.out_fbo.read(components=3,alignment=1),dtype='uint8').reshape(self.h,self.w,3)[::-1].copy()

    def close(self):
        if self.pressure is not None:self.pressure.close();self.pressure=None
        for k in ['material','state','compute','program','vao','bg_program','bg_vao','out_program','out_vao','fg_tex','bg_tex','fbo','tex','out_fbo','out_tex',
                  'release_tex','native_tex','flow_tex','conf_tex','compression','clock_offset']:
            getattr(self,k).release()


def reference_inputs(name):
    """参考重放的输入：方向、触点远近、种子与起点（初始条件，不含运动参数）。"""
    from v2_inputs import inputs
    q=inputs(name)
    keys={f.name for f in fields(Site)}
    return dict(direction=q['direction'],seed=q['seed'],touch_strength=q.get('touch_strength'),
                sites=[Site(**{k:v for k,v in s.items() if k in keys}) for s in q['sites']] if q['sites'] else None)
