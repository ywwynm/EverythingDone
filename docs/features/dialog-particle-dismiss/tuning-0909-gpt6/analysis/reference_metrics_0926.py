"""只在有效的参考像素和时间范围内计量；重建背景不能当作已知真值。"""
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

HERE=Path(__file__).resolve().parents[1]


def valid_pixels(meta):
    w,h=meta['frame'];mask=np.ones((h,w),bool)
    path=HERE/'assets'/meta['name']/'background-valid.png'
    if path.exists():
        mask&=np.array(Image.open(path).convert('L'))>250
    elif not meta.get('background_truth',False):
        # 背景无法观测的输入只给观看用原始差图，不列入像素准确率。
        mask[:]=False
    if meta['name']=='reference-0926-4':
        # 拍摄者手指进入画面，且与动画无关；这是测量遮罩，不输入渲染器。
        mask[600:,:180]=False
    return mask


def difference(truth,model,meta,phase,pad=.20):
    delta=np.abs(truth.astype('float32')-model.astype('float32'))
    soft=np.abs(cv2.GaussianBlur(truth.astype('float32'),(0,0),6)-
                cv2.GaussianBlur(model.astype('float32'),(0,0),6))
    x,y,x1,y1=meta['rect'];s=min(x1-x,y1-y);margin=round(s*pad)
    roi=np.zeros(delta.shape[:2],bool)
    roi[max(0,y-margin):min(len(roi),y1+margin),max(0,x-margin):min(roi.shape[1],x1+margin)]=True
    mask=valid_pixels(meta)&roi
    valid=phase<=(meta.get('reference') or {}).get('valid_progress',1)
    measured=valid and bool(mask.any())
    return dict(phase=phase,valid=valid,measured_pixels=int(mask.sum()) if measured else 0,
                rgb_mae=float(delta[mask].mean()) if measured else None,
                structure_mae=float(soft[mask].mean()) if measured else None,
                raw_visual_rgb_mae=float(delta[roi].mean()),
                note='有效动画区域，RGB 取值 0–255' if measured else '背景无真值或参考区间结束，不计准确率')
