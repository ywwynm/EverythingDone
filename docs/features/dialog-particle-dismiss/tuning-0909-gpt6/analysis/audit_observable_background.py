"""检查不完整原片中可直接观测的控件外背景；不改动资产和正式评分。

以动画前的配准原帧构建观测分布，保守排除控件、状态栏及不稳定区域。
仅当现有预览背景也落在观测区间内时，标记为可比较区域。
"""
from pathlib import Path
import json,sys
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from match_refs import RefFrames
from match_observe import SCREEN

def main():
    out=HERE/'analysis/match-cache/observable-background';out.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    for name in ['reference-0926-2','reference-0926-3']:
        ref=RefFrames(name);meta=ref.meta;rect=meta['rect'];x,y,x1,y1=rect
        indices=np.flatnonzero(ref.progress<-.005)
        frames=np.asarray(ref.frames[indices],np.float32)
        median=np.median(frames,axis=0)
        deviation=np.quantile(abs(frames-median).max(-1),.9,axis=0)
        lo=frames.min(0);hi=frames.max(0)
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png'),np.float32)
        outside=np.ones(median.shape[:2],np.uint8)
        outside[max(0,y-18):y1+18,max(0,x-18):x1+18]=0
        sx,sy,sx1,sy1=SCREEN[name];screen=np.zeros_like(outside)
        screen[max(sy,150):min(sy1,y1+220),sx+10:sx1-10]=1
        error=np.maximum(np.maximum(bg-hi,lo-bg),0).max(-1)
        stable=(deviation<8)&(error<6)&(outside>0)&(screen>0)
        # 在任何平滑/颗粒核附近也不能混入不确定控件区域。
        stable=cv2.erode(stable.astype(np.uint8),np.ones((9,9),np.uint8)).astype(bool)
        Image.fromarray(stable.astype(np.uint8)*255).save(out/f'{name}-valid.png')
        Image.fromarray(median.astype(np.uint8)).save(out/f'{name}-observed.png')
        overlay=median.copy();overlay[~stable]*=.27
        delta=cv2.GaussianBlur(np.asarray(ref.nearest(.58)[1],np.float32),(0,0),4)-cv2.GaussianBlur(median,(0,0),4)
        changed=stable&(abs(delta).max(-1)>10)
        tile=Image.new('RGB',(1080,640),(14,20,30));d=ImageDraw.Draw(tile)
        for j,(title,f) in enumerate([('动画前的原片观测',median),('保守有效区（亮色）',overlay),('原片进度 0.58',ref.nearest(.58)[1])]):
            tile.paste(Image.fromarray(np.uint8(np.clip(f,0,255))).resize((360,610)),(j*360,30))
            d.text((j*360+4,5),title,font=font,fill='white')
        tile.save(out/f'{name}.jpg',quality=94)
        report[name]=dict(pre_frame_count=len(indices),valid_pixels=int(stable.sum()),
            changed_pixels_at_058=int(changed.sum()),median_preview_error=float(error[stable].mean()) if stable.any() else None,
            scope='只包含动画前可见的控件外背景；不是完整画面真值，资产与正式评分未改动')
        print(name,report[name],flush=True)
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
