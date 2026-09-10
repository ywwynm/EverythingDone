"""r32 候选：减少逐粒速度分散，比较连续材料的寿命关联。只写分析产物。"""
import sys,types,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np
import moderngl
from PIL import Image,ImageDraw,ImageFont
from review import reference,crop
source=(ROOT/'renderer.py').read_text(encoding='utf-8')
variants={
 'r31':{},
 'coherent':{'.48*exp(-age/.11)+.10*smoothstep':'.16*exp(-age/.11)+.035*smoothstep','(.9+.2*m.random.x)':'(.97+.06*m.random.x)','(.55+.75*m.random.w)':'(.85+.15*m.random.w)'},
 'medium':{'.48*exp(-age/.11)+.10*smoothstep':'.26*exp(-age/.11)+.050*smoothstep','(.9+.2*m.random.x)':'(.96+.08*m.random.x)','(.55+.75*m.random.w)':'(.80+.25*m.random.w)'},
 'correlated':{'.48*exp(-age/.11)+.10*smoothstep':'.20*exp(-age/.11)+.045*smoothstep','(.9+.2*m.random.x)':'(.96+.08*m.random.x)','(.55+.75*m.random.w)':'(.80+.25*m.random.w)',
   'life=np.minimum(life,.865': 'cohort=.30+.08*np.sin(base[:,0]*.015+base[:,1]*.011)+.10*T\n        life=.45*life+.55*cohort\n        life=np.minimum(life,.865'},
 'coherent_curl':{'.48*exp(-age/.11)+.10*smoothstep':'.16*exp(-age/.11)+.035*smoothstep','(.9+.2*m.random.x)':'(.97+.06*m.random.x)','(.55+.75*m.random.w)':'(.85+.15*m.random.w)','span*.125*curl_gain':'span*.205*curl_gain'},
}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
times=[.24,.40,.56,.72]
for name in ['thanos','kobe','ironman','attachment']:
    ctx=moderngl.create_standalone_context(require=430)
    renderers=[]
    for variant,changes in variants.items():
        code=source
        for old,new in changes.items():
            assert old in code,old;code=code.replace(old,new)
        mod=types.ModuleType(variant);mod.__file__=str(ROOT/'renderer.py');exec(compile(code,mod.__file__,'exec'),mod.__dict__)
        renderers.append((variant,mod.Renderer(name,quality=2,ctx=ctx)))
    columns=len(variants)+(name!='attachment');W=340;H=420
    canvas=Image.new('RGB',(columns*W,len(times)*H),(18,23,31));draw=ImageDraw.Draw(canvas)
    for row,t in enumerate(times):
        frames=[]
        if name!='attachment':frames.append(('华为',reference(renderers[0][1].meta,t)))
        frames.extend((v,r.render(t)) for v,r in renderers)
        for col,(v,frame) in enumerate(frames):
            im=Image.fromarray(crop(frame,renderers[0][1].meta));im.thumbnail((W-6,H-34))
            canvas.paste(im,(col*W+(W-im.width)//2,row*H+32));draw.text((col*W+7,row*H+5),f'{v} {t:.2f}',font=font,fill='white')
    canvas.save(ROOT/'analysis'/f'r32-coherence-{name}.jpg',quality=96)
    for _,r in renderers:r.close()
    ctx.release()
    print(name,flush=True)
