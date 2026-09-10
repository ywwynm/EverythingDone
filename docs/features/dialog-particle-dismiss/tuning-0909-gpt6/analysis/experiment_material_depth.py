"""比较真实覆盖面积、碎片寿命与白色微面明暗；不改变静止纹理。"""
import sys,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import moderngl
from PIL import Image,ImageDraw,ImageFont
from review import reference,crop
source=(ROOT/'renderer.py').read_text(encoding='utf-8')
variants={
 'r31':{},
 'area':{'float content=pigmentation[gl_InstanceID];':'scale*=1.+.38*smoothstep(.015,.075,age)*(1.-smoothstep(.22,.48,age));\n    float content=pigmentation[gl_InstanceID];'},
 'depth':{'color=mix(color,min(color,vec3(1.)),body);':'float facet=.80+.20*(.5+.5*sin(age*11.+dot(uv,vec2(7.,11.))));\n    color=mix(color,min(color,vec3(mix(1.,facet,shape_out))),body);'},
 'area_depth':{'float content=pigmentation[gl_InstanceID];':'scale*=1.+.32*smoothstep(.015,.075,age)*(1.-smoothstep(.22,.48,age));\n    float content=pigmentation[gl_InstanceID];','color=mix(color,min(color,vec3(1.)),body);':'float facet=.80+.20*(.5+.5*sin(age*11.+dot(uv,vec2(7.,11.))));\n    color=mix(color,min(color,vec3(mix(1.,facet,shape_out))),body);', '.48*exp(-age/.11)+.10*smoothstep':'.32*exp(-age/.11)+.065*smoothstep'},
 'area_life':{'float content=pigmentation[gl_InstanceID];':'scale*=1.+.38*smoothstep(.015,.075,age)*(1.-smoothstep(.22,.48,age));\n    float content=pigmentation[gl_InstanceID];','life_gain=.70':'life_gain=.88'}
}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19);times=[.24,.40,.56,.72]
for name in ['thanos','kobe','ironman','attachment','color']:
    ctx=moderngl.create_standalone_context(require=430)
    rr=[]
    for label,changes in variants.items():
        code=source
        for old,new in changes.items():assert old in code,old;code=code.replace(old,new)
        mod=types.ModuleType(label);mod.__file__=str(ROOT/'renderer.py');exec(compile(code,mod.__file__,'exec'),mod.__dict__);rr.append((label,mod.Renderer(name,ctx=ctx)))
    W=340;H=420;ref=name in ['thanos','kobe','ironman']
    out=Image.new('RGB',(W*(len(rr)+ref),H*len(times)),(18,23,31));draw=ImageDraw.Draw(out)
    for row,t in enumerate(times):
        frames=([('华为',reference(rr[0][1].meta,t))] if ref else [])+[(label,r.render(t)) for label,r in rr]
        for col,(label,frame) in enumerate(frames):
            im=Image.fromarray(crop(frame,rr[0][1].meta));im.thumbnail((W-6,H-34));out.paste(im,(col*W+(W-im.width)//2,row*H+32));draw.text((col*W+7,row*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(ROOT/'analysis'/f'r32-depth-{name}.jpg',quality=96)
    for _,r in rr:r.close()
    ctx.release()
    print(name,flush=True)
