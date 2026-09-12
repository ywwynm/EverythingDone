"""用量化后的正式模型核验完整相位、留出帧、真实距离与共同输入。"""
from pathlib import Path
import sys,json,importlib.util
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import Reference,load_meta
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics
from frame_difference import OUT,BASE,PHASES,metrics,sheet

def frozen_renderer():
    def load(name,path):
        spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
    model=load('pixel_frozen_material',BASE/'unified_model.py');model.SHARED=BASE/'shared';model.RULES=model.load_rules()
    model.RELEASE=np.fromfile(model.SHARED/'common-release.f32','<f4').reshape(96,96)
    module=load('pixel_frozen_renderer',BASE/'renderer.py');module.HERE=HERE;module.RULES=model.RULES
    for name in ['materials','guidance','field_rotation','field_geometry','flow_confidence','touch_strength_from_point']:setattr(module,name,getattr(model,name))
    return module.Renderer

def appearance(ctx):
    old= np.load(BASE/'ironman.npy',mmap_mode='r')[::2]
    before=frozen_renderer()('ironman',ctx=ctx)
    assert np.array_equal(before.render(.4),old[24]),'基线必须恢复其自身资源和规则'
    before.close();r=Renderer('ironman',ctx=ctx)
    new=np.stack([r.render(t) for t in PHASES]);r.close();refs=np.load(OUT/'reference.npy',mmap_mode='r')
    np.save(OUT/'production-ironman.npy',new);sheet(new,refs,'production-ironman')
    yy,xx=np.mgrid[:1280,:720];masks={'calibration_region':(xx>=80)&(xx<635)&(yy>=220)&(yy<850),
        'full_animation_region':(xx>=35)&(xx<685)&(yy>=150)&(yy<930)}
    train=set(range(8,53,4));test=[i for i in range(8,53) if i not in train];results={}
    for scope,mask in masks.items():
        b=metrics(old,refs,mask);a=metrics(new,refs,mask)
        summary={}
        for name,indices in [('all_motion',list(range(8,53))),('held_phases',test),('mid_phase',list(range(12,49)))]:
            summary[name]={k:dict(before=float(np.mean([b[i][k] for i in indices])),after=float(np.mean([a[i][k] for i in indices]))) for k in ['mae_0','mae_2','mae_6','mae_12']}
        results[scope]=dict(before=b,after=a,summary=summary)
    print(json.dumps({k:v['summary'] for k,v in results.items()},ensure_ascii=False),flush=True)
    primary=results['full_animation_region']['summary']['held_phases']
    assert primary['mae_0']['after']<primary['mae_0']['before']
    assert primary['mae_6']['after']<primary['mae_6']['before']*.90
    return results

def main():
    ctx=moderngl.create_standalone_context(require=430);result=appearance(ctx);distances=[]
    for name in ['ironman','attachment','color']:
        for direction in [135,90]:
            group=[];births=[]
            for case in distance_cases(load_meta(name),direction):
                r=Renderer(name,ctx=ctx,direction=case['angle'],touch_gap=case['gap'],quality=1)
                births.append(r.base[:,[2,6]].copy());group.append(dict(scene=name,requested=direction,**case,touch_strength=r.touch_strength,**destination_metrics(r)));r.close()
            assert all(np.array_equal(births[0],b) for b in births[1:])
            for key in ['speed_median','displacement_median']:
                v=[c[key] for c in group];assert v[0]<v[1]<v[2] and 1.6<v[2]/v[0]<2.7,(name,direction,key,v)
            distances.extend(group)
    result=dict(model_hash=model_fingerprint(),appearance=result,distance_cases=distances,
        scope='差异图未经配准形变或时间重排；照片输入参与离线基准场校准，留出的是相位及其它素材，不能据此声称单粒子像素一致。')
    (OUT/'metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');ctx.release();print('正式量化模型及 18 个近远输入通过',flush=True)

if __name__=='__main__':main()
