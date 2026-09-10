"""用原片运动观测做因果控制实验；不是可发布的通用动画。"""
from pathlib import Path
import sys,json,argparse,shutil,hashlib
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from scipy.ndimage import gaussian_filter,zoom
import renderer,unified_model
from fields import field_grid
from export_videos import Reference

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--freeze',action='store_true');args=parser.parse_args()
    out=renderer.HERE/'analysis/edge-roll/observed-reconstruction';out.mkdir(parents=True,exist_ok=True)
    name='ironman';directory=renderer.HERE/'assets'/name
    meta=json.loads((directory/'scene.json').read_text('utf-8'));ref=Reference(meta)
    x,y,x1,y1=meta['rect'];cw=x1-x;ch=y1-y;span=min(cw,ch)
    path=out/'observed-flow.npy';nt=64;n=96
    if path.exists():flow=np.load(path)
    else:
        yy,xx=np.mgrid[:n,:n].astype('float32');u=-.45+(xx+.5)/n*1.9-.5;v=-.45+(yy+.5)/n*1.9-.5
        angle=np.deg2rad(meta['direction']-90);c=np.cos(angle);s=np.sin(angle)
        qx=((.5+c*u+s*v)*cw+x).astype('float32');qy=((.5-s*u+c*v)*ch+y).astype('float32')
        flow=[]
        for j,t in enumerate(np.linspace(0,1,nt)):
            ta=float(np.clip(t-.008,0,.98));tb=ta+.016
            ia=int(np.argmin(abs(ref.times-(meta['reference']['start']+ta*5.2))))
            ib=int(np.argmin(abs(ref.times-(meta['reference']['start']+tb*5.2))))
            dt=(ref.times[ib]-ref.times[ia])/5.2
            ag=cv2.cvtColor(ref.frames[ia],cv2.COLOR_RGB2GRAY);bg=cv2.cvtColor(ref.frames[ib],cv2.COLOR_RGB2GRAY)
            f=cv2.calcOpticalFlowFarneback(ag,bg,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)/(dt*span)
            f=np.clip(f,-1.8,1.8)
            sample=cv2.remap(f,qx,qy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
            flow.append(np.stack((c*sample[:,:,0]-s*sample[:,:,1],s*sample[:,:,0]+c*sample[:,:,1]),axis=-1))
            if j%16==0:print('观测运动',j,flush=True)
        flow=np.array(flow,dtype='float32');np.save(path,flow)
    profile=json.loads((directory/'profile.json').read_text('utf-8'))
    def release(nx,ny,direction):
        return zoom(gaussian_filter(field_grid(meta,64,64,direction,profile),1.2),(ny/64,nx/64),order=3).astype('float32')
    unified_model.release_field=release
    # 同一材质及生存规则；只让速度直接来自观测，保留未脱离区域接近零的速度。
    s=renderer.COMPUTE;start=s.index('    // 源位置');end=s.index('    float response=')
    s=s[:start]+'''    target=guide*(guide_gain/.9)+flow*.02+wind*u*.00001;
    float depth_target=-state[i].pos.z*3.*roll_gain;
    target+=wind*max(0.-dot(target,wind),0.);
'''+s[end:]
    renderer.COMPUTE=s
    ctx=moderngl.create_standalone_context(require=430);r=renderer.Renderer(name,ctx=ctx)
    r.flow_tex.release();r.flow_tex=ctx.texture3d((n,n,nt),2,flow.tobytes(),dtype='f4');r.flow_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);r.flow_tex.repeat_x=False;r.flow_tex.repeat_y=False;r.flow_tex.repeat_z=False
    if args.freeze:
        frozen=renderer.HERE/'archive/observed-approved';frozen.mkdir(parents=True,exist_ok=True)
        frames=np.lib.format.open_memmap(frozen/'ironman.npy',mode='w+',dtype='uint8',shape=(121,r.h,r.w,3))
        for i in range(121):frames[i]=r.render(i/120)
        frames.flush();np.save(frozen/'materials.npy',r.base)
        for file in ['renderer.py','unified_model.py','fields.py']:shutil.copy2(renderer.HERE/file,frozen/file)
        shutil.copy2(Path(__file__),frozen/Path(__file__).name)
        shutil.copy2(path,frozen/'observed-flow.npy');shutil.copy2(directory/'profile.json',frozen/'release-profile.json')
        shutil.copy2(unified_model.SHARED/'rules.properties',frozen/'rules.properties')
        (frozen/'source-identity.json').write_text(json.dumps({'accepted':'用户认可的观测运动控制组','input':meta,'source_hashes':{name:hashlib.sha256((directory/name).read_bytes()).hexdigest() for name in ['foreground.png','source.png','background.png']},'sample_fps':120},ensure_ascii=False,indent=2),'utf-8')
        print('已冻结认可结果',frozen,flush=True)
    lo=y-80;hi=y1+80;w=360;h=round((hi-lo)*w/r.w);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    sheet=Image.new('RGB',(w*4,2*(h+28)),(16,21,29));d=ImageDraw.Draw(sheet)
    for j,t in enumerate([.31,.43,.55,.65]):
        now=r.render(t);Image.fromarray(now).save(out/f'{name}-{t:.2f}.png')
        for row,(im,label) in enumerate([(ref.at(t),'参考'),(now,'观测运动控制组（非通用模型）')]):
            sheet.paste(Image.fromarray(im[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(j*w,row*(h+28)+28));d.text((j*w+5,row*(h+28)+3),f'{label} {t:.2f}',font=font,fill='white')
    sheet.save(out/f'{name}.jpg',quality=94);r.close();ctx.release()

if __name__=='__main__':main()
