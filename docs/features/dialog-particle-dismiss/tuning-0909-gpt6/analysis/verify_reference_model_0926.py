"""保护旧效果、重播确定性和开放边界；不将数值检查等同视觉复刻成功。"""
from pathlib import Path
import dataclasses,json,sys
import moderngl,numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from front_transport import FrontRenderer,FrontRules,ReleaseSite,sample_sites,front_field
from export_videos import load_meta
DATA=HERE/'analysis/new-references-0926'


def main():
    ctx=moderngl.create_standalone_context(require=430);old=[];motion=[]
    for name in ['ironman','thanos','kobe','ironman-up-reference','attachment','color','language']:
        a=Renderer(name,quality=1,ctx=ctx);b=FrontRenderer(name,quality=1,ctx=ctx)
        record=dict(scene=name,materials_equal=bool(np.array_equal(a.base,b.base)),frames=[])
        for t in [.25,.40,.56,.72,.88]:
            aa=a.render(t).astype('float32');bb=b.render(t).astype('float32');err=np.abs(aa-bb)
            sa=np.frombuffer(a.state.read(),np.float32).reshape(-1,8);sb=np.frombuffer(b.state.read(),np.float32).reshape(-1,8)
            active=(t>a.base[:,2])&(t<a.base[:,2]+a.base[:,6])
            delta=np.abs(sa[:,:2]-sb[:,:2])
            record['frames'].append(dict(phase=t,rgb_mean=float(err.mean()),rgb_max=float(err.max()),
                fraction_above_5=float((err>5).mean()),max_visible_position_px=float(delta[active].max(initial=0)),
                visible_position_p999=float(np.quantile(delta[active],.999)) if active.any() else 0.,
                max_all_position_px=float(delta.max())))
        a.close();b.close();old.append(record)
        print('旧效果检查',name,max(q['rgb_mean'] for q in record['frames']),flush=True)
    for name in ['ironman','attachment','color','reference-0926-5']:
        m=load_meta(name);x,y,x1,y1=m['rect']
        for angle,seed in [(0,42),(90,909602),(135,6),(270,173)]:
            sites=sample_sites(x1-x,y1-y,seed)
            r=FrontRenderer(name,ctx=ctx,sites=sites,direction=angle,seed=seed,quality=1)
            first=None;rows=[]
            for t in [.25,.45,.65,.85]:
                image=r.render(t);s=np.frombuffer(r.state.read(),np.float32).reshape(-1,8)
                age=t-r.base[:,2];active=(age>.05)&(age<r.base[:,6]-.03)
                speed=s[active,4:6]@np.array(r.wind)/r.span
                outside=(s[:,0]<0)|(s[:,1]<0)|(s[:,0]>r.cw)|(s[:,1]>r.ch)
                rows.append(dict(phase=t,finite=bool(np.isfinite(s).all()),active=int(active.sum()),
                    axial_speed_p01=float(np.quantile(speed,.01)) if len(speed) else None,
                    outside_original_rect=int((outside&active).sum())))
                if t==.45:first=image.copy()
            replay=r.render(.45)
            motion.append(dict(scene=name,angle=angle,seed=seed,rows=rows,replay_equal=bool(np.array_equal(first,replay))))
            r.close()
        print('运动检查',name,flush=True)
    old_ok=all(q['materials_equal'] and max(f['rgb_mean'] for f in q['frames'])<.01 and
        max(f['max_visible_position_px'] for f in q['frames'])<.10 and
        max(f['visible_position_p999'] for f in q['frames'])<.01 for q in old)
    motion_ok=all(q['replay_equal'] and all(f['finite'] and (f['axial_speed_p01'] is None or f['axial_speed_p01']>.05) for f in q['rows']) for q in motion)
    report=dict(rules=dataclasses.asdict(FrontRules()),old=old,motion=motion,old_ok=old_ok,motion_ok=motion_ok,
                warning='这些检查只证明回归和运动约束，不证明与华为接近逐像素一致。')
    (DATA/'model-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    (DATA/'compatibility-check.json').write_text(json.dumps(old,ensure_ascii=False,indent=2),'utf-8')
    assert old_ok and motion_ok,(old_ok,motion_ok)
    print('模型数值检查通过；视觉复刻仍须查看原片对照。',flush=True)


if __name__=='__main__':main()
