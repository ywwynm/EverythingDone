"""评估源色材料的保留与细化，固定 r29 运动；仅输出试验图。"""
import argparse,json,types
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from renderer import HERE
from review import reference,crop
from compare_metrics import feature

base=types.ModuleType('pigment_r29');base.__file__=str(HERE/'renderer.py')
exec(compile((HERE/'archive/r29/renderer.py').read_text(encoding='utf-8'),'archive/r29/renderer.py','exec'),base.__dict__)
VERT=base.VERTEX
FRAG=base.FRAGMENT
VARIANTS={'r29':(0.,0.),'pigment_life':(.65,0.),'pigment_area':(0.,.28),'pigment_both':(.45,.20),'pigment_strong':(.90,.28)}
VARIANTS.update({'chroma_light':(0.,0.,'preserve'),'chroma_area':(0.,.24,'preserve'),'chroma_boost':(0.,0.,'boost'),'chroma_both':(.35,.20,'preserve')})
VARIANTS.update({'fine_20':(0.,0.,'',2.0),'fine_18':(0.,0.,'',1.8),'fine_pigment':(.30,.20,'boost',2.0),'fine_color':(.30,.20,'preserve',2.0),'pigment_balanced':(.30,.20,'boost',2.35)})
TS=[.16,.32,.48,.64,.80,.94]
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)

def run(names,keys,tag):
    data=[]
    for name in names:
        W=390;H=465;canvas=Image.new('RGB',(W*(len(keys)+1),H*6),(18,23,31));d=ImageDraw.Draw(canvas);frames={}
        for key in keys:
            values=VARIANTS[key];life,area=values[:2];mode=values[2] if len(values)>2 else ''
            frag=FRAG
            if mode=='preserve':
                frag=frag.replace('color=mix(color,pow(color,vec3(.70)),shape_out*.85);','''float peak=max(max(color.r,color.g),color.b);
                vec3 preserved=color*pow(max(peak,.001),-.38);
                color=mix(color,preserved,shape_out*.85);''')
            elif mode=='boost':
                frag=frag.replace('if(diagnostic==1)','''float low=min(min(color.r,color.g),color.b);
                float high=max(max(color.r,color.g),color.b);
                color=max(vec3(0),color-vec3(low)*.44*smoothstep(.15,.65,high-low)*shape_out);
                if(diagnostic==1)''')
            base.FRAGMENT=frag
            vert=VERT.replace('uniform vec2 frame,card,offset,cell,wind;','uniform vec2 frame,card,offset,cell,wind;\nuniform sampler2D foreground;')
            vert=vert.replace('scale*=1.-.36*smoothstep(.12,.36,age);',f'''scale*=1.-.36*smoothstep(.12,.36,age);
            vec3 pigment=textureLod(foreground,m.src.xy/card,0.).rgb;
            float chroma=max(max(pigment.r,pigment.g),pigment.b)-min(min(pigment.r,pigment.g),pigment.b);
            float content=smoothstep(.30,.72,chroma);
            scale*=1.+content*{area:.4f}*smoothstep(.025,.07,age)*(1.-smoothstep(.22,.48,age));''')
            cell=values[3] if len(values)>3 else 2.35
            base.VERTEX=vert;r=base.Renderer(name,cell_px=cell)
            b=r.base;fg=np.array(Image.open(r.directory/'foreground.png').convert('RGB'),dtype='float32')/255
            rgb=fg[np.clip(b[:,1].astype(int),0,r.ch-1),np.clip(b[:,0].astype(int),0,r.cw-1)]
            sat=rgb.max(axis=1)-rgb.min(axis=1);content=np.clip((sat-.30)/.42,0,1);content=content*content*(3-2*content)
            b[:,6]=np.maximum(np.minimum(b[:,6]*(1+life*content),.865+.115*b[:,10]-b[:,2]),.11);r.material.write(b.tobytes())
            frames[key]=[r.render(t) for t in TS];m=r.meta;r.close()
        truths=[reference(m,t) for t in TS]
        for j,t in enumerate(TS):
            for i,key in enumerate(['华为']+keys):
                image=Image.fromarray(crop(truths[j] if i==0 else frames[key][j],m));image.thumbnail((W-8,H-34))
                canvas.paste(image,(i*W+(W-image.width)//2,j*H+33));d.text((i*W+8,j*H+5),f'{key} {t:.2f}',font=FONT,fill='white')
        scores={k:[float(abs(feature(a,m)-feature(t,m)).mean()) for a,t in zip(frames[k],truths)] for k in keys}
        canvas.save(HERE/'analysis'/f'{tag}-{name}.jpg',quality=96)
        data.append({'scene':name,'times':TS,'mae':scores});print(name,{k:round(np.mean(s),3) for k,s in scores.items()},flush=True)
    (HERE/'analysis'/f'{tag}.json').write_text(json.dumps(data,indent=2),encoding='utf-8')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['kobe','ironman','thanos']);p.add_argument('--variants',nargs='+',default=list(VARIANTS));p.add_argument('--tag',default='pigment-ablation');a=p.parse_args();run(a.scenes,a.variants,a.tag)
