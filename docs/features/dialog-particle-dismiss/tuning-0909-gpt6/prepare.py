"""准备独立桌面场景和参考时间轴。所有文件都写入本轮目录。"""
from pathlib import Path
import json, hashlib
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
ASSETS=HERE/'assets'
ANALYSIS=HERE/'analysis'
REF=ROOT/'tmp/particle-dismiss-tuning/ref-videos-all'
OLD=HERE/'inputs/imported'
DEVICE=HERE/'inputs/device'
FONT='C:/Windows/Fonts/msyh.ttc'

SPECS={
 'ironman':dict(title='钢铁侠',video=1,start=3.25,end=8.45,slow=3.42,rect=[126,261,594,735],radius=29,direction=122,
     origins=[[.61,1.02,0],[0,0,.20],[1,.75,.45]]),
 'thanos':dict(title='灭霸',video=7,start=5.4,end=7.95,slow=2.0,rect=[40,154,680,828],radius=39,direction=130,
     origins=[[1.0,.25,0],[0,0,.12],[1.,.95,.24]]),
 'kobe':dict(title='科比',video=5,start=5.2,end=6.25,slow=1.0,rect=[195,199,648,656],radius=27,direction=128,
     origins=[[0,.43,0],[.82,0,.36],[1,1,.66]])
}

def rgb(path): return np.array(Image.open(path).convert('RGB'))

def rounded_alpha(w,h,r):
    y,x=np.mgrid[:h,:w].astype(np.float32)+.5
    return np.clip(.5-(np.hypot(x-np.clip(x,r,w-r),y-np.clip(y,r,h-r))-r),0,1)

def save(name,source,bg,rect,radius,extra):
    out=ASSETS/name;out.mkdir(parents=True,exist_ok=True)
    x0,y0,x1,y1=rect; h,w=source.shape[:2]
    a=rounded_alpha(x1-x0,y1-y0,radius)
    fg=np.dstack((source[y0:y1,x0:x1],np.round(a*255).astype('uint8')))
    Image.fromarray(fg).save(out/'foreground.png')
    Image.fromarray(bg).save(out/'background.png')
    Image.fromarray(source).save(out/'source.png')
    meta=dict(name=name,frame=[w,h],rect=rect,radius=radius,duration=1.0,seed=909602,**extra)
    (out/'scene.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
    return meta

def clean_thanos(bg):
    # 尾部残留集中在左侧和顶部；从原背景的邻域插值，保留其余真实像素。
    mask=np.zeros(bg.shape[:2],np.uint8)
    mask[94:180,90:290]=255
    mask[230:590,:95]=255
    fixed=cv2.medianBlur(bg,15)
    sm=cv2.GaussianBlur(fixed,(0,0),3)
    weight=cv2.GaussianBlur((mask/255.).astype('float32'),(0,0),3)[...,None]
    return np.uint8(np.clip(bg*(1-weight)+sm*weight,0,255))

def reference_scene(name,spec):
    source=rgb(OLD/f'{name}-before.png');bg=rgb(OLD/f'{name}-background.png')
    if name=='thanos':
        # 1.0 秒处的卡片完整，尚未出现长按菜单和退出确认框。
        source=rgb(ANALYSIS/'thanos-clean-candidate-1.0.png')
        bg=clean_thanos(bg)
    file=next(REF.glob(str(spec['video'])+'-*.mp4'))
    meta=save(name,source,bg,spec['rect'],spec['radius'],dict(
        title=spec['title'],direction=spec['direction'],origins=spec['origins'],reference={
        'path':str(file),'start':spec['start'],'end':spec['end'],'slow_estimate':spec['slow'],
        'sha256':hashlib.sha256(file.read_bytes()).hexdigest()},
        background_note='原片背景；左侧与顶部少量残留粒子已局部插值清理' if name=='thanos' else '原片背景',
        dim_alpha=0.,background_truth=True))
    cap=cv2.VideoCapture(str(file));fps=cap.get(cv2.CAP_PROP_FPS)
    frame_id=0;frames=[];times=[]
    while True:
        ok,im=cap.read()
        if not ok:break
        t=frame_id/fps;frame_id+=1
        if spec['start']-.10<=t<=spec['end']+.01:
            frames.append(cv2.cvtColor(im,cv2.COLOR_BGR2RGB));times.append(t)
        if t>spec['end']+.02:break
    cap.release()
    np.save(ASSETS/name/'reference.npy',np.array(frames,dtype=np.uint8))
    np.save(ASSETS/name/'reference-times.npy',np.array(times))
    print(name,len(frames),'参考帧',flush=True)
    return meta

def fill_hidden(shot,rect):
    x0,y0,x1,y1=rect
    mask=np.zeros(shot.shape[:2],np.uint8);mask[max(0,y0-12):min(shot.shape[0],y1+14),max(0,x0-12):min(shot.shape[1],x1+12)]=255
    small=cv2.resize(shot,None,fx=.25,fy=.25,interpolation=cv2.INTER_AREA)
    ms=cv2.resize(mask,(small.shape[1],small.shape[0]),interpolation=cv2.INTER_NEAREST)
    filled=cv2.inpaint(small,ms,4,cv2.INPAINT_TELEA)
    filled=cv2.GaussianBlur(filled,(0,0),2.5)
    filled=cv2.resize(filled,(shot.shape[1],shot.shape[0]),interpolation=cv2.INTER_CUBIC)
    out=shot.copy();out[mask>0]=filled[mask>0]
    return out

def shot_scene(name,title,path,native_rect,native_radius,background=None):
    source=rgb(path);scale=720/source.shape[1]
    h=round(source.shape[0]*scale);h+=h%2
    source=cv2.resize(source,(720,h),interpolation=cv2.INTER_AREA)
    rect=[round(v*scale) for v in native_rect]
    if background:
        bg=cv2.resize(rgb(background),(720,h),interpolation=cv2.INTER_AREA)
        # 保留截图可见区域，包括状态栏、壁纸和已存在的遮罩，背景区域沿用实拍配对。
        note='同页无弹窗真机截图'
    else:
        # 无真实无遮挡照片，保留压暗状态以免推测不同系统的遮罩和色彩空间。
        bg=fill_hidden(source,rect);note='保留可见背景；弹窗遮挡区域为插值重建'
    # 在有实拍背景的版本中将遮罩嵌入基底；遮罩退场由 renderer 单独控制。
    return save(name,source,bg,rect,round(native_radius*scale),dict(title=title,direction=65,
        origins=None,reference=None,background_note=note,background_truth=bool(background),
        dim_alpha=.596 if background else 0.,source_path=str(path)))

def contact(metas):
    W=210;H=510
    out=Image.new('RGB',(W*len(metas),H),(18,23,31));d=ImageDraw.Draw(out);f=ImageFont.truetype(FONT,17)
    for i,m in enumerate(metas):
        bg=rgb(ASSETS/m['name']/'background.png').astype('float32')
        if m['dim_alpha']:bg*=1-m['dim_alpha']
        fg=np.array(Image.open(ASSETS/m['name']/'foreground.png'))/255
        x,y,x1,y1=m['rect'];a=fg[...,3:];bg[y:y1,x:x1]=fg[...,:3]*255*a+bg[y:y1,x:x1]*(1-a)
        im=Image.fromarray(bg.astype('uint8'));im.thumbnail((W-10,H-64))
        out.paste(im,(i*W+(W-im.width)//2,38));d.text((i*W+9,8),m['title'],font=f,fill='white')
    out.save(ANALYSIS/'scenes-prepared.jpg',quality=94)

def main():
    ASSETS.mkdir(exist_ok=True);ANALYSIS.mkdir(exist_ok=True)
    metas=[reference_scene(n,s) for n,s in SPECS.items()]
    metas+=[
      shot_scene('language','更改应用语言',HERE/'inputs/language.jpg',[160,726,1280,2537],69),
      shot_scene('color','调整颜色',HERE/'inputs/color.jpg',[28,612,1251,2730],57),
      shot_scene('attachment','添加附件 · 纯色背景',DEVICE/'dialog.png',[195,1055,1245,2102],60,DEVICE/'detail.png'),
      shot_scene('attachment-image','添加附件 · 图片背景',DEVICE/'latest-dialog-before.png',[195,1055,1245,2102],60)]
    contact(metas)
    (ASSETS/'scenes.json').write_text(json.dumps(metas,ensure_ascii=False,indent=2),encoding='utf-8')
    print('已准备',len(metas),'个场景',flush=True)

if __name__=='__main__':main()
