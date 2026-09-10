"""配准用户红线截图，记录时刻与轮廓；不将两条密度边界视为材料对应点。"""
from pathlib import Path
import sys,json,shutil
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,cv2
from PIL import Image,ImageDraw,ImageFont
from renderer import HERE
from export_videos import Reference

out=HERE/'analysis/edge-roll/redline-measurement';out.mkdir(parents=True,exist_ok=True)
meta=json.loads((HERE/'assets/ironman/scene.json').read_text('utf-8'));reference=Reference(meta)
paths=[Path('C:/Users/ywwynm/AppData/Local/Temp/codex-clipboard-aa9b1789-62e4-4677-8699-8a2cc615ed34.png'),Path('C:/Users/ywwynm/AppData/Local/Temp/codex-clipboard-7c8f0734-8dfe-443a-8bf1-90811950404c.png')]
orb=cv2.ORB_create(nfeatures=7000);matcher=cv2.BFMatcher(cv2.NORM_HAMMING)
measurements=[]
for number,path in enumerate(paths):
    shot=np.array(Image.open(path).convert('RGB'));shutil.copy2(path,out/f'user-redline-{number+1}.png')
    red=(shot[:,:,0]>210)&(shot[:,:,1]<150)&(shot[:,:,2]<150)&(np.indices(shot.shape[:2])[0]>400)
    red=cv2.morphologyEx(red.astype('uint8'),cv2.MORPH_CLOSE,np.ones((3,3),np.uint8))
    count,lab,stats,centroids=cv2.connectedComponentsWithStats(red)
    red=lab==(1+np.argmax(stats[1:,cv2.CC_STAT_AREA]))
    sample=reference.at(.35 if number==0 else .60)
    mask=np.zeros(shot.shape[:2],np.uint8);mask[145:1065,70:650]=255;mask[red]=0
    keys,desc=orb.detectAndCompute(cv2.cvtColor(shot,cv2.COLOR_RGB2GRAY),mask)
    rk,rd=orb.detectAndCompute(cv2.cvtColor(sample,cv2.COLOR_RGB2GRAY),None)
    pairs=matcher.knnMatch(rd,desc,k=2);good=[m for m,n in pairs if m.distance<.72*n.distance]
    src=np.float32([rk[m.queryIdx].pt for m in good]);dst=np.float32([keys[m.trainIdx].pt for m in good])
    affine,inliers=cv2.estimateAffinePartial2D(src,dst,method=cv2.RANSAC,ransacReprojThreshold=2.)
    inverse=cv2.invertAffineTransform(affine)
    aligned=cv2.warpAffine(shot,inverse,(720,1280),flags=cv2.INTER_LINEAR)
    aligned_red=cv2.warpAffine(red.astype('uint8'),inverse,(720,1280),flags=cv2.INTER_NEAREST)>0
    valid=np.zeros((1280,720),bool);valid[250:750,100:635]=True
    valid &= cv2.dilate(aligned_red.astype('uint8'),np.ones((19,19),np.uint8))==0
    best=None
    for i,t in enumerate(reference.times):
        phase=(t-meta['reference']['start'])/(meta['reference']['end']-meta['reference']['start'])
        if not (.20<phase<.75):continue
        error=float(np.mean(np.abs(reference.frames[i].astype('float32')-aligned)[valid]))
        if best is None or error<best[0]:best=(error,i,float(phase),float(t))
    ys,xs=np.where(aligned_red)
    trace=[]
    for x in range(int(xs.min()),int(xs.max())+1):
        rows=ys[xs==x]
        if len(rows):trace.append((x,float(np.median(rows))))
    trace=np.array(trace);x0,x1=np.quantile(trace[:,0],[.03,.97]);middle=(trace[:,0]>x0+.25*(x1-x0))&(trace[:,0]<x0+.75*(x1-x0))
    y0=float(np.interp(x0,trace[:,0],trace[:,1]));y1=float(np.interp(x1,trace[:,0],trace[:,1]))
    chord=y0+(trace[:,0]-x0)/(x1-x0)*(y1-y0)
    sag=float(np.median((trace[:,1]-chord)[middle]))
    record={'phase':best[2],'source_seconds':best[3],'registration_mae':best[0],'registration_inliers':int(inliers.sum()),'affine_ref_to_shot':affine.tolist(),'trace':trace.tolist(),'left':[float(x0),y0],'right':[float(x1),y1],'sagitta_px':sag,'warning':'红线是用户标注的密度边界；端点与采样位置不是同一粒子的对应点。'}
    measurements.append(record)
    Image.fromarray(aligned).save(out/f'aligned-{number+1}.png')
    print({k:v for k,v in record.items() if k not in ['trace','affine_ref_to_shot']},flush=True)
(out/'measurements.json').write_text(json.dumps(measurements,ensure_ascii=False,indent=2),encoding='utf-8')

# 沿真实相邻视频帧的稠密光流推进红线采样；前后误差累计超限即移除。
first,last=measurements;start=int(np.argmin(abs(reference.times-first['source_seconds'])));end=int(np.argmin(abs(reference.times-last['source_seconds'])))
points=np.array(first['trace'],dtype='float32')[::3];original=points.copy();confidence=np.ones(len(points),bool)
records=[];iy,ix=np.mgrid[:1280,:720].astype('float32')
previous=cv2.cvtColor(reference.frames[start],cv2.COLOR_RGB2GRAY)
for i in range(start+2,end+1,2):
    current=cv2.cvtColor(reference.frames[i],cv2.COLOR_RGB2GRAY)
    forward=cv2.calcOpticalFlowFarneback(previous,current,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
    reverse=cv2.calcOpticalFlowFarneback(current,previous,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
    flow=cv2.remap(forward,points[:,0,None],points[:,1,None],cv2.INTER_LINEAR).reshape(-1,2)
    moved=points+flow
    back=cv2.remap(reverse,moved[:,0,None],moved[:,1,None],cv2.INTER_LINEAR).reshape(-1,2)
    error=np.linalg.norm(flow+back,axis=1)
    confidence &= error<.8
    points=moved;previous=current
    records.append({'phase':float((reference.times[i]-meta['reference']['start'])/5.2),'valid':int(confidence.sum()),'median_forward_backward_px':float(np.median(error))})
np.savez(out/'advected-redline.npz',original=original,advected=points,valid=confidence)
result={'valid_count':int(confidence.sum()),'total_count':len(points),'records':records,'method':'相隔两视频帧 Farneback；每步前后误差 < 0.8 px 累积筛选，只代表光流一致，不证明粒子身份。'}
if confidence.any():
    target=np.array(last['trace']);delta=points[confidence,None,:]-target[None,:,:]
    result['distance_to_late_rim_median_px']=float(np.median(np.sqrt((delta*delta).sum(axis=2)).min(axis=1)))
(out/'advection.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print({k:v for k,v in result.items() if k!='records'},flush=True)

# 反向追踪后期密度边界，检查新增轮廓是否来自早期尚完整的区域。
points=np.array(last['trace'],dtype='float32')[::3];late=points.copy();confidence=np.ones(len(points),bool)
previous=cv2.cvtColor(reference.frames[end],cv2.COLOR_RGB2GRAY)
for i in range(end-2,start-1,-2):
    current=cv2.cvtColor(reference.frames[i],cv2.COLOR_RGB2GRAY)
    flow=cv2.calcOpticalFlowFarneback(previous,current,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
    reverse=cv2.calcOpticalFlowFarneback(current,previous,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
    v=cv2.remap(flow,points[:,0,None],points[:,1,None],cv2.INTER_LINEAR).reshape(-1,2)
    q=points+v;back=cv2.remap(reverse,q[:,0,None],q[:,1,None],cv2.INTER_LINEAR).reshape(-1,2)
    confidence &= np.linalg.norm(v+back,axis=1)<.8
    points=q;previous=current
source=np.array(Image.open(HERE/'assets/ironman/source.png').convert('RGB'))
early=reference.frames[start]
source_at=cv2.remap(source.astype('float32'),points[:,0,None],points[:,1,None],cv2.INTER_LINEAR).reshape(-1,3)
early_at=cv2.remap(early.astype('float32'),points[:,0,None],points[:,1,None],cv2.INTER_LINEAR).reshape(-1,3)
static_error=np.sqrt(np.mean((source_at-early_at)**2,axis=1))
np.savez(out/'backtracked-late-rim.npz',late=late,early=points,valid=confidence,static_error=static_error)
im=Image.fromarray(early);d=ImageDraw.Draw(im);d.line([tuple(p) for p in first['trace']],fill=(255,91,91),width=3);d.line([tuple(p) for p in points],fill=(12,225,218),width=3)
im.crop((65,450,400,740)).resize((670,580)).save(out/'backtracked-overlay.png')
print('反向追踪',{'valid':int(confidence.sum()),'total':len(points),'static_like_count':int((confidence&(static_error<20)).sum()),'early_endpoints':points[[0,-1]].tolist()},flush=True)
