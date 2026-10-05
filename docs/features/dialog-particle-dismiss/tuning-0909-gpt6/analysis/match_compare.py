"""原片逐帧对照：在原片每个独立帧的进度上渲染模型，比较同口径观测量。

数值只衡量形状、位置、密度、明暗与运动的接近程度，不能代替完整画面的目视验收。
手持片的配准误差、背景重建区、片尾缺失与遮挡帧单列，不计入模型误差。
"""
from pathlib import Path
import argparse,json,math,sys,time
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_refs import RefFrames,ALL,NEW,OLD
from match_observe import Scene,piv,BLOCK,OCC_T

OUT=HERE/'analysis/match-cache/reports'
# 原片中与粒子动画无关、会污染测量的时段（进度）：确认框淡出、手指进入等。
EXCLUDE={'reference-0926-5':[(-1,.06)],'reference-0926-4':[(-1,.05)],'reference-0926-3':[(-1,.03)],'reference-0926-1':[(-1,.0)],
         'spiderman-original':[(-1,.03)]}


def usable(name,p,valid):
    if p<0 or p>valid+1e-6:return False
    return not any(a<=p<=b for a,b in EXCLUDE.get(name,[]))


def lowfreq(frame,sigma=6):
    return cv2.GaussianBlur(frame.astype('float32'),(0,0),sigma)


class Comparison:
    def __init__(self,name,stride=1,piv_stride=2):
        self.name=name;self.scene=Scene(name);self.ref=RefFrames(name)
        valid=self.scene.meta['reference'].get('valid_progress',1.)
        # 噪声底取开场前（排除确认框、暗层时段）与片尾粒子已消失后的若干帧。
        pre=[np.asarray(self.ref.frames[i]) for i,p in enumerate(self.ref.progress) if p<=.012 and usable(name,max(p,0.),1.)][-3:]
        post=[np.asarray(self.ref.frames[i]) for i,p in enumerate(self.ref.progress) if p>=.95 and valid>=.99][-3:]
        if pre or post:self.scene.calibrate_noise(pre+post)
        self.index=[i for i,p in enumerate(self.ref.progress) if usable(name,p,valid)][::stride]
        self.piv_pairs=[(i,i+1) for i in self.index[::piv_stride] if i+1<len(self.ref) and usable(name,self.ref.progress[i+1],valid)]
        self.cache={}

    def ref_observation(self,i):
        if i not in self.cache:
            f=np.asarray(self.ref.frames[i]);o=self.scene.observe(f)
            self.cache[i]=(f,o,self.stats(f,o))
        return self.cache[i]

    def stats(self,f,o):
        s=self.scene;area=float(s.card.sum())
        occ,cov=s.blocks(o['occ']);par,_=s.blocks(o['particle']);sur,_=s.blocks(o['surface'])
        lum,lcov=s.blocks(o['lum'],o['occ'])
        m=o['particle']&s.roi;ys,xs=np.nonzero(m)
        centroid=(float(xs.mean()),float(ys.mean())) if len(xs)>50 else (math.nan,math.nan)
        spread=(float(xs.std()),float(ys.std())) if len(xs)>50 else (math.nan,math.nan)
        return dict(occ=occ,par=par,sur=sur,cov=cov,lum=lum,lcov=lcov,low=lowfreq(f),
                    total_occ=float((o['occ']&s.roi).sum())/area,total_particle=float(m.sum())/area,
                    total_surface=float((o['surface']&s.roi).sum())/max(float((s.card&s.distinct&s.roi).sum()),1.),
                    centroid=centroid,spread=spread)

    def compare(self,render):
        """render(progress)->frame。返回逐帧误差与时序。"""
        s=self.scene;rows=[];weight=(s.blocks(s.roi.astype('float32'))[1]>.5)
        for i in self.index:
            p=float(self.ref.progress[i]);f,o,a=self.ref_observation(i)
            g=render(p);b=self.stats(g,s.observe(g))
            w=weight
            low=np.abs(a['low']-b['low']).mean(2)[s.roi].mean()
            raw=np.abs(f.astype('float32')-g.astype('float32')).mean(2)[s.roi].mean()
            lw=w&(a['lcov']>.15)&(b['lcov']>.15)
            rows.append(dict(index=i,progress=p,occ=float(np.abs(a['occ']-b['occ'])[w].mean()),
                particle=float(np.abs(a['par']-b['par'])[w].mean()),surface=float(np.abs(a['sur']-b['sur'])[w].mean()),
                luminance=float(np.abs(a['lum']-b['lum'])[lw].mean()) if lw.any() else math.nan,
                lowfreq_rgb=float(low),raw_rgb=float(raw),
                ref_total_occ=a['total_occ'],mod_total_occ=b['total_occ'],
                ref_total_particle=a['total_particle'],mod_total_particle=b['total_particle'],
                ref_total_surface=a['total_surface'],mod_total_surface=b['total_surface'],
                ref_centroid=a['centroid'],mod_centroid=b['centroid'],ref_spread=a['spread'],mod_spread=b['spread']))
        return rows

    def motion(self,render):
        """同一对独立帧上的 PIV：比较云团局部位移（像素 / 进度）。"""
        s=self.scene;rows=[]
        for i,j in self.piv_pairs:
            pa,pb=float(self.ref.progress[i]),float(self.ref.progress[j]);dt=pb-pa
            if dt<=1e-4:continue
            vr=piv(s,np.asarray(self.ref.frames[i]),np.asarray(self.ref.frames[j]))
            vm=piv(s,render(pa),render(pb))
            def summary(v):
                if len(v)<6:return None
                w=v[:,4]*v[:,5];vel=v[:,2:4]/dt
                return dict(n=len(v),median=np.median(vel,0).tolist(),mean=(vel*w[:,None]).sum(0).tolist() if w.sum()==0 else ((vel*w[:,None]).sum(0)/w.sum()).tolist(),
                            speed=float(np.median(np.linalg.norm(vel,axis=1))))
            # 同一格点上两边都有效时比较矢量差。
            field=None
            if len(vr) and len(vm):
                kr={(int(a),int(b)):v for a,b,*v in vr};km={(int(a),int(b)):v for a,b,*v in vm}
                common=[k for k in kr if k in km]
                if common:
                    d=np.array([[kr[k][0]-km[k][0],kr[k][1]-km[k][1]] for k in common])/dt
                    field=dict(n=len(common),median_error=float(np.median(np.linalg.norm(d,axis=1))))
            rows.append(dict(progress=pa,dt=dt,ref=summary(vr),model=summary(vm),field=field))
        return rows


def summarize(rows,motion=None):
    keys=['occ','particle','surface','luminance','lowfreq_rgb','raw_rgb']
    out={k:float(np.nanmean([r[k] for r in rows])) for k in keys}
    out['total_occ_curve']=float(np.mean([abs(r['ref_total_occ']-r['mod_total_occ']) for r in rows]))
    out['total_particle_curve']=float(np.mean([abs(r['ref_total_particle']-r['mod_total_particle']) for r in rows]))
    out['total_surface_curve']=float(np.mean([abs(r['ref_total_surface']-r['mod_total_surface']) for r in rows]))
    d=[math.dist(r['ref_centroid'],r['mod_centroid']) for r in rows if not any(map(math.isnan,r['ref_centroid']+r['mod_centroid']))]
    out['centroid_px']=float(np.mean(d)) if d else math.nan
    if motion:
        e=[m['field']['median_error'] for m in motion if m['field']]
        out['piv_field_error']=float(np.mean(e)) if e else math.nan
        sp=[(m['ref']['speed'],m['model']['speed']) for m in motion if m['ref'] and m['model']]
        out['piv_speed_ratio']=float(np.median([b/max(a,1e-6) for a,b in sp])) if sp else math.nan
    out['frames']=len(rows)
    return out


def current_candidate(name,ctx):
    """当前桌面候选：新参考用记录的起点，旧参考沿用旧共同初始场。"""
    from front_transport import FrontRenderer,ReleaseSite
    m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
    sites=[ReleaseSite(**q) for q in m['release_sites']] if m.get('release_sites') else None
    return FrontRenderer(name,ctx=ctx,sites=sites,quality=2)


def v2_candidate(name,ctx,rules=None):
    """第二版共同模型：记录的方向、种子与起点作为输入；参数全部来自同一 V2Rules。"""
    from dissolve_v2 import V2Renderer,reference_inputs,RULES
    q=reference_inputs(name)
    return V2Renderer(name,direction=q['direction'],seed=q['seed'],sites=q['sites'],rules=rules or RULES,ctx=ctx,quality=2)


MODELS={'current':current_candidate,'v2':v2_candidate}


def renderer_function(r):
    return lambda p:r.render(float(p))


def sheet(comp,render,path,phases=None):
    """原片 / 模型 / 占用差（红=仅原片，青=仅模型，白=两者）。"""
    s=comp.scene;x,y,x1,y1=s.rect;mg=round(s.span*.42)
    cx0,cy0,cx1,cy1=max(0,x-mg),max(0,y-mg),min(s.W,x1+mg),min(s.H,y1+mg)
    valid=s.meta['reference'].get('valid_progress',1.)
    phases=phases or [q for q in [.05,.1,.16,.22,.3,.38,.46,.56,.68,.82] if q<=valid]
    tw=210;th=round((cy1-cy0)*tw/(cx1-cx0));F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    im=Image.new('RGB',(tw*len(phases),3*th+36),(14,20,30));d=ImageDraw.Draw(im)
    for k,p in enumerate(phases):
        i,f=comp.ref.nearest(p);p=float(comp.ref.progress[i]);g=render(p)
        a=s.observe(f)['occ'];b=s.observe(g)['occ']
        diff=np.zeros(a.shape+(3,),np.uint8);diff[a&~b]=[255,70,60];diff[b&~a]=[40,220,230];diff[a&b]=[235,235,235]
        diff[~s.valid]=[60,40,40]
        for row,arr in enumerate([f,g,diff]):
            c=np.ascontiguousarray(arr[cy0:cy1,cx0:cx1])
            im.paste(Image.fromarray(c).resize((tw,th),Image.Resampling.LANCZOS),(k*tw,18+row*(th+6)))
        d.text((k*tw+3,1),f'{p:.3f}',font=F,fill=(230,230,230))
    im.save(path,quality=92)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=ALL);ap.add_argument('--label',default='baseline')
    ap.add_argument('--model',default='current',choices=list(MODELS));ap.add_argument('--no-motion',action='store_true');a=ap.parse_args()
    import moderngl
    ctx=moderngl.create_standalone_context(require=430);OUT.mkdir(parents=True,exist_ok=True);report={}
    for name in a.scenes:
        t=time.perf_counter();comp=Comparison(name);r=MODELS[a.model](name,ctx);render=renderer_function(r)
        rows=comp.compare(render);motion=None if a.no_motion else comp.motion(render)
        report[name]=dict(summary=summarize(rows,motion),frames=rows,motion=motion)
        sheet(comp,render,OUT/f'{a.label}-{name}.jpg');r.close()
        print(name,{k:(round(v,4) if isinstance(v,float) else v) for k,v in report[name]['summary'].items()},round(time.perf_counter()-t,1),'s',flush=True)
    (OUT/f'{a.label}.json').write_text(json.dumps(report,ensure_ascii=False,indent=1,default=float),'utf-8')


if __name__=='__main__':main()
