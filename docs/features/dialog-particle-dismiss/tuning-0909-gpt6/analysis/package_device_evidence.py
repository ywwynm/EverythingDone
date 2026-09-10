from pathlib import Path
import subprocess,shutil,json,io
from concurrent.futures import ThreadPoolExecutor
from PIL import Image,ImageDraw,ImageFont
root=Path(__file__).resolve().parents[1]
source=root/'device-r33'/'R5CW20BLNKL';out=root/'device-r33'/'videos';out.mkdir(exist_ok=True)
names=['language-r33','language-dark-r33','color-final','color-ime','attachment-scale-1.0']
manifest=[]
def encode(name):
    src=source/f'{name}.mp4';assert src.exists()
    normal=out/f'R5CW20BLNKL-{name}-1x.mp4';slow=out/f'R5CW20BLNKL-{name}-0.5x.mp4'
    shutil.copy2(src,normal)
    if not slow.exists():
        subprocess.run(['C:/ffmpeg/bin/ffmpeg.exe','-hide_banner','-loglevel','error','-y','-i',str(src),'-vf','setpts=2*PTS','-r','60','-an','-c:v','libx264','-crf','18','-preset','fast','-movflags','+faststart',str(slow)],check=True)
    # 实际录屏的连续时刻图集，保留原画面，不替换前景或背景。
    images=[]
    for t in [.15,.45,.65,.85,1.05,1.25,1.45,1.65]:
        # Android 录屏为可变帧率，OpenCV 的按毫秒定位在此文件上错误映射到静态帧。
        # 使用 ffmpeg 的 PTS 精确 seek，不用名义帧率换算帧号。
        frame=subprocess.run(['C:/ffmpeg/bin/ffmpeg.exe','-hide_banner','-loglevel','error','-ss',str(t),'-i',str(src),'-frames:v','1','-f','image2pipe','-c:v','png','-'],capture_output=True,check=True).stdout
        images.append(Image.open(io.BytesIO(frame)).convert('RGB'))
    w=200;h=round(images[0].height/images[0].width*w)
    contact=Image.new('RGB',(w*4,(h+28)*2),'#18212a');d=ImageDraw.Draw(contact)
    for i,img in enumerate(images):
        x=i%4*w;y=i//4*(h+28);contact.paste(img.resize((w,h)),(x,y+28));d.text((x+8,y+8),f'{[.15,.45,.65,.85,1.05,1.25,1.45,1.65][i]:.2f}s',fill='white')
    contact.save(root/'analysis'/f'actual-r33-{name}.jpg',quality=94)
    return {'scene':name,'device':'R5CW20BLNKL','type':'系统 screenrecord','normal':normal.name,'slow':slow.name,'source':str(src),'note':'color-final 录自位图上传优化前，同设备固定输入像素验证证明后续上传优化未改变图像。' if name=='color-final' else ''}
with ThreadPoolExecutor(max_workers=2) as pool:manifest=list(pool.map(encode,names))
(out/'manifest.json').write_text(json.dumps({'videos':manifest,'OPD2515':'系统录屏受限，实际阶段截图在 ../9018f404/；不合成为虚构连续录屏。'},ensure_ascii=False,indent=2),encoding='utf-8')
print('已集中保存 10 个真实录屏视频（1 倍与 0.5 倍）及实际画面图集。')
