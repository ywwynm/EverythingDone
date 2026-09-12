"""保存用户真机反例及当前模型，生成不改变原始像素的逐帧检查材料。"""
from pathlib import Path
import sys,json,shutil
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(HERE))
import unified_model as model
OUT=HERE/'analysis/device-filament-origins'
BASE=HERE/'archive/before-device-filament-origins'
INPUT=Path('E:/WeChatFiles/xwechat_files/wxid_yizrz7pph07f22_8943/temp/RWTemp/2026-09/9e20f478899dc29eb19741386f9343c8')
FILES=['585d6a38655604c87ec2b79bd98c4a0e.mp4','8ccd706fbe0be0d878887e2580c441f2.mp4','bee232f1854e7c5abe255c7a6d6c2925.mp4']

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    if not (BASE/'identity.json').exists():
        (BASE/'shared').mkdir(parents=True,exist_ok=True)
        for name in ['renderer.py','unified_model.py','android_shaders.py','export_videos.py','touch_geometry.py']:
            shutil.copy2(HERE/name,BASE/name)
        for path in model.SHARED.iterdir():
            if path.is_file():shutil.copy2(path,BASE/'shared'/path.name)
        for name in ['ironman','thanos','kobe','attachment','attachment-image','language','color','ironman-up-reference']:
            for ext in ['.json','.npy']:shutil.copy2(HERE/'cache'/(name+ext),BASE/(name+ext))
        (BASE/'identity.json').write_text(json.dumps({'model_hash':model.model_fingerprint()},indent=2),'utf-8')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for j,file in enumerate(FILES,1):
        shutil.copy2(INPUT/file,OUT/file)
        cap=cv2.VideoCapture(str(OUT/file));frames=[]
        while True:
            ok,frame=cap.read()
            if not ok:break
            frames.append(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB))
        cap.release();np.save(OUT/f'device-{j}.npy',frames)
        ids=list(range(15,min(75,len(frames)),4))
        w,h=222,480
        sheet=Image.new('RGB',(w*5,(h+26)*3),'#0e141e');d=ImageDraw.Draw(sheet)
        for k,i in enumerate(ids):
            x=(k%5)*w;y=(k//5)*(h+26)
            sheet.paste(Image.fromarray(frames[i]).resize((w,h)),(x,y+26))
            d.text((x+3,y+2),f'录像 {j} · {i/30:.3f}s · 帧{i}',font=font,fill='white')
        sheet.save(OUT/f'device-{j}-whole.jpg',quality=96)
        print(j,len(frames),frames[0].shape,flush=True)

if __name__=='__main__':main()
