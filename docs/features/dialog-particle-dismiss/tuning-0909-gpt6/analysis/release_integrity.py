"""原片与模型同口径的解除时刻图：源纹理在局部是否仍然完整。

`match_observe` 的“表面保留”用 5×5 邻域容差，颗粒刚解除、还没走远时仍会被判成表面，
比目视的“开始碎成颗粒”晚。这里改用紧容差（桌面录屏 3×3，手持片因配准残差用 5×5）逐像素比对
源图，按 10 像素块统计“仍与源图一致”的比例。先在前 0.20 中确认可辨认的表面，排除开场确认浮层；
此后完整度持续降到该参考值一半以下的进度，记为该块解除时刻。原片与模型用同一套计算。

观测解除时刻不等于解析到达时刻 T。表面交接、颗粒遮挡、源图与背景对比度会引入空间变化的滞后；
固定 26 灰阶容差尤其可能高估暗部保留。拟合结果必须实际渲染后按同口径复测，不能把逐段常数滞后
当作已验证的通用校准。本工具只用于离线检查与拟合，不向运行模型提供逐段前沿。

用法：
  python analysis/release_integrity.py --sheet <标签> [--rules <json>] [--inputs <json>]
  python analysis/release_integrity.py --fit <标签> [--rounds 3]
"""
from pathlib import Path
import argparse,dataclasses,hashlib,json,math,sys,time
import numpy as np,cv2

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from dissolve_v2 import RULES,Site,release_field,reference_inputs
from v2_inputs import OBSERVED_SITES

CACHE=HERE/'analysis/match-cache/release-integrity'
B=10;TOL=26.;VERSION=4
# RELEASE_WINDOW=k（k≥1）时改用「窗口完整度」：像素只要在前后 k 个采样帧内有一帧与原图一致就算完整。
# 从别处飞过、持续覆盖在原表面上的颗粒会移动，窗口内总有一帧露出原图；原表面真的解除之后则一帧也对不上。
# 用来分开「表面被颗粒流盖住」与「表面已解除」（参考 7、科比这类颗粒浓密的片段）。缓存按窗口分开。
import os as _os0
WINDOW=int(_os0.environ.get('RELEASE_WINDOW','0'))
SCENES=['ironman','thanos','kobe','spiderman-original','reference-0926-1',
        'reference-0926-4','reference-0926-5','reference-0926-6','reference-0926-7']
# 向上钢铁侠留出；参考 2、3 背景无法观测（片尾不完整、背景为重建），只细化起点、目视验收，不参与全局规则。
HOLDOUT=['ironman-up-reference','reference-0926-2','reference-0926-3']
GLOBAL=[('duration',.6,1.8),('exponent',1.,2.2),('softmin',3.,12.),('round_front',0.,.15),('meeting',0.,.15),
        ('meeting_width',.01,.15),('edge_speed',0.,2.5),('edge_depth',.01,.1),('front_aniso',-.6,.6),
        ('edge_ring',.1,1.),('edge_ring_width',0.,.4),('edge_site_margin',0.,.3),('content_edge',0.,8.),('content_edge_power',.5,3.),('late_bump',0.,.4),('late_bump_radius',.08,.45),('sweep_radius',.1,.7),('sweep_time',.02,.25),('creep_speed',.1,1.2),('edge_lead',0.,1.),('bulk_time',.35,.75),('bulk_spread',0.,.3),('bulk_noise_scale',.15,.8)]
# 环境变量 RELEASE_FIXED='meeting=0,edge_speed=0' 可把个别全局项固定在给定值（检验某个结构是否必要）。
import os as _os
FIXED={k:float(v) for k,v in (q.split('=') for q in _os.environ.get('RELEASE_FIXED','').split(',') if q)}
GLOBAL=[g for g in GLOBAL if g[0] not in FIXED]
# RELEASE_BOUNDS='edge_depth=0.08:0.2' 改个别全局项的搜索范围。
for q in (x for x in _os.environ.get('RELEASE_BOUNDS','').split(',') if x):
    k,r=q.split('=');a,b=(float(v) for v in r.split(':'))
    GLOBAL=[(g[0],a,b) if g[0]==k else g for g in GLOBAL]


def load_rules(path):
    if not path:return RULES
    d=json.loads(Path(path).read_text('utf-8'))
    d=d.get('best',d).get('rules',d)
    return dataclasses.replace(RULES,**{k:v for k,v in d.items() if k in dataclasses.asdict(RULES)})


class Probe:
    """一段素材的紧容差完整度测量。"""
    def __init__(self,name):
        from match_compare import Comparison
        from match_observe import HANDHELD
        self.name=name;self.comp=Comparison(name);s=self.scene=self.comp.scene
        x,y,x1,y1=s.rect;self.rect=(x,y,x1,y1)
        if name in HANDHELD:hi,lo=s.srcmax,s.srcmin
        else:
            k=np.ones((3,3),np.uint8);hi,lo=cv2.dilate(s.source,k),cv2.erode(s.source,k)
        self.hi=hi[y:y1,x:x1];self.lo=lo[y:y1,x:x1]
        roi=s.roi
        if not roi.any():
            # 背景无法观测的参考（2、3）：完整度只需要源图，用手机屏幕内的整张控件测。
            from match_observe import SCREEN
            roi=np.zeros_like(s.card)
            if name in SCREEN:sx,sy,sx1,sy1=SCREEN[name];roi[sy:sy1,sx:sx1]=True
        self.known=(s.card&s.distinct&roi)[y:y1,x:x1]
        h,w=self.known.shape;self.bh,self.bw=h//B,w//B
        den=self.known[:self.bh*B,:self.bw*B].reshape(self.bh,B,self.bw,B).sum((1,3))
        self.valid=den>B*B*.3;self.den=np.maximum(den,1)
        self.u=(np.arange(self.bw)+.5)*B/(x1-x);self.v=(np.arange(self.bh)+.5)*B/(y1-y)
        self.direction=reference_inputs(name)['direction']
        idx=[i for i in self.comp.index if self.comp.ref.progress[i]<=.85]
        self.index=idx[::max(1,len(idx)//48)];self.progress=[float(self.comp.ref.progress[i]) for i in self.index]

    def matches(self,frame):
        x,y,x1,y1=self.rect;f=frame[y:y1,x:x1].astype('float32')
        e=np.maximum(np.maximum(f-self.hi,0),np.maximum(self.lo-f,0)).max(2)
        return (e<TOL)&self.known

    def integrity(self,frame):
        ok=self.matches(frame)
        return ok[:self.bh*B,:self.bw*B].reshape(self.bh,B,self.bw,B).sum((1,3))/self.den

    def integrities(self,frames):
        """逐帧完整度；WINDOW>0 时按窗口完整度（见文件开头）。"""
        if WINDOW<=0:return [self.integrity(f) for f in frames]
        ms=[self.matches(f) for f in frames];out=[]
        for k in range(len(ms)):
            ok=np.zeros_like(ms[k])
            for j in range(max(0,k-WINDOW),min(len(ms),k+WINDOW+1)):ok|=ms[j]
            out.append(ok[:self.bh*B,:self.bw*B].reshape(self.bh,B,self.bw,B).sum((1,3))/self.den)
        return out

    def release(self,values):
        """解除时刻：已确认的表面降到参考完整度一半以下、此后不再回升。

        颗粒从仍完好的表面上方飞过也会让完整度暂时下降，但飞过之后会回升；真正解除之后不会回升。
        “此后不再回升”允许一次偶发（手持片的单帧配准误差）。
        """
        v=np.stack(values);progress=np.asarray(self.progress)
        early=progress<=.20
        if not early.any():
            raise ValueError('缺少前 0.20 的表面观测，无法确定解除时刻')
        # 最初三帧可能被“确认删除”浮层遮住。只在前段曾明确看见源表面的块上测量，
        # 并从首次达到该完整度的时刻开始，避免把浮层退场误当成解除或永久盲区。
        init=v[early].max(0)
        anchor=np.argmax(v[early]>=.85*init[None],axis=0)
        anchor_time=progress[np.flatnonzero(early)[anchor]]
        T=np.full(init.shape,np.nan)
        use=self.valid&(init>.55);above=v>=.6*init
        later=np.cumsum(above[::-1],axis=0)[::-1]      # 该采样点及以后回升到六成以上的次数
        stays=(v<.5*init)&(later<=1)&(progress[:,None,None]>=anchor_time)
        for k,p in enumerate(self.progress):
            hit=stays[k]&np.isnan(T)&use;T[hit]=p
        T[use&np.isnan(T)]=1.
        return T

    def huawei(self):
        CACHE.mkdir(parents=True,exist_ok=True)
        # 旧版测量作为历史证据保留；新语义不得直接覆盖旧拟合使用的缓存。
        suffix='' if WINDOW<=0 else f'-w{WINDOW}'
        path=CACHE/f'{self.name}{suffix}-v{VERSION}.npz'
        if path.exists():
            z=np.load(path)
            if int(z['version'])==VERSION and z['T'].shape==(self.bh,self.bw):return z['T']
        T=self.release(self.integrities([np.asarray(self.comp.ref.frames[i]) for i in self.index]))
        np.savez(path,T=T,version=VERSION);return T

    def model(self,renderer):
        return self.release(self.integrities([cv2.GaussianBlur(renderer.render(p),(0,0),.65) for p in self.progress]))

    def analytic(self,rules,sites,seed,panel=0.,direction=None):
        x,y,x1,y1=self.rect;w,h=x1-x,y1-y
        nx=64 if w>=h else max(8,round(64*w/h));ny=64 if h>=w else max(8,round(64*h/w))
        if not hasattr(self,'_fg'):
            from PIL import Image
            self._fg=np.array(Image.open(HERE/'assets'/self.name/'foreground.png').convert('RGBA'))
        T=release_field(nx,ny,w,h,sites,seed,rules,panel,self.direction if direction is None else direction,self._fg)
        from scipy.ndimage import map_coordinates
        V,U=np.meshgrid(self.v,self.u,indexing='ij')
        return map_coordinates(T.astype('float64'),[V*ny-.5,U*nx-.5],order=1,mode='nearest')


def site_list(sites):
    keys={f.name for f in dataclasses.fields(Site)}
    return [s if isinstance(s,Site) else Site(**{k:v for k,v in s.items() if k in keys}) for s in sites]


def error(pred,TH,valid):
    """已解除的块比较时刻；到 0.85 仍未解除的块只惩罚预测过早。"""
    use=valid&np.isfinite(TH)
    if not use.any():return 0.
    done=use&(TH<1);late=use&(TH>=1)
    e=np.abs(pred-TH)[done].sum()+np.maximum(.85-pred,0)[late].sum()
    return float(e/use.sum())


# ---------- 拟合（多进程） ----------
_W={}


def _setup(names):
    from unified_model import panel_material
    from PIL import Image
    for n in names:
        p=Probe(n);q=reference_inputs(n)
        fg=np.array(Image.open(HERE/'assets'/n/'foreground.png').convert('RGBA'))
        lag=json.loads((CACHE/'lag.json').read_text('utf-8'))[n]
        if _os.environ.get('RELEASE_LAG_MAP') and (CACHE/f'lag-{n}.npy').exists():
            m=np.load(CACHE/f'lag-{n}.npy')
            if m.shape==(p.bh,p.bw):lag=m
        # 滞后的倍数：0 表示假定解除即消失（解析解除时刻直接对华为实测），用来检验滞后图是否把结构带偏。
        lag=lag*float(_os.environ.get('RELEASE_LAG_SCALE','1'))
        _W[n]=dict(probe=p,TH=p.huawei(),seed=q['seed'],panel=panel_material(fg)[1],lag=lag)


def _scene_loss(name,rules,sites):
    w=_W[name];pred=np.minimum(w['probe'].analytic(rules,sites,w['seed'],w['panel'])+w['lag'],1.)
    return error(pred,w['TH'],w['probe'].valid)


def _eval_global(args):
    rules,inputs=args
    return float(np.mean([_scene_loss(n,rules,site_list(inputs[n])) for n in inputs]))


def _fit_scene(args):
    name,rules,sites,gens,pop,shape=args
    import cma
    obs=OBSERVED_SITES[name];s0=site_list(sites);lo=[];hi=[];x0=[]
    # RELEASE_LINE=1 时每个起点多一个沿边长度（0 为点起点，1 为整条边）一起细化。
    line=bool(_os.environ.get('RELEASE_LINE'));K=6
    for s,(ox,oy,ot) in zip(s0,obs):
        # 沿边程度（0～1）只在 site_edge 打开时作为逐起点的随机量细化；否则固定为规则默认值。
        rv=float(_os.environ.get('RELEASE_RATE','.5'))    # 与全局 rate_variation 一致：起点速率倍数的范围 exp(±rv)
        sp=float(_os.environ.get('RELEASE_SITE_SPAN','.12'));st=float(_os.environ.get('RELEASE_START_SPAN','.08'))
        lo+=[max(-.08,ox-sp),max(-.08,oy-sp),max(0.,ot-st),math.exp(-rv),0.,0.];hi+=[min(1.08,ox+sp),min(1.08,oy+sp),ot+st,math.exp(rv),1. if shape else 0.,1. if line else 0.]
        x0+=[s.x,s.y,s.start,1. if s.rate is None else s.rate,(.5 if s.edge is None else s.edge) if shape else 0.,s.length if line else 0.]
    sp=float(_os.environ.get('RELEASE_SITE_SPAN','.12'));st=float(_os.environ.get('RELEASE_START_SPAN','.08'))
    lo=np.array(lo);hi=np.array(hi);span=np.maximum(hi-lo,1e-9);x0=np.clip(np.array(x0),lo,hi)
    def decode(u):
        v=lo+span*np.clip(u,0,1)
        return [Site(float(np.clip(v[K*k],-.06,1.06)),float(np.clip(v[K*k+1],-.06,1.06)),float(max(v[K*k+2],0.)),float(v[K*k+3]),
                     float(v[K*k+4]) if shape else None,float(v[K*k+5]) if line else 0.) for k in range(len(s0))]
    def f(u):
        ss=decode(u)
        pen=sum(.3*((a.x-ox)**2+(a.y-oy)**2)/sp**2+.3*(a.start-ot)**2/st**2 for a,(ox,oy,ot) in zip(ss,obs))*.01
        return _scene_loss(name,rules,ss)+pen
    u0=(x0-lo)/span;best=(f(u0),u0)
    # 线段起点的长度从几档不同初值各试一次（整条边与点起点之间的损失面多峰）。
    starts=[u0]
    if line:
        for L in (.35,.7,1.):
            u=u0.copy();u[5::K]=L;starts.append(u)
    for k0,us0 in enumerate(starts):
        es=cma.CMAEvolutionStrategy(us0,.18,{'bounds':[0,1],'popsize':pop,'seed':7+k0,'verbose':-9})
        for _ in range(gens):
            us=es.ask();vals=[f(u) for u in us];es.tell(us,vals);i=int(np.argmin(vals))
            if vals[i]<best[0]:best=(vals[i],np.array(us[i]))
    return name,[dataclasses.asdict(s) for s in decode(best[1])],best[0]


def measure_lag(names,rules):
    """当前模型的测量滞后：渲染测量值减解析 T 的中位数（逐段常数）。"""
    import moderngl
    from dissolve_v2 import V2Renderer
    from unified_model import panel_material
    from PIL import Image
    ctx=moderngl.create_standalone_context(require=430);out={}
    for n in names:
        p=Probe(n);q=reference_inputs(n);r=V2Renderer(n,rules=rules,ctx=ctx,quality=2,**q)
        fg=np.array(Image.open(HERE/'assets'/n/'foreground.png').convert('RGBA'))
        Tm=p.model(r);Ta=p.analytic(rules,site_list(q['sites']),q['seed'],panel_material(fg)[1]);r.close()
        ok=p.valid&np.isfinite(Tm)&(Tm<1)
        out[n]=float(np.median((Tm-Ta)[ok])) if ok.sum()>20 else 0.
        # 逐块滞后：同一测量对纹理、明暗有系统偏差（亮块、强纹理块“看起来仍是原图”的时间更长），原片与模型都有。
        # 用模型自己的逐块滞后（平滑、缺值取中位数）代替常数，拟合时才不把测量偏差当成解除差异。
        lag=np.where(ok,Tm-Ta,np.nan);fill=np.where(np.isfinite(lag),lag,out[n]).astype('float32')
        wgt=np.isfinite(lag).astype('float32')
        num=cv2.GaussianBlur(np.nan_to_num(lag).astype('float32'),(0,0),1.5);den=cv2.GaussianBlur(wgt,(0,0),1.5)
        smooth=np.where(den>.2,num/np.maximum(den,1e-6),out[n])
        np.save(CACHE/f'lag-{n}.npy',np.clip(smooth,-.1,.25).astype('float32'))
        print(f'滞后 {n:22s} {out[n]:+.3f}  逐块标准差 {float(np.nanstd(lag)):.3f}',flush=True)
    return out


def fit(label,rounds,workers,shape=False,start_from=None):
    import multiprocessing as mp,cma
    CACHE.mkdir(parents=True,exist_ok=True)
    names=SCENES
    if not (CACHE/'lag.json').exists():
        (CACHE/'lag.json').write_text(json.dumps(measure_lag(names+HOLDOUT,RULES),indent=1),'utf-8')
    # 原片解除时刻图先在主进程算好并落盘，避免多个进程同时写同一个缓存文件。
    for n in names+HOLDOUT:Probe(n).huawei()
    lag_rules=_os.environ.get('RELEASE_LAG_RULES')
    if lag_rules:
        # 颗粒外观（出生粒径、收缩）改变后，模型“看起来仍是原图”的时长随之改变，滞后要按新外观重测。
        (CACHE/'lag.json').write_text(json.dumps(measure_lag(names+HOLDOUT,load_rules(lag_rules)),indent=1),'utf-8')
    rules=RULES;inputs={n:[dataclasses.asdict(s) if isinstance(s,Site) else s for s in reference_inputs(n)['sites']] for n in names}
    extra={}
    if start_from:
        prev=json.loads(Path(start_from).read_text('utf-8'));rules=dataclasses.replace(RULES,**prev['rules'])
        inputs.update({k:v for k,v in prev['inputs'].items() if k in names})
        extra={k:v for k,v in prev['inputs'].items() if k not in names}   # 留出素材的起点原样带到结果里
    rules=dataclasses.replace(rules,**FIXED)
    ctx=mp.get_context('spawn')
    with ctx.Pool(workers,initializer=_setup,initargs=(names,)) as pool:
        base=np.mean(pool.map(_eval_global,[(rules,inputs)]))
        print(f'起始 {base:.4f}',flush=True)
        for rd in range(rounds):
            # 1) 固定全局规则，逐段细化起点（在观测值附近）。
            res=pool.map(_fit_scene,[(n,rules,inputs[n],45,12,shape) for n in names])
            for n,s,l in res:inputs[n]=s
            cur=float(np.mean([l for _,_,l in res]))
            print(f'第 {rd+1} 轮 起点细化后 {cur:.4f}',flush=True)
            # 2) 固定起点，拟合全局解除规则。
            keys=[k for k,_,_ in GLOBAL];lo=np.array([a for _,a,_ in GLOBAL]);hi=np.array([b for _,_,b in GLOBAL])
            x0=np.clip(np.array([getattr(rules,k) for k in keys],float),lo,hi);u0=(x0-lo)/(hi-lo)
            def dec(u):return dataclasses.replace(rules,**{k:float(v) for k,v in zip(keys,lo+(hi-lo)*np.clip(u,0,1))})
            es=cma.CMAEvolutionStrategy(u0,.15,{'bounds':[0,1],'popsize':16,'seed':11+rd,'verbose':-9})
            best=(pool.map(_eval_global,[(rules,inputs)])[0],u0)
            for g in range(30):
                us=es.ask();vals=pool.map(_eval_global,[(dec(u),inputs) for u in us]);es.tell(us,vals);i=int(np.argmin(vals))
                if vals[i]<best[0]:best=(vals[i],np.array(us[i]))
            rules=dec(best[1])
            print(f'第 {rd+1} 轮 全局后 {best[0]:.4f}  '+'  '.join(f'{k}={getattr(rules,k):.3f}' for k in keys),flush=True)
    out=dict(label=label,created=time.strftime('%Y-%m-%d %H:%M:%S'),start=base,final=best[0],shape=shape,
             rules={**{k:getattr(rules,k) for k,_,_ in GLOBAL},**FIXED},inputs={**inputs,**extra})
    path=CACHE/f'fit-{label}.json';path.write_text(json.dumps(out,ensure_ascii=False,indent=1),'utf-8');print(path)


def refine_only(label,start_from,names,workers):
    """全局解除规则固定，只细化给定素材的起点（例如留出素材：它不参与全局规则的拟合，但起点仍按观测细化）。"""
    import multiprocessing as mp
    for n in names:Probe(n).huawei()
    prev=json.loads(Path(start_from).read_text('utf-8'));rules=dataclasses.replace(RULES,**prev['rules'])
    inputs={n:prev['inputs'].get(n) or [dataclasses.asdict(s) if isinstance(s,Site) else s for s in reference_inputs(n)['sites']] for n in names}
    with mp.get_context('spawn').Pool(min(workers,len(names)),initializer=_setup,initargs=(names,)) as pool:
        before=pool.starmap(_scene_loss_args,[(n,rules,inputs[n]) for n in names])
        res=pool.map(_fit_scene,[(n,rules,inputs[n],60,12,False) for n in names])
    for (n,sites,l),b in zip(res,before):
        prev['inputs'][n]=sites;print(f'{n:22s} {b:.4f} -> {l:.4f}',flush=True)
    prev['label']=label;path=CACHE/f'fit-{label}.json';path.write_text(json.dumps(prev,ensure_ascii=False,indent=1),'utf-8');print(path)


def _scene_loss_args(name,rules,sites):return _scene_loss(name,rules,site_list(sites))


def sheet(label,rules_path,inputs_path,names):
    import moderngl
    from PIL import Image,ImageDraw,ImageFont
    from dissolve_v2 import V2Renderer
    rules=load_rules(rules_path);over=json.loads(Path(inputs_path).read_text('utf-8')) if inputs_path else {}
    if 'rules' in over:rules=dataclasses.replace(rules,**over['rules'])
    ctx=moderngl.create_standalone_context(require=430);F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14);rows=[];report={}
    def colour(T,W):
        vis=cv2.applyColorMap(np.uint8(np.clip(np.nan_to_num(T,nan=0)/.8,0,1)*255),cv2.COLORMAP_JET)[:,:,::-1].copy()
        vis[np.isnan(T)]=[40,40,50]
        return Image.fromarray(vis).resize((W,round(W*T.shape[0]/T.shape[1])),Image.Resampling.NEAREST)
    for n in names:
        p=Probe(n);q=reference_inputs(n)
        if n in over.get('inputs',{}):q=dict(q,sites=site_list(over['inputs'][n]))
        TH=p.huawei();r=V2Renderer(n,rules=rules,ctx=ctx,quality=2,**q);Tm=p.model(r);r.close()
        e=error(np.nan_to_num(Tm,nan=1.),TH,p.valid);report[n]=e
        d=np.full(TH.shape+(3,),40,np.uint8);dd=np.nan_to_num(Tm-TH);both=np.isfinite(TH)&np.isfinite(Tm)&p.valid
        d[...,0]=np.uint8(np.clip(128+dd*600,0,255));d[...,2]=np.uint8(np.clip(128-dd*600,0,255));d[...,1]=np.uint8(np.clip(128-np.abs(dd)*300,0,255));d[~both]=[40,40,50]
        W=220;tiles=[colour(np.where(p.valid,TH,np.nan),W),colour(np.where(p.valid,Tm,np.nan),W),Image.fromarray(d).resize((W,round(W*d.shape[0]/d.shape[1])),Image.Resampling.NEAREST)]
        row=Image.new('RGB',(3*(W+4)+120,max(t.height for t in tiles)+18),(14,20,30));dr=ImageDraw.Draw(row)
        dr.text((2,20),n[-13:],font=F,fill='white');dr.text((2,40),f'误差 {e:.3f}',font=F,fill='white')
        for k,(t,lab) in enumerate(zip(tiles,['华为 解除时刻','模型 解除时刻','模型−华为 红晚 蓝早'])):
            row.paste(t,(120+k*(W+4),18));dr.text((120+k*(W+4)+2,1),lab,font=F,fill=(255,230,120))
        rows.append(row);print(f'{n:22s} {e:.4f}',flush=True)
    Wd=max(r.width for r in rows);out=Image.new('RGB',(Wd,sum(r.height+4 for r in rows)),(14,20,30));yo=0
    for r in rows:out.paste(r,(0,yo));yo+=r.height+4
    CACHE.mkdir(parents=True,exist_ok=True);out.save(CACHE/f'{label}.jpg',quality=92)
    (CACHE/f'{label}.json').write_text(json.dumps(report,indent=1),'utf-8')
    print('平均',round(float(np.mean([report[n] for n in names if n not in HOLDOUT])),4),CACHE/f'{label}.jpg')


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--sheet');ap.add_argument('--fit');ap.add_argument('--rules');ap.add_argument('--inputs')
    ap.add_argument('--scenes',nargs='+',default=SCENES+HOLDOUT);ap.add_argument('--rounds',type=int,default=3);ap.add_argument('--workers',type=int,default=12)
    ap.add_argument('--site-edge',dest='shape',action='store_true',help='逐起点的沿边程度（0～1）作为输入细化')
    ap.add_argument('--start-from',help='从上一次拟合结果（规则与起点）继续')
    ap.add_argument('--refine',help='只细化 --scenes 的起点（全局规则取 --start-from），结果另存为该标签')
    a=ap.parse_args()
    if a.refine:refine_only(a.refine,a.start_from,a.scenes,a.workers)
    elif a.fit:fit(a.fit,a.rounds,a.workers,a.shape,a.start_from)
    if a.sheet:sheet(a.sheet,a.rules,a.inputs,a.scenes)


if __name__=='__main__':main()
