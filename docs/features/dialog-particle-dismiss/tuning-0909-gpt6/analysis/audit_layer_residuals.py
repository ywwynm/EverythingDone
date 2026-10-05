"""按实际渲染层检查原纹理残留；诊断画面不替代华为的材料真值。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison
from release_integrity import Probe


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman'])
    a=ap.parse_args();out=HERE/'analysis/match-cache/layer-residuals';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    for name in a.scenes:
        c=Comparison(name);probe=Probe(name)
        r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        tw=285;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(5*tw,4*(th+26)),(14,20,30));draw=ImageDraw.Draw(sheet)
        rows=[]
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            i,reference=c.ref.nearest(p);p=float(c.ref.progress[i])
            complete=r.render(p);surface=r.render(p,diagnostic=3);cloud=r.render(p,diagnostic=2)
            frames=[('华为原片',reference),('当前完整画面',complete),('仅原位表面与背景',surface),('仅运动片与背景',cloud)]
            values={}
            for k,(label,frame) in enumerate(frames):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26))
                draw.text((j*tw+2,k*(th+26)+2),f'{label} {p:.3f}',font=font,fill='white')
                if p<.45:
                    Image.fromarray(frame).save(out/f'{name}-{j}-{k}.png')
                integrity=probe.integrity(frame)
                # 只报告和原始纹理相近的块占比，粒子遮挡也会影响该值，不能称为真实释放率。
                values[label]=float(integrity[probe.valid].mean()) if probe.valid.any() else None
            rows.append(dict(phase=p,source_similarity=values))
        r.close();sheet.save(out/f'{name}.jpg',quality=94);report[name]=rows
        print(name,rows,flush=True)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
