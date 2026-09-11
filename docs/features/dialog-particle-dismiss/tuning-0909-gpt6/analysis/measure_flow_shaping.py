"""同一批可见微片扣除群体平移后测形变；同时检查持续运动和触点距离。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from expand_stalled_edges import visibility
OUT=HERE/'analysis/flow-shaping'
CASES=[(n,d,s) for n,d,s in [
 ('ironman',122,909602),('ironman',135,0),('ironman',225,7),
 ('thanos',130,909602),('kobe',128,909602),('attachment',65,909602),
 ('attachment',135,23),('color',65,909602),('color',135,0),
 ('color',45,7),('color',270,23),('language',135,909602),
 ('holdout-wide',135,78192745),('holdout-tall',45,78192745),
 ('holdout-compact-dialog',334,9600910)]]

def measure(r):
    positions=[]
    for k in range(61):
        r.seek(k/60);positions.append(np.frombuffer(r.state.read(),np.float32).reshape(r.n,8)[:,:2].copy())
    positions=np.array(positions)
    fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
    xy=np.clip(r.base[:,:2].astype(int),[0,0],[r.cw-1,r.ch-1]);alpha=fg[xy[:,1],xy[:,0],3]/255
    vis=np.array([visibility(r.base,alpha,i/60)[0] for i in range(61)])
    paths=np.cumsum(np.linalg.norm(np.diff(positions,axis=0,prepend=positions[:1]),axis=2)/r.span,axis=0)
    last=np.where(vis,np.arange(61)[:,None],-1).max(axis=0);ok=last>=0;idx=last[ok]
    travel=paths[idx,np.flatnonzero(ok)]
    axial=np.sum((positions[idx,np.flatnonzero(ok)]-r.base[ok,:2])*r.wind,axis=1)/r.span
    sustained=vis[8:]&vis[:-8];rolling=paths[8:]-paths[:-8]
    grid=np.minimum((r.base[:,:2]/[r.cw,r.ch]*8).astype(int),7)
    group=grid[:,1]*8+grid[:,0];edge=(grid.min(axis=1)==0)|(grid.max(axis=1)==7)
    residuals=[];ratios=[];cohorts=[]
    for k in [24,33,42,48]:
        live=vis[k]&vis[k+8]&((k/60-r.base[:,2])>.06)
        for g in range(64):
            sel=live&(group==g)
            if sel.sum()<24:continue
            delta=(positions[k+8,sel]-positions[k,sel])/r.span
            centroid=delta.mean(axis=0)
            residual=float(np.sqrt(np.mean(np.sum((delta-centroid)**2,axis=1))))
            ratio=residual/(float(np.linalg.norm(centroid))+1e-5)
            aa=positions[k,sel].astype(float)/r.span;bb=positions[k+8,sel].astype(float)/r.span
            aa-=aa.mean(axis=0);bb-=bb.mean(axis=0)
            angle=np.arctan2(np.sum(aa[:,0]*bb[:,1]-aa[:,1]*bb[:,0]),np.sum(aa*bb))
            rotation=np.array([[np.cos(angle),np.sin(angle)],[-np.sin(angle),np.cos(angle)]])
            nonrigid=float(np.sqrt(np.mean(np.sum((aa@rotation-bb)**2,axis=1))))
            boundary=bool(edge[sel][0]);cohorts.append(dict(frame=k,group=g,edge=boundary,n=int(sel.sum()),residual=residual,nonrigid=nonrigid,translation=float(np.linalg.norm(centroid)),ratio=ratio))
            if boundary:residuals.append(residual);ratios.append(ratio)
    return dict(particles=r.n,path_median=float(np.median(travel)),axial_median=float(np.median(axial)),
        path_p90=float(np.quantile(travel,.9)),axial_p90=float(np.quantile(axial,.9)),
        stagnant_windows=int(((rolling<.02)&sustained).sum()),visible_windows=int(sustained.sum()),
        edge_residual_median=float(np.median(residuals)),edge_deformation_ratio=float(np.median(ratios)),
        edge_rigid_fraction=float(np.mean(np.array(ratios)<.08)),
        reverse_steps=int(((np.diff(positions,axis=0)@np.asarray(r.wind))<-.001).sum()),cohorts=cohorts)

def main():
    p=argparse.ArgumentParser();p.add_argument('label');p.add_argument('--distances',action='store_true');a=p.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430);rows=[]
    cases=[(n,d,s,None) for n,d,s in CASES]
    if a.distances:cases+=[(n,d,909602,g) for n in ['ironman','attachment','color'] for d in [135,90] for g in [.15,.65,1.5]]
    for name,angle,seed,gap in cases:
        kw={} if gap is None else dict(touch_gap=gap)
        r=Renderer(name,direction=angle,seed=seed,ctx=ctx,quality=1,**kw)
        row=dict(scene=name,angle=angle,seed=seed,gap=gap,**measure(r));r.close();rows.append(row)
        print(name,angle,seed,gap,'行程',round(row['axial_median'],3),'形变',round(row['edge_deformation_ratio'],3),'低速窗口',row['stagnant_windows'],flush=True)
    ctx.release();(OUT/f'{a.label}.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),cases=rows),ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
