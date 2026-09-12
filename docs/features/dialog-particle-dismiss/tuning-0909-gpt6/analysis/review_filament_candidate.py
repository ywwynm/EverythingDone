"""按完整画面检查去除尘缕后的共同模型，包括其它材质和方向。"""
from pathlib import Path
import sys,json,copy,argparse
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from filament_lifetime import candidate
from filament_probe import OUT,sheet
from export_videos import Reference,load_meta

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',default='refined-config.json');p.add_argument('--tag',default='candidate');args=p.parse_args()
    ctx=moderngl.create_standalone_context(require=430)
    phases=[.33,.40,.48,.55,2/3,.78]
    config=json.loads((OUT/args.config).read_text('utf-8'))
    cases=[('ironman',122,None),('ironman',90,909610),('thanos',None,None),
           ('kobe',None,None),('attachment',45,909602),('color',135,909610),
           ('color',270,909604),('holdout-compact-dialog',180,909602)]
    original={}
    for name,angle,seed in cases:
        r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
        original[name,str(angle)]=[r.render(t) for t in phases];r.close()
    candidate(config,'combined')
    for name,angle,seed in cases:
        r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
        ims=[r.render(t) for t in phases];r.close()
        np.save(OUT/f'{args.tag}-{name}-{angle}.npy',ims)
        if load_meta(name).get('reference') and (name,angle) not in [('ironman',90)]:
            ref=Reference(load_meta(name));refs=[ref.at(t) for t in phases];title='华为参考'
        else:
            refs=[original[name,str(angle)][0]]*len(phases);title='输入与方向检查'
        sheet([(title,refs),('当前发布',original[name,str(angle)]),('去除尘缕候选',ims)],f'{args.tag}-{name}-{angle}',phases)
        print(name,angle,'完成',flush=True)
    ctx.release()

if __name__=='__main__':main()
