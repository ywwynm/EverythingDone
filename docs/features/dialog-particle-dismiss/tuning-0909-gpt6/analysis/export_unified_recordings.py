"""保留三星 VFR 录屏的实际时间，导出双速度并按实际 PTS 取样。"""
from pathlib import Path
import subprocess,json,cv2,argparse
from PIL import Image,ImageDraw
p=argparse.ArgumentParser();p.add_argument('--device-dir',default='device-unified');p.add_argument('--report-dir',default='analysis/unified-validation');args=p.parse_args()
base=Path(__file__).resolve().parents[1];dest=base/args.device_dir/'videos';dest.mkdir(parents=True,exist_ok=True)
rows=[]
for name in ['attachment-back','color-cancel']:
    source=base/args.device_dir/'R5CW20BLNKL'/f'{name}.mp4'
    for speed in [1.,.5]:
        target=dest/f'samsung-{name}-{speed:g}x.mp4'
        subprocess.run(['C:/ffmpeg/bin/ffmpeg.exe','-v','error','-y','-i',str(source),'-an',
            '-vf',f'setpts=PTS/{speed},fps=60','-c:v','libx264','-crf','18','-preset','fast',
            '-pix_fmt','yuv420p','-movflags','+faststart',str(target)],check=True)
        probe=json.loads(subprocess.check_output(['C:/ffmpeg/bin/ffprobe.exe','-v','error','-show_entries',
            'format=duration:stream=width,height,avg_frame_rate','-of','json',str(target)]))
        assert probe['streams'][0]['avg_frame_rate']=='60/1'
        rows.append(dict(file=target.name,rate=speed,probe=probe,source=str(source)))
    # OpenCV 按 POS_MSEC 跳转 VFR 文件会按平均帧率换算，故顺序解码并读取实际 PTS。
    cap=cv2.VideoCapture(str(source));targets=[0.,.30,.55,.80,1.10];samples=[]
    while targets:
        ok,frame=cap.read();assert ok
        t=cap.get(cv2.CAP_PROP_POS_MSEC)/1000
        if t>=targets[0]:
            samples.append((t,Image.fromarray(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB))));targets.pop(0)
    cap.release()
    sheet=Image.new('RGB',(1500,570),'#15202b');draw=ImageDraw.Draw(sheet)
    for i,(t,im) in enumerate(samples):
        im.thumbnail((298,530));sheet.paste(im,(i*300+(300-im.width)//2,30))
        draw.text((i*300+10,5),f'{t:.3f} sec (actual PTS)',fill='white')
    for filename in [f'real-samsung-{name}-motion.jpg',f'real-R5CW20BLNKL-{name}.jpg']:
        sheet.save(base/args.report_dir/filename,quality=95)
    assert samples[0][1].tobytes()!=samples[2][1].tobytes()
manifest={'note':'三星真实系统录屏，保留实际 VFR 时间戳；输出重采样为 60 帧/秒，不代表原生采集或系统显示帧率。','videos':rows}
(dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('4 个双速度录屏及实际时间戳阶段表通过')
