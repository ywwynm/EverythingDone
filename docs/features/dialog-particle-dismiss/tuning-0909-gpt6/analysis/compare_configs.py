"""同一损失口径下比较几套配置（规则 + 起点）：块级综合损失、致密带距离与各分项，逐段并行。

用法：python analysis/compare_configs.py --configs 名称=规则json[:起点json] ... [--scenes ...] [--frames 12]
规则 json 取 rules 字段（空文件 {"rules":{}} 即当前默认值）；起点 json 取 inputs 字段，缺省用 v2_inputs。
--over 名称='{"motion_legacy":1,...}' 可在某套配置的规则上再覆盖。致密带权重由 MATCH_FIT_BAND 决定。
"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
SCENES=['ironman','thanos','kobe','spiderman-original','reference-0926-1','reference-0926-4','reference-0926-5',
        'reference-0926-6','reference-0926-7']


def _job(args):
    name,configs,frames=args
    import moderngl
    import match_fit as F
    from dissolve_v2 import RULES,Site
    ctx=moderngl.create_standalone_context(require=430);t=F.Target(name,ctx,frames);out={}
    keys={f.name for f in dataclasses.fields(Site)};default_sites=t.renderer.sites;default_touch=t.renderer.touch_strength
    for label,(rules_d,sites_d,touch_d) in configs.items():
        rules=dataclasses.replace(RULES,**{k:v for k,v in rules_d.items() if k in dataclasses.asdict(RULES)})
        sites=[Site(**{k:v for k,v in s.items() if k in keys}) for s in sites_d[name]] if sites_d and name in sites_d else default_sites
        t.renderer.set_sites(sites);t.renderer.set_touch_strength(touch_d.get(name,default_touch) if touch_d else default_touch)
        v,det=t.loss(rules,True);out[label]=dict(total=v,**det)
    t.renderer.close();ctx.release();return name,out


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--configs',nargs='+',required=True);ap.add_argument('--scenes',nargs='+',default=SCENES)
    ap.add_argument('--frames',type=int,default=12);ap.add_argument('--workers',type=int,default=9);ap.add_argument('--over',nargs='*',default=[])
    ap.add_argument('--save')
    a=ap.parse_args();over={}
    for o in a.over:
        k,v=o.split('=',1);over[k]=json.loads(v)
    configs={}
    for c in a.configs:
        label,spec=c.split('=',1);parts=spec.split('::')
        d=json.loads(Path(parts[0]).read_text('utf-8'));d=d.get('best',d);rules=dict(d.get('rules',{}));rules.update(over.get(label,{}))
        sites=None;touch=None
        if len(parts)>1:
            e=json.loads(Path(parts[1]).read_text('utf-8'));sites=e.get('inputs');touch=e.get('touch')
        configs[label]=(rules,sites,touch)
    import multiprocessing as mp
    res={}
    with mp.get_context('spawn').Pool(min(a.workers,len(a.scenes))) as pool:
        for n,o in pool.imap_unordered(_job,[(n,configs,a.frames) for n in a.scenes]):res[n]=o
    labels=list(configs)
    print(f"{'素材':22s}"+''.join(f'{l[:14]:>16s}' for l in labels))
    for n in a.scenes:
        print(f'{n:22s}'+''.join(f"{res[n][l]['total']:8.1f} /{res[n][l]['dense_band']:5.2f} " for l in labels))
    print(f"{'平均':22s}"+''.join(f"{np.mean([res[n][l]['total'] for n in a.scenes]):8.1f} /{np.mean([res[n][l]['dense_band'] for n in a.scenes]):5.2f} " for l in labels))
    if a.save:Path(a.save).write_text(json.dumps(res,ensure_ascii=False,indent=1),'utf-8')


if __name__=='__main__':main()
