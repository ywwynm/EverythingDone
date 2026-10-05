"""给定一套 V2Rules，输出各参考的“原片 / 模型 / 占用差”联系表、同口径误差与结构统计。"""
from pathlib import Path
import argparse,dataclasses,json,math,sys
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from match_compare import Comparison,sheet,summarize,OUT
from match_observe import structure_stats
from dissolve_v2 import V2Renderer,RULES,reference_inputs


def load(path):
    if not path:return RULES
    d=json.loads(Path(path).read_text('utf-8'))
    return dataclasses.replace(RULES,**d.get('best',d).get('rules',d))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules');ap.add_argument('--label',required=True)
    ap.add_argument('--scenes',nargs='+',default=['ironman','reference-0926-5','spiderman-original','reference-0926-6','reference-0926-7','reference-0926-1','thanos','reference-0926-4','kobe','ironman-up-reference'])
    ap.add_argument('--phases');ap.add_argument('--structure',action='store_true');a=ap.parse_args()
    import moderngl
    rules=load(a.rules);ctx=moderngl.create_standalone_context(require=430);report={}
    phases=[float(v) for v in a.phases.split(',')] if a.phases else None
    for name in a.scenes:
        comp=Comparison(name);q=reference_inputs(name)
        r=V2Renderer(name,direction=q['direction'],seed=q['seed'],sites=q['sites'],rules=rules,ctx=ctx,quality=2)
        render=lambda p:r.render(float(p))
        rows=comp.compare(render);s=summarize(rows)
        sheet(comp,render,OUT/f'{a.label}-{name}.jpg',phases)
        line={k:round(v,4) for k,v in s.items() if isinstance(v,float)}
        if a.structure:
            st=[]
            for p in [.2,.35,.5,.65]:
                i,f=comp.ref.nearest(p)
                if p>comp.scene.meta['reference'].get('valid_progress',1):continue
                x=structure_stats(comp.scene,f);y=structure_stats(comp.scene,render(float(comp.ref.progress[i])))
                if x and y:st.append((p,x['band'],y['band'],x['roughness'],y['roughness']))
            line['structure']=' '.join(f'{p:.2f}:band {b1*1e3:.1f}/{b2*1e3:.1f} rough {r1:.1f}/{r2:.1f}' for p,b1,b2,r1,r2 in st)
        report[name]=line;print(name,line,flush=True);r.close()
    (OUT/f'{a.label}.json').write_text(json.dumps(dict(rules=dataclasses.asdict(rules),scenes=report),ensure_ascii=False,indent=1),'utf-8')


if __name__=='__main__':main()
