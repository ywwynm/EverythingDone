"""离线提炼一份共同的释放与速度表示；生成过程读取冻结观测，运行端不读取。"""
from pathlib import Path
import json,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from scipy.ndimage import gaussian_filter,map_coordinates
from fields import field_grid
from renderer import HERE

def main():
    frozen=HERE/'archive/observed-approved';out=HERE/'analysis/transport-model/shared';out.mkdir(parents=True,exist_ok=True)
    meta=json.loads((frozen/'source-identity.json').read_text('utf-8'))['input']
    x,y,x1,y1=meta['rect'];w=x1-x;h=y1-y;span=min(w,h)
    angle=np.deg2rad(meta['direction']);normalized=np.arctan2(np.sin(angle)/h,np.cos(angle)/w)-np.pi/2
    oldangle=angle-np.pi/2
    release=gaussian_filter(field_grid(meta,64,64,meta['direction'],json.loads((frozen/'release-profile.json').read_text('utf-8'))),1.2)
    n=96;yy,xx=np.mgrid[:n,:n].astype(float);qx=-.45+(xx+.5)/n*1.9-.5;qy=-.45+(yy+.5)/n*1.9-.5
    c,s=np.cos(normalized),np.sin(normalized)
    ux=.5+c*qx+s*qy;uy=.5-s*qx+c*qy
    ix=np.clip(ux,0,1)*63;iy=np.clip(uy,0,1)*63
    at=lambda values:map_coordinates(values,[iy,ix],order=1,mode='nearest')
    gy,gx=np.gradient(release,1/63,1/63)
    # 源方形以外平滑延续边界斜率，使旋转后的角区也有连续的释放时间。
    timing=at(release)+at(gx)*(ux-np.clip(ux,0,1))+at(gy)*(uy-np.clip(uy,0,1))
    timing=np.clip(timing,.001,.78).astype('<f4');timing.tofile(out/'common-release.f32')
    original=np.load(frozen/'observed-flow.npy').astype(float)
    filtered=gaussian_filter(original,(.40,.48,.48,0))
    nt=48;n=64;tt,yy,xx=np.mgrid[:nt,:n,:n].astype(float)
    qx=-.45+(xx+.5)/n*1.9-.5;qy=-.45+(yy+.5)/n*1.9-.5
    # 观测场按旧屏幕角归一化；新场改用源矩形归一化速度，尺寸变换使用完整雅可比。
    delta=oldangle-normalized;c,s=np.cos(delta),np.sin(delta)
    ox=.5+c*qx-s*qy;oy=.5+s*qx+c*qy
    coords=[(tt+.5)/nt*64-.5,(oy+.45)/1.9*96-.5,(ox+.45)/1.9*96-.5]
    a=np.stack([map_coordinates(filtered[:,:,:,k],coords,order=1,mode='nearest') for k in range(2)],axis=-1)
    c,s=np.cos(oldangle),np.sin(oldangle)
    vx=(c*a[...,0]+s*a[...,1])*span/w;vy=(-s*a[...,0]+c*a[...,1])*span/h
    c,s=np.cos(normalized),np.sin(normalized)
    result=np.stack((c*vx-s*vy,s*vx+c*vy),axis=-1).astype('<f2')
    result.tofile(out/'common-flow.f16')
    info={'release_shape':[96,96],'release_domain':[-.45,1.45],'flow_shape':[48,64,64,2],
          'basis':'一份连续空间与时间系数网格；释放与速度在同一归一化坐标系下变换',
          'trained_from':'用户认可的观测控制组；所有素材共用，不按名称、材质纹理或拟合文件选择',
          'runtime_dependencies':['common-release.f32','common-flow.f16','rules.properties'],
          'bytes':int(timing.nbytes+result.nbytes)}
    (out/'distillation.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),'utf-8');print(info,flush=True)

if __name__=='__main__':main()
