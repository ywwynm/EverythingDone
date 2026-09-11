"""复用全部既有输入，检查群体形变；不渲染或导出标注视频。"""
import sys,json,argparse,importlib.util
from pathlib import Path
import moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from measure_flow_shaping import measure,OUT,CASES
import renderer,unified_model

def frozen_renderer():
    archive=HERE/'archive/before-flow-shaping'
    def read_module(name,path):
        spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module);return module
    model=read_module('baseline_model',archive/'unified_model.py');model.SHARED=archive/'particle-dismiss'
    sys.modules['unified_model']=model
    try:old=read_module('baseline_renderer',archive/'renderer.py')
    finally:sys.modules['unified_model']=unified_model
    old.HERE=HERE;return old.Renderer

def main():
    p=argparse.ArgumentParser();p.add_argument('variant',choices=['before','after']);p.add_argument('--focused',action='store_true');a=p.parse_args()
    constructor=frozen_renderer() if a.variant=='before' else renderer.Renderer
    cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
    metas=json.loads((HERE/'assets/scenes.json').read_text('utf-8'))
    cases += [dict(id='G-'+m['name'],scene=m['name'],seed=m['seed'],angle=m['direction']) for m in metas if not m.get('holdout')]
    if a.focused:cases=[dict(id=f'S-{i}',scene=n,angle=d,seed=s) for i,(n,d,s) in enumerate(CASES)]
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    for i,c in enumerate(cases):
        r=constructor(c['scene'],direction=c['angle'],seed=c['seed'],ctx=ctx,quality=1)
        row=dict(id=c['id'],scene=c['scene'],seed=c['seed'],angle=c['angle'],**measure(r));rows.append(row);r.close()
        if i%16==0:print(a.variant,i+1,'/',len(cases),flush=True)
    ctx.release();(OUT/f'{"shape" if a.focused else "audit"}-{a.variant}.json').write_text(json.dumps(rows,ensure_ascii=False),'utf-8')
    print(a.variant,len(rows),'组状态检查完成',flush=True)

if __name__=='__main__':main()
