"""渲染进循环的起点细化：全局规则（解除、运动、外观）固定，逐段素材只细化起点（位置、启动时刻、速率、线段长度），
损失用 match_fit 的块级综合损失（可加致密带项，见环境变量 MATCH_FIT_BAND）。

完整度解除图在暗区没有数据、对成带结构（弧形亮带、成股的流）不敏感，只用它拟合起点得不到钢铁侠、灭霸的结构；
这里直接比较渲染结果。运动规则固定不动，所以不会出现“运动去补结构”的问题；但拟合出的起点只对这一套运动成立。

用法：python analysis/site_render_fit.py --label <标签> --rules <规则 json> --inputs <起点 json> [--scenes ...]
      [--gens 16] [--pop 10] [--workers 9] [--frames 14]
规则 json 可以是拟合结果（取 rules 字段）或 {"rules":{...}}；起点 json 取 inputs 字段（与 V2_SITES 同格式）。
结果写到 analysis/match-cache/site-render/fit-<标签>.json（rules + inputs），可直接作为 V2_SITES 使用。
"""
from pathlib import Path
import argparse,dataclasses,json,math,os,sys,time
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
OUT=HERE/'analysis/match-cache/site-render'
SCENES=['ironman','thanos','kobe','spiderman-original','reference-0926-1','reference-0926-4','reference-0926-5',
        'reference-0926-6','reference-0926-7']


def load(path):
    d=json.loads(Path(path).read_text('utf-8'))
    return d.get('best',d)


def _job(args):
    name,rules_d,sites_d,gens,pop,frames,seed,touch0=args
    import moderngl,cma
    import match_fit as F
    from dissolve_v2 import RULES,Site
    rules=dataclasses.replace(RULES,**{k:v for k,v in rules_d.items() if k in dataclasses.asdict(RULES)})
    ctx=moderngl.create_standalone_context(require=430)
    t=F.Target(name,ctx,frames)
    keys={f.name for f in dataclasses.fields(Site)}
    s0=[Site(**{k:v for k,v in s.items() if k in keys}) for s in sites_d]
    # 位置在当前值 ±0.12 内；启动时刻可提前 0.08、推迟到 +0.3（推迟相当于让某个起点基本不起作用）。
    sp,st,late,rv=.12,.08,float(os.environ.get('SITE_LATE','.3')),.5
    lo=[];hi=[];x0=[]
    for s in s0:
        lo+=[max(-.08,s.x-sp),max(-.08,s.y-sp),max(0.,s.start-st),math.exp(-rv),0.]
        hi+=[min(1.08,s.x+sp),min(1.08,s.y+sp),s.start+late,math.exp(rv),1.]
        x0+=[s.x,s.y,s.start,1. if s.rate is None else s.rate,s.length]
    # SITE_TOUCH=1 时触点远近（0～1，输入）一起细化：它决定运动幅度，原片的真实触点未知。
    touch=bool(os.environ.get('SITE_TOUCH'))
    if touch0 is None:touch0=t.renderer.touch_strength
    else:t.renderer.set_touch_strength(touch0)      # 起点文件里给了触点远近：固定用它（SITE_TOUCH 时作为初值）
    if touch:lo+=[0.];hi+=[1.];x0+=[touch0]
    lo=np.array(lo);hi=np.array(hi);span=np.maximum(hi-lo,1e-9);x0=np.clip(np.array(x0),lo,hi)
    def decode_touch(u):
        return float(lo[-1]+span[-1]*np.clip(u[-1],0,1)) if touch else None
    def decode(u):
        v=lo+span*np.clip(u,0,1)
        return [Site(float(np.clip(v[5*k],-.06,1.06)),float(np.clip(v[5*k+1],-.06,1.06)),float(max(v[5*k+2],0.)),float(v[5*k+3]),s0[k].edge,float(v[5*k+4])) for k in range(len(s0))]
    # 解除时刻的实测约束（SITE_RI_WEIGHT>0 时）：起点的解析解除时刻对华为紧容差完整度解除图的误差（滞后取 0）。
    # 只看渲染损失时，起点会去迁就运动的不足（例如颗粒散得太快，就把解除推迟，让原表面多留一会儿）；
    # 这一项让解除时序在有数据的区域保持与实测一致。
    wri=float(os.environ.get('SITE_RI_WEIGHT','0'))
    if wri>0:
        from PIL import Image
        from release_integrity import Probe,error
        from unified_model import panel_material
        probe=Probe(name);TH=probe.huawei()
        fg=np.array(Image.open(HERE/'assets'/name/'foreground.png').convert('RGBA'));panel=panel_material(fg)[1]
        seed=t.renderer.seed
    # SITE_RI_LAG 指向逐段常数滞后（模型自身「完整度实测 − 解析」的中位数）：解析时刻加上它再与原片比，
    # 修正模型外观带来的整体偏晚／偏早；只是一个常数，不带入逐块结构。
    lag0=0.
    if wri>0 and os.environ.get('SITE_RI_LAG'):
        lag0=float(json.loads(Path(os.environ['SITE_RI_LAG']).read_text('utf-8'))['lag'].get(name,0.))
    def ri(sites):
        if wri<=0:return 0.
        pred=np.minimum(probe.analytic(rules,sites,seed,panel)+lag0,1.)
        return wri*error(pred,TH,probe.valid)
    def f(u):
        ss=decode(u);t.renderer.set_sites(ss)
        if touch:t.renderer.set_touch_strength(decode_touch(u))
        return float(t.loss(rules))+ri(ss)
    u0=(x0-lo)/span;base=f(u0);best=(base,u0)
    es=cma.CMAEvolutionStrategy(u0,.15,{'bounds':[0,1],'popsize':pop,'seed':seed,'verbose':-9})
    t0=time.time()
    for g in range(gens):
        us=es.ask();vals=[f(u) for u in us];es.tell(us,vals);i=int(np.argmin(vals))
        if vals[i]<best[0]:best=(vals[i],np.array(us[i]))
        print(f'{name:22s} 第 {g+1} 代 {min(vals):.2f} 最好 {best[0]:.2f}（起始 {base:.2f}，{time.time()-t0:.0f} 秒）',flush=True)
    t.renderer.set_sites(decode(best[1]))
    if touch:t.renderer.set_touch_strength(decode_touch(best[1]))
    _,det=t.loss(rules,True);det['release_error']=ri(decode(best[1]))/wri if wri>0 else None
    tb=float(t.renderer.touch_strength);t.renderer.close();ctx.release()
    return name,[dataclasses.asdict(s) for s in decode(best[1])],base,best[0],det,tb


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--label',required=True);ap.add_argument('--rules',required=True)
    ap.add_argument('--inputs',required=True);ap.add_argument('--scenes',nargs='+',default=SCENES)
    ap.add_argument('--gens',type=int,default=16);ap.add_argument('--pop',type=int,default=10)
    ap.add_argument('--workers',type=int,default=9);ap.add_argument('--frames',type=int,default=14)
    ap.add_argument('--over',default='{}',help='在规则 json 之上再覆盖的规则（JSON）')
    a=ap.parse_args()
    rules=load(a.rules).get('rules',load(a.rules));rules={**rules,**json.loads(a.over)}
    prev=load(a.inputs);inputs=dict(prev.get('inputs',{}));touch=dict(prev.get('touch',{}))
    from dissolve_v2 import reference_inputs
    jobs=[]
    for n in a.scenes:
        s=inputs.get(n) or [dataclasses.asdict(x) for x in reference_inputs(n)['sites']]
        jobs.append((n,rules,s,a.gens,a.pop,a.frames,7,prev.get('touch',{}).get(n)))
    import multiprocessing as mp
    OUT.mkdir(parents=True,exist_ok=True);report={}
    with mp.get_context('spawn').Pool(min(a.workers,len(jobs))) as pool:
        for n,s,b0,b1,det,tb in pool.imap_unordered(_job,jobs):
            inputs[n]=s;touch[n]=tb;report[n]=dict(start=b0,best=b1,detail=det)
            print(f'完成 {n:22s} {b0:.2f} → {b1:.2f}',flush=True)
    out=dict(label=a.label,created=time.strftime('%Y-%m-%d %H:%M:%S'),rules=rules,inputs=inputs,touch=touch,report=report,
             band_weight=float(os.environ.get('MATCH_FIT_BAND','0')))
    path=OUT/f'fit-{a.label}.json';path.write_text(json.dumps(out,ensure_ascii=False,indent=1),'utf-8');print(path)


if __name__=='__main__':main()
