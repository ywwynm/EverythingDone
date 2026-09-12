"""逐帧比较：原始差异、空间尺度差异、静态记录偏差与完整画面并列。"""
from pathlib import Path
import sys,json,shutil,argparse,hashlib
import numpy as np, cv2
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
from unified_model import SHARED,model_fingerprint
OUT=HERE/'analysis/frame-difference'
BASE=HERE/'archive/before-frame-difference'
PHASES=np.arange(61)/60

def freeze():
    if (BASE/'identity.json').exists():return
    BASE.mkdir(parents=True,exist_ok=True);(BASE/'shared').mkdir(exist_ok=True)
    for n in ['renderer.py','unified_model.py','android_shaders.py','export_videos.py','touch_geometry.py']:
        shutil.copy2(HERE/n,BASE/n)
    for p in SHARED.iterdir():
        if p.is_file():shutil.copy2(p,BASE/'shared'/p.name)
    for n in ['ironman','thanos','kobe','attachment','attachment-image','language','color','ironman-up-reference']:
        for ext in ['.npy','.json']:shutil.copy2(HERE/'cache'/(n+ext),BASE/(n+ext))
    (BASE/'identity.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),description='本轮前已发布基线，保留原始帧与共同资源'),ensure_ascii=False,indent=2),'utf-8')

def blur(a,s):
    return cv2.GaussianBlur(a.astype('float32'),(0,0),s) if s else a.astype('float32')

def metrics(images,refs,mask):
    result=[]
    for p,im,ref in zip(PHASES,images,refs):
        errors={f'mae_{s}':float(np.abs(blur(im,s)-blur(ref,s))[mask].mean()) for s in [0,2,6,12]}
        result.append(dict(phase=float(p),**errors))
    return result

def sheet(images,refs,name,phases=(.25,.40,.56,.72),reference_title='华为参考'):
    cw=360;ch=640;hh=30;out=Image.new('RGB',(cw*len(phases),(ch+hh)*3),(14,20,30))
    d=ImageDraw.Draw(out);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for j,p in enumerate(phases):
        i=round(p*60);err=np.abs(blur(images[i],6)-blur(refs[i],6)).mean(2)
        heat=cv2.cvtColor(cv2.applyColorMap(np.clip(err*4,0,255).astype('uint8'),cv2.COLORMAP_INFERNO),cv2.COLOR_BGR2RGB)
        for k,(a,title) in enumerate([(refs[i],reference_title),(images[i],name),(heat,'差异：亮处误差大（×4）')]):
            out.paste(Image.fromarray(a).resize((cw,ch)),(j*cw,k*(ch+hh)+hh))
            d.text((j*cw+6,k*(ch+hh)+4),f'{title} · {i/60:.3f}',font=font,fill='white')
    out.save(OUT/(name+'.jpg'),quality=95)

def main():
    OUT.mkdir(parents=True,exist_ok=True);freeze()
    m=load_meta('ironman');ref=Reference(m);refs=np.stack([ref.at(t) for t in PHASES])
    np.save(OUT/'reference.npy',refs)
    base=np.load(BASE/'ironman.npy',mmap_mode='r')[::2]
    approved=np.load(HERE/'archive/observed-approved/ironman.npy',mmap_mode='r')[::2]
    h,w=base.shape[1:3];yy,xx=np.mgrid[:h,:w]
    mask=(yy>=220)&(yy<850)&(xx>=80)&(xx<635)
    rows={}
    for name,ims in [('baseline',base),('observed-control',approved)]:
        rows[name]=metrics(ims,refs,mask);sheet(ims,refs,name)
    src=np.asarray(Image.open(HERE/'assets/ironman/source.png').convert('RGB'))
    stable=(yy>=870)&(yy<1070)&(xx>=90)&(xx<630)
    # 固定背景只诊断录屏偏移；不将模型对参考做任意形变来改善分数。
    rows['static_background']=[dict(phase=float(t),mae=float(abs(a.astype(float)-src)[stable].mean())) for t,a in zip(PHASES,refs)]
    (OUT/'baseline-errors.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:{key:float(np.mean([r[key] for r in v][12:49])) for key in v[0] if key!='phase'} for k,v in rows.items()},ensure_ascii=False),flush=True)

if __name__=='__main__':main()
