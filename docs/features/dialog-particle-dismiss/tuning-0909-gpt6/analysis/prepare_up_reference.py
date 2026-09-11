"""把手持参考对齐到固定控件平面；不将相机运动用作粒子速度。"""
from pathlib import Path
import sys,json,hashlib
import cv2,numpy as np
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1]
OUT=HERE/'analysis/targeted-release';VIDEO=OUT/'huawei-ironman-up.mp4'

def main():
    cap=cv2.VideoCapture(str(VIDEO))
    def frame(t):
        cap.set(cv2.CAP_PROP_POS_MSEC,t*1000);ok,a=cap.read();assert ok
        return a
    template=frame(4.20);gray=cv2.cvtColor(template,cv2.COLOR_BGR2GRAY)
    mask=np.zeros(gray.shape,np.uint8);mask[232:980,120:568]=255;mask[290:755,140:560]=0
    sift=cv2.SIFT_create(nfeatures=3000);kp0,des0=sift.detectAndCompute(gray,mask)
    matcher=cv2.BFMatcher();stats=[]
    # 控件四角（原片像素）；透视变换只用于取材和比较，模型接收普通矩形快照。
    source=np.float32([[151,334],[519,310],[531,691],[172,695]])
    rect=[126,261,594,735];destination=np.float32([[126,261],[594,261],[594,735],[126,735]])
    plane=cv2.getPerspectiveTransform(source,destination)
    def aligned(t):
        a=frame(t);g=cv2.cvtColor(a,cv2.COLOR_BGR2GRAY);kp,des=sift.detectAndCompute(g,None)
        pairs=matcher.knnMatch(des0,des,k=2);good=[m for m,n in pairs if m.distance<.72*n.distance]
        assert len(good)>=10,(t,len(good))
        q0=np.float32([kp0[m.queryIdx].pt for m in good]);q1=np.float32([kp[m.trainIdx].pt for m in good])
        h,inliers=cv2.findHomography(q1,q0,cv2.RANSAC,2.5)
        assert h is not None and inliers.sum()>=8,(t,len(good))
        err=np.linalg.norm(cv2.perspectiveTransform(q1[:,None],h)[:,0]-q0,axis=1)
        stats.append(dict(time=t,matches=len(good),inliers=int(inliers.sum()),median_error=float(np.median(err[inliers[:,0]>0]))))
        return cv2.cvtColor(cv2.warpPerspective(a,plane@h,(720,1280),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT),cv2.COLOR_BGR2RGB)
    directory=HERE/'assets/ironman-up-reference';directory.mkdir(exist_ok=True)
    bottom=aligned(4.20);top=aligned(4.033333);background=aligned(6.60)
    # 关闭确认框与粒子动画相互重叠，没有单帧完整源图。上半取较早帧，下半取确认框消失后的帧。
    fg=bottom[261:735,126:594].copy();early=top[261:735,126:594]
    weight=np.clip((np.arange(474)-205)/32,0,1)[:,None,None]
    fg=np.uint8(np.round(early*(1-weight)+fg*weight))
    alpha=Image.new('L',(468,474));ImageDraw.Draw(alpha).rounded_rectangle((0,0,467,473),radius=21,fill=255)
    rgba=np.dstack([fg,np.array(alpha)]);Image.fromarray(rgba).save(directory/'foreground.png')
    source=background.copy();region=source[261:735,126:594];a=np.array(alpha)[:,:,None]/255
    region[:]=np.uint8(np.round(fg*a+region*(1-a)))
    Image.fromarray(source).save(directory/'source.png');Image.fromarray(background).save(directory/'background.png')
    times=np.arange(124,197)/30
    frames=np.stack([aligned(float(t)) for t in times]);np.save(directory/'reference.npy',frames);np.save(directory/'reference-times.npy',times)
    meta=dict(name='ironman-up-reference',frame=[720,1280],rect=rect,radius=21,duration=1.,seed=2,
        title='钢铁侠 · 新增向上参考',direction=90,reference=dict(path=str(VIDEO.resolve()),start=124/30,end=6.466666667,
        sha256=hashlib.sha256(VIDEO.read_bytes()).hexdigest()),background_note='手持参考已透视对齐；源控件由前后两帧合成，背景取动画结束帧',
        background_truth=True,dim_alpha=0.,research_input=True)
    (directory/'scene.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),'utf-8')
    path=HERE/'assets/scenes.json';metas=json.loads(path.read_text('utf-8'))
    metas=[m for m in metas if m['name']!=meta['name']];metas.insert(1,meta);path.write_text(json.dumps(metas,ensure_ascii=False,indent=2),'utf-8')
    (OUT/'reference-registration.json').write_text(json.dumps(dict(frames=stats,source_note=meta['background_note']),indent=2),'utf-8')
    im=Image.new('RGB',(1080,900),'#0e141e');draw=ImageDraw.Draw(im)
    for i,t in enumerate([4.20,4.40,4.65,4.95,5.3,5.8]):
        arr=aligned(t);im.paste(Image.fromarray(arr[150:1050]).resize((360,450)),((i%3)*360,(i//3)*450))
    im.save(OUT/'reference-stabilized.jpg',quality=95)
    print('参考取材完成',len(times),'帧；配准误差中位数',np.median([s['median_error'] for s in stats]))

if __name__=='__main__':main()
