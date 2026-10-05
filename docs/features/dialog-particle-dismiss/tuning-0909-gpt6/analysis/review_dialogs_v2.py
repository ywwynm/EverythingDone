"""自有白底弹窗（无华为同内容参考）：第二版模型与此前已认可模型并排，多方向、多种子。只供审阅。"""
from pathlib import Path
import argparse,json,sys
import numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from dissolve_v2 import V2Renderer
from renderer import Renderer
from review_v2 import load_rules,crop_box,OUT


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules');ap.add_argument('--label',required=True)
    ap.add_argument('--scenes',nargs='+',default=['attachment','color','language'])
    ap.add_argument('--cases',default='135:7,90:11,315:3');ap.add_argument('--phases',default='.1,.25,.4,.55,.7,.85');a=ap.parse_args()
    import moderngl
    OUT.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430);rules=load_rules(a.rules)
    phases=[float(v) for v in a.phases.split(',')];F=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14)
    cases=[tuple(int(v) for v in c.split(':')) for c in a.cases.split(',')]
    for name in a.scenes:
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));box=crop_box(meta,.3)
        tw=170;th=round((box[3]-box[1])*tw/(box[2]-box[0]));rows=[]
        for direction,seed in cases:
            for label,r in [('此前模型',Renderer(name,ctx=ctx,direction=direction,seed=seed)),('v2',V2Renderer(name,ctx=ctx,direction=direction,seed=seed,rules=rules))]:
                row=Image.new('RGB',(len(phases)*(tw+4),th+18),(14,20,30));d=ImageDraw.Draw(row)
                for k,p in enumerate(phases):
                    row.paste(Image.fromarray(np.ascontiguousarray(r.render(p)[box[1]:box[3],box[0]:box[2]])).resize((tw,th),Image.Resampling.LANCZOS),(k*(tw+4),18))
                    d.text((k*(tw+4)+2,1),f'{label} {direction}° 种子{seed} {p:.2f}',font=F,fill=(255,230,120) if label=='v2' else (170,190,210))
                rows.append(row);r.close()
        W=max(x.width for x in rows);sheet=Image.new('RGB',(W,sum(x.height+4 for x in rows)),(14,20,30));y=0
        for x in rows:sheet.paste(x,(0,y));y+=x.height+4
        sheet.save(OUT/f'{a.label}-{name}-dialog.jpg',quality=88);print(name,sheet.size,flush=True)


if __name__=='__main__':main()
