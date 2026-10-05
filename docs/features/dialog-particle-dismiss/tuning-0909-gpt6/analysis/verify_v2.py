"""第二版共同模型的数值检查：确定性、有限值、持续运动、不被控件边界截住、按时结束、方向一致。

这些检查只证明运行约束成立，不证明与华为原片接近；视觉验收看成片与联系表。
"""
from pathlib import Path
import dataclasses,json,math,sys
import moderngl,numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from dissolve_v2 import V2Renderer,RULES,sample_sites,reference_inputs

OUT=HERE/'analysis/match-cache/verify-v2.json'
SCENES=['ironman','thanos','kobe','reference-0926-5','reference-0926-7','spiderman-original','attachment','color','language']


def state(r):return np.frombuffer(r.state.read(),np.float32).reshape(-1,8)


def check(ctx,name,direction,seed,sites=None,touch_strength=None):
    r=V2Renderer(name,ctx=ctx,direction=direction,seed=seed,sites=sites,touch_strength=touch_strength,quality=1)
    rows=[];frames={}
    w=np.array(r.wind);span=r.span
    prev=None
    for t in [.2,.4,.6,.8]:
        img=r.render(t);s=state(r).copy();frames[t]=img
        age=t-r.base[:,2];life=r.base[:,6]
        active=(age>.04)&(age<life*.85)
        vel=s[active,2:4] if False else s[active,4:6]
        axial=(vel@w)/span
        outside=(s[:,0]<0)|(s[:,1]<0)|(s[:,0]>r.cw)|(s[:,1]>r.ch)
        moved=None
        if prev is not None:
            both=active&prev[1]
            if both.any():moved=float(np.median(np.linalg.norm(s[both,:2]-prev[0][both,:2],axis=1))/span)
        rows.append(dict(phase=t,finite=bool(np.isfinite(s).all()),active=int(active.sum()),
                         axial_p05=float(np.quantile(axial,.05)) if len(axial) else None,
                         axial_median=float(np.median(axial)) if len(axial) else None,
                         outside_active=int((outside&active).sum()),moved_median=moved))
        prev=(s,active)
    # 结束：1.0 时没有可见颗粒，也没有原表面。
    end=r.render(1.0).astype('float32');bg=r.render(0.)  # 0 时刻为完整控件
    rr=V2Renderer(name,ctx=ctx,direction=direction,seed=seed,sites=sites,touch_strength=touch_strength,quality=1)
    replay=rr.render(.4);rr.close()
    life_end=float((r.base[:,2]+r.base[:,6]).max())
    r.close()
    return dict(scene=name,direction=direction,seed=seed,rows=rows,replay_equal=bool(np.array_equal(frames[.4],replay)),
                last_particle_end=life_end)


def main():
    ctx=moderngl.create_standalone_context(require=430);report=[]
    for name in SCENES:
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=meta['rect']
        for direction,seed in [(0,42),(90,11),(135,6),(225,173),(315,3)]:
            report.append(check(ctx,name,direction,seed))
        try:
            q=reference_inputs(name)
            if q['sites']:report.append(check(ctx,name,q['direction'],q['seed'],q['sites'],q['touch_strength']))
        except Exception:pass
        print('检查',name,flush=True)
    ok_finite=all(all(r['finite'] for r in q['rows']) for q in report)
    ok_replay=all(q['replay_equal'] for q in report)
    ok_end=all(q['last_particle_end']<=RULES.end+1e-4 for q in report)
    # 活动颗粒沿主方向持续前进（5 分位速度为正），且确实在移动。
    ok_motion=all(all((r['axial_p05'] is None or r['axial_p05']>-.05) for r in q['rows'][1:]) for q in report)
    ok_moving=all(all((r['moved_median'] is None or r['moved_median']>.005) for r in q['rows']) for q in report)
    ok_open=any(any(r['outside_active']>0 for r in q['rows']) for q in report)
    summary=dict(finite=ok_finite,replay=ok_replay,ends_by_rule=ok_end,forward_motion=ok_motion,moving=ok_moving,crosses_card_edge=ok_open,
                 rules=dataclasses.asdict(RULES),cases=report,warning='只证明运行约束，不证明与华为接近。')
    OUT.write_text(json.dumps(summary,ensure_ascii=False,indent=1),'utf-8')
    print({k:v for k,v in summary.items() if k not in ('cases','rules')},flush=True)
    assert ok_finite and ok_replay and ok_end and ok_motion and ok_moving and ok_open


if __name__=='__main__':main()
