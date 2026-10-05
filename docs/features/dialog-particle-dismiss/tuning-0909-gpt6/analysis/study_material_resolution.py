"""完整材料网格加密消融；同时记录实际粒子数，不能只增加绘制副片。"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_coherence import CoherenceRenderer
from unified_model import RULES
from check_cloud_evolution import configuration
from fit_particle_optics import measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class ResolutionRenderer(CoherenceRenderer):
    def __init__(self,*args,resolution=1.,**kwargs):
        self.resolution=resolution
        super().__init__(*args,**kwargs)

    def material_builder(self,*args,**kwargs):
        old_cell=RULES['cell'];old_max=RULES['max_cells']
        try:
            RULES['cell']=old_cell/self.resolution
            RULES['max_cells']=int(old_max*self.resolution**2)
            return super().material_builder(*args,**kwargs)
        finally:
            RULES['cell']=old_cell;RULES['max_cells']=old_max


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);ap.add_argument('--motion',action='store_true');ap.add_argument('--motion-values',nargs=2,type=float,default=[.65,.85]);ap.add_argument('--label');a=ap.parse_args()
    out=HERE/'analysis/match-cache'/(a.label or ('material-carrying' if a.motion else 'material-resolution'));out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False)
    variants={'before':1.,'dense1.5':1.5,'dense2':2.}
    if a.motion:variants={'before':kw['rules'].motion,**{f'motion-{v:g}':v for v in a.motion_values}}
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-30),max(0,y-30),min(m['frame'][0],x1+30),min(m['frame'][1],y1+30))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+25)*4),(14,20,30));d=ImageDraw.Draw(sheet)
        refs=[(float(c.ref.progress[i]),f) for i,f in [c.ref.nearest(t) for t in [.23,.38,.53,.70]]];rows=[dict(phase=p) for p,_ in refs];counts={}
        def paste(f,j,k,label,p):
            sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25));d.text((j*tw+4,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
        for j,(p,ref) in enumerate(refs):paste(ref,j,0,'华为原片',p)
        for k,(key,scale) in enumerate(variants.items(),1):
            options=dict(kw)
            if a.motion:options['rules']=dataclasses.replace(options['rules'],motion=scale)
            r=ResolutionRenderer(name,ctx=ctx,quality=2,resolution=1. if a.motion else scale,sites=[Site(*s) for s in OBSERVED_SITES[name]],**options);counts[key]=r.n
            for j,(p,ref) in enumerate(refs):
                f=r.render(p);paste(f,j,k,key,p);target=measure(ref,c.scene.roi)
                rows[j][key]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            r.close()
        report[name]=dict(rows=rows,counts=counts);sheet.save(out/f'{name}.jpg',quality=94)
        print(name,counts,{k:{metric:round(np.mean([q[k][metric] for q in rows]),4) for metric in ['rgb_4','grain']} for k in variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
