"""逐段细化参考重放的输入：消逝方向、触点远近、起点位置／启动／速率倍数。全局规则固定。

这些都是用户允许的差异来源（方向、触点远近、起点数量与位置、随机量），原片里又无法直接读出，
所以在测量值附近做有界细化：方向 ±20°，起点位置 ±0.12、启动 ±0.06，速率倍数与触点远近限制在
运行时随机分布与触点映射的范围内，并对偏离测量值加惩罚。全局运动规则不随任何一段改变。
"""
from pathlib import Path
import argparse,dataclasses,json,math,sys,time
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from dissolve_v2 import RULES,Site
from v2_inputs import OVERRIDES,inputs,OBSERVED_SITES


def refine(name,rules_dict,rv,gens,pop,refine_sites):
    import moderngl,cma
    import match_fit as F
    ctx=moderngl.create_standalone_context(require=430)
    rules=dataclasses.replace(RULES,**rules_dict)
    t=F.Target(name,ctx,18);r=t.renderer;r.set_rules(rules)
    q=inputs(name);d0=q['direction'];s0=[Site(**s) for s in q['sites']];ts0=.5 if q['touch_strength'] is None else q['touch_strength']
    # 方向界限相对场景记录的测量方向（不随多轮细化漂移）。
    measured=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))['direction']
    n=len(s0)
    # 参数：方向偏移、触点远近、每个起点 (x, y, 启动, 速率)。
    lo=[measured-20.-d0,0.];hi=[measured+20.-d0,1.];x0=[0.,ts0]
    obs=OBSERVED_SITES[name]
    for s,(ox,oy,ot) in zip(s0,obs):
        rate=1. if s.rate is None else s.rate
        if refine_sites:
            # 界限相对观测值（不随多轮细化漂移）：位置 ±0.12、启动 ±0.08。
            lo+=[ox-.12,oy-.12,max(0.,ot-.08),math.exp(-rv)];hi+=[ox+.12,oy+.12,ot+.08,math.exp(rv)]
        else:
            lo+=[s.x,s.y,s.start,rate];hi+=[s.x,s.y,s.start,rate]
        x0+=[s.x,s.y,s.start,rate]
    lo=np.array(lo);hi=np.array(hi);x0=np.clip(np.array(x0),lo,hi)
    span=np.maximum(hi-lo,1e-9)
    def decode(u):
        v=lo+span*np.clip(u,0,1)
        sites=[Site(float(np.clip(v[2+4*k],-.06,1.06)),float(np.clip(v[3+4*k],-.06,1.06)),float(max(v[4+4*k],0.)),float(v[5+4*k])) for k in range(n)]
        return float(v[0]),float(v[1]),sites
    def loss(u):
        dd,ts,sites=decode(u)
        r.set_direction(d0+dd);r.set_touch_strength(ts);r.set_sites(sites)
        penalty=2.*((d0+dd-measured)/20.)**2+sum(.8*((a.x-ox)**2+(a.y-oy)**2)/.12**2+.8*(a.start-ot)**2/.08**2 for a,(ox,oy,ot) in zip(sites,obs))
        return t.loss(rules)+penalty
    u0=(x0-lo)/span
    before=loss(u0);best=(before,u0)
    es=cma.CMAEvolutionStrategy(u0,.2,{'bounds':[0,1],'popsize':pop,'seed':53,'verbose':-9})
    for g in range(gens):
        us=es.ask();vals=[loss(u) for u in us];es.tell(us,vals);i=int(np.argmin(vals))
        if vals[i]<best[0]:best=(vals[i],np.array(us[i]))
    dd,ts,sites=decode(best[1])
    return dict(scene=name,before=before,after=best[0],direction=d0+dd,measured_direction=d0,touch_strength=ts,
                sites=[dataclasses.asdict(s) for s in sites])


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules',required=True);ap.add_argument('--scenes',nargs='+',required=True)
    ap.add_argument('--rv',type=float,default=.30);ap.add_argument('--gens',type=int,default=22);ap.add_argument('--pop',type=int,default=12)
    ap.add_argument('--workers',type=int,default=10);ap.add_argument('--no-sites',action='store_true');ap.add_argument('--write',action='store_true')
    a=ap.parse_args()
    rules=json.loads(Path(a.rules).read_text('utf-8'))['best']['rules'];rules={k:v for k,v in rules.items() if k in dataclasses.asdict(RULES)}
    import multiprocessing as mp
    t0=time.perf_counter()
    with mp.get_context('spawn').Pool(min(a.workers,len(a.scenes))) as pool:
        results=pool.starmap(refine,[(n,rules,a.rv,a.gens,a.pop,not a.no_sites) for n in a.scenes])
    for q in results:
        print(f"{q['scene']:22s} 损失 {q['before']:.3f} -> {q['after']:.3f}  方向 {q['measured_direction']:.0f}->{q['direction']:.1f}  触点 {q['touch_strength']:.2f}  起点 {[(round(s['x'],2),round(s['y'],2),round(s['start'],3),round(s['rate'],2)) for s in q['sites']]}",flush=True)
    out=HERE/'analysis/match-cache'/f'inputs-{time.strftime("%H%M%S")}.json'
    out.write_text(json.dumps(results,ensure_ascii=False,indent=1),'utf-8')
    if a.write:
        data=json.loads(OVERRIDES.read_text('utf-8')) if OVERRIDES.exists() else {}
        for q in results:
            data[q['scene']]=dict(direction=round(q['direction'],2),touch_strength=round(q['touch_strength'],3),
                                  sites=[{k:round(v,4) for k,v in s.items()} for s in q['sites']],
                                  note='match_inputs.py 在测量值附近有界细化的输入（方向、触点远近、起点）；全局规则固定')
        OVERRIDES.write_text(json.dumps(data,ensure_ascii=False,indent=1),'utf-8')
    print('用时',round(time.perf_counter()-t0),'s',out)


if __name__=='__main__':main()
