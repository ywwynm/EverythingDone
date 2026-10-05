"""把原控件边界放到原片和候选的同一坐标，用多个参考核对起点外扩。"""
from pathlib import Path
import json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from shared_transport import SharedTransportRenderer,load_transport
from conditional_material import ConditionalRules
from learned_release import load_weights
from v2_inputs import OBSERVED_SITES
from dissolve_v2 import Site
from match_compare import Comparison


def main():
    names=['ironman','spiderman-original','reference-0926-6','reference-0926-7']
    out=HERE/'analysis/match-cache/origin-volume';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430)
    weights=load_weights(HERE/'conditional-release-weights.json');transport=load_transport(HERE/'conditional-transport-weights.json')
    rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    sheet=Image.new('RGB',(880,490*len(names)+55),(14,20,30));d=ImageDraw.Draw(sheet)
    d.text((12,8),'青色虚线：原控件边界；粒子位置没有额外标线。',font=font,fill='white')
    report={}
    for i,name in enumerate(names):
        c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];span=min(x1-x,y1-y);pad=round(span*.20)
        box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad))
        index,ref=c.ref.nearest(.25);p=float(c.ref.progress[index])
        r=SharedTransportRenderer(name,weights=weights,transport_weights=transport,rules=rules,sites=[Site(*s) for s in OBSERVED_SITES[name]],ctx=ctx,quality=2)
        current=r.render(p);r.close();report[name]=p
        for col,(f,label) in enumerate([(ref,'华为原片'),(current,'共同模型 · 鼓起仍不足')]):
            image=Image.fromarray(np.asarray(f)).crop(box);image.thumbnail((430,440),Image.Resampling.LANCZOS)
            q=ImageDraw.Draw(image);sx=image.width/(box[2]-box[0]);sy=image.height/(box[3]-box[1]);left=(x-box[0])*sx;top=(y-box[1])*sy;right=(x1-box[0])*sx;bottom=(y1-box[1])*sy
            color=(70,225,220)
            for px in np.arange(left,right,12):
                for py in [top,bottom]:q.line((px,py,min(px+5,right),py),fill=color,width=1)
            for py in np.arange(top,bottom,12):
                for px in [left,right]:q.line((px,py,px,min(py+5,bottom)),fill=color,width=1)
            ox=col*440+(440-image.width)//2;oy=55+i*490
            d.text((col*440+8,oy),f'{label} · {p:.3f}',font=font,fill='white');sheet.paste(image,(ox,oy+31))
            d.text((col*440+8,oy+470),m['title'],font=font,fill=(170,190,208))
    sheet.save(out/'origin-support-review.jpg',quality=96)
    (out/'origin-support-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');ctx.release()


if __name__=='__main__':main()
