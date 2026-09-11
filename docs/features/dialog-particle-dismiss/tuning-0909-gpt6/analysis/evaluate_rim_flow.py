"""直接检查实际运动、材料交接与旧问题集合；不以指标代替观感。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint,SHARED
from export_videos import load_meta
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics,frozen
OUT=HERE/'analysis/rim-flow'

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--all-cases',action='store_true');a=parser.parse_args()
    if a.all_cases:
        import evaluate_curve_split
        evaluate_curve_split.OUT=OUT;evaluate_curve_split.main();return
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    for name in ['ironman','attachment','color']:
        for direction in [135,90]:
            triple=[];births=[]
            for c in distance_cases(load_meta(name),direction):
                r=Renderer(name,ctx=ctx,quality=1,direction=c['angle'],touch_gap=c['gap'])
                births.append(r.base[:,[2,6]].copy())
                row=dict(scene=name,requested=direction,**c,touch_strength=r.touch_strength,**destination_metrics(r))
                triple.append(row);rows.append(row);r.close()
            assert all(np.array_equal(births[0],b) for b in births[1:]),'距离不应改变出生与寿命'
            for key in ['speed_median','displacement_median']:
                values=[r[key] for r in triple]
                assert values[0]<values[1]<values[2] and 1.6<values[2]/values[0]<2.7,(name,direction,key,values)
            print(name,direction,'实际速度',*[round(r['speed_median'],3) for r in triple],
                  '实际位移',*[round(r['displacement_median'],3) for r in triple],flush=True)
    # 一个独立输入，不改方向、触点或纹理，仅改变距离幅度：轨迹按各自出生位置缩放。
    states=[]
    for strength in [.05,.5,.95]:
        r=Renderer('holdout-wide',direction=17,seed=2718,ctx=ctx,quality=1)
        r.compute['touch_strength']=strength;r.seek(.57)
        state=np.frombuffer(r.state.read(),np.float32).reshape(r.n,8).copy()
        states.append((state[:,:2]-r.base[:,:2])/(.55+.9*strength));r.close()
    material_frame_error=max(float(np.max(np.abs(s-states[1]))) for s in states)
    assert material_frame_error<.15,material_frame_error
    unchanged=[]
    for name in ['common-flow.f16','common-release.f32','flow-confidence.u8']:
        assert (SHARED/name).read_bytes()==(HERE/'archive/before-rim-flow/particle-dismiss'/name).read_bytes()
        unchanged.append(name)
    r=Renderer('ironman',ctx=ctx);xy=r.base[:,:2]/[r.cw,r.ch];regions={}
    before,_=frozen('before-rim-flow');old=before('ironman',ctx=ctx)
    assert np.array_equal(r.base,old.base),'本轮不应提前释放任何原材料';old.close()
    for title,mask in [('left',(xy[:,0]>.02)&(xy[:,0]<.1)&(xy[:,1]>.3)&(xy[:,1]<.85)),
                       ('top',(xy[:,1]>.02)&(xy[:,1]<.1)&(xy[:,0]>.3)&(xy[:,0]<.8))]:
        regions[title]=float(np.mean(r.base[mask,2]>.43))
    r.close();ctx.release()
    (OUT/'metrics.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),distance_cases=rows,
        material_frame_error_px=material_frame_error,birth_and_lifetime_identical=True,
        unchanged_fields=unchanged,ironman_intact_at_043=regions,
        scope='幅度在 0.55 至 1.45 内；数值检查只说明位移、速度、时序及连续性，不代表审美验收。'),ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
