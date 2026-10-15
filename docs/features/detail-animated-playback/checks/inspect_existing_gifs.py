"""只读抽检已授权设备的现有 v10 派生文件；私有媒体留在忽略目录，仅输出统计。"""
from pathlib import Path
import json
import subprocess
import sys
import numpy as np
from PIL import Image, ImageSequence

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[4] / 'tmp/detail-gif-flicker/existing'
ROOT.mkdir(parents=True, exist_ok=True)
ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
REMOTE = '/sdcard/Android/data/com.ywwynm.everythingdone/cache/video-cover-previews'
results = []
for serial, label in [('3B1629006YC00000','oppo'), ('R5CW20BLNKL','samsung'), ('192.168.100.67:40807','meizu')]:
    listing = subprocess.run([ADB,'-s',serial,'shell','ls','-t',REMOTE],capture_output=True,check=True).stdout.decode().splitlines()
    names = [name for name in listing if name.endswith('_v10.gif') and '_113105_' not in name][:3]
    for index, name in enumerate(names):
        path=ROOT/f'{label}-{index}.gif'
        subprocess.run([ADB,'-s',serial,'pull',f'{REMOTE}/{name}',str(path)],capture_output=True,check=True)
        im=Image.open(path)
        frames=[np.array(frame.convert('RGBA').resize((64,64))).astype(np.int16) for frame in ImageSequence.Iterator(im)]
        internal=[float(np.abs(a[:,:,:3]-b[:,:,:3]).mean()) for a,b in zip(frames,frames[1:])]
        results.append(dict(sample=f'{label}-{index}',frames=len(frames),duration_ms=im.info.get('duration'),
            blank_frames=[i for i,a in enumerate(frames) if a[:,:,3].max()==0],
            dark_frames=[i for i,a in enumerate(frames) if a[:,:,:3].mean()<3],
            white_frames=[i for i,a in enumerate(frames) if a[:,:,:3].mean()>252],
            loop_difference=round(float(np.abs(frames[-1][:,:,:3]-frames[0][:,:,:3]).mean()),3),
            adjacent_median=round(float(np.median(internal)),3),adjacent_max=round(max(internal, default=0),3)))
(ROOT/'summary.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
print(json.dumps(results))
