"""区分持续半透明微片与具有固定随机显隐阈值的细粒，不修改默认绘制。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_sampling import SamplingRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

class VisibilityRenderer(SamplingRenderer):
    fragment_source=SamplingRenderer.fragment_source.replace('void main(){','uniform float visibility_strength;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','''
    // 阈值固定于材料，不逐帧重新采样；部分覆盖仍通过宽显隐过渡进入和退出。
    float visibility_random=fract(random_out.x*29.817+random_out.y*43.131+random_out.w*17.413);
    float probability=clamp(alpha/max(shape,.0001),0.,1.);
    float discrete=1.-smoothstep(max(0.,probability-.05),min(1.,probability+.05),visibility_random);
    alpha=mix(alpha,shape*discrete,visibility_strength*smoothstep(.025,.08,age));
    frag=vec4(color*alpha,alpha);''',1)
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw);self.program['visibility_strength']=0.

def main():
    out=HERE/'analysis/match-cache/particle-visibility';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    variants=[('当前',1,0.,0.,0.),('细粒',3,1.,.008,0.),('细粒显隐',3,1.,.008,1.),('中等细粒显隐',2,1.,.006,1.)]
    for name in ['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']:
        c=Comparison(name);r=VisibilityRenderer(name,ctx=ctx,quality=2,sites=[Site(*q) for q in OBSERVED_SITES[name]],**kw);rows=[]
        sheet=Image.new('RGB',(320*4,(569+24)*5),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,phase in enumerate([.24,.39,.54,.71]):
            i,ref=c.ref.nearest(phase);p=float(c.ref.progress[i]);target=measure(ref,c.scene.roi);row=dict(phase=p)
            frames=[('华为原片',ref)]
            for label,n,ind,spread,strength in variants:
                r.program['sampling_side']=n;r.program['sampling_independence']=ind;r.program['sampling_spread']=spread;r.program['visibility_strength']=strength
                f=r.render(p);frames.append((label,f));row[label]={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(label,f) in enumerate(frames):
                im=Image.fromarray(f);im.thumbnail((320,569),Image.Resampling.LANCZOS);sheet.paste(im,(j*320,k*593+24));draw.text((j*320,k*593),f'{label} {p:.3f}',font=font,fill='white')
                if j==1:Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        report[name]=rows;sheet.save(out/f'{name}.jpg',quality=95);r.close();print(name,{v[0]:{m:round(float(np.mean([q[v[0]][m] for q in rows])),3) for m in ['rgb_4','grain']} for v in variants},flush=True)
        (out/'review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
