"""纠正留出通知的背景配准，保持前景、材质输入和冻结模型不变。"""
from pathlib import Path
import sys,json
import cv2,numpy as np
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import ROOT,model_fingerprint
out=HERE/'analysis/unified-validation';frozen=json.loads((out/'freeze.json').read_text('utf-8'))
assert model_fingerprint()==frozen['model_hash']
folder=HERE/'assets/holdout-notification';m=json.loads((folder/'scene.json').read_text('utf-8'))
source=cv2.imread(str(out/'ref3-4.00.png'))
cap=cv2.VideoCapture(str(next((ROOT/'tmp/particle-dismiss-tuning/ref-videos-all').glob('3-*.mp4'))))
cap.set(cv2.CAP_PROP_POS_MSEC,5550);ok,end=cap.read();cap.release();assert ok
mask=np.zeros(source.shape[:2],np.uint8);x,y,x1,y1=m['rect']
mask[y:y1,x:x1]=np.array(Image.open(folder/'foreground.png'))[:,:,3]
outside=cv2.bitwise_not(cv2.dilate(mask,np.ones((45,45),np.uint8)))
orb=cv2.ORB_create(6000)
k1,d1=orb.detectAndCompute(cv2.cvtColor(end,cv2.COLOR_BGR2GRAY),outside)
k2,d2=orb.detectAndCompute(cv2.cvtColor(source,cv2.COLOR_BGR2GRAY),outside)
matches=cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(d1,d2,k=2)
good=[a for a,b in matches if a.distance<.78*b.distance and np.linalg.norm(np.array(k1[a.queryIdx].pt)-k2[a.trainIdx].pt)<60]
a,inliers=cv2.estimateAffinePartial2D(np.float32([k1[g.queryIdx].pt for g in good]),np.float32([k2[g.trainIdx].pt for g in good]),method=cv2.RANSAC,ransacReprojThreshold=2.5)
assert a is not None and inliers.sum()>20
scale=np.hypot(a[0,0],a[1,0]);assert .9<scale<1.1 and np.max(np.abs(a[:,2]))<60,a
aligned=cv2.warpAffine(end,a,(source.shape[1],source.shape[0]),borderMode=cv2.BORDER_REFLECT)
# 源帧通知背后保持模糊；后续无通知桌面只提供被遮挡内容的估计。
aligned=cv2.GaussianBlur(aligned,(0,0),9)
bg=source.copy();bg[mask>0]=aligned[mask>0]
assert np.mean(bg[mask>0])>30 and np.mean(np.max(bg[mask>0],axis=1)==0)<.001
Image.fromarray(cv2.cvtColor(bg,cv2.COLOR_BGR2RGB)).save(folder/'background.png')
# 静态左栏始终使用实际源帧，避免把重建结果描述为真机截图。
Image.fromarray(cv2.cvtColor(source,cv2.COLOR_BGR2RGB)).save(folder/'source.png')
note='第三段参考 4.00 秒通知快照；未用于本轮调参。遮挡背景由 5.55 秒无通知帧估计仿射配准并模糊重建，不代表真实隐藏内容；左栏为实际源帧。'
m['background_note']=m['provenance']=note
(folder/'scene.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
allpath=HERE/'assets/scenes.json';items=json.loads(allpath.read_text('utf-8'))
items=[m if v['name']==m['name'] else v for v in items];allpath.write_text(json.dumps(items,ensure_ascii=False,indent=2),encoding='utf-8')
(out/'background-correction.json').write_text(json.dumps({'model_hash':model_fingerprint(),'affine':a.tolist(),'inliers':int(inliers.sum()),'note':note},ensure_ascii=False,indent=2),encoding='utf-8')
print('仅修复背景素材，模型保持',model_fingerprint())
