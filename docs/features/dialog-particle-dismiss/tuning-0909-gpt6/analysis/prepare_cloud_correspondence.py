"""离线 RGB／位置最优输运对应，仅作为共同函数的训练信号。

对应点不进入运行模型；使用同一颜色、距离和熵代价，限制背景和未解除表面的影响。
参考 DROT 的 RGBXY 观测思路，不假设它就是原动画的物理过程。
"""
from pathlib import Path
import argparse,json,pickle,sys
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_particle_proxy import LO,SIZE
from match_compare import Comparison


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',required=True);ap.add_argument('--out',required=True)
    ap.add_argument('--distance',type=float,default=.16);ap.add_argument('--color',type=float,default=.25)
    ap.add_argument('--size',type=int,default=44);a=ap.parse_args()
    import torch
    torch.set_num_threads(4)
    rows=pickle.loads(Path(a.cache).read_bytes());out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    n=a.size;yy,xx=np.mgrid[:n,:n].astype(np.float32);points=np.stack([(xx+.5)/n,(yy+.5)/n],-1).reshape(-1,2)
    tensor=lambda x:torch.as_tensor(np.ascontiguousarray(x,np.float32),device='cuda')
    result=[];last=None
    for index,d in enumerate(rows):
        name=d['name']
        if name!=last:c=Comparison(name);last=name
        p=d['progress'];_,ref=c.ref.nearest(p);x,y,x1,y1=c.scene.rect;w,h=x1-x,y1-y
        qx=x+(LO+(xx+.5)/n*SIZE)*w;qy=y+(LO+(yy+.5)/n*SIZE)*h
        sample=lambda im:cv2.remap(cv2.GaussianBlur(im.astype(np.float32),(0,0),max(1.,min(w,h)/n*.5)),qx,qy,cv2.INTER_LINEAR)
        # 模型像素已经过缩小前低通；源表面由独立 pass 的实际结果扣除。
        base=np.where(d['base']<=.0031308,12.92*d['base'],1.055*np.maximum(d['base'],1e-8)**(1/2.4)-.055)
        # 用真实运动片的面积和着色确定模型质量；两幅合成图相减会把
        # sRGB 低通次序的差异误认为屏幕边缘存在颗粒。
        pos=d['pos'];cell=pos*n-.5;ij=np.floor(cell).astype(int);fraction=cell-ij
        bp=np.concatenate([cv2.remap(base,(part[:,0]*base.shape[1]-.5).astype(np.float32)[:,None],
            (part[:,1]*base.shape[0]-.5).astype(np.float32)[:,None],cv2.INTER_LINEAR)[:,0]
            for part in np.array_split(pos,max(1,(len(pos)+15999)//16000))])
        color=np.where(d['color']<=.0031308,12.92*d['color'],1.055*np.maximum(d['color'],1e-8)**(1/2.4)-.055)
        material_weight=d['mass']*np.minimum(np.linalg.norm(color-bp,axis=-1),1.)
        model_weight=np.zeros(n*n,np.float32)
        for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
            px=ij[:,0]+dx;py=ij[:,1]+dy;valid=(px>=0)&(px<n)&(py>=0)&(py<n)
            factor=(fraction[:,0] if dx else 1-fraction[:,0])*(fraction[:,1] if dy else 1-fraction[:,1])
            model_weight+=np.bincount((py[valid]*n+px[valid]),weights=(material_weight*factor)[valid],minlength=n*n)
        model_weight=cv2.GaussianBlur(model_weight.reshape(n,n)*(n/d['n'])**2,(0,0),.7)
        observed=c.scene.observe(ref)
        reference_weight=sample((observed['particle']&c.scene.roi).astype(np.float32))
        mask=sample(c.scene.roi.astype(np.float32))
        model_weight*=mask
        first=(model_weight>.008).ravel();second=(reference_weight>.018).ravel()
        pa=points[first];pb=points[second]
        ca=cv2.resize(cv2.GaussianBlur(d['actual'],(0,0),1.2),(n,n),interpolation=cv2.INTER_AREA).reshape(-1,3)[first]
        cb=(sample(ref)/255).reshape(-1,3)[second]
        wa=model_weight.ravel()[first];wb=reference_weight.ravel()[second]
        flow=np.zeros((n*n,2),np.float32);confidence=np.zeros(n*n,np.float32)
        if min(len(pa),len(pb))>8:
            ta,tb=tensor(pa),tensor(pb);fa,fb=tensor(ca),tensor(cb)
            sa=tensor(wa/wa.sum());sb=tensor(wb/wb.sum());scale=tensor([w/min(w,h),h/min(w,h)])
            def solve(q,f,mass):
                distance=torch.cdist(ta*scale,q*scale).square()*(SIZE/a.distance)**2
                color=torch.cdist(fa,f).square()/a.color**2
                # 有限熵避免把整片同色区域压到一个对应点；空间代价抑制远程误配。
                kernel=torch.exp(-(distance+color)/.55).clamp(min=1e-30)
                u=torch.ones_like(sa);v=torch.ones_like(mass)
                for _ in range(100):
                    u=(sa/(kernel@v).clamp(min=1e-25)).pow(.93)
                    v=(mass/(kernel.T@u).clamp(min=1e-25)).pow(.93)
                coupling=kernel*v[None]
                coupling/=coupling.sum(1,keepdim=True).clamp(min=1e-25)
                return coupling@q
            with torch.no_grad():
                shifted=solve(tb,fb,sb);self_shift=solve(ta,fa,sa)
                delta=(shifted-self_shift).cpu().numpy()
            flow[first]=delta
            confidence[first]=np.minimum(wa/.12,1.)*np.exp(-np.maximum(np.linalg.norm(delta,axis=-1)*SIZE-.16,0)**2/.12**2)
        flow=flow.reshape(n,n,2);confidence=confidence.reshape(n,n)
        # 低频对应只指导有可见颗粒的区域；不据它修改原位表面。
        result.append(dict(flow=flow,weight=confidence,name=name,phase=p))
        if index%8 in [2,4,6]:
            im=Image.fromarray(np.uint8(np.clip(d['actual'],0,1)*255)).resize((440,440))
            draw=ImageDraw.Draw(im)
            for iy in range(2,n,3):
                for ix in range(2,n,3):
                    if confidence[iy,ix]<.2:continue
                    start=((ix+.5)*440/n,(iy+.5)*440/n);v=flow[iy,ix]*440
                    draw.line([start,(start[0]+v[0],start[1]+v[1])],fill=(60,255,150),width=1)
            im.save(out/f'{name}-{p:.3f}.jpg',quality=95)
        print(name,round(p,3),round(float(np.quantile(np.linalg.norm(flow,axis=-1)*SIZE,.95)),4),flush=True)
    (out/'correspondence.pkl').write_bytes(pickle.dumps(result,protocol=5))
    (out/'method.json').write_text(json.dumps(dict(cache=str(Path(a.cache).resolve()),distance=a.distance,color=a.color,n=n,
        description='仅离线辅助目标；全部参考使用同一代价，无逐素材运行时资源'),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
