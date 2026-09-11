"""检查适度距离变化、原有形态保留及状态连续；数值不替代视觉判断。"""
from pathlib import Path
import argparse,hashlib,json,sys
import numpy as np
import moderngl

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint,release_locality,release_patches,SHARED
from touch_geometry import distance_cases
from measure_flow_shaping import measure
from evaluate_targeted_release import frozen,destination_metrics
OUT=HERE/'analysis/flow-family'


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--all-cases',action='store_true');args=parser.parse_args()
    ctx=moderngl.create_standalone_context(require=430);metas=json.loads((HERE/'assets/scenes.json').read_text('utf-8'))
    if args.all_cases:
        cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
        cases += [dict(id='G-'+m['name'],scene=m['name'],angle=m['direction'],seed=m['seed']) for m in metas if not m.get('holdout')]
        rows=[]
        for i,c in enumerate(cases):
            r=Renderer(c['scene'],direction=c['angle'],seed=c['seed'],quality=1,ctx=ctx)
            row=dict(c);row.update(measure(r));rows.append(row);r.close()
            if i%16==0:print('状态检查',i+1,'/',len(cases),flush=True)
        (OUT/'regression.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),cases=rows),ensure_ascii=False),'utf-8')
        assert sum(r['stagnant_windows'] for r in rows)==0
        assert sum(r['reverse_steps'] for r in rows)==0
        print('状态检查通过',len(rows),flush=True)
    else:
        rows=[]
        for name in ['ironman','attachment','color']:
            meta=next(m for m in metas if m['name']==name)
            for requested in [135,90]:
                triple=[]
                for case in distance_cases(meta,requested):
                    r=Renderer(name,direction=case['angle'],touch_gap=case['gap'],quality=1,ctx=ctx)
                    row=dict(scene=name,requested=requested,**case,**destination_metrics(r));triple.append(row);rows.append(row);r.close()
                for key in ['speed_median','displacement_median']:
                    values=[r[key] for r in triple]
                    assert values[2]>values[0],(name,requested,key,values)
                    assert values[2]<values[0]*1.6,(name,requested,'差异过大',key,values)
                print(name,requested,'速度',*[round(r['speed_median'],3) for r in triple],
                      '位移',*[round(r['displacement_median'],3) for r in triple],flush=True)
        old,_=frozen();preservation=[]
        for name in ['ironman','thanos','kobe']:
            r=Renderer(name,quality=1,ctx=ctx);before=old(name,quality=1,ctx=ctx)
            frames=[]
            for t in [.35,.48,.63]:
                r.seek(t);before.seek(t)
                a=np.frombuffer(r.state.read(),np.float32).reshape(r.n,8)
                b=np.frombuffer(before.state.read(),np.float32).reshape(before.n,8)
                assert np.array_equal(r.base[:,3],before.base[:,3])
                live=(r.base[:,2]<t)&(r.base[:,2]+r.base[:,6]>t)&(before.base[:,2]<t)&(before.base[:,2]+before.base[:,6]>t)
                error=np.linalg.norm(a[live,:2]-b[live,:2],axis=1)/r.span
                frames.append(dict(time=t,median=float(np.median(error)),p90=float(np.quantile(error,.9))))
            row=dict(scene=name,release_delta_mean=float(np.mean(np.abs(r.base[:,2]-before.base[:,2]))),frames=frames,
                locality=release_locality(r.cw,r.ch,r.direction,r.meta['seed']))
            # 保护先前正常输入：同身份粒子不因新参考而全盘改换轨迹。
            assert max(f['median'] for f in frames)<.10,(name,row)
            preservation.append(row);r.close();before.close()
        frozen_path=HERE/'archive/before-targeted-release/particle-dismiss'
        for name in ['common-flow.f16','flow-confidence.u8']:
            assert hashlib.sha256((SHARED/name).read_bytes()).digest()==hashlib.sha256((frozen_path/name).read_bytes()).digest()
        result=dict(model_hash=model_fingerprint(),distance_cases=rows,preservation=preservation,
            scope='原输入轨迹变化、屏幕内触点及距离增量检查；不以此宣称华为形态精确复现。')
        (OUT/'metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
        print('距离与原输入保留检查通过',flush=True)
    ctx.release()


if __name__=='__main__':main()
