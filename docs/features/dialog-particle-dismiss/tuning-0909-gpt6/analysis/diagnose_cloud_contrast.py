"""分离已有覆盖、源颜色与细分扩散对灰色团块的作用；仅用于诊断。"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2, moderngl
from PIL import Image, ImageDraw, ImageFont
HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from fit_particle_optics import measure


class ContrastRenderer(ScatterRenderer):
    fragment_source = ScatterRenderer.fragment_source.replace('void main(){', 'uniform float contrast_white;\nvoid main(){', 1)
    fragment_source = fragment_source.replace('frag=vec4(color*alpha,alpha);', 'color=mix(color,vec3(.85),contrast_white);\nfrag=vec4(color*alpha,alpha);', 1)
    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.program['contrast_white'] = 0.


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman-up-reference', 'reference-0926-2', 'ironman'])
    a = ap.parse_args()
    out = HERE / 'analysis/match-cache/cloud-contrast'
    out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    _, kw = configuration(HERE)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
    variants = [('当前', .010, 0.), ('较小细分扩散', .003, 0.), ('无细分扩散', 0., 0.),
                ('中性白色覆盖诊断', .010, 1.), ('白色且无细分扩散', 0., 1.)]
    report = {}
    for name in a.scenes:
        c = Comparison(name)
        r = ContrastRenderer(name, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[name]], **kw)
        x,y,x1,y1 = c.scene.rect
        pad = round(min(x1-x, y1-y)*.18)
        box = (max(0,x-pad),max(0,y-pad),min(r.w,x1+pad),min(r.h,y1+pad))
        tw = 340; th = round((box[3]-box[1])*tw/(box[2]-box[0]))
        sheet = Image.new('RGB',(tw*3,(th+25)*(len(variants)+1)),(14,20,30))
        d = ImageDraw.Draw(sheet); rows = []
        for j,t in enumerate([.22,.32,.42]):
            i,ref = c.ref.nearest(t); p = float(c.ref.progress[i]); target = measure(ref,c.scene.roi)
            frames = [('华为原片',ref)]; row = dict(phase=p)
            for label,spread,white in variants:
                r.program['fission_spread'] = spread
                r.program['contrast_white'] = white
                f = r.render(p); frames.append((label,f))
                row[label] = {k:float(abs(u-v).mean()) if u.size else None for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
            for k,(label,f) in enumerate(frames):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25))
                d.text((j*tw+2,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
                if j==1: Image.fromarray(f).save(out/f'{name}-{k}.png')
            rows.append(row)
        r.close(); sheet.save(out/f'{name}.jpg',quality=95); report[name]=rows
        print(name,{label:round(float(np.mean([q[label]['rgb_4'] for q in rows])),4) for label,_,_ in variants} if c.scene.roi.any() else '背景不完整',flush=True)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__': main()
