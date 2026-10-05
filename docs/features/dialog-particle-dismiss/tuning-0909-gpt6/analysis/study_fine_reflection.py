"""细材料采样与微片反光联合消融。只写研究产物，不修改默认配置。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_population import PopulationRenderer
from check_cloud_evolution import configuration
from fit_particle_optics import measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from unified_model import RULES


class FineRenderer(PopulationRenderer):
    fragment_source=PopulationRenderer.fragment_source.replace('void main(){','uniform float fine_reflection;\nvoid main(){',1)
    fragment_source=fragment_source.replace('color=max(color+vec3(population_reflection),vec3(0.));', '''
    float facet_response=pow(.5+.5*sin(age*11.+random_out.w*6.28318),4.)/.2734375;
    color=max(color+vec3(population_reflection+fine_reflection*facet_response*smoothstep(.008,.06,age)),vec3(0.));''',1)

    def __init__(self,*args,resolution=1.,**kw):
        self.resolution=resolution;super().__init__(*args,**kw);self.program['fine_reflection']=0.

    def material_builder(self,*args,**kw):
        old_cell,old_max=RULES['cell'],RULES['max_cells']
        try:
            RULES['cell']=old_cell/self.resolution;RULES['max_cells']=int(old_max*self.resolution**2)
            return super().material_builder(*args,**kw)
        finally:RULES['cell'],RULES['max_cells']=old_cell,old_max


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/fine-reflection';out.mkdir(parents=True,exist_ok=True)
    variants={'当前默认':(1.,0.,0.),'细采样':(1.8,0.,0.),'细采样中性反射':(1.8,.055,0.),'细采样微片反光':(1.8,0.,.055)}
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-35),max(0,y-35),min(m['frame'][0],x1+35),min(m['frame'][1],y1+35))
        tw=310;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*4,(th+24)*(len(variants)+1)),(14,20,30));d=ImageDraw.Draw(sheet)
        refs=[(float(c.ref.progress[i]),f) for i,f in [c.ref.nearest(t) for t in [.24,.39,.55,.71]]];rows=[dict(phase=p) for p,_ in refs]
        def paste(frame,col,row,title,p):
            sheet.paste(Image.fromarray(frame).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,row*(th+24)+24));d.text((col*tw+3,row*(th+24)),f'{title} {p:.3f}',font=font,fill='white')
        for col,(p,f) in enumerate(refs):paste(f,col,0,'华为原片',p)
        for row,(label,(resolution,neutral,facet)) in enumerate(variants.items(),1):
            r=FineRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],resolution=resolution,**kw)
            r.program['population_delta']=(0.,0.,neutral);r.program['fine_reflection']=facet
            for col,(p,ref) in enumerate(refs):
                f=r.render(p);paste(f,col,row,label,p);target=measure(ref,c.scene.roi)
                rows[col][label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            r.close()
        sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        print(name,{k:{key:round(np.mean([r[k][key] for r in rows]),4) for key in ['rgb_4','grain']} for k in variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
