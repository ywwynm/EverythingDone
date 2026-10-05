"""从独立参考帧复核初始事件；只输出小型联系图和输入记录。"""
from pathlib import Path
import argparse,json,sys
import numpy as np
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'analysis')]
from desktop_inputs import REFS,META
from reference_initial_conditions import SITES
from match_refs import RefFrames


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    colors=['#ffcc55','#5ff0ee','#ff75c1','#a6ee6d']
    report={}
    for name in REFS:
        m=META[name];ref=RefFrames(name);sites=SITES[name]
        x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
        box=(max(0,x-18),max(0,y-18),min(m['frame'][0],x1+18),min(m['frame'][1],y1+18))
        sheet=Image.new('RGB',(960,1105),'#10151d');draw=ImageDraw.Draw(sheet)
        draw.text((6,3),f'{name} · 方向 {m["direction"]}° · {len(sites)} 个观测起点',font=font,fill='white')
        line=' / '.join(f'{j+1}:({a:.2f},{b:.2f})@{t:.3f}' for j,(a,b,t) in enumerate(sites))
        draw.text((6,25),line,font=font,fill='white')
        frames=[]
        for k,p in enumerate([0.,.025,.05,.075,.10,.15,.20,.26,.34]):
            i,frame=ref.nearest(p);im=Image.fromarray(frame).crop(box)
            im.thumbnail((320,320));ox=(k%3)*320+(320-im.width)//2;oy=57+(k//3)*347
            sheet.paste(im,(ox,oy+23));draw.text((ox,oy),f'独立帧 {i} · {ref.progress[i]:.3f}',font=font,fill='white')
            if k==0:
                scale=im.width/(box[2]-box[0])
                for j,(a,b,t) in enumerate(sites):
                    cx=ox+(x+a*w-box[0])*scale;cy=oy+23+(y+b*h-box[1])*scale
                    draw.ellipse((cx-6,cy-6,cx+6,cy+6),outline=colors[j],width=2)
                    draw.text((max(ox,cx-5),max(oy+23,cy-23)),str(j+1),font=font,fill=colors[j])
            frames.append(dict(index=i,progress=float(ref.progress[i])))
        sheet.save(args.out/f'{name}-starts.jpg',quality=94)
        report[name]=dict(direction=m['direction'],sites=sites,frames=frames,
                          source='早期人工观测；以此联系图重新核对，坐标约 ±0.03，不代表原机真实触点')
    (args.out/'inputs-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print('已生成十二段早期原片联系图，未读取后段运动来拟合起点。')


if __name__=='__main__':main()
