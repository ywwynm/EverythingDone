"""仅用于区分释放误差和输运误差的参考控制组，严禁导出为运行模型。"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from scipy.ndimage import gaussian_filter,zoom
from fields import field_grid
from export_videos import Reference
import renderer,unified_model
from fit_flow import evaluate

def main():
    out=renderer.HERE/'analysis/edge-roll/reference-transport-control';out.mkdir(exist_ok=True,parents=True)
    # 固定旧版运动形式，只比较共同引导与参考观测引导，避免同时改三类变量。
    s=renderer.COMPUTE
    start=s.index('    // 弯曲流管');end=s.index('    // 主方向持续前进')
    renderer.COMPUTE=s[:start]+'    float depth_target=-state[i].pos.z*3.;\n'+s[end:]
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for name in ['ironman']:
        meta=json.loads((renderer.HERE/'assets'/name/'scene.json').read_text('utf-8'))
        profile=json.loads((renderer.HERE/'assets'/name/'profile.json').read_text('utf-8'))
        def release(nx,ny,direction):
            t=field_grid(meta,64,64,direction,profile)
            return zoom(gaussian_filter(t,1.2),(ny/64,nx/64),order=3).astype('float32')
        unified_model.release_field=release
        for mode in ['common','observed']:
            if mode=='observed':
                fp=json.loads((renderer.HERE/'assets'/name/'flow-profile.json').read_text('utf-8'))
                yy,xx=np.mgrid[:36,:36];x=-.45+(xx+.5)/36*1.9;y=-.45+(yy+.5)/36*1.9
                angle=np.deg2rad(fp['direction']-90);c=np.cos(angle);s=np.sin(angle)
                qx=.5+c*(x-.5)+s*(y-.5);qy=.5-s*(x-.5)+c*(y-.5)
                def layer(t):
                    v=evaluate(fp,qx,qy,t)
                    return np.stack((c*v[...,0]-s*v[...,1],s*v[...,0]+c*v[...,1]),axis=-1)
                observed=np.array([layer(t) for t in np.linspace(0,1,32)],dtype='float32')
                renderer.guidance=lambda:observed
            r=renderer.Renderer(name,ctx=ctx);ref=Reference(meta)
            x,y,x1,y1=meta['rect'];lo=max(0,y-80);hi=min(r.h,y1+80);w=360;h=round((hi-lo)*w/r.w)
            sheet=Image.new('RGB',(w*4,2*(h+28)),(16,21,29));d=ImageDraw.Draw(sheet)
            for j,t in enumerate([.31,.43,.55,.65]):
                now=r.render(t);Image.fromarray(now).save(out/f'{name}-{mode}-{t:.2f}.png')
                for row,(im,label) in enumerate([(ref.at(t),'参考'),(now,f'控制组 {mode}（非通用模型）')]):
                    sheet.paste(Image.fromarray(im[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(j*w,row*(h+28)+28))
                    d.text((j*w+5,row*(h+28)+3),f'{label} {t:.2f}',font=font,fill='white')
            sheet.save(out/f'{name}-{mode}.jpg',quality=94);r.close();print(name,mode,flush=True)
    ctx.release()

if __name__=='__main__':main()
