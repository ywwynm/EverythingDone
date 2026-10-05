"""运动活跃度（原片与模型同口径）：一段进度窗口内颗粒走了多远。

块级误差与致密带都对“颗粒动得多快”不敏感：原片里颗粒解除后常在原处悬停、缓慢漂移，模型颗粒一路流走，
两者在块级占用上可以很接近。这里取窗口内几帧，逐像素求亮度的时间范围（最大减最小），
「变化面积」= 范围超过阈值的像素数，「颗粒面积」= 窗口内各帧颗粒像素数的平均；
比值约等于 1 + 颗粒在窗口内的位移 / 粒径，颗粒不动时接近 1，走得越远越大。
只用于离线评估，不进入运行模型。

用法：python analysis/activity.py --scenes reference-0926-7 [--rules <json>] [--over '{"gain_near":0.3}']
"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,cv2

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
THRESH=24.      # 亮度范围阈值（0～255）：高于录制噪声与压缩块边缘
WINDOWS=[(.30,.38),(.40,.48),(.50,.58),(.60,.68)]


def luminance(f):
    return np.asarray(f,np.float32)@np.float32([.299,.587,.114])


def activity(scene,frames):
    """frames：同一窗口内按先后排列的整帧（原片或模型，同一尺寸）。返回 (比值, 变化面积占比, 颗粒面积占比)。"""
    roi=scene.roi
    L=np.stack([luminance(f) for f in frames])
    rng=(L.max(0)-L.min(0))
    changed=(rng>THRESH)&roi
    part=[]
    for f in frames:
        o=scene.observe(np.asarray(f));part.append(float(((o['occ']&~o['surface'])&roi).sum()))
    area=float(roi.sum())
    p=max(float(np.mean(part)),1.);c=float(changed.sum())
    return c/p,c/area,p/area


def window_indices(progress,a,b,limit=8):
    idx=[i for i,p in enumerate(progress) if a<=p<=b]
    if len(idx)>limit:idx=[idx[k] for k in np.linspace(0,len(idx)-1,limit).round().astype(int)]
    return idx


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',required=True);ap.add_argument('--rules');ap.add_argument('--over',default='{}')
    ap.add_argument('--width',type=float,default=.08,help='窗口宽度（进度）；独立画面稀的片段（向上钢铁侠）要放宽')
    a=ap.parse_args()
    global WINDOWS
    WINDOWS=[(w0,w0+a.width) for w0,_ in WINDOWS]
    import moderngl
    from match_compare import Comparison
    from dissolve_v2 import V2Renderer,RULES,reference_inputs
    rules=RULES
    if a.rules:
        d=json.loads(Path(a.rules).read_text('utf-8'));d=d.get('best',d).get('rules',d)
        rules=dataclasses.replace(RULES,**{k:v for k,v in d.items() if k in dataclasses.asdict(RULES)})
    rules=dataclasses.replace(rules,**json.loads(a.over))
    ctx=moderngl.create_standalone_context(require=430)
    for n in a.scenes:
        C=Comparison(n);s=C.scene;r=V2Renderer(n,rules=rules,ctx=ctx,quality=2,**reference_inputs(n))
        out=[]
        for wa,wb in WINDOWS:
            idx=window_indices([float(C.ref.progress[i]) for i in C.index],wa,wb)
            if len(idx)<3:continue
            ps=[float(C.ref.progress[C.index[i]]) for i in idx]
            hf=[np.asarray(C.ref.frames[C.index[i]]) for i in idx]
            mf=[cv2.GaussianBlur(r.render(p),(0,0),.65) for p in ps]
            h=activity(s,hf);m=activity(s,mf);out.append((wa,h,m))
        r.close()
        print(n,' '.join(f'{wa:.2f}: 华为 {h[0]:.2f}({h[2]:.2f}) 模型 {m[0]:.2f}({m[2]:.2f})' for wa,h,m in out),flush=True)


if __name__=='__main__':main()
