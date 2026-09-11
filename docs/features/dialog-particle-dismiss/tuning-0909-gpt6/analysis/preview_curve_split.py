"""在正式编码前检查三素材的同屏近远形态、位移和速度。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from export_videos import load_meta,label,BG
from touch_geometry import distance_cases,distance_view_bounds
OUT=HERE/'analysis/curve-split';OUT.mkdir(exist_ok=True)
ctx=moderngl.create_standalone_context(require=430);rows=[]
for name in ['ironman','attachment','color']:
    meta=load_meta(name)
    for direction in [135,90]:
        bounds=distance_view_bounds(meta,direction);cases=distance_cases(meta,direction)
        w=360;h=round((bounds[3]-bounds[1])*w/meta['frame'][0]);times=[.4,.55,.7]
        sheet=Image.new('RGB',(3*w,(h+40)*3),BG);d=ImageDraw.Draw(sheet)
        for col,case in enumerate(cases):
            r=Renderer(name,ctx=ctx,direction=case['angle'],touch_gap=case['gap'],view_bounds=bounds)
            for row,t in enumerate(times):
                arr=r.render(t);sheet.paste(Image.fromarray(arr).resize((w,h)),(col*w,row*(h+40)+40))
                label(d,(col*w+8,row*(h+40)+8),f'{case["label"]} · 进度 {t:.2f}',22)
                state=np.frombuffer(r.state.read(),dtype='<f4').reshape(-1,8)
                live=(r.base[:,2]+.08<t)&(r.base[:,2]+r.base[:,6]-.04>t)
                speed=np.linalg.norm(state[live,4:6],axis=1)
                travel=np.linalg.norm(state[live,:2]-r.base[live,:2],axis=1)
                rows.append(dict(scene=name,direction=direction,label=case['label'],time=t,
                    strength=r.touch_strength,speed_p50=float(np.median(speed)),travel_p50=float(np.median(travel))))
            r.close()
        sheet.save(OUT/f'distance-{name}-{direction}.jpg',quality=94)
        print(name,direction,flush=True)
ctx.release();(OUT/'distance-preview.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),'utf-8')
