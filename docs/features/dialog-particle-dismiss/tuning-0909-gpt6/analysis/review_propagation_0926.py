"""起裂脉冲假设的整幅视觉检查；不会修改正式规则或场景种子。"""
from pathlib import Path
import json,sys
import numpy as np
import moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import front_transport
from study_front_propagation_0926 import field,Propagation
from front_transport import FrontRenderer,ReleaseSite
from export_videos import load_meta,Reference
from reference_observations_0926 import OBSERVED_SITES
from reference_metrics_0926 import difference
OUT=HERE/'analysis/new-references-0926'


def main():
    data=json.loads((OUT/'propagation-study.json').read_text('utf-8'))
    rules=Propagation(**data['rules'])
    front_transport.front_field=lambda nx,ny,w,h,d,s,sites,unused:field(nx,ny,w,h,d,s,sites,rules)
    ctx=moderngl.create_standalone_context(require=430)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    dest=OUT/'propagation-pulse';dest.mkdir(exist_ok=True);metrics=[]
    phases=[.12,.25,.4,.56,.72,.88]
    for name in OBSERVED_SITES:
        m=load_meta(name);reference=Reference(m);sites=[ReleaseSite(*q) for q in OBSERVED_SITES[name]]
        r=FrontRenderer(name,sites=sites,seed=data['seeds'][name],ctx=ctx,quality=1.5)
        sheet=Image.new('RGB',(1440,600),'#0e141e');draw=ImageDraw.Draw(sheet);records=[]
        x,y,x1,y1=m['rect'];margin=round(min(x1-x,y1-y)*.2)
        rect=max(0,x-margin),max(0,y-margin),min(m['frame'][0],x1+margin),min(m['frame'][1],y1+margin)
        for j,p in enumerate(phases):
            model=r.render(p);truth=reference.at(p)
            records.append(difference(truth,model,m,p))
            for k,(im,label) in enumerate([(truth,'华为'),(model,'共同起裂脉冲')]):
                panel=Image.fromarray(im).crop(rect);panel.thumbnail((240,272))
                sheet.paste(panel,(j*240+(240-panel.width)//2,k*300+28));draw.text((j*240+4,k*300+3),f'{label} {p:.2f}',font=font,fill='white')
        sheet.save(dest/f'{name}.jpg',quality=95);r.close();metrics.append(dict(scene=name,frames=records));print(name,flush=True)
    (dest/'metrics.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
