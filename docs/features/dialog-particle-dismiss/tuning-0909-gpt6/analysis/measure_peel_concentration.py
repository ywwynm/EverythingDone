"""以运动状态测量细缕集中程度，测试区不进入运行端。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl,cv2
from scipy.ndimage import gaussian_filter,map_coordinates
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from probe_device_filaments import OUT,configure

def concentration(r,t,region):
    r.seek(t);states=np.frombuffer(r.state.read(),'float32').reshape(-1,8);b=r.base;age=t-b[:,2]
    alive=(age>.045)&(age<b[:,6]-.02)
    q=states[:,:2];h,w=r.ch,r.cw
    ix=np.floor(q[:,0]).astype(int);iy=np.floor(q[:,1]).astype(int)
    valid=alive&(ix>=0)&(ix<w)&(iy>=0)&(iy<h)
    hist=np.bincount(iy[valid]*w+ix[valid],minlength=w*h).reshape(h,w).astype(float)*r.cell[0]*r.cell[1]
    narrow=gaussian_filter(hist,2);wide=gaussian_filter(hist,9)
    # 与尚未释放表面分开，测的是已释放粒子形成的独立窄峰。
    birth=model.release_field(w,h,r.direction,w,h,r.meta['seed'])
    yy,xx=np.mgrid[:h,:w]
    l,top,rr,bot=region
    mask=(xx/w>l)&(xx/w<rr)&(yy/h>top)&(yy/h<bot)&(birth<t-.08)&(wide>.035)
    ratio=narrow/(wide+.015);peak=ratio.copy();peak[~mask]=0
    # 连续的小片区域而非单个随机高点。
    score=gaussian_filter(peak,2)
    iy,ix=np.unravel_index(score.argmax(),score.shape)
    return dict(peak=float(score[iy,ix]),location=[int(ix),int(iy)],density=float(narrow[iy,ix]),base_density=float(wide[iy,ix])),peak

def main():
    ctx=moderngl.create_standalone_context(require=430);results=[]
    for j,t,region in [(1,.24,[.42,.64,.93,.95]),(3,.24,[.66,.32,.97,.92]),(2,.56,[.05,.40,.66,.99])]:
        for kind in ['current','no-peel','peel-half','peel-dispersion','peel-drag']:
            configure(kind);r=renderer.Renderer(str(OUT/f'input-{j}'),ctx=ctx)
            stats,peak=concentration(r,t,region);r.close();results.append(dict(video=j,kind=kind,phase=t,**stats))
            if kind in ['current','peel-dispersion']:
                heat=cv2.applyColorMap(np.clip(peak/3*255,0,255).astype('uint8'),cv2.COLORMAP_INFERNO)
                cv2.imwrite(str(OUT/f'concentration-{j}-{kind}.png'),heat)
    ctx.release();(OUT/'concentration.json').write_text(json.dumps(results,indent=2),'utf-8');print(json.dumps(results),flush=True)
if __name__=='__main__':main()
