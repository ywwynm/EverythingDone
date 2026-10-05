"""按原片的原位表面解除细化起点（初始条件），以实际渲染的同口径观测计算误差。

全局规则固定；只调整每段参考的起点位置、启动时刻和推进速率倍数（速率限制在全局随机范围内）。
误差只用“原位表面”这一项，不用云团的位置和形状，避免把运动误差吸收进起点。
可选再加一个起点：初值放在“原片已解除而模型仍保留”最多的地方，只有显著降低误差才采用。
"""
from pathlib import Path
import argparse,dataclasses,json,math,sys,time
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_compare import Comparison
from match_observe import BLOCK
from dissolve_v2 import V2Renderer,Site,RULES
from v2_inputs import SITES,inputs

OUT=HERE/'analysis/match-cache'


def surface_blocks(scene,frame,box):
    y0,y1,x0,x1=box;o=scene.observe(frame[y0:y1,x0:x1])
    m=(scene.card&scene.distinct&scene.roi).astype('float32')
    h=(y1-y0)//BLOCK*BLOCK;w=(x1-x0)//BLOCK*BLOCK
    def pool(a):return a[:h,:w].reshape(h//BLOCK,BLOCK,w//BLOCK,BLOCK).sum((1,3))
    count=pool(m);sur=pool(o['surface'].astype('float32')*m)/np.maximum(count,1)
    return sur,count>BLOCK*BLOCK*.3


class Target:
    def __init__(self,name,rules,ctx,max_progress=.7,frames=16):
        self.name=name;comp=Comparison(name);s=comp.scene
        x,y,x1,y1=s.rect;self.box=(max(0,y-8),min(s.H,y1+8),max(0,x-8),min(s.W,x1+8))
        self.scene=s.cropped(self.box)
        idx=[i for i in comp.index if comp.ref.progress[i]<=max_progress]
        pick=np.unique(np.linspace(0,len(idx)-1,min(frames,len(idx))).round().astype(int))
        self.frames=[idx[i] for i in pick];self.progress=[float(comp.ref.progress[i]) for i in self.frames]
        self.ref=[surface_blocks(self.scene,np.asarray(comp.ref.frames[i]),self.box) for i in self.frames]
        q=inputs(name);self.q=q;self.rules=rules;self.ctx=ctx
        self.sites0=[Site(**d) for d in q['sites']]
        self.renderer=None

    def render_loss(self,sites,detail=False):
        if self.renderer is None:
            self.renderer=V2Renderer(self.name,direction=self.q['direction'],seed=self.q['seed'],sites=sites,rules=self.rules,ctx=self.ctx,quality=1)
        else:self.renderer.set_sites(sites)
        errs=[];maps=[]
        for p,(a,wa) in zip(self.progress,self.ref):
            b,wb=surface_blocks(self.scene,self.renderer.render(p),self.box);w=wa&wb
            errs.append(float(np.abs(a-b)[w].mean()) if w.any() else 0.);maps.append((a,b,w))
        return (float(np.mean(errs)),maps) if detail else float(np.mean(errs))


def pack(sites):return np.array([[s.x,s.y,s.start,1. if s.rate is None else s.rate] for s in sites]).ravel()


def unpack(v):return [Site(float(a),float(b),float(max(c,0.)),float(d)) for a,b,c,d in np.asarray(v).reshape(-1,4)]


def optimize(t,sites,rv,span_xy=.15,span_t=.08,gens=30,pop=10,fixed_new=None):
    import cma
    x0=pack(sites);lo=x0.copy();hi=x0.copy();n=len(sites)
    for k in range(n):
        free=fixed_new is not None and k==n-1
        sx=.6 if free else span_xy;st=.2 if free else span_t
        lo[4*k:4*k+2]-=sx;hi[4*k:4*k+2]+=sx;lo[4*k+2]=max(0.,x0[4*k+2]-st);hi[4*k+2]=min(.45,x0[4*k+2]+st)
        lo[4*k+3]=math.exp(-rv);hi[4*k+3]=math.exp(rv)
    lo[0::4]=np.maximum(lo[0::4],-.06);hi[0::4]=np.minimum(hi[0::4],1.06);lo[1::4]=np.maximum(lo[1::4],-.06);hi[1::4]=np.minimum(hi[1::4],1.06)
    x0=np.clip(x0,lo,hi)
    def f(u):return t.render_loss(unpack(lo+(hi-lo)*np.clip(u,0,1)))
    u0=(x0-lo)/np.maximum(hi-lo,1e-9)
    es=cma.CMAEvolutionStrategy(u0,.18,{'bounds':[0,1],'popsize':pop,'seed':31,'verbose':-9})
    best=(f(u0),u0)
    for g in range(gens):
        us=es.ask();vals=[f(u) for u in us];es.tell(us,vals);i=int(np.argmin(vals))
        if vals[i]<best[0]:best=(vals[i],np.array(us[i]))
    return best[0],unpack(lo+(hi-lo)*best[1])


def refine(name,rules_dict,rv,extra):
    import moderngl
    ctx=moderngl.create_standalone_context(require=430)
    rules=dataclasses.replace(RULES,**rules_dict)
    t=Target(name,rules,ctx);before,maps=t.render_loss(t.sites0,True)
    val,sites=optimize(t,t.sites0,rv)
    result=dict(scene=name,before=before,after=val,sites=[dataclasses.asdict(s) for s in sites],extra=None)
    if extra:
        # 残差最大处：原片已解除而模型仍保留（块级），按全程累计。
        _,maps=t.render_loss(sites,True);acc=None
        for a,b,w in maps:
            d=np.where(w,np.maximum(b-a,0),0);acc=d if acc is None else acc+d
        yy,xx=np.unravel_index(np.argmax(acc),acc.shape);y0,y1,x0,x1=t.box;X,Y,X1,Y1=t.scene.rect
        px=(x0+xx*BLOCK+BLOCK/2-X)/(X1-X);py=(y0+yy*BLOCK+BLOCK/2-Y)/(Y1-Y)
        first=next((p for p,(a,b,w) in zip(t.progress,maps) if (np.maximum(b-a,0)*w)[yy,xx]>.5),.1)
        guess=list(sites)+[Site(float(np.clip(px,0,1)),float(np.clip(py,0,1)),float(max(0.,first-.05)),1.)]
        val2,sites2=optimize(t,guess,rv,fixed_new=True)
        result['extra']=dict(after=val2,sites=[dataclasses.asdict(s) for s in sites2],guess=[px,py,first])
    t.renderer.close()
    return result


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules',required=True);ap.add_argument('--scenes',nargs='+',default=list(SITES))
    ap.add_argument('--rv',type=float,default=.30);ap.add_argument('--extra',action='store_true');ap.add_argument('--out',default='refined-sites-render.json')
    ap.add_argument('--workers',type=int,default=6);a=ap.parse_args()
    rules=json.loads(Path(a.rules).read_text('utf-8'))['best']['rules']
    rules={k:v for k,v in rules.items() if k in dataclasses.asdict(RULES)}
    import multiprocessing as mp
    t0=time.perf_counter()
    with mp.get_context('spawn').Pool(a.workers) as pool:
        results=pool.starmap(refine,[(n,rules,a.rv,a.extra) for n in a.scenes])
    report={r['scene']:r for r in results}
    for r in results:
        line=f"{r['scene']:22s} 表面误差 {r['before']:.4f} -> {r['after']:.4f}"
        if r['extra']:line+=f" | 加一个起点 {r['extra']['after']:.4f} 初值 {tuple(round(v,2) for v in r['extra']['guess'])}"
        print(line,flush=True)
    (OUT/a.out).write_text(json.dumps(report,ensure_ascii=False,indent=1),'utf-8')
    print('用时',round(time.perf_counter()-t0),'s')


if __name__=='__main__':main()
