"""共同验收：原始参考、冻结实现、候选及多尺度像素差。"""
from pathlib import Path
import argparse, importlib.util, json, sys, time
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import Reference,load_meta
from reference_metrics_0926 import difference
OUT=HERE/'analysis/new-references-0926'
OLD=['ironman','thanos','kobe','ironman-up-reference','spiderman-original']
NEW=[f'reference-0926-{i}' for i in range(1,8)]
PHASES=[.12,.25,.40,.56,.72,.88]


def crop(a,m,pad=.14):
    x,y,x1,y1=m['rect'];s=min(x1-x,y1-y);b=round(s*pad)
    return a[max(0,y-b):min(len(a),y1+b),max(0,x-b):min(a.shape[1],x1+b)]


def main():
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=NEW+OLD)
    p.add_argument('--tag',default='baseline');p.add_argument('--candidate',action='store_true')
    p.add_argument('--rules-json');a=p.parse_args()
    if a.candidate:
        from front_transport import FrontRenderer,FrontRules,ReleaseSite
        from reference_observations_0926 import OBSERVED_SITES
        renderer=FrontRenderer
    else:renderer=Renderer
    ctx=moderngl.create_standalone_context(require=430)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    stats=[];dest=OUT/a.tag;dest.mkdir(exist_ok=True)
    for name in a.scenes:
        start=time.perf_counter();m=load_meta(name);ref=Reference(m)
        inputs=dict(sites=[ReleaseSite(*q) for q in OBSERVED_SITES[name]]) if a.candidate and name in OBSERVED_SITES else {}
        if a.rules_json:inputs['rules']=FrontRules(**json.loads(Path(a.rules_json).read_text('utf-8')))
        r=renderer(name,ctx=ctx,quality=1.5,**inputs)
        tile=240;h=278;sheet=Image.new('RGB',(tile*6,h*3),'#0e141e');d=ImageDraw.Draw(sheet)
        row=[]
        for j,t in enumerate(PHASES):
            model=r.render(t);truth=ref.at(t);rr=crop(truth,m);mm=crop(model,m)
            diff=np.abs(rr.astype('float32')-mm.astype('float32'))
            soft=np.abs(cv2.GaussianBlur(rr.astype('float32'),(0,0),6)-cv2.GaussianBlur(mm.astype('float32'),(0,0),6))
            heat=np.uint8(np.clip(diff.mean(2)*3,0,255));heat=cv2.applyColorMap(heat,cv2.COLORMAP_INFERNO)[:,:,::-1]
            for k,(im,label) in enumerate([(rr,'华为'),(mm,a.tag),(heat,'像素差 ×3')]):
                panel=Image.fromarray(im);panel.thumbnail((tile,h-28));x=j*tile+(tile-panel.width)//2;y=k*h
                sheet.paste(panel,(x,y+28));d.text((j*tile+5,y+3),f'{label}  {t:.2f}',font=font,fill='white')
            row.append(difference(truth,model,m,t))
            Image.fromarray(model).save(dest/f'{name}-{round(t*100):02d}.png')
        r.close();sheet.save(dest/f'{name}.jpg',quality=95)
        stats.append(dict(scene=name,frames=row,background_truth=m['background_truth']))
        print(name,a.tag,f'{time.perf_counter()-start:.1f}s',flush=True)
    (dest/'metrics.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
