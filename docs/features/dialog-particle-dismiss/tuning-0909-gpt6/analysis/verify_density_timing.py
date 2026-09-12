"""实际渲染验证：渐隐交接、初期覆盖、运动差异与屏幕内触点。"""
from pathlib import Path
import sys,json,importlib.util
import numpy as np,moderngl
from PIL import Image
from scipy.ndimage import gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import load_meta,Reference,sampled
from unified_model import SHARED,model_fingerprint
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics
OUT=HERE/'analysis/density-timing';BASE=HERE/'archive/before-density-timing'

def baseline_renderer():
    spec=importlib.util.spec_from_file_location('density_baseline',BASE/'renderer.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.HERE=HERE
    return module.Renderer

def linear(x):return np.where(x<=.04045,x/12.92,((x+.055)/1.055)**2.4)

def main():
    ctx=moderngl.create_standalone_context(require=430);old=baseline_renderer();details={};motion={};frames={}
    times=[.28,.35,.40,.44,.48,.52,.56,.60,.65,.72,.80,.88]
    for title,cls in [('baseline',old),('current',Renderer)]:
        r=cls('ironman',ctx=ctx);frames[title]=[r.render(t) for t in times]
        # 相同源材料的位移对照，不由渲染公式重新构造预期值。
        r.seek(.65);state=np.frombuffer(r.state.read(),np.float32).reshape(r.n,8).copy()
        motion[title]=(r.base.copy(),state)
        fg=np.array(Image.open(r.directory/'foreground.png').convert('RGBA'));fg[:,:,:3]=255
        r.fg_tex.write(fg.tobytes());r.bg_tex.write(bytes(r.w*r.h*3))
        l,t,right,bottom=r.meta['rect'];partial=[]
        for phase in [.40,.44,.48,.52,.56]:
            surface=linear(r.render(phase,diagnostic=3)[t:bottom,l:right,0]/255.)
            partial.append(float(np.mean((surface>.25)&(surface<.75))))
        # 在原触发角附近测量粒子覆盖，不包含静止表面。
        corners=[]
        for phase in [.28,.35,.40]:
            arr=linear(r.render(phase,diagnostic=2)[:,:,0]/255.)
            corners.append(float(arr[t-75:t+130,l-65:l+150].mean()))
        details[title]=dict(surface_partial_coverage=partial,upper_particle_coverage=corners)
        r.close()
    assert np.array_equal(motion['baseline'][0],motion['current'][0]),'出生、寿命、随机材料不应变化'
    assert np.mean(details['current']['surface_partial_coverage'])>1.6*np.mean(details['baseline']['surface_partial_coverage'])
    assert np.mean(details['current']['upper_particle_coverage'])<.94*np.mean(details['baseline']['upper_particle_coverage'])
    base=motion['current'][0];age=.65-base[:,2];active=(age>.08)&(age<base[:,6])
    delta=motion['current'][1][active,:2]-motion['baseline'][1][active,:2]
    delta_norm=np.linalg.norm(delta,axis=1)
    # 有可见、有限的独立展开；不能用整体平移代替，也不能改变已认可的主轮廓。
    assert 1.<np.median(delta_norm)<20. and np.quantile(delta_norm,.95)<45.
    refs=[Reference(load_meta('ironman')).at(t) for t in times]
    errors={}
    for title,images in frames.items():
        values=[]
        for a,b in zip(images,refs):
            difference=abs(gaussian_filter(a.astype('float32'),(4,4,0))-gaussian_filter(b.astype('float32'),(4,4,0)))
            values.append(float(difference[195:820,65:645].mean()))
        errors[title]=values
    # 同尺度整体外观的诊断误差，不作为“相似度百分比”。
    assert np.mean(errors['current'])<np.mean(errors['baseline'])
    assert np.mean(errors['current'][3:9])<np.mean(errors['baseline'][3:9])
    rows=[]
    for name in ['ironman','attachment','color']:
        for direction in [135,90]:
            group=[];births=[]
            for c in distance_cases(load_meta(name),direction):
                r=Renderer(name,ctx=ctx,quality=1,direction=c['angle'],touch_gap=c['gap'])
                births.append(r.base[:,[2,6]].copy())
                row=dict(scene=name,requested=direction,**c,touch_strength=r.touch_strength,**destination_metrics(r))
                group.append(row);rows.append(row);r.close()
            assert all(np.array_equal(births[0],b) for b in births[1:])
            for key in ['speed_median','displacement_median']:
                v=[c[key] for c in group];assert v[0]<v[1]<v[2] and 1.6<v[2]/v[0]<2.7,(name,direction,key,v)
    unchanged=['common-flow.f16','common-release.f32','flow-confidence.u8','rules.properties']
    for name in unchanged:assert (SHARED/name).read_bytes()==(BASE/'shared'/name).read_bytes()
    ctx.release()
    result=dict(model_hash=model_fingerprint(),distance_cases=rows,unchanged_fields=unchanged,rendered_coverage=details,
        material_birth_life_random_identical=True,grain_displacement_delta_px=dict(median=float(np.median(delta_norm)),p95=float(np.quantile(delta_norm,.95)),centroid=np.mean(delta,axis=0).tolist()),
        appearance_error=dict(phases=times,**errors),scope='实际图像与状态诊断；整体误差不等于视觉相似度，仍需完整过程审阅。')
    (OUT/'metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(json.dumps({k:v for k,v in result.items() if k!='distance_cases'},ensure_ascii=False),flush=True)

if __name__=='__main__':main()
