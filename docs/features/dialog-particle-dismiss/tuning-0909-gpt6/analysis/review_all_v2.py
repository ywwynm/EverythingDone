"""全部参考的总览：每段一行，原片与第二版模型在相同进度上成对排列。只供审阅。"""
from pathlib import Path
import argparse,sys
import numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from dissolve_v2 import V2Renderer,reference_inputs
from match_refs import RefFrames
from review_v2 import load_rules,crop_box,OUT

SCENES=['ironman','reference-0926-5','spiderman-original','reference-0926-6','reference-0926-7','reference-0926-1',
        'thanos','reference-0926-4','kobe','ironman-up-reference','reference-0926-2','reference-0926-3']


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules');ap.add_argument('--label',required=True)
    ap.add_argument('--phases',default='.15,.3,.45,.6,.75');ap.add_argument('--scenes',nargs='+',default=SCENES);a=ap.parse_args()
    import moderngl
    OUT.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430);rules=load_rules(a.rules)
    phases=[float(v) for v in a.phases.split(',')];F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14)
    tw=150;rows=[]
    for name in a.scenes:
        R=RefFrames(name);r=V2Renderer(name,rules=rules,ctx=ctx,quality=2,**reference_inputs(name))
        box=crop_box(R.meta,.35);th=round((box[3]-box[1])*tw/(box[2]-box[0]));valid=R.meta['reference'].get('valid_progress',1.)
        row=Image.new('RGB',(len(phases)*(2*tw+10)+4,th+18),(14,20,30));d=ImageDraw.Draw(row)
        for k,p in enumerate(phases):
            i,f=R.nearest(min(p,valid));pp=float(R.progress[i]);g=r.render(pp)
            for c,arr in enumerate([f,g]):
                row.paste(Image.fromarray(np.ascontiguousarray(arr[box[1]:box[3],box[0]:box[2]])).resize((tw,th),Image.Resampling.LANCZOS),(k*(2*tw+10)+c*tw,18))
            d.text((k*(2*tw+10)+2,1),f'{name[-10:]} {pp:.2f} 华为|v2',font=F,fill=(255,230,120))
        rows.append(row);r.close();print(name,flush=True)
    W=max(x.width for x in rows);sheet=Image.new('RGB',(W,sum(x.height+4 for x in rows)),(14,20,30));y=0
    for x in rows:sheet.paste(x,(0,y));y+=x.height+4
    sheet.save(OUT/f'{a.label}-all.jpg',quality=88);print(sheet.size)


if __name__=='__main__':main()
