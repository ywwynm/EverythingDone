"""使用原参考的原表面交接时间，校准空间约束候选的同一释放场。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl,cv2
from scipy import sparse
from scipy.sparse.linalg import spsolve
from scipy.ndimage import map_coordinates,gaussian_filter
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import unified_model as model
from calibrate_common_release import clocks
from calibrate_spatial_circulation import apply
from probe_projected_peel import ProjectedRenderer
from probe_edge_support import OUT
from export_videos import Reference,load_meta

def main():
    meta=load_meta('ironman');ref=Reference(meta);x,y,x1,y1=meta['rect'];w,h=x1-x,y1-y
    source=ref.original[y:y1,x:x1].astype('float32')/255
    bg=np.array(Image.open(HERE/'assets/ironman/background.png'))[y:y1,x:x1].astype('float32')/255
    refs=np.stack([ref.at(i/60) for i in range(61)])
    frames=np.load(OUT/'spatial-circulation-ironman.npy',mmap_mode='r')
    rc,rv,variance,_=clocks([a[y:y1,x:x1].astype('float32')/255 for a in refs],source,bg)
    cc,cv,_,_=clocks([a[y:y1,x:x1].astype('float32')/255 for a in frames],source,bg)
    yy,xx=np.mgrid[:h,:w];a=model.field_rotation(meta['direction'],w,h);params=model.variation(meta['seed'])
    qx=(xx+.5)/w-.5;qy=(yy+.5)/h-.5;c,s=np.cos(a),np.sin(a)
    u,v=model.warp_coordinates(.5+c*qx-s*qy,.5+s*qx+c*qy,params)
    n=12;gx=(u+.45)/1.9*n-.5;gy=(v+.45)/1.9*n-.5
    select=rv&cv&(xx%3==0)&(yy%3==0);ix=np.floor(gx[select]).astype(int);iy=np.floor(gy[select]).astype(int)
    fx=gx[select]-ix;fy=gy[select]-iy;rows=[];cols=[];vals=[];count=len(ix);ids=np.arange(count)
    for dx,dy,f in [(0,0,(1-fx)*(1-fy)),(1,0,fx*(1-fy)),(0,1,(1-fx)*fy),(1,1,fx*fy)]:
        rows.extend(ids);cols.extend(np.clip(iy+dy,0,n-1)*n+np.clip(ix+dx,0,n-1));vals.extend(f)
    A=sparse.csr_matrix((vals,(rows,cols)),shape=(count,n*n));weights=np.sqrt(np.minimum(variance[select],.02))
    D=sparse.diags([np.ones(n-1),-2*np.ones(n),np.ones(n-1)],[-1,0,1]);lap=sparse.kron(sparse.eye(n),D)+sparse.kron(D,sparse.eye(n))
    coefficients=spsolve(A.T@sparse.diags(weights)@A+.22*(lap.T@lap)+.04*sparse.eye(n*n),A.T@(weights*np.clip((rc-cc)[select],-.10,.10))).reshape(n,n)
    yy,xx=np.mgrid[:96,:96];delta=map_coordinates(coefficients,[(yy+.5)/96*n-.5,(xx+.5)/96*n-.5],order=3,mode='nearest')
    delta=np.clip(gaussian_filter(delta,1.4),-.065,.065).astype('float32');np.save(OUT/'spatial-release-delta.npy',delta)
    np.savez(OUT/'spatial-release-clocks.npz',reference=rc,candidate=cc,valid=rv&cv,delta=delta)
    print('释放时刻修正',delta.min(),delta.max(),flush=True)
    flowcfg=json.loads((OUT/'spatial-circulation-config.json').read_text());base=model.RELEASE.copy();ctx=moderngl.create_standalone_context(require=430)
    for gain in [.6,1.0]:
        apply(flowcfg['coefficients']);model.RELEASE=np.clip(base+delta*gain,.001,.84)
        r=ProjectedRenderer('ironman',ctx=ctx);frames=np.stack([r.render(i/60) for i in range(61)]);r.close()
        np.save(OUT/f'spatial-release-{gain:g}-ironman.npy',frames);print(gain,'完成',flush=True)
    ctx.release()

if __name__=='__main__':main()
