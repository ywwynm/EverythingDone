"""复核收束与增密的折中，防止已认可的钢铁侠被全局加重。"""
import sys,json,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from review import reference,crop
from compare_metrics import feature
from fit_flow import texture
source=(ROOT/'renderer.py').read_text(encoding='utf-8')
source=source.replace('.32*exp(-age/.11)+.065*smoothstep','.44*exp(-age/.11)+.085*smoothstep').replace('(.24-.16*body_weight)','(.08-.05*body_weight)')
mod=types.ModuleType('balanced');mod.__file__=str(ROOT/'renderer.py');exec(compile(source,mod.__file__,'exec'),mod.__dict__)
regions={'thanos':[[.72,.67,.32,.39,-.65,.30,6.,.18,.44]],'kobe':[[.81,.57,.34,.54,-.15,.45,4.,.38,.58]]}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
for name in ['ironman','thanos','kobe']:
    r=mod.Renderer(name);profile=json.loads((r.directory/'flow-profile.json').read_text(encoding='utf-8'))
    if name in regions:profile['focusing_regions']=regions[name];r.flow_tex.write(texture(profile).tobytes())
    old=np.load(ROOT/'archive/r31'/f'{name}.npy',mmap_mode='r');times=[.12,.24,.40,.56,.72,.88]
    W=350;H=420;out=Image.new('RGB',(W*3,H*6),(18,23,31));draw=ImageDraw.Draw(out);metrics=[]
    for row,t0 in enumerate(times):
        idx=round(t0*120);t=idx/120;truth=reference(r.meta,t);a=r.render(t);b=old[idx]
        metrics.append((float(abs(feature(b,r.meta)-feature(truth,r.meta)).mean()),float(abs(feature(a,r.meta)-feature(truth,r.meta)).mean())))
        for col,(label,frame) in enumerate([('华为',truth),('r31',b),('balanced',a)]):
            im=Image.fromarray(crop(frame,r.meta));im.thumbnail((W-6,H-34));out.paste(im,(col*W+(W-im.width)//2,row*H+32));draw.text((col*W+7,row*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(ROOT/'analysis'/f'r32-balanced-{name}.jpg',quality=96)
    print(name,np.mean(metrics,axis=0).tolist(),flush=True);r.close()
