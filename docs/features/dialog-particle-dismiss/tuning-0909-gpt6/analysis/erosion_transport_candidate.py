"""局部定向释放的隔离试验；不读取人物拟合配置，不改正式模型。"""
from pathlib import Path
import argparse,json,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter
from PIL import Image,ImageDraw,ImageFont
import renderer,unified_model
from fields import propagation,rotate_uv
from export_videos import Reference

BASE_RELEASE=unified_model.release_field

def configure(anisotropy=0.,entrainment=False):
    def release(nx,ny,direction):
        if not anisotropy:return BASE_RELEASE(nx,ny,direction)
        yy,xx=np.mgrid[:ny,:nx].astype(float)
        x,y=rotate_uv((xx+.5)/nx,(yy+.5)/ny,direction-125.)
        # 主释放区沿横风方向较快展开，沿风方向较慢推进。
        # 只改变原有右下源的传播度量，不添加周边释放源。
        parts=[]
        for i,(ox,oy,delay) in enumerate([[.60,1.02,0.],[0.,.06,.15],[1.04,.20,.28]]):
            params=[ox,oy,delay,.66,.67,0.]
            if i==0:params=[ox,oy,delay,.66*(1.+anisotropy*.35),.67*(1.-anisotropy*.60),.84]
            parts.append(propagation(x,y,[params]))
        from fields import smooth_min
        t=smooth_min(smooth_min(parts[0],parts[1]),parts[2]).astype('float32')
        low=float(t.min());scale=.59/max(float(np.quantile(t,.997))-low,1e-8)
        return np.clip((t-low)*scale+.018,0,.78).astype('float32')
    unified_model.release_field=release
    if entrainment:
        s=renderer.COMPUTE
        start=s.index('    // 分离速度只在释放后')
        end=s.index('    // 主方向持续前进')
        s=s[:start]+'''    // 年轻材料与局部释放面的前进方向一致；速度靠近前沿速度时会浓集。
    // 对反向推进的起始角不应用此项，避免把粒群整体推回去。
    vec2 normal=normalize(m.physical.xy+vec2(.000001));
    float alignment=smoothstep(.25,.80,dot(normal,wind));
    float young=(1.-smoothstep(.055,.16,age))*alignment;
    vec2 front=normal*front_speed[i]*.82;
    vec2 transverse=target-normal*dot(target,normal);
    target=mix(target,front+transverse*.65,young);
    float depth_target=(span*.018*sin(dot(m.src.xy,vec2(.014,.021))+time*4.)-state[i].pos.z*3.)*roll_gain;
'''+s[end:]
        s=s.replace('uniform int count;','layout(std430,binding=3) readonly buffer D {float front_speed[];};\nuniform int count;')
        s=s.replace('float entrained=1.+.24*smoothstep(.01,.15,age);','float entrained=.65+.59*smoothstep(.01,.15,age);')
        s=s.replace('flow*.85','flow*.45')
        s=s.replace('(.27*exp(-age/.08)+.095*smoothstep(.16,.34,age))','(.08*exp(-age/.08)+.095*smoothstep(.16,.34,age))')
        renderer.COMPUTE=s
    return release

def front_buffer(r,field):
    t=field(r.nx,r.ny,r.direction)
    gy,gx=np.gradient(gaussian_filter(t,3),r.cell[1],r.cell[0])
    speed=1./np.maximum(np.hypot(gx,gy),1e-6)
    ids=r.base[:,3].astype(int)%(r.nx*r.ny)
    values=np.clip(speed.ravel()[ids],min(r.cw,r.ch)*.12,min(r.cw,r.ch)*1.8).astype('float32')
    b=r.ctx.buffer(values.tobytes());b.bind_to_storage_buffer(3)
    return b

def main():
    p=argparse.ArgumentParser();p.add_argument('--anisotropy',type=float,default=0);p.add_argument('--entrainment',action='store_true');p.add_argument('--scenes',nargs='+',default=['ironman','thanos','kobe','attachment','color']);a=p.parse_args()
    out=renderer.HERE/'analysis/edge-roll'/f'local-source-{a.anisotropy:g}-entrainment{int(a.entrainment)}';out.mkdir(parents=True,exist_ok=True)
    field=configure(a.anisotropy,a.entrainment)
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    metrics=[]
    for name in a.scenes:
        r=renderer.Renderer(name,ctx=ctx);ref=Reference(r.meta);extra=front_buffer(r,field) if a.entrainment else None
        x,y,x1,y1=r.meta['rect'];lo=max(0,y-80);hi=min(r.h,y1+80);w=360;h=round((hi-lo)*w/r.w)
        sheet=Image.new('RGB',(w*4,2*(h+28)),(16,21,29));d=ImageDraw.Draw(sheet)
        for j,t in enumerate([.31,.43,.55,.65]):
            now=r.render(t);Image.fromarray(now).save(out/f'{name}-{t:.2f}.png')
            for row,(im,label) in enumerate([(ref.at(t),'参考'),(now,'局部释放试验')]):
                sheet.paste(Image.fromarray(im[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(j*w,row*(h+28)+28))
                d.text((j*w+5,row*(h+28)+3),f'{label} {t:.2f}',font=font,fill='white')
        sheet.save(out/f'{name}.jpg',quality=94)
        if name=='ironman':
            yy,xx=np.mgrid[:120,:120];xx=(xx+.5)/120;yy=(yy+.5)/120;tt=field(120,120,r.direction)
            for region,key in [((xx>.02)&(xx<.10)&(yy>.30)&(yy<.85),'left_middle'),((yy>.02)&(yy<.10)&(xx>.30)&(xx<.80),'top_middle')]:
                metrics.append({'region':key,'unreleased_at_043':float((tt[region]>.43).mean())})
        if extra:extra.release()
        r.close();print(name,flush=True)
    (out/'parameters.json').write_text(json.dumps({'parameters':vars(a),'source_check':metrics},ensure_ascii=False,indent=2),'utf-8');print(metrics,flush=True);ctx.release()

if __name__=='__main__':main()
