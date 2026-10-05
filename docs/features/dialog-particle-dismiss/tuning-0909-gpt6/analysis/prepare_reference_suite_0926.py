"""测量输入的取材与配准；这些标注不进入粒子运动规则。"""
from pathlib import Path
import argparse
import hashlib
import json
import sys
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from reference_observations_0926 import OBSERVED_DIRECTIONS,OBSERVED_SITES

HERE = Path(__file__).resolve().parents[1]
ROOT = next(p for p in HERE.parents if (p / 'gradlew.bat').exists())
OUT = HERE / 'analysis/new-references-0926'

# quad 为模板帧上控件的四角，phone 为配准时可以使用的手机区域。
# 时间、几何和遮挡有效性属于测量数据；没有每段专属的运动系数。
SPECS = [
    dict(number=1, title='白衣人物', template=7.133333, source=7.133333,
         start=7.15, end=9.10, background=9.50,
         quad=[[158,384],[501,388],[495,723],[163,725]], phone=[120,308,536,984]),
    dict(number=2, title='钢铁侠 · 内部起点', template=7.183333, source=1.0,
         start=6.85, end=14.95, valid_end=14.1, background=14.1,
         quad=[[207,371],[509,357],[514,662],[207,667]], phone=[177,300,550,925],
         incomplete=True),
    dict(number=3, title='蜘蛛侠 · 红色手套', template=7.50, source=.62,
         start=7.333333, end=9.15, valid_end=8.70, background=8.70,
         quad=[[170,451],[539,453],[536,850],[167,850]], phone=[141,241,559,1184],
         incomplete=True),
    dict(number=4, title='钢铁侠 · 雪山背景', template=9.466667, source=1.0,
         start=9.10, end=13.55, background=14.1,
         quad=[[171,208],[644,236],[622,703],[140,672]], phone=[89,120,689,1092]),
    dict(number=5, title='钢铁侠 · 浅色背景', template=0.1, source=0.1,
         start=3.70, end=8.40, background=8.60,
         rect=[88,90,632,638], static=True),
    dict(number=6, title='钢铁侠 · 地球背景', template=6.233333, source=1.0,
         start=6.233333, end=12.10, background=12.40,
         rect=[42,116,680,778], static=True),
    dict(number=7, title='蜘蛛侠 · 电影画面', template=4.50, source=4.60,
         start=4.70, end=6.983333, background=7.35,
         quad=[[110,393],[525,400],[516,807],[111,804]], phone=[67,312,565,1123]),
    dict(number=8, title='蜘蛛侠 · 旧参考', template=.60, source=.60,
         start=3.10, end=8.45, background=8.55,
         rect=[37,126,551,684], static=True, old=True),
]


class Registration:
    def __init__(self, path, spec):
        self.spec = spec
        self.cap = cv2.VideoCapture(str(path))
        self.fps = self.cap.get(cv2.CAP_PROP_FPS)
        self.template = self.read(spec['template'])
        self.stats = []
        self.cache = {}
        if spec.get('static'):
            self.size = self.template.shape[1::-1]
            self.rect = spec['rect']
            self.plane = np.eye(3)
            return
        self.size = (720,1280)
        self.rect = [120,220,600,700]
        self.plane = cv2.getPerspectiveTransform(np.float32(spec['quad']),
            np.float32([[120,220],[600,220],[600,700],[120,700]]))
        mask = np.zeros(self.template.shape[:2], np.uint8)
        x,y,x1,y1 = spec['phone']; mask[y:y1,x:x1] = 255
        q = np.array(spec['quad'],np.int32)
        cv2.fillConvexPoly(mask,q,0)
        mask=cv2.erode(mask,np.ones((7,7),np.uint8))
        # 录制者的手指不是手机平面上的特征。
        if spec['number']==4: mask[690:950,:460]=0
        self.sift = cv2.SIFT_create(nfeatures=6000, contrastThreshold=.012)
        self.k0,self.d0 = self.sift.detectAndCompute(cv2.cvtColor(self.template,cv2.COLOR_BGR2GRAY),mask)
        self.matcher = cv2.BFMatcher()

    def read(self,t):
        index=round(t*self.fps)
        self.cap.set(cv2.CAP_PROP_POS_FRAMES,index)
        ok,a=self.cap.read()
        if not ok: raise RuntimeError(f'无法解码 {t:.4f}')
        return a

    def at(self,t):
        frame=round(t*self.fps)
        if frame in self.cache: return self.cache[frame].copy()
        a=self.read(t)
        if self.spec.get('static'):
            out=cv2.cvtColor(a,cv2.COLOR_BGR2RGB)
        else:
            k,d=self.sift.detectAndCompute(cv2.cvtColor(a,cv2.COLOR_BGR2GRAY),None)
            pairs=self.matcher.knnMatch(self.d0,d,k=2)
            good=[p[0] for p in pairs if len(p)==2 and p[0].distance<.73*p[1].distance]
            q0=np.float32([self.k0[m.queryIdx].pt for m in good])
            q1=np.float32([k[m.trainIdx].pt for m in good])
            if len(good)<8: raise RuntimeError(f'配准匹配不足 {self.spec["number"]} {t}: {len(good)}')
            h,inliers=cv2.findHomography(q1,q0,cv2.RANSAC,2.0,maxIters=5000)
            if h is None or inliers.sum()<7: raise RuntimeError(f'配准失败 {self.spec["number"]} {t}')
            residual=np.linalg.norm(cv2.perspectiveTransform(q1[:,None],h)[:,0]-q0,axis=1)
            self.stats.append(dict(time=t,matches=len(good),inliers=int(inliers.sum()),
                median_error=float(np.median(residual[inliers[:,0]>0]))))
            out=cv2.cvtColor(cv2.warpPerspective(a,self.plane@h,self.size,flags=cv2.INTER_LINEAR),cv2.COLOR_BGR2RGB)
        # 缓存的尺寸有上限，避免完整 60 fps 原片常驻内存。
        if len(self.cache)<8: self.cache[frame]=out.copy()
        return out


def prepare(s):
    n=s['number'];name='spiderman-original' if s.get('old') else f'reference-0926-{n}'
    path=(ROOT/'tmp/particle-dismiss-tuning/ref-videos-all/8-第三秒开始.mp4' if s.get('old')
          else next((HERE/'new-references-0926').glob(f'{n}-*.mp4')))
    reg=Registration(path,s);directory=HERE/'assets'/name;directory.mkdir(exist_ok=True)
    previous=json.loads((directory/'scene.json').read_text('utf-8')) if (directory/'scene.json').exists() else {}
    source=reg.at(s['source']);bg=reg.at(s['background']);x,y,x1,y1=reg.rect
    fg=source[y:y1,x:x1].copy();w,h=x1-x,y1-y
    alpha=Image.new('L',(w,h));ImageDraw.Draw(alpha).rounded_rectangle((0,0,w-1,h-1),radius=round(min(w,h)*.065),fill=255)
    Image.fromarray(np.dstack([fg,np.array(alpha)])).save(directory/'foreground.png')
    if s.get('incomplete'):
        # 原片没有干净背景。保留明确标注的重建图，只用于观看，不能作为像素真值。
        mask=np.zeros(bg.shape[:2],np.float32);mask[max(0,y-45):y1+45,max(0,x-35):x1+35]=1
        mask=cv2.GaussianBlur(mask,(0,0),8)[:,:,None]
        reconstructed=cv2.GaussianBlur(bg,(0,0),4).astype('float32')
        if n==2:
            # 消除末段剩余的亮粒子；这是近似干净底图，不作准确背景标注。
            reconstructed=cv2.morphologyEx(bg,cv2.MORPH_OPEN,np.ones((21,21),np.uint8))
            reconstructed=cv2.GaussianBlur(reconstructed,(0,0),10).astype('float32')
        if n==3:
            # 末帧仍有大块前景，使用两侧可见壁纸延拓。该区域始终排除于 RGB 误差。
            left=bg[:,max(0,x-28):x-8].mean(1);right=bg[:,x1+8:min(bg.shape[1],x1+28)].mean(1)
            u=np.clip((np.arange(bg.shape[1])-x)/(x1-x),0,1)[None,:,None]
            reconstructed=left[:,None,:]*(1-u)+right[:,None,:]*u
        bg=np.uint8(np.clip(bg*(1-mask)+reconstructed*mask,0,255))
    if n==1:
        # 桌面图标在粒子动画后才重新布局；不能提前出现在解体后的空洞里。
        icon_mask=np.zeros(bg.shape[:2],np.uint8);icon_mask[y-10:y+130,x-25:x+230]=255
        # 大孔洞的 Telea 填充会留下不属于壁纸的直线接缝。仅为预览重建低频底色，
        # 显式留下无真值遮罩，绝不把此区域当作已知背景计算像素匹配。
        yy,xx=np.mgrid[:bg.shape[0],:bg.shape[1]].astype('float64')
        xx=(xx-(x+110))/300;yy=(yy-(y+60))/240
        support=(np.abs(xx)<1.)&(np.abs(yy)<.65)&(icon_mask==0)
        design=np.stack([xx**i*yy**j for i in range(4) for j in range(4-i)],axis=-1)
        blurred=cv2.GaussianBlur(bg,(0,0),8).astype('float64')
        coefficients=np.linalg.lstsq(design[support],blurred[support],rcond=None)[0]
        fitted=np.clip(design@coefficients,0,255)
        blend=cv2.GaussianBlur(icon_mask.astype('float64')/255,(0,0),5)
        bg=np.uint8(np.clip(bg*(1-blend[:,:,None])+fitted*blend[:,:,None],0,255))
        Image.fromarray(np.uint8((1-blend)*255)).save(directory/'background-valid.png')
    Image.fromarray(bg).save(directory/'background.png')
    a=np.array(alpha)[:,:,None]/255
    combined=bg.copy();combined[y:y1,x:x1]=np.uint8(fg*a+combined[y:y1,x:x1]*(1-a))
    Image.fromarray(combined).save(directory/'source.png')
    max_p=min(1.,(s.get('valid_end',s['end'])-s['start'])/(s['end']-s['start']))
    times=s['start']+np.linspace(0,max_p,121)*(s['end']-s['start'])
    times=np.unique(np.round(times*reg.fps)/reg.fps)
    frames=np.lib.format.open_memmap(directory/'reference.npy',mode='w+',dtype='uint8',shape=(len(times),reg.size[1],reg.size[0],3))
    for i,t in enumerate(times): frames[i]=reg.at(float(t))
    frames.flush();np.save(directory/'reference-times.npy',times)
    note='屏摄按手机平面配准' if not s.get('static') else '屏幕参考原像素'
    if s.get('incomplete'): note+='；片尾不完整，背景重建，仅有效区间参与分析'
    meta=dict(name=name,title=s['title'],frame=list(reg.size),rect=reg.rect,radius=round(min(w,h)*.065),
        duration=1.,seed=previous.get('seed',909602),direction=OBSERVED_DIRECTIONS[name],dim_alpha=0.,background_truth=not s.get('incomplete') and n!=1,
        background_note=note,reference=dict(path=str(path.resolve()),start=s['start'],end=s['end'],valid_progress=max_p,
        sha256=hashlib.sha256(path.read_bytes()).hexdigest()),research_input=True)
    meta['release_sites']=[dict(zip(['x','y','start'],q)) for q in OBSERVED_SITES[name]]
    meta['input_note']='起点位置和启动顺序来自前段观测；方向由整体流动估计，准确触点与原始随机种子未知。'
    if previous.get('seed_note'):meta['seed_note']=previous['seed_note']
    (directory/'scene.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),'utf-8')
    (OUT/f'registration-{n}.json').write_text(json.dumps(dict(spec=s,frames=reg.stats),ensure_ascii=False,indent=2),'utf-8')
    # 只显示取材与测量结果；此时还没有生成模型动画。
    cw=240;ch=round(reg.size[1]*cw/reg.size[0]);sheet=Image.new('RGB',(cw*4,(ch+28)*2),'#0e141e')
    draw=ImageDraw.Draw(sheet);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for j,p in enumerate([0,.12,.24,.36,.48,.60,.75,max_p]):
        at=round(min(p,max_p)/max_p*(len(times)-1));xx=j%4*cw;yy=j//4*(ch+28)
        sheet.paste(Image.fromarray(frames[at]).resize((cw,ch)),(xx,yy+28))
        draw.text((xx+4,yy+3),f'{n}  p={min(p,max_p):.2f}',font=font,fill='white')
    sheet.save(OUT/f'registered-{n}.jpg',quality=94)
    median=np.median([q['median_error'] for q in reg.stats]) if reg.stats else 0.
    print(f'{name}: {len(times)} 帧，配准中位误差 {median:.3f} 源像素',flush=True)
    reg.cap.release()
    return meta


def main():
    p=argparse.ArgumentParser();p.add_argument('--numbers',type=int,nargs='+',default=list(range(1,9)));a=p.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    result=[prepare(s) for s in SPECS if s['number'] in a.numbers]
    manifest=OUT/'prepared-scenes.json'
    if manifest.exists():
        existing=json.loads(manifest.read_text('utf-8'));names={q['name'] for q in result}
        result=[q for q in existing if q['name'] not in names]+result
    (OUT/'prepared-scenes.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__': main()
