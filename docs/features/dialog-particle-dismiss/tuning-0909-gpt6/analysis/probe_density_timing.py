"""密度与交接时序的逐项对照；诊断区域不进入运行时模型。"""
from pathlib import Path
import sys, ast, json, shutil, argparse
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import renderer
import moderngl
from export_videos import HERE, Reference, load_meta, sampled, label, BG, code_hash
from unified_model import SHARED, model_fingerprint

OUT=HERE/'analysis'/'density-timing'
BASE=HERE/'archive'/'before-density-timing'
TIMES=[.28,.35,.40,.44,.48,.52,.56,.60,.65,.72,.80,.88]

def freeze():
    OUT.mkdir(parents=True,exist_ok=True)
    if (BASE/'identity.json').exists():return
    BASE.mkdir(parents=True,exist_ok=True)
    for name in ['renderer.py','unified_model.py','export_videos.py','touch_geometry.py','android_shaders.py']:
        shutil.copy2(HERE/name,BASE/name)
    shutil.copytree(SHARED,BASE/'shared',dirs_exist_ok=True)
    for scene in ['ironman','ironman-up-reference','thanos','kobe','language','color','attachment','attachment-image']:
        for ext in ['.npy','.json']:shutil.copy2(HERE/'cache'/(scene+ext),BASE/(scene+ext))
    (BASE/'identity.json').write_text(json.dumps({'model':model_fingerprint(),'video':code_hash()},indent=2),encoding='utf-8')

def original():
    tree=ast.parse((BASE/'renderer.py').read_text(encoding='utf-8'))
    return {n.targets[0].id:ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']}

def contact(rows,path,times=TIMES,crop=None,w=300):
    for group in range((len(times)+3)//4):
        ids=list(range(group*4,min(group*4+4,len(times))))
        sample=rows[0][1][0];bound=crop or (0,0,sample.shape[1],sample.shape[0]);h=round((bound[3]-bound[1])*w/(bound[2]-bound[0]));rh=h+30
        im=Image.new('RGB',(w*len(ids),rh*len(rows)),BG);d=ImageDraw.Draw(im)
        for r,(title,frames) in enumerate(rows):
            for col,i in enumerate(ids):
                label(d,(col*w+7,r*rh+4),f'{title} · {times[i]:.2f}',18)
                im.paste(Image.fromarray(frames[i]).crop(bound).resize((w,h),Image.Resampling.LANCZOS),(col*w,r*rh+30))
        im.save(OUT/f'{path}-{group}.jpg',quality=96)

def baseline(scene):
    a=np.load(BASE/f'{scene}.npy',mmap_mode='r');ref=Reference(load_meta(scene))
    return [ref.at(t) for t in TIMES],[sampled(a,t) for t in TIMES]

def shaders(mode):
    if mode=='density-balanced':
        s=shaders('soft-handoff-flow')
        s['VERTEX']=s['VERTEX'].replace('vec2 vertex=move+', '''float forward_front=smoothstep(.20,.85,dot(-m.physical.xy,wind));
    scale*=1.-.18*forward_front*smoothstep(.002,.022,age)*(1.-smoothstep(.10,.24,age));
    vec2 vertex=move+''')
        return s
    if mode in ['settled-handoff','settled-motion']:
        s=shaders('handoff-balanced' if mode=='settled-handoff' else 'soft-handoff-flow')
        s['VERTEX']=s['VERTEX'].replace('surface_out=1.-smoothstep', 'source_clock+=.025*smoothstep(.38,.55,source_clock);\n    surface_out=1.-smoothstep')
        return s
    if mode in ['soft-handoff-flow','soft-facets','soft-facets-air']:
        s=shaders('handoff-balanced');s['COMPUTE']=shaders('dispersion')['COMPUTE']
        if mode in ['soft-facets','soft-facets-air']:
            s['FRAGMENT']=s['FRAGMENT'].replace('.22*pow(facing,12.)','.72*pow(facing,18.)')
        if mode=='soft-facets-air':s['FRAGMENT']=s['FRAGMENT'].replace('mix(1.,.92,smoothstep','mix(1.,.78,smoothstep')
        return s
    if mode=='facets':
        s=original();s['FRAGMENT']=s['FRAGMENT'].replace('.22*pow(facing,12.)','.72*pow(facing,18.)');return s
    if mode in ['differential-handoff','coordinated']:
        s=shaders('handoff-balanced' if mode=='differential-handoff' else 'combined')
        if mode=='coordinated':
            s['FRAGMENT']=s['FRAGMENT'].replace('.40*forward_out','.28*forward_out')
            s['COMPUTE']=s['COMPUTE'].replace('1.+.75*','1.+.40*').replace('span*.13*independent','span*.085*independent')
        return s
    s=original();c,v,f=s['COMPUTE'],s['VERTEX'],s['FRAGMENT']
    if mode=='clock':c=c.replace('time-m.physical.w','time')
    if mode=='surface':v=v.replace('mix(.20,1.,trailing)','mix(.75,1.,trailing)')
    if mode=='dispersion':
        c=c.replace('float depth_target=', '''// 诊断：后期增加单片响应差异，不改变出生时刻。
    target*=1.+.50*(m.random.x-.5)*smoothstep(.025,.14,age);
    target+=curl(p+vec2(m.random.x,m.random.y)*span*.4,time)*span*.075*smoothstep(.04,.16,age);
    float depth_target=''')
    if mode=='edge-share':f=f.replace('.78*trailing_out','.50*trailing_out')
    if mode in ['handoff','handoff-release','handoff-open','handoff-balanced','combined']:
        v=v.replace('source_clock-.050','source_clock-.025').replace('source_clock+.028','source_clock+.065')
        v=v.replace('mix(.20,1.,trailing)','mix(.85,1.,trailing)')
        f=f.replace('float alpha=src.a*fade*shape*','float alpha=src.a*fade*shape*(1.-surface_out)*')
        if mode in ['handoff-balanced','combined']:
            v=v.replace('mix(.85,1.,trailing)','mix(.70,1.,trailing)')
            v=v.replace('source_clock+.065','source_clock+.060')
    if mode=='handoff-open':f=f.replace('.78*trailing_out','.50*trailing_out')
    if mode in ['forward-density','combined']:
        v=v.replace('flat out float trailing_out;','flat out float trailing_out;\nflat out float forward_out;')
        v=v.replace('life_out=m.physical.z;', 'forward_out=smoothstep(.20,.85,dot(-m.physical.xy,wind));\n    life_out=m.physical.z;')
        f=f.replace('flat in float trailing_out;','flat in float trailing_out;\nflat in float forward_out;')
        f=f.replace('if(alpha<.001)discard;', '''if(random_out.x<.40*forward_out)discard;
    if(alpha<.001)discard;''')
    if mode in ['microflow','combined']:
        c=c.replace('float depth_target=', '''// 颗粒响应与细尺度涡动逐渐分离，主流方向和出生区域不变。
    float independent=smoothstep(.020,.13,age);
    target*=1.+.75*(m.random.x-.5)*independent;
    vec2 transverse=vec2(-wind.y,wind.x);
    float phase0=dot(p,wind)/(span*.14)+time*3.1;
    float phase1=dot(p,transverse)/(span*.11)-time*2.7;
    target+=(transverse*cos(phase0+m.random.w*3.)*cos(phase1)
            -wind*sin(phase0)*sin(phase1+m.random.y*3.))*span*.13*independent;
    float depth_target=''')
    return dict(COMPUTE=c,VERTEX=v,FRAGMENT=f)

def render_mode(mode,scene,ctx,diagnostic=0):
    for k,v in shaders(mode).items():setattr(renderer,k,v)
    r=renderer.Renderer(scene,ctx=ctx)
    if mode in ['release-span','handoff-release']:
        shift=r.base[:,2]*.10
        r.base[:,2]+=shift;r.base[:,7]+=shift
        r.base[:,6]=np.maximum(.11,np.minimum(r.base[:,6],.865+.115*r.base[:,10]-r.base[:,2]))
        r.material.write(r.base.tobytes())
        r.program['release_spread']=r.release_spread*1.10
    if mode in ['differential-release','differential-handoff','coordinated']:
        from unified_model import smooth
        forward=smooth((-r.base[:,4:6]@np.array(r.wind)-.20)/.65)
        shift=.20*(r.base[:,2]-.35)+.045*forward
        birth=np.clip(r.base[:,2]+shift,.001,.82)
        r.base[:,7]+=birth-r.base[:,2];r.base[:,2]=birth
        r.base[:,6]=np.maximum(.11,np.minimum(r.base[:,6],.865+.115*r.base[:,10]-birth))
        r.material.write(r.base.tobytes())
        r.program['release_spread']=r.release_spread*1.20
    frames=[r.render(t,diagnostic=diagnostic) for t in TIMES]
    if mode=='baseline':
        print(scene,'出生/局部时差/寿命分位',*[np.quantile(r.base[:,i],[.05,.25,.5,.75,.95]).round(4).tolist() for i in [2,7,6]],flush=True)
    r.close();return frames

def main():
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['ironman']);p.add_argument('--modes',nargs='+');a=p.parse_args();freeze()
    ctx=moderngl.create_standalone_context(require=430) if a.modes else None
    for scene in a.scenes:
        refs,base=baseline(scene)
        contact([('华为参考',refs),('当前基线',base)],scene+'-baseline')
        if a.modes:
            for mode in a.modes:
                frames=render_mode(mode,scene,ctx);np.save(OUT/f'{scene}-{mode}.npy',np.stack(frames))
                contact([('华为参考',refs),('本轮调整前',base),(mode,frames)],scene+'-'+mode,w=270)
                if scene=='ironman':contact([('华为参考',refs),('本轮调整前',base),(mode,frames)],scene+'-'+mode+'-detail',crop=(75,195,640,775),w=300)
                print(scene,mode,flush=True)
    if ctx:ctx.release()
    print(OUT,flush=True)

if __name__=='__main__':main()
