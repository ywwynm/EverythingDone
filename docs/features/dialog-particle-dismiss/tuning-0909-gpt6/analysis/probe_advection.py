"""以稳定版验证连续补入和颗粒受流加速，所有场景共享候选规则。"""
from pathlib import Path
import sys,argparse,json,importlib.util
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter,zoom
from PIL import Image,ImageDraw,ImageFont
import renderer,unified_model
from fields import rotate_uv,warp,smooth_min,field_grid
from export_videos import Reference

p=argparse.ArgumentParser();p.add_argument('--edge',type=float,default=0);p.add_argument('--speed',type=float,default=1.);p.add_argument('--flow',type=Path);p.add_argument('--highlight',type=float,default=0);p.add_argument('--sector',type=float,default=0);p.add_argument('--boundary',type=float,default=0);p.add_argument('--density',type=float,default=0);p.add_argument('--coverage',type=float,default=0);p.add_argument('--coherent',action='store_true');p.add_argument('--attenuation',type=float,default=0);p.add_argument('--canonical-release',action='store_true');p.add_argument('--tag');a=p.parse_args()
out=renderer.HERE/'analysis/edge-roll'/f'advection-edge{a.edge:g}-speed{a.speed:g}{"-pooled" if a.flow else ""}{f"-highlight{a.highlight:g}" if a.highlight else ""}{f"-sector{a.sector:g}" if a.sector else ""}{f"-boundary{a.boundary:g}" if a.boundary else ""}{f"-density{a.density:g}" if a.density else ""}{f"-coverage{a.coverage:g}" if a.coverage else ""}{"-coherent" if a.coherent else ""}';out.mkdir(parents=True,exist_ok=True)
if a.tag:out=renderer.HERE/'analysis/edge-roll'/a.tag;out.mkdir(parents=True,exist_ok=True)
(out/'parameters.json').write_text(json.dumps(vars(a),default=str,ensure_ascii=False,indent=2),encoding='utf-8')
if a.coherent:
    unified_model.RULES['release_spread']=.006;unified_model.RULES['white_spread']=.134
old_release=unified_model.release_field
def release(nx,ny,direction):
    t=old_release(nx,ny,direction)
    y,x=np.mgrid[:ny,:nx].astype(float)
    x,y=rotate_uv((x+.5)/nx,(y+.5)/ny,direction-unified_model.RULES['release_direction'])
    if a.sector:
        wx,wy=warp(x,y);w=np.array([-.5735764,-.8191520]);parts=[]
        origins=[[float(v) for v in z.split(',')] for z in unified_model.RULES['release_origins'].split(';')]
        for ox,oy,delay in origins:
            dx=wx-ox;dy=wy-oy;r=np.maximum(np.hypot(dx,dy),.0001)
            alignment=(dx*w[0]+dy*w[1])/r
            slow=unified_model.smooth((alignment-.55)/.38)
            upwind=unified_model.smooth((-np.dot(np.array([ox,oy])-.5,w)+.04)/.42)
            strength=a.sector*upwind*unified_model.smooth((r-.10)/.28)
            parts.append(delay+(r*.665)**.88*(1.-strength*(1.-slow)))
        t=parts[0]
        for part in parts[1:]:t=smooth_min(t,part)
        t=(t-t.min())/(np.quantile(t,.997)-t.min())*.59+.018
    distance=np.maximum(np.minimum.reduce([x,1-x,y,1-y]),0.)
    # 边缘相对于中心更早释放；早期已出现的起点保持原时刻，中心不全局打孔。
    near_edge=np.exp(-(distance/.24)**2)
    t=.018+(t-.018)*(1.-a.edge*near_edge)
    if a.boundary:
        w=nx/min(nx,ny);h=ny/min(nx,ny);perimeter=2*(w+h)
        py,px=np.mgrid[:ny,:nx].astype(float);px=(px+.5)/nx*w;py=(py+.5)/ny*h
        feet=[(px,py),(w+py,w-px),(w+h+w-px,h-py),(2*w+h+h-py,px)]
        origins=[[float(v) for v in z.split(',')] for z in unified_model.RULES['release_origins'].split(';')]
        for ox,oy,delay in origins:
            ox,oy=rotate_uv(ox,oy,unified_model.RULES['release_direction']-direction);ox=np.clip(ox,0,1)*w;oy=np.clip(oy,0,1)*h
            side=int(np.argmin([oy,w-ox,h-oy,ox]));source=[ox,w+oy,w+h+w-ox,2*w+h+h-oy][side]
            for foot,distance in feet:
                route=np.abs(foot-source);route=np.minimum(route,perimeter-route)
                candidate=.018+delay+route/a.boundary+2.0*distance*distance
                t=smooth_min(t,candidate,.025)
    if a.canonical_release:
        # 诊断：各输入共用同一个低通释放模板，检验输运与释放各自的贡献。
        profile=json.loads((renderer.HERE/'assets/ironman/profile.json').read_text('utf-8'))
        template=field_grid({'direction':122},32,32,direction,profile)
        template=gaussian_filter(template,1.2)
        t=zoom(template,(ny/32,nx/32),order=3)
    return np.maximum(t,.001).astype('float32')
unified_model.release_field=release
if a.flow:renderer.guidance=lambda:np.load(a.flow).astype('float32')
s=renderer.COMPUTE
start=s.index('    // 分离速度只在释放后')
end=s.index('    // 主方向持续前进')
s=s[:start]+'''    // 颗粒离开原位后由同一输运场带走，深度仅保留小幅材质层次。
    float depth_target=span*.035*sin(dot(m.src.xy,vec2(.014,.021))+time*4.)*smoothstep(.02,.12,age)-state[i].pos.z*3.;
'''+s[end:]
s=s.replace('float entrained=1.+.24*smoothstep(.01,.15,age);',f'float entrained=(.28+.95*smoothstep(.008,.14,age))*{a.speed:.6f};')
s=s.replace('flow*(.35+.65*loose)','flow*(.20+.45*loose)').replace('flow*.85','flow*.30')
s=s.replace('(.27*exp(-age/.08)+.095*smoothstep(.16,.34,age))','(.10*exp(-age/.08)+.075*smoothstep(.16,.34,age))')
s=s.replace('old_v=target*.85','old_v=target*.25')
s=s.replace('float depth_target=span*.035','float depth_target=roll_gain*span*.035')
renderer.COMPUTE=s
if a.attenuation:
    renderer.FRAGMENT=renderer.FRAGMENT.replace('float alpha=src.a*fade*shape;',f'float alpha=src.a*fade*shape*exp(-max(age-.035,0.)*{a.attenuation:.5f});')
if a.coverage:
    renderer.VERTEX=renderer.VERTEX.replace('    // 原色明显的微片',f'''    float fresh=smoothstep(.006,.028,age)*(1.-smoothstep(.07,.16,age));
    scale*=1.+{a.coverage:.5f}*(1.-.65*body_weight)*fresh;
    // 原色明显的微片''')
if a.highlight:
    renderer.FRAGMENT=renderer.FRAGMENT.replace('    // 彩色材料避免',f'''    float fresh=smoothstep(.008,.025,age)*(1.-smoothstep(.045,.12,age));
    color=mix(color,max(color,vec3(.72)),fresh*{a.highlight:.5f});
    // 彩色材料避免''')
if a.density:
    from cloud_field import SAMPLE
    spec=importlib.util.spec_from_file_location('density_probe',renderer.HERE/'archive/rejected-sheet-experiments/renderer.py')
    driver=importlib.util.module_from_spec(spec);spec.loader.exec_module(driver)
    driver.HERE=renderer.HERE;driver.guidance=renderer.guidance
    s=s.replace('vec2 curl(vec2 p,float t) {',SAMPLE+'\nvec2 curl(vec2 p,float t) {')
    s=s.replace('    // 主方向持续前进',f'''
    CloudCell neighborhood=sampleCloud(p);
    vec2 gradient=neighborhood.shape.yz;
    float edge=neighborhood.shape.w*(1.-smoothstep(.50,.85,neighborhood.shape.x));
    float gather=edge*smoothstep(.03,.09,age)*(1.-smoothstep(.22,.38,age));
    target+=gradient/max(length(gradient),.01)*span*{a.density:.6f}*gather;
    state[i].pos.w=gather;
    // 主方向持续前进''')
    driver.COMPUTE=s;driver.VERTEX=renderer.VERTEX;driver.FRAGMENT=renderer.FRAGMENT
    renderer=driver
(out/'candidate.comp').write_text(s,encoding='utf-8')
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);ctx=moderngl.create_standalone_context(require=430)
for name in ['ironman','thanos','kobe','color','attachment']:
    r=renderer.Renderer(name,ctx=ctx);ref=Reference(r.meta)
    x,y,x1,y1=r.meta['rect'];lo=max(0,y-120);hi=min(r.h,y1+80);w=300;h=round((hi-lo)*w/r.w)
    sheet=Image.new('RGB',(w*4,2*(h+28)),(16,21,29));d=ImageDraw.Draw(sheet)
    for j,t in enumerate([.31,.43,.55,.65]):
        now=r.render(t);Image.fromarray(now).save(out/f'{name}-{t:.2f}.png')
        for row,(im,label) in enumerate([(ref.at(t),'参考'),(now,'连续补入与输运')]):
            sheet.paste(Image.fromarray(im[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(j*w,row*(h+28)+28))
            d.text((j*w+5,row*(h+28)+3),f'{label} {t:.2f}',font=font,fill='white')
    sheet.save(out/f'{name}.jpg',quality=94);r.close();print(name,flush=True)
ctx.release()
