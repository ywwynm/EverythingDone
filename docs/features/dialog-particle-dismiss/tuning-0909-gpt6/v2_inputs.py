"""参考重放的输入：观测到的消逝方向、粒子化起点位置与启动时刻。

这些是测量得到的初始条件，不含速度、卷度、寿命或光照参数；所有素材共用 dissolve_v2.V2Rules。
起点位置有约 0.03 个控件宽度的不确定性，启动时刻按独立帧序号换算的进度（真实采集时间）观测。
原片的准确触点和华为的随机种子未知，这里的种子只用于复现同类布局，不代表恢复了原始随机状态。
"""
import json,os
from pathlib import Path

HERE=Path(__file__).resolve().parent

# (x, y, 启动进度)，x/y 为控件归一化坐标，边缘起点允许略在边外。
OBSERVED_SITES={
    'reference-0926-1':[(.49,-.02,0.),(1.,.82,.03),(0.,.78,.13)],
    'reference-0926-2':[(.39,.45,0.)],
    'reference-0926-3':[(0.,0.,0.),(0.,.63,.035),(1.,.66,.04)],
    'reference-0926-4':[(.44,.83,0.),(.50,0.,.13),(0.,.28,.15),(1.,.55,.17)],
    'reference-0926-5':[(.42,.42,0.),(.56,0.,.035),(0.,.86,.06)],
    'reference-0926-6':[(.42,0.,0.),(0.,.32,.06)],
    'reference-0926-7':[(.90,.13,0.),(0.,.38,.025)],
    'spiderman-original':[(.72,0.,0.),(0.,.36,.07),(1.,.65,.20)],
    # 旧参考此前一直使用旧共同初始场；2026-09-26 按早期帧补记起点，与新参考同一口径。
    'ironman':[(.62,1.,.02),(1.,.62,.11),(0.,0.,.12)],
    'thanos':[(.86,.46,0.),(0.,0.,.13)],
    'kobe':[(0.,.47,.02),(.55,1.,.20)],
    'ironman-up-reference':[(.78,0.,.02),(.25,.72,.06)],
}


# 2026-09-26：按原位表面解除细化（match_sites.py，全局前沿规则固定，位置 ±0.10、启动 ±0.06 以内）。
# 只用表面解除，不用云团运动；细化前的观测值保留在 OBSERVED_SITES。
REFINED_SITES={
    'ironman':[(0.584, 1.05, 0.003), (1.05, 0.529, 0.088), (-0.05, -0.033, 0.105)],
    'spiderman-original':[(0.82, 0.016, 0.0), (-0.03, 0.26, 0.13), (1.05, 0.55, 0.163)],
    'reference-0926-6':[(0.52, 0.076, 0.0), (0.031, 0.42, 0.013)],
    'reference-0926-7':[(0.8, 0.23, 0.0), (0.025, 0.427, 0.016)],
    'reference-0926-1':[(0.467, 0.011, 0.06), (0.944, 0.836, 0.0), (0.02, 0.88, 0.07)],
    'thanos':[(0.76, 0.484, 0.0), (0.034, -0.05, 0.19)],
    'reference-0926-4':[(0.453, 0.93, 0.06), (0.4, -0.009, 0.144), (0.042, 0.38, 0.155), (0.9, 0.45, 0.165)],
    'kobe':[(0.037, 0.558, 0.004), (0.482, 0.9, 0.173)],
    'ironman-up-reference':[(0.68, 0.1, 0.0), (0.26, 0.62, 0.0)],
    'reference-0926-5':[(0.413, 0.32, 0.0), (0.66, 0.1, 0.0), (0.028, 0.76, 0.0)],
}
SITES={**OBSERVED_SITES,**REFINED_SITES}


# 逐段细化后的输入（方向、触点远近、起点及其速率倍数）记录在同名 JSON 中，由细化脚本写入；
# 缺省时用上面的观测值与场景方向，触点远近取中值 0.5。
OVERRIDES=HERE/'v2_inputs.json'


def inputs(name):
    meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
    sites=SITES.get(name)
    out=dict(direction=float(meta['direction']),seed=int(meta['seed']),touch_strength=None,
             sites=[dict(x=a,y=b,start=c) for a,b,c in sites] if sites else None)
    if OVERRIDES.exists():
        extra=json.loads(OVERRIDES.read_text('utf-8')).get(name)
        if extra:out.update({k:v for k,v in extra.items() if k in out})
    # 离线试验：环境变量 V2_SITES 指向一份 {"inputs": {素材: [起点…]}, "touch": {素材: 触点远近}}，
    # 只替换其中列出的起点与触点远近，不改本文件与 JSON。
    trial=os.environ.get('V2_SITES')
    if trial:
        d=json.loads(Path(trial).read_text('utf-8'))
        extra=d.get('inputs',{}).get(name)
        if extra:out['sites']=extra
        if name in d.get('touch',{}):out['touch_strength']=float(d['touch'][name])
    return out
