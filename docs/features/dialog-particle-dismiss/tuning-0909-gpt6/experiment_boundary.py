"""固定 r30 的逐因素边界试验；仅写 analysis，不改交付模型与素材。"""
import argparse,json,types
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
from scipy.ndimage import gaussian_filter
from pathlib import Path
HERE=Path(__file__).resolve().parent
BASE=(HERE/'archive/r30/renderer.py').read_text(encoding='utf-8')
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
TIMES=[.20,.36,.50,.66]
VARIANTS={
 'r30':{},
 'stagger08':{'width':.08},'stagger16':{'width':.16},'stagger24':{'width':.24},
 'seated':{'seat':.10},'white_light':{'white':True},
 'soft12':{'width':.12,'white':True},
 'soft16':{'width':.16,'white':True},
 'soft16_seat':{'width':.16,'white':True,'seat':.06},
 'adaptive':{'width':.16,'adaptive':True,'white':True},
 'spatial':{'width':.16,'spatial':True,'white':True},
 'spatial12':{'width':.16,'spatial':True,'band':.12,'white':True},
 'spatial18':{'width':.18,'spatial':True,'band':.18,'white':True},
 'ui_blend':{'width':.12,'ui':True,'white':True},
 'material12':{'width':.12,'material':True,'white':True},
 'material14':{'width':.14,'material':True,'white':True},
 'transfer_iron':{'width':.12,'material':True,'white':True,'transfer':'ironman'},
 'transfer_mix':{'width':.12,'material':True,'white':True,'transfer':'mix'},
 'transfer_half':{'width':.12,'material':True,'white':True,'transfer':'half'},
 'regional':{'width':.12,'material':True,'white':True,'regions':True},
}

def module(key):
    v=VARIANTS[key];src=BASE
    if v.get('white'):
        src=src.replace('if(diagnostic==1)', '''float body=smoothstep(.80,.98,min(src.r,min(src.g,src.b)));
    color=mix(color,min(color,vec3(1.)),body);
    if(diagnostic==1)''')
    if v.get('seat'):
        src=src.replace('float response=1.-exp(-h/m.physical.w);',f'''target*=.18+.82*smoothstep(0.,{v['seat']},age);
    float response=1.-exp(-h/m.physical.w);''')
    m=types.ModuleType('boundary_'+key);m.__file__=str(HERE/'archive/r30/renderer.py')
    exec(compile(src,'boundary_'+key,'exec'),m.__dict__)
    return m

def configure(r,key):
    v=VARIANTS[key];b=r.base
    if 'width' in v:
        width=v['width'];fg=np.array(Image.open(r.directory/'foreground.png').convert('RGB'),dtype='float32')/255
        if v.get('material'):
            alpha=np.array(Image.open(r.directory/'foreground.png'))[:,:,3]>.95*255
            fraction=float(np.mean((fg.min(axis=2)>.9)[alpha]))
            body=np.clip((fraction-.30)/.35,0,1);body=body*body*(3-2*body)
            width=.060+(width-.060)*body
        if v.get('adaptive'):
            lum=fg.mean(axis=2);mean=gaussian_filter(lum,6)
            deviation=np.sqrt(np.maximum(gaussian_filter(lum*lum,6)-mean*mean,0))
            flat=np.clip((.065-deviation)/.05,0,1);flat=flat*flat*(3-2*flat)
            light=np.clip((mean-.60)/.28,0,1);light=light*light*(3-2*light)
            factor=(flat*light)[np.clip(b[:,1].astype(int),0,r.ch-1),np.clip(b[:,0].astype(int),0,r.cw-1)]
            width=.024+(width-.024)*factor
        if v.get('spatial'):
            from fields import field_grid
            profile=json.loads((r.directory/'profile.json').read_text(encoding='utf-8')) if (r.directory/'profile.json').exists() else None
            T=field_grid(r.meta,r.nx,r.ny,r.direction,profile)
            gy,gx=np.gradient(gaussian_filter(T,3),r.cell[1],r.cell[0])
            smooth=np.clip(fg.min(axis=2),0,1)
            tone=gaussian_filter(smooth,8)
            tone=np.clip((tone-.70)/.24,0,1);tone=tone*tone*(3-2*tone)
            tone=tone[np.clip(b[:,1].astype(int),0,r.ch-1),np.clip(b[:,0].astype(int),0,r.cw-1)]
            widths=np.clip(np.hypot(gx,gy).ravel()[b[:,3].astype(int)]*r.span*v.get('band',.15),.048,width)
            width=.048+(widths-.048)*tone
        if v.get('ui'):width=width if not r.meta['reference'] else .060
        sample=b[:,11]
        if v.get('spatial'):sample=.5-np.sin(np.arcsin(1-2*sample)/3)
        delta=width*(sample-.5)-.024*(b[:,11]-.5)
        b[:,2]=np.maximum(.001,b[:,2]+delta)
        b[:,6]=np.maximum(np.minimum(b[:,6],.865+.115*b[:,10]-b[:,2]),.11)
        r.material.write(b.tobytes());r.reset()
    if v.get('transfer') and not r.meta['reference']:
        from fields import field_grid
        from fit_flow import common_texture,texture
        kind=v['transfer'];fields=[]
        for name in ['ironman','thanos','kobe']:
            p=HERE/'assets'/name
            meta=json.loads((p/'scene.json').read_text(encoding='utf-8'));profile=json.loads((p/'profile.json').read_text(encoding='utf-8'))
            field=field_grid(meta,r.nx,r.ny,r.direction,profile)
            field=np.clip((field-np.quantile(field,.01))/(np.quantile(field,.99)-np.quantile(field,.01)),0,1)*.59+.018
            fields.append(field)
        T=fields[0] if kind=='ironman' else .5*fields[0]+.25*fields[1]+.25*fields[2]
        T=np.clip((T-T.min())/(np.quantile(T,.997)-T.min()),0,1)*.59+.018
        if kind=='half':T=.5*T+.5*field_grid(r.meta,r.nx,r.ny,r.direction,None)
        gy,gx=np.gradient(gaussian_filter(T,3),r.cell[1],r.cell[0]);l=np.maximum(np.hypot(gx,gy),1e-6);ids=b[:,3].astype(int)
        b[:,2]=np.maximum(.001,T.ravel()[ids]+.12*(b[:,11]-.5));b[:,4]=gaussian_filter(gx/l,7).ravel()[ids];b[:,5]=gaussian_filter(gy/l,7).ravel()[ids]
        b[:,6]=np.maximum(np.minimum(b[:,6],.865+.115*b[:,10]-b[:,2]),.11)
        r.material.write(b.tobytes());r.reset()
    if v.get('regions') and (HERE/'analysis'/f"{r.meta['name']}-release-regions-candidate.json").exists():
        from fields import field_grid
        profile=json.loads((r.directory/'profile.json').read_text(encoding='utf-8'))
        profile.setdefault('origin_time_corrections',[]).extend(json.loads((HERE/'analysis'/f"{r.meta['name']}-release-regions-candidate.json").read_text(encoding='utf-8'))['origin_time_corrections'])
        T=field_grid(r.meta,r.nx,r.ny,r.direction,profile)
        gy,gx=np.gradient(gaussian_filter(T,3),r.cell[1],r.cell[0]);l=np.maximum(np.hypot(gx,gy),1e-6);ids=b[:,3].astype(int)
        b[:,2]=np.maximum(.001,T.ravel()[ids]+.06*(b[:,11]-.5));b[:,4]=gaussian_filter(gx/l,7).ravel()[ids];b[:,5]=gaussian_filter(gy/l,7).ravel()[ids]
        rgb=fg[np.clip(b[:,1].astype(int),0,r.ch-1),np.clip(b[:,0].astype(int),0,r.cw-1)];sat=rgb.max(axis=1)-rgb.min(axis=1)
        content=np.clip((sat-.30)/.42,0,1);content=content*content*(3-2*content)
        life=(.10+.22*(-np.log(np.maximum(b[:,8],.004)))**.85+.20*b[:,2])*.70
        life=np.maximum(np.minimum(life,.865+.115*b[:,10]-b[:,2]),.11)
        b[:,6]=np.maximum(np.minimum(life*(1+.30*content),.865+.115*b[:,10]-b[:,2]),.11)
        r.material.write(b.tobytes());r.reset()
    return r

def crop(a,meta):
    x,y,x1,y1=meta['rect'];pad=round(min(x1-x,y1-y)*.25)
    return a[max(0,y-pad):min(meta['frame'][1],y1+pad),max(0,x-pad):min(meta['frame'][0],x1+pad)]

def run(scenes,keys,tag):
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    from review import reference
    from compare_metrics import feature
    for name in scenes:
        frames={};stats={}
        for key in keys:
            r=configure(module(key).Renderer(name,ctx=ctx),key);m=r.meta
            frames[key]=[r.render(t) for t in TIMES]
            if m['reference']:
                stats[key]=[float(abs(feature(a,m)-feature(reference(m,t),m)).mean()) for t,a in zip(TIMES,frames[key])]
            r.close()
        columns=(['华为'] if m['reference'] else [])+keys
        W=340;H=440 if name in ['color','language'] else 360
        canvas=Image.new('RGB',(len(columns)*W,H*len(TIMES)),(18,23,31));d=ImageDraw.Draw(canvas)
        for row,t in enumerate(TIMES):
            for col,key in enumerate(columns):
                a=reference(m,t) if key=='华为' else frames[key][row]
                im=Image.fromarray(crop(a,m));im.thumbnail((W-8,H-35))
                canvas.paste(im,(col*W+(W-im.width)//2,row*H+33));d.text((col*W+8,row*H+5),f'{key} {t:.2f}',font=FONT,fill='white')
        canvas.save(HERE/'analysis'/f'{tag}-{name}.jpg',quality=96)
        rows.append({'scene':name,'times':TIMES,'variants':{k:VARIANTS[k] for k in keys},'lowpass_mae':stats})
        print(name,{k:round(np.mean(v),3) for k,v in stats.items()},flush=True)
    (HERE/'analysis'/f'{tag}.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8');ctx.release()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['attachment','color','thanos','kobe','ironman']);p.add_argument('--variants',nargs='+',default=['r30','stagger08','stagger16','seated','white_light']);p.add_argument('--tag',default='boundary-first');a=p.parse_args();run(a.scenes,a.variants,a.tag)
