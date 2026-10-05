"""补充背景不完整参考的高频观测，不将重建背景升级为 RGB 真值。

这里只使用原片本身能观测到的局部高频图像量。它仍含源纹理与拍摄噪声，
因此单列掩码、损失类别及结果，不能和十段可信背景的像素误差合并。
"""
from pathlib import Path
import argparse, hashlib, json, pickle, sys
import cv2, moderngl, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from fit_particle_transport import prepare, N, LO, SIZE, PHASES
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def high_frequency(frame):
    f=np.asarray(frame,np.float32)/255.
    return cv2.GaussianBlur(abs(f-cv2.GaussianBlur(f,(0,0),1.5)),(0,0),8.)


def observed_mask(meta,shape):
    """控件及其附近，避开状态栏、手机外部；不是背景真值掩码。"""
    h,w=shape[:2];x,y,x1,y1=meta['rect'];span=min(x1-x,y1-y)
    yy,xx=np.mgrid[:h,:w]
    dx=np.maximum(np.maximum(x-xx,xx-x1),0)
    dy=np.maximum(np.maximum(y-yy,yy-y1),0)
    weight=np.clip(1-np.hypot(dx,dy)/(span*.12),0,1).astype(np.float32)
    weight[:max(0,round(y-span*.08))]=0
    return weight


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--fresh',action='store_true',help='按当前全部配置重建十二段实际材料和高频观测，不复用历史基线')
    ap.add_argument('--out',type=Path,default=HERE/'analysis/match-cache/uncertain-grain/cache')
    ap.add_argument('--root',type=Path,default=HERE,help='完整基准配置目录；非默认配置须使用 --fresh')
    ap.add_argument('--pair-offset',type=float,default=0.,help='为每个观测补充稍后的独立原片帧，供连续画面变化约束；需要 --fresh')
    a=ap.parse_args()
    if a.root.resolve()!=HERE.resolve() and not a.fresh:ap.error('非默认配置需要 --fresh，不能与历史缓存混合')
    if a.pair_offset and (not a.fresh or not 0.<a.pair_offset<.08):ap.error('相邻观测需要 --fresh，间隔须在 0～0.08 之间')
    base=HERE/'analysis/match-cache/particle-transport/particles-fa3198331bd76d22.pkl'
    grain=HERE/'analysis/match-cache/transport-capacity/all-reference-grain/grain-targets.npz'
    out=a.out;out.mkdir(parents=True,exist_ok=True)
    if a.fresh:
        data=[];actual=[];target=[]
        phases=sorted(PHASES+[min(.94,p+a.pair_offset) for p in PHASES]) if a.pair_offset else None
        extra=prepare(a.root/'conditional-transport-weights.json',fine=True,scenes=list(OBSERVED_SITES),phases=phases,root=a.root)
    else:
        data=pickle.loads(base.read_bytes());g=np.load(grain)
        actual=list(g['actual']);target=list(g['target'])
        extra=prepare(HERE/'conditional-transport-weights.json',fine=True,scenes=['reference-0926-2','reference-0926-3'])
    cls,kw=configuration(a.root);ctx=moderngl.create_standalone_context(require=430)
    for name in dict.fromkeys(d['name'] for d in extra):
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=c.scene.meta['rect'];w,h=x1-x,y1-y
        yy,xx=np.mgrid[:N,:N].astype(np.float32)
        qx=x+(LO+(xx+.5)/N*SIZE)*w;qy=y+(LO+(yy+.5)/N*SIZE)*h
        sample=lambda f:cv2.remap(f,qx,qy,cv2.INTER_LINEAR)
        mask=observed_mask(c.scene.meta,(r.h,r.w))
        for d in [v for v in extra if v['name']==name]:
            if not d['mask'].any():
                d['grain_mask']=sample(mask);d['photo_weight']=0.
            elif not a.fresh:raise AssertionError('历史补充仅适用于没有可信全图背景的参考')
            i,ref=c.ref.nearest(d['progress']);assert abs(float(c.ref.progress[i])-d['progress'])<1e-7
            actual.append(sample(high_frequency(r.render(d['progress']))))
            target.append(sample(high_frequency(ref)))
            data.append(d)
        r.close();print('实际高频观测' if a.fresh else '仅高频观测',name,flush=True)
    ctx.release()
    (out/'particles.pkl').write_bytes(pickle.dumps(data,protocol=5))
    np.savez_compressed(out/'grain-targets.npz',actual=actual,target=target,names=[d['name'] for d in data],phases=[d['progress'] for d in data])
    identity=hashlib.sha256((a.root/'conditional-transport-weights.json').read_bytes()+(a.root/'particle-scatter-weights.json').read_bytes()).hexdigest()
    (out/'scope.json').write_text(json.dumps(dict(source_cache='当前实际 GPU' if a.fresh else str(base),root=str(a.root.resolve()),fresh=a.fresh,identity=identity,rows=len(data),additional=len(extra),pair_offset=a.pair_offset,
        observation='未知背景参考仅使用局部高频；含源纹理与拍摄噪声，不作为全图 RGB 或纯粒子真值'),ensure_ascii=False,indent=2),'utf-8')
    print(out,flush=True)


if __name__=='__main__':main()
