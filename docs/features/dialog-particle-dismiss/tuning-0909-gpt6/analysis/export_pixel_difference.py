"""原画面、原始像素差与低频结构差同时保留；不作图像配准或时间形变。"""
from pathlib import Path
import sys,json
import cv2,numpy as np
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import OUT,PRE,POST,BG,MUTED,Reference,load_meta,make_cache,sampled,encode,label,footer

def export_pixel_difference(ctx):
    meta=load_meta('ironman');ref=Reference(meta);current=make_cache(ctx,'ironman');items=[]
    def heat(error):
        return cv2.cvtColor(cv2.applyColorMap(np.rint(np.clip(error*3,0,255)).astype('uint8'),cv2.COLORMAP_INFERNO),cv2.COLOR_BGR2RGB)
    for rate in [1.,.5]:
        def frame(t,rate=rate):
            p=float(np.clip(t-PRE,0,1));a=ref.at(p);b=sampled(current,p)
            raw=abs(a.astype('float32')-b.astype('float32')).mean(2)
            low=abs(cv2.GaussianBlur(a.astype('float32'),(0,0),6)-cv2.GaussianBlur(b.astype('float32'),(0,0),6)).mean(2)
            width=360;height=640;im=Image.new('RGB',(width*4,height+146),BG);d=ImageDraw.Draw(im)
            for col,(title,pixels) in enumerate([('华为参考',a),('共同模型',b),('原始 RGB 差异 ×3',heat(raw)),('结构差异 ×3（σ=6）',heat(low))]):
                label(d,(col*width+10,10),title,22)
                label(d,(col*width+10,47),f'进度 {p:.3f} · {rate:g} 倍速',18,MUTED)
                im.paste(Image.fromarray(pixels).resize((width,height),Image.Resampling.LANCZOS),(col*width,80))
            roi=(slice(150,930),slice(35,685))
            footer(im,f'亮处表示误差大 · 完整动画区 RGB MAE {raw[roi].mean():.2f}/255，结构 MAE {low[roi].mean():.2f}/255 · 水印、时钟差异保留显示',p)
            return np.asarray(im)
        item=encode(OUT/f'ironman-pixel-difference-{rate:g}x.mp4',PRE+1+POST,rate,frame)
        item.update(scene='ironman',title='钢铁侠',kind='pixel-difference');items.append(item)
    return items
