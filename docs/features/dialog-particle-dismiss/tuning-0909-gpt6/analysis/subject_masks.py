"""各素材源控件的主体显著度（BiRefNet_lite，本机已有权重），只用于离线检验“主体是否比背景晚解除”的假设。

推理契约与空间照片特性一致：方形 1024²、ImageNet 归一化、输出 logits 过 sigmoid（见 spatial-photo-effect 文档）。
结果写到 analysis/match-cache/subject/<素材>.png（8 位，控件尺寸）。不进入运行模型。
"""
from pathlib import Path
import sys
import numpy as np,cv2
from PIL import Image

HERE=Path(__file__).resolve().parents[1]
ROOT=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
MODEL=ROOT/'tmp/birefnet-lite/onnx/model.onnx'
OUT=HERE/'analysis/match-cache/subject'
NAMES=['ironman','thanos','kobe','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in range(1,8)]+['attachment','color','language']


def main():
    import onnxruntime as ort
    names=sys.argv[1:] or NAMES
    sess=ort.InferenceSession(str(MODEL),providers=['CPUExecutionProvider']);inp=sess.get_inputs()[0].name
    mean=np.array([.485,.456,.406],np.float32);std=np.array([.229,.224,.225],np.float32)
    OUT.mkdir(parents=True,exist_ok=True)
    for n in names:
        fg=np.array(Image.open(HERE/'assets'/n/'foreground.png').convert('RGBA'))
        rgb=fg[...,:3].astype(np.float32)/255.
        h,w=rgb.shape[:2]
        t=((cv2.resize(rgb,(1024,1024),interpolation=cv2.INTER_AREA)-mean)/std).transpose(2,0,1)[None].astype(np.float32)
        logits=sess.run(None,{inp:t})[-1].reshape(1024,1024)
        a=1/(1+np.exp(-logits));a=cv2.resize(a,(w,h),interpolation=cv2.INTER_LINEAR)*(fg[...,3]>8)
        Image.fromarray(np.uint8(np.clip(a,0,1)*255)).save(OUT/f'{n}.png')
        print(n,f'主体占比 {float((a>.5).mean()):.2f}',flush=True)


if __name__=='__main__':main()
