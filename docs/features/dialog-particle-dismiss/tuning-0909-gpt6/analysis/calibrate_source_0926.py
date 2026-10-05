"""仅校正动画开始前的取材几何／曝光；不变换任何模型动画帧。"""
from pathlib import Path
import json,sys
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
OUT=HERE/'analysis/new-references-0926';BACKUP=OUT/'source-originals'


def main():
    BACKUP.mkdir(exist_ok=True);results=[]
    for name in [f'reference-0926-{i}' for i in range(1,8)]+['spiderman-original']:
        path=HERE/'assets'/name;meta=load_meta(name);ref=Reference(meta);x,y,x1,y1=meta['rect']
        original=BACKUP/f'{name}.png'
        if not original.exists():original.write_bytes((path/'foreground.png').read_bytes())
        rgba=np.array(Image.open(original));src=rgba[:,:,:3]
        # 这两个片段的确认框与粒子启动重叠，稍后帧仍有大面积原表面可用于取材匹配。
        t=.095 if name in ['reference-0926-3','spiderman-original'] else .02
        target=ref.at(t)[y:y1,x:x1]
        before=float(np.median(np.abs(src.astype(float)-target).mean(2)))
        fit=src.copy();matrix=np.array([[1.,0,0],[0,1.,0]])
        count=0;residual=0.;gains=[]
        mask=rgba[:,:,3].copy();mask[:8]=0;mask[-8:]=0;mask[:,:8]=0;mask[:,-8:]=0
        detector=cv2.SIFT_create(nfeatures=2500,contrastThreshold=.01)
        ks,ds=detector.detectAndCompute(cv2.cvtColor(src,cv2.COLOR_RGB2GRAY),mask)
        kt,dt=detector.detectAndCompute(cv2.cvtColor(target,cv2.COLOR_RGB2GRAY),mask)
        pairs=cv2.BFMatcher().knnMatch(ds,dt,k=2)
        good=[q[0] for q in pairs if len(q)==2 and q[0].distance<.70*q[1].distance]
        if len(good)>=10:
            ps=np.float32([ks[q.queryIdx].pt for q in good]);pt=np.float32([kt[q.trainIdx].pt for q in good])
            a,valid=cv2.estimateAffinePartial2D(ps,pt,method=cv2.RANSAC,ransacReprojThreshold=1.5)
            if a is not None and valid.sum()>8:
                scale=np.hypot(a[0,0],a[0,1]);shift=np.linalg.norm(a[:,2])
                if not .9<scale<1.1 or shift>min(src.shape[:2])*.10:
                    raise ValueError(f'{name}: 取材缩放超过预期，需重新检查 {a}')
                matrix=a;count=int(valid.sum());residual=float(np.median(np.linalg.norm(ps@a[:,:2].T+a[:,2]-pt,axis=1)[valid[:,0]>0]))
                fit=cv2.warpAffine(src,a,src.shape[1::-1],flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_REFLECT_101)
        # 只做整个源图共享的三通道仿射曝光；局部粒子／遮挡不参加最小二乘。
        error=np.mean(np.abs(fit.astype(float)-target),axis=2)
        usable=(mask==255)&(error<np.quantile(error[mask==255],.60))
        for c in range(3):
            u=fit[:,:,c][usable].astype(float);v=target[:,:,c][usable].astype(float)
            a=np.stack([u,np.ones_like(u)],1);coef=np.array([1.,0.])
            for _ in range(5):
                e=v-a@coef;weight=np.minimum(1,5/np.maximum(np.abs(e),1e-4))
                coef=np.linalg.lstsq(a*weight[:,None],v*weight,rcond=None)[0]
            coef[0]=np.clip(coef[0],.85,1.18);coef[1]=np.clip(coef[1],-18,18)
            fit[:,:,c]=np.uint8(np.clip(fit[:,:,c].astype(float)*coef[0]+coef[1],0,255));gains.append(coef.tolist())
        after=float(np.median(np.abs(fit.astype(float)-target).mean(2)))
        attempted_after=after;applied=after<=before+.1
        if not applied:fit=src;after=before
        Image.fromarray(np.dstack([fit,rgba[:,:,3]])).save(path/'foreground.png')
        bg=np.array(Image.open(path/'background.png').convert('RGB'));alpha=rgba[:,:,3:]/255
        bg[y:y1,x:x1]=np.uint8(fit*alpha+bg[y:y1,x:x1]*(1-alpha));Image.fromarray(bg).save(path/'source.png')
        results.append(dict(scene=name,reference_phase=t,median_before=before,median_after=after,
            applied=applied,attempted_median_after=attempted_after,
            source_affine=matrix.tolist(),source_affine_inliers=count,source_affine_residual=residual,exposure=gains))
        print(name,round(before,3),'->',round(after,3),'配准点',count,flush=True)
    (OUT/'source-calibration.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
