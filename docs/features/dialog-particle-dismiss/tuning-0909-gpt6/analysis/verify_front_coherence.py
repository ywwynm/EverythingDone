"""固定整帧复现与材料追踪：衔接、收束、原表面及距离回归。"""
from pathlib import Path
import sys,json,importlib.util
import numpy as np,moderngl
from PIL import Image
from scipy.ndimage import gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import load_meta,Reference
from unified_model import SHARED,model_fingerprint
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics
OUT=HERE/'analysis/front-coherence';BASE=HERE/'archive/before-front-coherence'
TIMES=[.33,.404,.48,.562,.574,.63,.72,.84]

def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def baseline_renderer():
    model=load('coherence_old_model',BASE/'unified_model.py')
    module=load('coherence_old_renderer',BASE/'renderer.py')
    module.HERE=HERE;module.materials=model.materials
    return module.Renderer

def main():
    OUT.mkdir(exist_ok=True);ctx=moderngl.create_standalone_context(require=430)
    frames={};metrics={};materials={}
    for title,cls in [('baseline',baseline_renderer()),('current',Renderer)]:
        r=cls('ironman',ctx=ctx)
        frames[title]=[r.render(t) for t in TIMES]
        np.save(OUT/f'ironman-{title}.npy',np.stack(frames[title]))
        a=r.base;materials[title]=a.copy();q=a[:,:2]/[r.cw,r.ch]
        regions={'upper_right':(q[:,0]>.88)&(q[:,1]<.24),
            'core':(q[:,0]>.48)&(q[:,0]<.72)&(q[:,1]>.25)&(q[:,1]<.55),
            'tail':(q[:,0]<.20)&(q[:,1]>.70),
            'top_intact':(q[:,1]<.035)&(q[:,0]>.30)&(q[:,0]<.70),
            'left_intact':(q[:,0]<.035)&(q[:,1]>.25)&(q[:,1]<.55)}
        row={k:dict(birth_median=float(np.median(a[mask,2])),released_043=float(np.mean(a[mask,2]<.43))) for k,mask in regions.items()}
        r.seek(.574);state=np.frombuffer(r.state.read(),np.float32).reshape(-1,8).copy()
        active=(a[:,2]<.574)&(a[:,2]+a[:,6]>.574)
        for k in ['upper_right','tail']:
            mask=regions[k]&active
            delta=state[mask,:2]-a[mask,:2]
            row[k].update(displacement_p95=float(np.quantile(np.linalg.norm(delta,axis=1),.95)),
                width_p90=float(np.quantile(state[mask,0],.95)-np.quantile(state[mask,0],.05)),
                speed_median=float(np.median(np.linalg.norm(state[mask,4:6],axis=1))))
        # 以相同白色表面、黑色背景测量覆盖，不把原图明暗差异算成粒子密度。
        fg=np.array(Image.open(r.directory/'foreground.png').convert('RGBA'));fg[:,:,:3]=255
        r.fg_tex.write(fg.tobytes());r.bg_tex.write(bytes(r.w*r.h*3))
        l,t,rr,b=r.meta['rect'];w=rr-l;h=b-t
        row['core']['surface_0562']=float(r.render(.562,diagnostic=3)[t+int(h*.25):t+int(h*.55),l+int(w*.48):l+int(w*.72),0].mean()/255.)
        particle=r.render(.574,diagnostic=2)[:,:,0]/255.
        particle=np.where(particle<=.04045,particle/12.92,((particle+.055)/1.055)**2.4)
        row['upper_right']['outside_coverage']=float(particle[max(0,t-100):t+int(h*.25),rr+12:].sum())
        trail=particle[t+int(h*.70):t+int(h*1.01),max(0,l-90):l+int(w*.27)]
        column=trail.sum(axis=0);cumulative=column.cumsum()/max(column.sum(),1e-6)
        row['tail']['rendered_width_p80']=int(np.searchsorted(cumulative,.90)-np.searchsorted(cumulative,.10))
        row['tail']['rendered_coverage']=float(trail.sum())
        metrics[title]=row;r.close()
    # 输入身份、着色、随机时钟保持不变；本次有意改变释放和法线。
    assert np.array_equal(materials['baseline'][:,[0,1,3,7,8,9,10,11]],materials['current'][:,[0,1,3,7,8,9,10,11]])
    before,after=metrics['baseline'],metrics['current']
    print(json.dumps(metrics,ensure_ascii=False),flush=True)
    assert after['upper_right']['birth_median']<before['upper_right']['birth_median']-.025
    assert after['core']['surface_0562']>before['core']['surface_0562']+.10
    assert after['upper_right']['outside_coverage']<before['upper_right']['outside_coverage']*.6
    # 释放提前后同一源区已有更多颗粒进入弧边，源区状态宽度不能代表屏幕上的拖尾。
    # 检查实际可见覆盖收窄且颗粒份额保留，防止仅靠删粒子通过。
    assert after['tail']['rendered_width_p80']<=before['tail']['rendered_width_p80']-4
    assert after['tail']['rendered_coverage']>before['tail']['rendered_coverage']*.8
    for key in ['top_intact','left_intact']:assert after[key]['released_043']<=before[key]['released_043']+.01
    refs=[Reference(load_meta('ironman')).at(t) for t in TIMES];errors={}
    for name,images in frames.items():
        errors[name]=[float(abs(gaussian_filter(a.astype('float32'),(4,4,0))-gaussian_filter(b.astype('float32'),(4,4,0)))[195:820,65:645].mean()) for a,b in zip(images,refs)]
    print(json.dumps(dict(errors=errors,phases=TIMES)),flush=True)
    # 完整画面是主要审阅；此误差用于发现整体退步，不是感知相似度百分比。
    assert np.mean(errors['current'])<np.mean(errors['baseline'])
    distances=[]
    for name in ['ironman','attachment','color']:
        for direction in [135,90]:
            group=[];births=[]
            for c in distance_cases(load_meta(name),direction):
                r=Renderer(name,ctx=ctx,quality=1,direction=c['angle'],touch_gap=c['gap'])
                births.append(r.base[:,[2,6]].copy())
                group.append(dict(scene=name,requested=direction,**c,touch_strength=r.touch_strength,**destination_metrics(r)));r.close()
            assert all(np.array_equal(births[0],b) for b in births[1:])
            for key in ['speed_median','displacement_median']:
                v=[c[key] for c in group];assert v[0]<v[1]<v[2] and 1.6<v[2]/v[0]<2.7,(name,direction,key,v)
            distances.extend(group)
    unchanged=['common-flow.f16','common-release.f32','flow-confidence.u8','rules.properties']
    for name in unchanged:assert (SHARED/name).read_bytes()==(BASE/'shared'/name).read_bytes()
    result=dict(model_hash=model_fingerprint(),distance_cases=distances,unchanged_fields=unchanged,regions=metrics,
        appearance_error=dict(phases=TIMES,**errors),source_identity_random_clock_identical=True,
        scope='区域仅用于诊断，运行时没有场景或内容坐标规则；状态和整帧误差不代表与参考完全一致。')
    (OUT/'metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='distance_cases'},ensure_ascii=False),flush=True)
    ctx.release()

if __name__=='__main__':main()
