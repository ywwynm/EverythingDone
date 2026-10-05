"""解除后原处还剩多少颗粒：按「解除后经过的时间」（年龄）统计每个 10 像素块里的颗粒占用，原片与模型同口径。

颗粒解除后若在原处悬停、寿命又长，原处的颗粒占用会维持较久；若一解除就流走或很快消失，原处很快变空
（除非有上风来的颗粒经过）。年龄 = 帧的进度 − 该块在华为原片里的紧容差解除时刻（模型与原片用同一张解除图，
模型的起点就是按它细化的）。输出各年龄档的平均颗粒占用（颗粒像素／块面积）。只用于离线评估，不进入运行模型。

用法：python analysis/presence_age.py --scenes kobe reference-0926-7 [--over '{"...":...}']
"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,cv2

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
AGES=[(0.,.05),(.05,.1),(.1,.15),(.15,.2),(.2,.3),(.3,.4)]


def presence(probe,frames,progress,T):
    """frames：按进度排列的整帧；返回 [(年龄, 块内颗粒占用)]。"""
    from release_integrity import B
    s=probe.comp.scene;x,y,x1,y1=probe.rect;out=[]
    for f,p in zip(frames,progress):
        o=s.observe(np.asarray(f));part=((o['occ']&~o['surface'])&s.roi)[y:y1,x:x1].astype(np.float32)
        sur=(o['surface']&s.roi)[y:y1,x:x1].astype(np.float32)
        occ=part[:probe.bh*B,:probe.bw*B].reshape(probe.bh,B,probe.bw,B).mean((1,3))
        so=sur[:probe.bh*B,:probe.bw*B].reshape(probe.bh,B,probe.bw,B).mean((1,3))
        use=probe.valid&np.isfinite(T)&(T<p)
        for a,v,w in zip((p-T)[use],occ[use],so[use]):out.append((float(a),float(v),float(w)))
    return out


def table(res,k=1):
    return [(float(np.mean([q[k] for q in res if lo<=q[0]<hi])) if sum(1 for q in res if lo<=q[0]<hi)>=20 else np.nan) for lo,hi in AGES]


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',required=True);ap.add_argument('--rules');ap.add_argument('--over',default='{}')
    a=ap.parse_args()
    import moderngl
    from release_integrity import Probe
    from dissolve_v2 import V2Renderer,RULES,reference_inputs
    rules=RULES
    if a.rules:
        d=json.loads(Path(a.rules).read_text('utf-8'));d=d.get('best',d).get('rules',d)
        rules=dataclasses.replace(RULES,**{k:v for k,v in d.items() if k in dataclasses.asdict(RULES)})
    rules=dataclasses.replace(rules,**json.loads(a.over))
    ctx=moderngl.create_standalone_context(require=430)
    print('年龄分档',' '.join(f'{x:.2f}-{y:.2f}' for x,y in AGES))
    for n in a.scenes:
        pr=Probe(n);T=pr.huawei();C=pr.comp
        idx=[i for i in C.index if .1<=C.ref.progress[i]<=.85]
        prog=[float(C.ref.progress[i]) for i in idx]
        r=V2Renderer(n,rules=rules,ctx=ctx,quality=2,**reference_inputs(n))
        mf=[cv2.GaussianBlur(r.render(p),(0,0),.65) for p in prog];r.close()
        ph=presence(pr,[C.ref.frames[i] for i in idx],prog,T);pm=presence(pr,mf,prog,T)
        print(f'{n:22s} 华为 颗粒 '+' '.join(f'{v:5.2f}' for v in table(ph))+'   原表面 '+' '.join(f'{v:5.2f}' for v in table(ph,2)))
        print(f'{"":22s} 模型 颗粒 '+' '.join(f'{v:5.2f}' for v in table(pm))+'   原表面 '+' '.join(f'{v:5.2f}' for v in table(pm,2)),flush=True)


if __name__=='__main__':main()
