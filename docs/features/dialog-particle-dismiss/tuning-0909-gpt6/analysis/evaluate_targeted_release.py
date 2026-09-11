"""检查实际目标接近、同身份近远位移、局部等时线及既有停滞回归。"""
from pathlib import Path
import argparse,importlib.util,json,sys
import cv2,moderngl,numpy as np
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model
from measure_flow_shaping import measure,CASES
from expand_stalled_edges import visibility
OUT=HERE/'analysis/targeted-release'

def frozen(folder_name='before-targeted-release'):
    folder=HERE/'archive'/folder_name
    def load(name,path):
        spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
    oldmodel=load('before_target_model',folder/'unified_model.py');oldmodel.SHARED=folder/'particle-dismiss'
    sys.modules['unified_model']=oldmodel
    try:old=load('before_target_renderer',folder/'renderer.py')
    finally:sys.modules['unified_model']=unified_model
    old.HERE=HERE
    return old.Renderer,oldmodel

def destination_metrics(r):
    positions=[]
    for k in range(61):
        r.seek(k/60);positions.append(np.frombuffer(r.state.read(),np.float32).reshape(r.n,8)[:,:2].copy())
    positions=np.array(positions);wind=np.array(r.wind)
    edge=min(r.cw/(2*max(abs(wind[0]),1e-6)),r.ch/(2*max(abs(wind[1]),1e-6)))
    target=np.array([r.cw,r.ch])*.5+wind*(edge+r.span*r.touch_gap)
    fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
    xy=np.clip(r.base[:,:2].astype(int),[0,0],[r.cw-1,r.ch-1]);alpha=fg[xy[:,1],xy[:,0],3]/255
    vis=np.array([visibility(r.base,alpha,k/60)[0] for k in range(61)])
    last=np.where(vis,np.arange(61)[:,None],-1).max(axis=0);valid=last>0;ids=np.flatnonzero(valid)
    q=positions[last[valid],ids];start=r.base[valid,:2]
    d0=np.linalg.norm(target-start,axis=1);d1=np.linalg.norm(target-q,axis=1)
    speed=np.linalg.norm(np.diff(positions,axis=0),axis=2)*60/r.span
    visible_speed=speed[(vis[1:]&vis[:-1])]
    return dict(target=target.tolist(),speed_median=float(np.median(visible_speed)),speed_p90=float(np.quantile(visible_speed,.9)),
        destination_closure_median=float(np.median((d0-d1)/np.maximum(d0,1))),
        moved_closer_fraction=float(np.mean(d1<d0)),displacement_median=float(np.median(np.linalg.norm(q-start,axis=1)/r.span)))

def front_measure(model,width,height,angle,seed):
    nx=96;ny=round(nx*height/width);f=model.release_field(nx,ny,angle,width,height,seed)
    direction=np.array([np.cos(np.deg2rad(angle)),-np.sin(np.deg2rad(angle))]);cross=np.array([-direction[1],direction[0]])
    across_extent=abs(cross[0])*width+abs(cross[1])*height;forward_extent=abs(direction[0])*width+abs(direction[1])*height
    spans=[];perpendicular=[];components=[]
    for t in np.arange(.12,.72,.05):
        band=(abs(f-t)<.018).astype(np.uint8)
        # 只统计有足够长度的前沿，不把零散短小轮廓作为异常。
        n,labels,stats,_=cv2.connectedComponentsWithStats(band,8)
        for i in range(1,n):
            y,x=np.where(labels==i)
            if len(x)<24:continue
            points=np.column_stack([(x+.5)/nx*width,(y+.5)/ny*height])
            span=np.ptp(points@cross)/across_extent;depth=np.ptp(points@direction)/forward_extent
            spans.append(float(span));perpendicular.append(bool(span>.85 and depth<.40))
        if t<.24:components.append(int(sum(stats[1:,4]>=24)))
    return dict(long_transverse=sum(perpendicular),fronts=len(perpendicular),max_span=max(spans,default=0),early_components=components)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--all-cases',action='store_true');args=parser.parse_args()
    ctx=moderngl.create_standalone_context(require=430);old,oldmodel=frozen();rows=[]
    if args.all_cases:
        cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
        metas=json.loads((HERE/'assets/scenes.json').read_text('utf-8'))
        cases += [dict(id='G-'+m['name'],scene=m['name'],angle=m['direction'],seed=m['seed']) for m in metas if not m.get('holdout')]
        for i,c in enumerate(cases):
            r=renderer.Renderer(c['scene'],direction=c['angle'],seed=c['seed'],quality=1,ctx=ctx)
            row=dict(c);row.update(measure(r));rows.append(row);r.close()
            if i%12==0:print('停滞回归',i+1,'/',len(cases),flush=True)
        result=dict(model_hash=unified_model.model_fingerprint(),cases=rows)
        (OUT/'regression.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
        assert sum(r['stagnant_windows'] for r in rows)==0
        assert sum(r['reverse_steps'] for r in rows)==0
        print('全部状态回归通过',len(rows),flush=True)
    else:
        for name in ['ironman','attachment','color']:
            for angle in [135,90]:
                for gap in [.15,.65,1.5]:
                    r=renderer.Renderer(name,direction=angle,touch_gap=gap,quality=1,ctx=ctx)
                    result=dict(scene=name,angle=angle,gap=gap,**measure(r),**destination_metrics(r));r.close();rows.append(result)
                    print(name,angle,gap,'速度',round(result['speed_median'],3),'位移',round(result['displacement_median'],3),flush=True)
        front=[]
        for name in ['ironman','attachment','color','holdout-wide','holdout-tall']:
            m=json.loads((HERE/f'assets/{name}/scene.json').read_text('utf-8'));x,y,x1,y1=m['rect']
            for angle in [0,45,90,135,180,225,270,315]:
                for seed in [0,2,909602]:
                    front.append(dict(scene=name,angle=angle,seed=seed,before=front_measure(oldmodel,x1-x,y1-y,angle,seed),after=front_measure(unified_model,x1-x,y1-y,angle,seed)))
        for name in ['ironman','attachment','color']:
            for angle in [135,90]:
                triplet=[r for r in rows if r['scene']==name and r['angle']==angle]
                assert triplet[2]['speed_median']>triplet[0]['speed_median']*1.65
                assert triplet[2]['displacement_median']>triplet[0]['displacement_median']*1.65
                assert all(r['moved_closer_fraction']>.85 for r in triplet)
        (OUT/'distances-and-fronts.json').write_text(json.dumps(dict(model_hash=unified_model.model_fingerprint(),distance=rows,fronts=front),ensure_ascii=False),'utf-8')
        print('远近速度、位移与目标接近检查通过；长横向前沿',sum(r['before']['long_transverse'] for r in front),'→',sum(r['after']['long_transverse'] for r in front),flush=True)
    ctx.release()

if __name__=='__main__':main()
