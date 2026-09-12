"""离线校准同一低频释放场；运行时不读取参考、差异图或区域标注。"""
from pathlib import Path
import sys,json
import numpy as np,cv2
from scipy.ndimage import median_filter,map_coordinates,gaussian_filter
from scipy import sparse
from scipy.sparse.linalg import spsolve
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import unified_model as model
from export_videos import Reference,load_meta
from frame_difference import OUT,BASE,blur

def clocks(images,source,background):
    hp=source-background-blur(source-background,3)
    var=blur((hp*hp).sum(2),4)
    strength=blur(((source-background)**2).sum(2),4)
    tracks=[]
    for image in images:
        h=image-background-blur(image-background,3)
        tracks.append(np.clip(blur((h*hp).sum(2),4)/np.maximum(var,.00005),0,1.2))
    tracks=median_filter(np.stack(tracks),size=(3,1,1));tracks=np.minimum.accumulate(tracks,axis=0)
    half=np.full(var.shape,.99);last=tracks[0]
    for i,track in enumerate(tracks[1:],1):
        mask=(half==.99)&(track<.5)
        half[mask]=((i-1)+np.clip((last[mask]-.5)/np.maximum(last[mask]-track[mask],1e-6),0,1))/60
        last=track
    valid=(var>.002)&(strength>.012)&(half>.04)&(half<.82)
    return half,valid,var,tracks

def main():
    OUT.mkdir(exist_ok=True);meta=load_meta('ironman');ref=Reference(meta)
    x,y,x1,y1=meta['rect'];w,h=x1-x,y1-y
    crop=lambda a:a[y:y1,x:x1].astype('float32')/255
    source=crop(ref.original);bg=crop(np.array(Image.open(HERE/'assets/ironman/background.png')))
    reference=np.load(OUT/'reference.npy',mmap_mode='r');base=np.load(BASE/'ironman.npy',mmap_mode='r')[::2]
    rc,rv,rvar,rt=clocks([crop(a) for a in reference],source,bg)
    bc,bv,bvar,bt=clocks([crop(a) for a in base],source,bg)
    weight=np.sqrt(np.minimum(rvar,.02))*(rv&bv)
    delta=rc-bc
    # 12×12 低频系数仅描述共同场；二阶正则抑制纹理噪声与照片内部小图案。
    n=12;yy,xx=np.mgrid[:h,:w];params=model.variation(meta['seed']);a=model.field_rotation(meta['direction'],w,h)
    qx=(xx+.5)/w-.5;qy=(yy+.5)/h-.5;c,s=np.cos(a),np.sin(a)
    u,v=model.warp_coordinates(.5+c*qx-s*qy,.5+s*qx+c*qy,params)
    gx=(u+.45)/1.9*n-.5;gy=(v+.45)/1.9*n-.5
    select=(weight>0)&(xx%3==0)&(yy%3==0)
    ix=np.floor(gx[select]).astype(int);iy=np.floor(gy[select]).astype(int)
    fx=gx[select]-ix;fy=gy[select]-iy;ns=len(ix)
    ids=np.arange(ns);rows=[];cols=[];vals=[]
    for dx,dy,f in [(0,0,(1-fx)*(1-fy)),(1,0,fx*(1-fy)),(0,1,(1-fx)*fy),(1,1,fx*fy)]:
        rows.extend(ids);cols.extend(np.clip(iy+dy,0,n-1)*n+np.clip(ix+dx,0,n-1));vals.extend(f)
    A=sparse.csr_matrix((vals,(rows,cols)),shape=(ns,n*n));weights=weight[select]
    W=sparse.diags(weights)
    # 超出有效源区的修正趋零，避免把照片中的局部噪声延伸到全场。
    D=sparse.diags([np.ones(n-1),-2*np.ones(n),np.ones(n-1)],[-1,0,1],shape=(n,n))
    lap=sparse.kron(sparse.eye(n),D)+sparse.kron(D,sparse.eye(n))
    lhs=A.T@W@A+.16*(lap.T@lap)+.025*sparse.eye(n*n)
    coeff=spsolve(lhs,A.T@(weights*np.clip(delta[select],-.13,.13))).reshape(n,n)
    yy,xx=np.mgrid[:96,:96]
    common_delta=map_coordinates(coeff,[(yy+.5)/96*n-.5,(xx+.5)/96*n-.5],order=3,mode='nearest')
    # 保留连续形态；幅度有界，不让近乎平坦的原场产生新的孤立小孔。
    common_delta=np.clip(gaussian_filter(common_delta,1.2),-.11,.11).astype('float32')
    np.savez(OUT/'release-calibration.npz',delta=common_delta,coefficients=coeff,reference_clock=rc,baseline_clock=bc,weight=weight)
    np.save(OUT/'reference-surface.npy',rt)
    np.save(OUT/'baseline-surface.npy',bt)
    report=dict(samples=ns,parameter_count=n*n,clock_difference_quantiles=np.quantile(delta[select],[.1,.5,.9]).tolist(),delta_range=[float(common_delta.min()),float(common_delta.max())],seed_variation=params.tolist(),locality=model.release_locality(w,h,meta['direction'],meta['seed']))
    (OUT/'release-calibration.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ims=[]
    for arr in [rc,bc,np.clip(delta/.24+.5,0,1)]:
        im=cv2.cvtColor(cv2.applyColorMap(np.rint(np.clip(arr,0,1)*255).astype('uint8'),cv2.COLORMAP_TURBO),cv2.COLOR_BGR2RGB)
        im[weight<=0]=[16,22,30];ims.append(im)
    Image.fromarray(np.concatenate(ims,1)).save(OUT/'release-clock-comparison.png')
    print(json.dumps(report,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
