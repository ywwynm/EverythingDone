"""检查屏幕内距离的实际位移、共同输入以及完整旧问题集合的状态。"""
from pathlib import Path
import argparse,hashlib,json,sys
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint,SHARED
from touch_geometry import distance_cases
from measure_flow_shaping import measure
from evaluate_targeted_release import destination_metrics
OUT=HERE/'analysis/curve-split'

def main():
    p=argparse.ArgumentParser();p.add_argument('--all-cases',action='store_true');a=p.parse_args()
    ctx=moderngl.create_standalone_context(require=430)
    metas=json.loads((HERE/'assets/scenes.json').read_text('utf-8'));rows=[]
    if a.all_cases:
        cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
        cases+=[dict(id='G-'+m['name'],scene=m['name'],angle=m['direction'],seed=m['seed']) for m in metas if not m.get('holdout')]
        for i,c in enumerate(cases):
            r=Renderer(c['scene'],direction=c['angle'],seed=c['seed'],quality=1,ctx=ctx)
            rows.append({**c,**measure(r)});r.close()
            if i%20==0:print('状态检查',i+1,'/',len(cases),flush=True)
        (OUT/'regression.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),cases=rows),ensure_ascii=False),'utf-8')
        assert not any(r['stagnant_windows'] or r['reverse_steps'] for r in rows)
        print('状态检查通过',len(rows),flush=True)
    else:
        for name in ['ironman','attachment','color']:
            meta=next(m for m in metas if m['name']==name)
            for direction in [135,90]:
                triple=[]
                for case in distance_cases(meta,direction):
                    r=Renderer(name,ctx=ctx,quality=1,direction=case['angle'],touch_gap=case['gap'])
                    row=dict(scene=name,requested=direction,**case,touch_strength=r.touch_strength,**destination_metrics(r))
                    triple.append(row);rows.append(row);r.close()
                for key in ['speed_median','displacement_median']:
                    values=[r[key] for r in triple]
                    # 明显但有界的差异；不要求所有粒子到达或被吸入触点。
                    assert values[0]<values[1]<values[2] and 1.2<values[2]/values[0]<1.85,(name,direction,key,values)
                print(name,direction,'速度比',round(triple[2]['speed_median']/triple[0]['speed_median'],3),
                    '位移比',round(triple[2]['displacement_median']/triple[0]['displacement_median'],3),flush=True)
        unchanged=[]
        for name in ['common-flow.f16','common-release.f32','flow-confidence.u8']:
            assert (SHARED/name).read_bytes()==(HERE/'archive/before-curve-split/particle-dismiss'/name).read_bytes()
            unchanged.append(name)
        r=Renderer('ironman',ctx=ctx);normalized=r.base[:,:2]/[r.cw,r.ch]
        regions={}
        for title,mask in [('left',(normalized[:,0]<.06)&(normalized[:,1]>.20)),('top',(normalized[:,1]<.06)&(normalized[:,0]>.20))]:
            regions[title]=float(np.mean(r.base[mask,2]<.43))
        r.close()
        (OUT/'metrics.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),distance_cases=rows,
            unchanged_fields=unchanged,ironman_release_at_043=regions,
            scope='共同场未替换；数值检查实际近远输入和运动，不据此认定弧边与华为一致。'),ensure_ascii=False,indent=2),'utf-8')
        print('距离检查通过；0.43 边缘释放比例',regions,flush=True)
    ctx.release()

if __name__=='__main__':main()
