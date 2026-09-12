"""追踪一条已在当前共同模型中看见的窄细缕；来源框不参与正式渲染。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl,cv2
from scipy.ndimage import gaussian_filter
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_filament_layers import OUT
def main():
    ctx=moderngl.create_standalone_context(require=430);r=renderer.Renderer('attachment',direction=270,seed=909602,ctx=ctx)
    t=.30;im=r.render(t,diagnostic=2);state=np.frombuffer(r.state.read(),'float32').reshape(-1,8).copy();base=r.base
    x,y,_,_=r.meta['rect'];pts=state[:,:2]+[x,y];source=base[:,:2]+[x,y]
    # 由完整画面和粒子层共同确认的上方窄曲线，之后按图像亮脊在 ±12 px 内精定位。
    control=np.array([[330,546],[346,588],[381,632],[414,684]],dtype='float32')
    ys=np.arange(550,680,dtype='float32');xs=np.interp(ys,control[:,1],control[:,0]);normal=np.array([np.ones(len(xs)),-np.gradient(xs)])
    normal/=np.linalg.norm(normal,axis=0);lum=im.astype('float32').mean(2);contrast=gaussian_filter(lum,1.5)-gaussian_filter(lum,6.)
    offsets=np.arange(-12,13);cx=xs[:,None]+normal[0,:,None]*offsets;cy=ys[:,None]+normal[1,:,None]*offsets
    response=cv2.remap(contrast,cx.astype('float32'),cy.astype('float32'),cv2.INTER_LINEAR)
    # 强制轨迹平滑，避免逐行追到互不相关颗粒；此处只精定位诊断曲线。
    path=gaussian_filter(offsets[np.argmax(gaussian_filter(response,(3,1)),axis=1)].astype(float),4)
    line=np.stack([xs+normal[0]*path,ys+normal[1]*path],axis=1)
    binary=np.full((r.h,r.w),255,'uint8');cv2.polylines(binary,[np.rint(line).astype('int32')],False,0,1);dist=cv2.distanceTransform(binary,cv2.DIST_L2,5)
    ii=np.rint(pts).astype(int);inside=(ii[:,0]>=0)&(ii[:,0]<r.w)&(ii[:,1]>=0)&(ii[:,1]<r.h)
    distance=np.full(len(base),1000.);distance[inside]=dist[ii[inside,1],ii[inside,0]]
    age=t-base[:,2];alive=(age>.022)&(age<base[:,6]-.02);mask=alive&(distance<4.);near=alive&(distance>10)&(distance<18)
    for name,points in [('current',pts),('source',source)]:
        picture=Image.fromarray(im if name=='current' else r.render(0));draw=ImageDraw.Draw(picture)
        for a,b in points[mask]:draw.ellipse((a-1,b-1,a+1,b+1),fill=(255,65,50))
        if name=='current':draw.line([tuple(p) for p in line],fill=(255,215,55),width=1)
        picture.save(OUT/f'trace-{name}.png')
    grid=np.argsort(base[:,3]);original=grid[base[grid,3]<r.nx*r.ny];nx=r.nx;ny=r.ny
    n=base[original,4:6].reshape(ny,nx,2);cellx,celly=r.cell
    gy=np.stack([gaussian_filter(np.gradient(n[:,:,j],celly,axis=0),1) for j in range(2)],axis=-1)
    gx=np.stack([gaussian_filter(np.gradient(n[:,:,j],cellx,axis=1),1) for j in range(2)],axis=-1)
    # 下向运动剥离力主要沿横向，负横向导数代表左右材料向同一区域靠拢。
    compress=-gx[:,:,0]
    ids=(base[:,3].astype('int64')%(nx*ny));derivative=compress.ravel()[ids]
    def stats(m):
        return {key:np.percentile(val[m],[5,25,50,75,95]).tolist() for key,val in {'birth':base[:,2],'age':age,'normal_x':base[:,4],'normal_y':base[:,5],'normal_length':np.linalg.norm(base[:,4:6],axis=1),'peel_compression':derivative,'displacement':np.linalg.norm(pts-source,axis=1),'speed':np.linalg.norm(state[:,4:6],axis=1),'source_x':base[:,0],'source_y':base[:,1]}.items()}
    result=dict(count=int(mask.sum()),neighbours=int(near.sum()),filament=stats(mask),neighbour=stats(near));print(json.dumps(result),flush=True)
    np.savez_compressed(OUT/'traced-peel-cohort.npz',base=base,state=state,mask=mask,near=near,line=line,gradient_x=gx,gradient_y=gy)
    (OUT/'traced-peel-statistics.json').write_text(json.dumps(result,indent=2),'utf-8');r.close();ctx.release()
if __name__=='__main__':main()
