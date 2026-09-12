"""仅分析用户原录像：匹配标注截图并保存连续原帧；不改变动画模型。"""
from pathlib import Path
import json,shutil,sys
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
OUT=HERE/'analysis/recorded-filament-trace'
VIDEO=Path('E:/WeChatFiles/xwechat_files/wxid_yizrz7pph07f22_8943/temp/RWTemp/2026-09/b95204e02d0afaf2bf4fb5148d30500c/b385642279edad91efde395d7e3a2ed7.mp4')
ANNOTATION=Path('C:/Users/ywwynm/AppData/Local/Temp/codex-clipboard-8e0dc383-1b52-4593-8009-04d366ef1cf2.png')
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    shutil.copy2(VIDEO,OUT/'original.mp4');shutil.copy2(ANNOTATION,OUT/'user-annotation.png')
    cap=cv2.VideoCapture(str(VIDEO));fps=cap.get(cv2.CAP_PROP_FPS);frames=[]
    while True:
        ok,bgr=cap.read()
        if not ok:break
        frames.append(cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB))
    cap.release();frames=np.asarray(frames);np.save(OUT/'original-frames.npy',frames)
    h,w=frames.shape[1:3]
    ann=np.array(Image.open(ANNOTATION).convert('RGB'))
    small=cv2.resize(ann,(w,h),interpolation=cv2.INTER_AREA)
    # 排除用户黄色标线和视频进度条；仅比较弹窗内部的低频结构以寻找同一相位。
    mask=np.zeros((h,w),np.uint8);mask[int(h*.35):int(h*.72),int(w*.10):int(w*.91)]=1
    yellow=(small[:,:,0]>170)&(small[:,:,1]>140)&(small[:,:,2]<110)
    mask[cv2.dilate(yellow.astype('uint8'),np.ones((9,9),'uint8'))>0]=0
    target=cv2.GaussianBlur(small.astype('float32'),(0,0),1.8)
    losses=[float(np.mean(abs(cv2.GaussianBlur(f.astype('float32'),(0,0),1.8)-target)[mask>0])) for f in frames]
    order=np.argsort(losses)[:8]
    info={'fps':fps,'size':[w,h],'frame_count':len(frames),'closest_frames':[{'frame':int(i),'seconds':float(i/fps),'mae':losses[i]} for i in order]}
    (OUT/'match.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(info),flush=True)
    best=int(order[0]);Image.fromarray(frames[best]).save(OUT/'matched-original.png')
    # 将截图中黄色闭合标注映射到视频。视频上下含黑边，按宽度等比缩放。
    s=w/ann.shape[1];oy=(h-ann.shape[0]*s)*.5
    mapped=cv2.warpAffine(ann,np.array([[s,0,0],[0,s,oy]],'float32'),(w,h))
    yellow=(mapped[:,:,0]>180)&(mapped[:,:,1]>155)&(mapped[:,:,2]<100)
    targets=[]
    marked=Image.fromarray(frames[best]);markdraw=ImageDraw.Draw(marked)
    # 两条闭合标记可能因压缩成为四条不连通边；按截图明确的左右位置合并。
    xx=np.arange(w)[None,:]
    for name,region in [('A',xx<w*.62),('B',xx>=w*.62)]:
        ys,xs=np.nonzero(yellow&region);points=[]
        for yy in range(int(ys.min()),int(ys.max())+1):
            xx=xs[ys==yy]
            if len(xx):points.append([float((xx.min()+xx.max())*.5),float(yy)])
        targets.append({'name':name,'centerline':points,'bbox':[int(xs.min()),int(ys.min()),int(xs.max()-xs.min()+1),int(ys.max()-ys.min()+1)]})
    targets.sort(key=lambda r:r['name']);(OUT/'annotation-targets.json').write_text(json.dumps(targets,indent=2),'utf-8')
    # 线条仅绘在独立标注栏；左栏保留原像素。
    for row,color in zip(targets,[(255,187,55),(70,219,234)]):
        pts=np.array(row['centerline']);left=pts.copy();right=pts.copy();left[:,0]-=7;right[:,0]+=7
        markdraw.line([tuple(x) for x in left],fill=color,width=2)
        markdraw.line([tuple(x) for x in right],fill=color,width=2)
    pair=Image.new('RGB',(w*2,h));pair.paste(Image.fromarray(frames[best]),(0,0));pair.paste(marked,(w,0));pair.save(OUT/'target-location.png')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    ids=list(range(0,len(frames),4));cw=192;ch=round(h*cw/w);cols=5
    sheet=Image.new('RGB',(cw*cols,(ch+25)*int(np.ceil(len(ids)/cols))),'#101620');d=ImageDraw.Draw(sheet)
    for k,i in enumerate(ids):
        x=(k%cols)*cw;y=(k//cols)*(ch+25)
        sheet.paste(Image.fromarray(frames[i]).resize((cw,ch)),(x,y+25));d.text((x+3,y+2),f'{i:02d} · {i/fps:.3f}s',font=font,fill='white')
    sheet.save(OUT/'whole-sequence.jpg',quality=95)
    for i in range(max(0,best-12),min(len(frames),best+21)):
        Image.fromarray(frames[i]).save(OUT/f'frame-{i:03d}.png')
if __name__=='__main__':main()
