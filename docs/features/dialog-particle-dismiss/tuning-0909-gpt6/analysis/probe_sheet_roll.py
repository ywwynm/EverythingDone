"""局部运动机理对照；候选仅在本进程覆盖，不能进入正式运行时。"""
from pathlib import Path
import sys,argparse,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter
from PIL import Image,ImageDraw,ImageFont
import renderer,unified_model
from fields import propagation,rotate_uv,field_grid
from export_videos import Reference

p=argparse.ArgumentParser();p.add_argument('--mode',choices=['inward','outward','dipole','volume','frontroll','current'],required=True);p.add_argument('--gain',type=float,default=1.7);p.add_argument('--elliptic',action='store_true');p.add_argument('--mean-release',action='store_true');p.add_argument('--macro',type=float,default=0);a=p.parse_args()
out=renderer.HERE/'analysis/edge-roll'/f'{a.mode}-{a.gain:g}{"-elliptic" if a.elliptic else ""}{"-mean-release" if a.mean_release else ""}{f"-macro{a.macro:g}" if a.macro else ""}';out.mkdir(parents=True,exist_ok=True)
if a.macro:
    renderer.RESOLVE=renderer.RESOLVE.replace('    // 中心保留',f'''
    vec2 macroFlow=vec2(0.);
    const vec3 origins[3]=vec3[3](vec3(.60,1.02,0.),vec3(0.,.06,.04),vec3(1.04,.20,.15));
    vec2 referenceWind=vec2(-.5735764,-.8191520);
    float c=dot(referenceWind,wind),s=referenceWind.x*wind.y-referenceWind.y*wind.x;
    for(int i=0;i<3;i++){{
        vec2 source=origins[i].xy-.5;
        source=vec2(c*source.x-s*source.y,s*source.x+c*source.y);
        vec2 center=(source+.5)*card+wind*span*(.28+.20*max(time-origins[i].z,0.));
        vec2 d=(world-center)/span;
        float radius=.29-.035*float(i);
        float crossWind=wind.x*source.y-wind.y*source.x;
        float spin=crossWind<0.?1.:-1.;
        float falloff=exp(-dot(d,d)/(2.*radius*radius));
        float phase=smoothstep(origins[i].z,origins[i].z+.15,time);
        macroFlow+=spin*vec2(-d.y,d.x)*falloff*phase*{a.macro:.5f};
    }}
    // 中心保留''').replace('vec3 flow=broad*1.10+fine*(.04+.38*edge);','vec3 flow=broad*.10+fine*(.015+.16*edge);')
    renderer.RESOLVE=renderer.RESOLVE.replace('flow.xy=wind*flow.x+side*flow.y;','flow.xy=wind*flow.x+side*flow.y+macroFlow;')
if a.elliptic:
    def release(nx,ny,direction):
        y,x=np.mgrid[:ny,:nx].astype(float)
        x,y=rotate_uv((x+.5)/nx,(y+.5)/ny,direction-unified_model.RULES['release_direction'])
        origins=[[.60,1.02,0.],[0.,.06,.15],[1.04,.20,.28]]
        params=[[ox,oy,delay,.90,.34,.85] for ox,oy,delay in origins]
        t=propagation(x,y,params).astype('float32')
        low=float(t.min());extent=max(float(np.quantile(t,.997))-low,1e-8)
        return np.clip((t.astype(float)-low)*(.59/extent)+.018,0,.78).astype('float32')
    unified_model.release_field=release
if a.mean_release:
    profiles=[(json.loads((renderer.HERE/'assets'/name/'scene.json').read_text('utf-8')),json.loads((renderer.HERE/'assets'/name/'profile.json').read_text('utf-8'))) for name in ['ironman','thanos','kobe']]
    def mean_release(nx,ny,direction):
        return np.mean([field_grid(meta,nx,ny,direction,profile) for meta,profile in profiles],axis=0).astype('float32')
    unified_model.release_field=mean_release
start=renderer.COMPUTE.index('    float peel=')
end=renderer.COMPUTE.index('    state[i].pos.w=')
if a.mode in ['inward','outward']:
    sign=-1 if a.mode=='inward' else 1
    replacement=f'''
    vec2 normal=normalize(m.physical.xy+vec2(.00001));
    float omega=11.0+1.8*sin(dot(m.src.xy/card,vec2(3.3,2.8)));
    float theta=min(age*omega,3.14159265);
    float fold=sin(theta)*smoothstep(.002,.025,age);
    target+=normal*span*{sign*a.gain:.5f}*fold*roll_gain;
    float depth_target=span*.12*omega*sin(theta)-state[i].pos.z*2.;
'''
elif a.mode=='dipole':
    replacement=f'''
    vec2 side=vec2(-wind.y,wind.x);
    vec2 center=card*.5+wind*span*(.05+.35*time);
    vec2 q=vec2(dot(p-center,wind),dot(p-center,side))/span;
    vec2 scale=vec2(.23,.32);
    float gauss=exp(-.5*dot(q/scale,q/scale));
    vec2 curl_v={a.gain:.5f}*gauss*vec2(1.-q.y*q.y/(scale.y*scale.y),q.x*q.y/(scale.x*scale.x));
    target+=span*(wind*curl_v.x+side*curl_v.y)*smoothstep(.01,.06,age)*roll_gain;
    float depth_target=0.;
'''
elif a.mode=='volume':
    replacement=f'''
    vec2 normal=wind;
    vec2 center=card*.5+wind*span*(.15+.35*time);
    float across=dot(p-center,normal);
    float omega={a.gain:.5f}*roll_gain;
    float envelope=smoothstep(.005,.045,age)*(1.-smoothstep(.35,.60,age));
    target+=normal*omega*state[i].pos.z*envelope;
    float depth_target=-omega*across*envelope-state[i].pos.z*.60;
'''
elif a.mode=='frontroll':
    original_materials=renderer.materials
    unified_model.RULES['release_spread']=.008
    def front_materials(width,height,foreground,direction,seed,cell_px):
        built=original_materials(width,height,foreground,direction,seed,cell_px)
        field=unified_model.release_field(built['nx'],built['ny'],direction)
        gy,gx=np.gradient(gaussian_filter(field,3),built['cell'][1],built['cell'][0])
        speed=np.clip(1/np.maximum(np.hypot(gx,gy),1e-5),min(width,height)*.35,min(width,height)*4.)
        ids=built['base'][:,3].astype(int)%(built['nx']*built['ny'])
        built['base'][:,7]=speed.ravel()[ids]
        return built
    renderer.materials=front_materials
    renderer.COMPUTE=renderer.COMPUTE.replace('exp(-h/m.physical.w)','exp(-h/(.018+.036*m.random.z))')
    replacement=f'''
    vec2 normal=normalize(m.physical.xy+vec2(.00001));
    float omega={a.gain:.5f};
    float theta=min(age*omega,6.2831853);
    float unfold=(1.-cos(theta))*roll_gain;
    target+=normal*m.physical.w*unfold;
    float depth_target=m.physical.w*sin(theta)-state[i].pos.z*.60;
'''
if a.mode!='current':renderer.COMPUTE=renderer.COMPUTE[:start]+replacement+renderer.COMPUTE[end:]
(out/'candidate.comp').write_text(renderer.COMPUTE,encoding='utf-8')
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17);ctx=moderngl.create_standalone_context(require=430)
for name in ['ironman','thanos','color']:
    r=renderer.Renderer(name,ctx=ctx);ref=Reference(r.meta)
    rect=(65,380,490,890) if name=='ironman' else (0,max(0,r.meta['rect'][1]-80),r.w,min(r.h,r.meta['rect'][3]+80))
    x,y,x1,y1=rect;sw=280;sh=round((y1-y)*sw/(x1-x));sheet=Image.new('RGB',(sw*7,2*(sh+28)),(16,20,27));d=ImageDraw.Draw(sheet)
    for j,t in enumerate(np.linspace(.35,.65,7)):
        current=r.render(t)
        Image.fromarray(current).save(out/f'{name}-{t:.2f}.png')
        for row,(im,label) in enumerate([(ref.at(t),'参考'),(current,a.mode)]):
            sheet.paste(Image.fromarray(im[y:y1,x:x1]).resize((sw,sh),Image.Resampling.LANCZOS),(sw*j,row*(sh+28)+28))
            d.text((sw*j+6,row*(sh+28)+3),f'{label} {t:.2f}',font=font,fill='white')
    sheet.save(out/f'{name}.jpg',quality=95);r.close();print(name,flush=True)
ctx.release()
