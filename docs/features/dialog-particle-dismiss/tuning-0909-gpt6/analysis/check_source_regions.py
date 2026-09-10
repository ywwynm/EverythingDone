"""先检查仍完整的源区域；测量不驱动渲染，也不写参考拟合配置。"""
from pathlib import Path
import json,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from scipy.ndimage import gaussian_filter
from export_videos import Reference
from unified_model import release_field,materials

HERE=Path(__file__).resolve().parents[1]
OUT=HERE/'analysis/edge-roll/source-regions'
OUT.mkdir(parents=True,exist_ok=True)

def measure(name):
    directory=HERE/'assets'/name
    meta=json.loads((directory/'scene.json').read_text('utf-8'))
    ref=Reference(meta);x,y,x1,y1=meta['rect'];n=120
    src=np.asarray(Image.open(directory/'foreground.png').convert('RGB'))
    bg=np.asarray(Image.open(directory/'background.png').convert('RGB'))[y:y1,x:x1]
    source=cv2.resize(src,(n,n),interpolation=cv2.INTER_AREA).astype(float)
    back=cv2.resize(bg,(n,n),interpolation=cv2.INTER_AREA).astype(float)
    contrast=np.sqrt(np.mean((source-back)**2,axis=-1))
    stable=release_field(n,n,meta['direction'])
    phases=[.31,.43,.55,.65]
    yy,xx=np.mgrid[:n,:n];xx=(xx+.5)/n;yy=(yy+.5)/n
    # 只使用距边缘一小段的纹理窗口，避开圆角及边框背景混合。
    regions={'left_middle':(xx>.02)&(xx<.10)&(yy>.30)&(yy<.85),
             'top_middle':(yy>.02)&(yy<.10)&(xx>.30)&(xx<.80),
             'lower_right':(xx>.50)&(yy>.65),
             'upper_left':(xx<.20)&(yy<.20)}
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    sheet=Image.new('RGB',(360*4,370*3),(18,23,31));draw=ImageDraw.Draw(sheet)
    reports=[]
    for j,t in enumerate(phases):
        frame=ref.at(t)
        now=cv2.resize(frame[y:y1,x:x1],(n,n),interpolation=cv2.INTER_AREA).astype(float)
        relative=np.sqrt(np.mean((now-source)**2,axis=-1))/(contrast+30)
        # 与原材质接近只说明纹理仍可辨，不足以证明该处完全没有运动颗粒。
        confidence=(contrast>40)
        intact=(relative<.17)&confidence
        measured=np.zeros((n,n,3),np.uint8);measured[:]=[45,49,55]
        measured[confidence]=[245,157,74];measured[intact]=[72,198,166]
        baseline=np.zeros_like(measured);baseline[:]=[245,157,74];baseline[stable>t]=[72,198,166]
        for i,(im,title) in enumerate([(frame[y-50:y1+50,max(0,x-50):min(meta['frame'][0],x1+50)],'参考'),(measured,'纹理仍接近源图：绿色'),(baseline,'稳定模型尚未释放：绿色')]):
            thumb=Image.fromarray(im)
            if i:thumb=thumb.resize((320,320),Image.Resampling.NEAREST)
            else:thumb.thumbnail((350,332))
            sheet.paste(thumb,(j*360+(360-thumb.width)//2,i*370+32))
            draw.text((j*360+8,i*370+5),f'{title} {t:.2f}',font=font,fill='white')
        reports.append({'phase':t,'regions':{key:{'valid_fraction':float(confidence[region].mean()),'reference_intact_proxy':float(intact[region&confidence].mean()),'baseline_unreleased':float((stable[region]>t).mean())} for key,region in regions.items()}})
        np.savez_compressed(OUT/f'{name}-{t:.2f}.npz',relative=relative,confidence=confidence,intact=intact,stable=stable)
    sheet.save(OUT/f'{name}.jpg',quality=95)
    result={'scene':name,'measurement':'相对原材质差 / (前景背景差+30) < 0.17，剔除低对比区域；不是逐粒子出生真值','samples':reports}
    if name=='ironman':
        rgba=np.asarray(Image.open(directory/'foreground.png').convert('RGBA'))
        built=materials(x1-x,y1-y,rgba,meta['direction'],meta['seed'])
        b=built['base'];primary=b[:,3]<built['nx']*built['ny'];bx=b[:,0]/(x1-x);by=b[:,1]/(y1-y)
        check={}
        for key,region,minimum in [('left_middle',(bx>.02)&(bx<.10)&(by>.30)&(by<.85),.70),('top_middle',(by>.02)&(by<.10)&(bx>.30)&(bx<.80),.85)]:
            fraction=float((b[primary&region,2]>.43).mean())
            check[key]={'unreleased_fraction_with_random_birth':fraction,'minimum_regression_guard':minimum}
            assert fraction>=minimum,(name,key,fraction,'再次提前侵蚀了应保留的区域')
        result['regression']=check
        # 回归检查针对用户指出的这一输入及阶段；不能声称所有原片起始区域相同。
    (OUT/f'{name}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(name,json.dumps(result.get('regression',reports[1]),ensure_ascii=False),flush=True)

if __name__=='__main__':
    for name in ['ironman','thanos','kobe']:measure(name)
