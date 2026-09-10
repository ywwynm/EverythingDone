"""参数冻结后才生成独立材质压力输入；不修改模型或参数。"""
from pathlib import Path
import sys,json,hashlib
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from skimage import data
from unified_model import model_fingerprint,ROOT
from renderer import HERE

record=HERE/'analysis/unified-validation';frozen=json.loads((record/'freeze.json').read_text('utf-8'))
assert model_fingerprint()==frozen['model_hash']
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
cases=[]

def save(name,title,fg,bg,rect,direction,note):
    folder=HERE/'assets'/name;folder.mkdir(exist_ok=True)
    x,y,x1,y1=rect;source=bg.copy();source.paste(fg.convert('RGB'),(x,y),fg.getchannel('A'))
    fg.save(folder/'foreground.png');bg.save(folder/'background.png');source.save(folder/'source.png')
    m=dict(name=name,title=title,frame=list(bg.size),rect=rect,radius=0,duration=1.,seed=78192745,
           direction=direction,dim_alpha=0.,reference=None,background_truth=False,background_note=note,holdout=True,
           provenance=note,frozen_model_hash=frozen['model_hash'])
    (folder/'scene.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
    cases.append(m)

def background(w=720,h=1000):
    yy,xx=np.mgrid[:h,:w];a=xx/w*.65+yy/h*.35
    start=np.array([21,40,65]);end=np.array([78,119,128])
    return Image.fromarray((start+(end-start)*a[...,None]).astype('uint8'))

def panel(w,h,dark=False):
    im=Image.new('RGBA',(w,h),(0,0,0,0));d=ImageDraw.Draw(im)
    d.rounded_rectangle((0,0,w-1,h-1),radius=28,fill=(27,31,38,255) if dark else (255,255,255,255))
    return im

# 全新的自然照片：模型冻结后才载入，既非三张人物图的裁剪，也非它们的换色。
fg=panel(520,550);fg.paste(Image.fromarray(data.astronaut()).resize((480,480)),(20,20))
ImageDraw.Draw(fg).text((22,511),'独立照片 · 未参与调参',font=small,fill=(28,48,76,255))
save('holdout-photo','留出验证 · 独立照片',fg,background(),[100,200,620,750],32,'scikit-image 本地 astronaut 照片；冻结后首次用于该模型，背景为构造渐变。')

fg=panel(496,600,True);d=ImageDraw.Draw(fg)
d.text((30,34),'深色通知',font=font,fill=(121,209,225,255))
for i in range(7):
    y=100+i*58;d.ellipse((30,y,54,y+24),fill=(104+i*18,135,226,255));d.text((74,y),f'新消息 {i+1} · 预览内容',font=small,fill=(225,230,238,255))
save('holdout-dark','留出验证 · 深色密集文字',fg,background(),[112,170,608,770],205,'冻结后新构造的深色文字与彩色图标面板。')
fg=panel(640,176);d=ImageDraw.Draw(fg);d.text((26,30),'文件已经准备好',font=font,fill=(40,68,88,255))
for i in range(4):d.rounded_rectangle((26+i*152,92,164+i*152,144),radius=14,fill=(34,124+i*20,181,255))
save('holdout-wide','留出验证 · 宽面板',fg,background(),[40,390,680,566],0,'冻结后新构造的宽高比 3.64 面板。')
fg=panel(230,784);d=ImageDraw.Draw(fg);d.text((22,32),'事项列表',font=font,fill=(63,95,128,255))
for i in range(10):
    y=104+i*60;d.rounded_rectangle((20,y,45,y+25),radius=5,outline=(70,132,199,255),width=2);d.text((62,y),f'待办 {i+1}',font=small,fill=(65,68,78,255))
save('holdout-tall','留出验证 · 窄长列表',fg,background(),[245,108,475,892],90,'冻结后新构造的窄长面板。')
fg=panel(560,440);arr=np.array(fg);yy,xx=np.mgrid[:440,:560]
arr[:,:,:3]=np.stack((80+xx*.2,145+yy*.18,np.full_like(xx,215)),axis=-1).astype('uint8')
arr[:,:,3]=(arr[:,:,3].astype(float)*(.28+.72*xx/559)).astype('uint8');arr[(xx-180)**2+(yy-210)**2<72**2,3]=0
fg=Image.fromarray(arr);d=ImageDraw.Draw(fg);d.text((40,42),'透明渐变与镂空',font=font,fill=(255,255,255,245))
save('holdout-alpha','留出验证 · 透明渐变与镂空',fg,background(),[80,265,640,705],315,'冻结后新构造的透明度梯度和圆形镂空，不使用纯白矩形假设。')

# 真实长通知的分层只决定输入素材，不参与模型调参。
source=cv2.imread(str(record/'ref3-4.00.png'));source=cv2.cvtColor(source,cv2.COLOR_BGR2RGB)
mask=np.zeros(source.shape[:2],dtype='uint8');cv2.fillConvexPoly(mask,np.array([[105,360],[580,378],[557,885],[86,865]]),255)
mask=cv2.GaussianBlur(mask,(3,3),.65)
cap=cv2.VideoCapture(str(next((ROOT/'tmp/particle-dismiss-tuning/ref-videos-all').glob('3-*.mp4'))));cap.set(cv2.CAP_PROP_POS_MSEC,5800);ok,end=cap.read();cap.release();assert ok
end=cv2.cvtColor(end,cv2.COLOR_BGR2RGB)
# 估计拍屏相机变化，背景只替换通知遮挡区；可见外侧始终保持源帧。
orb=cv2.ORB_create(3000);outside=cv2.bitwise_not(cv2.dilate(mask,np.ones((25,25),np.uint8)))
k1,v1=orb.detectAndCompute(cv2.cvtColor(end,cv2.COLOR_RGB2GRAY),None);k2,v2=orb.detectAndCompute(cv2.cvtColor(source,cv2.COLOR_RGB2GRAY),outside)
matches=cv2.BFMatcher(cv2.NORM_HAMMING).knnMatch(v1,v2,k=2);good=[m for m,n in matches if m.distance<.70*n.distance]
h,_=cv2.findHomography(np.float32([k1[m.queryIdx].pt for m in good]),np.float32([k2[m.trainIdx].pt for m in good]),cv2.RANSAC,3.)
aligned=cv2.warpPerspective(end,h,(720,1280));bg=source.copy();bg[mask>0]=aligned[mask>0]
rect=[84,358,582,888];x,y,x1,y1=rect;fg=Image.fromarray(np.dstack((source[y:y1,x:x1],mask[y:y1,x:x1])))
save('holdout-notification','留出验证 · 真实长通知',fg,Image.fromarray(bg),rect,145,'第三段参考 4.00 秒通知快照；未用于本轮调参。遮挡背景用后续帧配准重建，拍屏透视和重建误差保留；对照列为静态源图。')

p=HERE/'assets/scenes.json';original=json.loads(p.read_text('utf-8'));assert not any(m.get('holdout') for m in original)
p.write_text(json.dumps(original+cases,ensure_ascii=False,indent=2),encoding='utf-8')
report={'frozen_model_hash':model_fingerprint(),'cases':cases,'strictly_new_materials':5,'previously_reviewed_but_not_tuned_this_round':1}
assert report['frozen_model_hash']==frozen['model_hash']
(record/'holdouts.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('留出素材准备完成；模型指纹保持',report['frozen_model_hash'])
