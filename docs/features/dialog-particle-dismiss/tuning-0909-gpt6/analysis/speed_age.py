"""颗粒速度随“解除后经过的时间”（年龄）怎么变：原片与模型同口径。

逐帧看原片时发现：刚解除的材料整体平移（科比的脸 0.44～0.54），更早解除的材料几乎静止（参考 7 的 0.44～0.46，
钢铁侠 0.58～0.70 的弧）。颗粒没有身份，这里用局部块的相位相关估计相邻独立画面之间的位移：
32 像素块、只取原位表面已解除（华为紧容差解除时刻早于该帧 0.02 以上）且有颗粒的块；手持片先扣掉控件外背景的
整体位移（手抖）。年龄 = 帧的进度 − 该块的解除时刻（原片与模型都用华为实测的解除时刻，模型的起点按它拟合）。
输出按年龄分档的位移速度中位数（短边／进度）。只用于离线评估，不进入运行模型。

用法：python analysis/speed_age.py --scenes kobe reference-0926-7 [--over '{"...":...}']
"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,cv2

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
P=32
AGES=[(0.,.05),(.05,.1),(.1,.15),(.15,.22),(.22,.3),(.3,.45)]


def gray(f):return cv2.cvtColor(np.ascontiguousarray(f),cv2.COLOR_RGB2GRAY).astype(np.float32)


def shifts(a,b,mask,win):
    """a、b 两帧（灰度），mask 指定可用块（块坐标）；返回 [(by,bx,dx,dy,响应)]。"""
    out=[]
    for by,bx in zip(*np.nonzero(mask)):
        y,x=by*P,bx*P
        pa=a[y:y+P,x:x+P]*win;pb=b[y:y+P,x:x+P]*win
        if pa.std()<3 or pb.std()<3:continue
        (dx,dy),r=cv2.phaseCorrelate(pa,pb)
        out.append((by,bx,dx,dy,r))
    return out


def measure(scene_name,frames,progress,T,probe,handheld):
    """frames：同一素材按进度排列的整帧；T：块级（10 像素）解除时刻；返回 [(年龄, 速度)]。"""
    from release_integrity import B
    s=probe.comp.scene;x,y,x1,y1=probe.rect;win=np.outer(np.hanning(P),np.hanning(P)).astype(np.float32)
    H,W=frames[0].shape[:2];nby,nbx=H//P,W//P
    # 块中心的解除时刻（控件外为 nan）
    cy=(np.arange(nby)*P+P/2);cx=(np.arange(nbx)*P+P/2)
    Tb=np.full((nby,nbx),np.nan)
    for i,yy in enumerate(cy):
        for j,xx in enumerate(cx):
            ty=int((yy-y)//B);tx=int((xx-x)//B)
            if 0<=ty<T.shape[0] and 0<=tx<T.shape[1] and probe.valid[ty,tx]:Tb[i,j]=T[ty,tx]
    bgmask=np.zeros((nby,nbx),bool)
    if handheld:
        # 控件外、离控件至少 20 像素的背景块：用来估计手抖的整体位移
        for i,yy in enumerate(cy):
            for j,xx in enumerate(cx):
                if (xx<x-20 or xx>x1+20 or yy<y-20 or yy>y1+20) and s.roi[int(yy),int(xx)] if 0<=int(yy)<H and 0<=int(xx)<W else False:bgmask[i,j]=True
    res=[]
    for k in range(len(frames)-1):
        t0,t1=progress[k],progress[k+1];dt=t1-t0
        if dt<=0 or dt>.05:continue
        a,b=gray(frames[k]),gray(frames[k+1])
        g=(0.,0.)
        if handheld and bgmask.any():
            bs=[(dx,dy) for _,_,dx,dy,r in shifts(a,b,bgmask,win) if r>.3]
            if len(bs)>=3:g=(float(np.median([q[0] for q in bs])),float(np.median([q[1] for q in bs])))
        tm=(t0+t1)/2
        use=np.isfinite(Tb)&(Tb<tm-.02)
        for by,bx,dx,dy,r in shifts(a,b,use,win):
            if r<.15:continue
            v=np.hypot(dx-g[0],dy-g[1])/dt/probe.comp.scene.span
            res.append((tm-Tb[by,bx],v))
    return res


def table(res):
    row=[]
    for a,b in AGES:
        v=[q[1] for q in res if a<=q[0]<b]
        row.append((np.median(v) if len(v)>=8 else np.nan,len(v)))
    return row


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',required=True);ap.add_argument('--rules');ap.add_argument('--over',default='{}')
    a=ap.parse_args()
    import moderngl
    from release_integrity import Probe
    from match_observe import HANDHELD
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
        idx=[i for i in C.index if .2<=C.ref.progress[i]<=.8]
        prog=[float(C.ref.progress[i]) for i in idx]
        hf=[np.asarray(C.ref.frames[i]) for i in idx]
        r=V2Renderer(n,rules=rules,ctx=ctx,quality=2,**reference_inputs(n))
        mf=[cv2.GaussianBlur(r.render(p),(0,0),.65) for p in prog];r.close()
        hh=table(measure(n,hf,prog,T,pr,n in HANDHELD));mm=table(measure(n,mf,prog,T,pr,False))
        print(f'{n:22s} 华为 '+' '.join(f'{v:5.2f}' if np.isfinite(v) else '   - ' for v,_ in hh))
        print(f'{"":22s} 模型 '+' '.join(f'{v:5.2f}' if np.isfinite(v) else '   - ' for v,_ in mm)+'   样本 '+'/'.join(str(c) for _,c in hh),flush=True)


if __name__=='__main__':main()
