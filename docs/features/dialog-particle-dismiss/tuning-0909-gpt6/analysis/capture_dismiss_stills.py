"""ADB 低频实屏采样：保存真实采样时间，明确不作为系统流畅度录屏。"""
import argparse,subprocess,xml.etree.ElementTree as ET,re,time,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('name');p.add_argument('--id');p.add_argument('--back',action='store_true');p.add_argument('--output',default='device-unified');a=p.parse_args()
root=Path(__file__).resolve().parents[1]/a.output/a.serial;root.mkdir(exist_ok=True,parents=True)
base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout
adb('shell','uiautomator','dump','/sdcard/particle-ui.xml');raw=adb('shell','cat','/sdcard/particle-ui.xml');(root/f'{a.name}-before.xml').write_bytes(raw)
if not a.back:
    nodes=[n for n in ET.fromstring(raw).iter('node') if n.get('resource-id')==a.id];assert len(nodes)==1
    b=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));xy=[str((b[0]+b[2])//2),str((b[1]+b[3])//2)]
adb('shell','screencap','-p','/sdcard/particle-before.png');adb('pull','/sdcard/particle-before.png',str(root/f'{a.name}-before.png'))
start=time.perf_counter();adb('shell','input','keyevent','4') if a.back else adb('shell','input','tap',*xy)
samples=[]
for i in range(10):
    t=time.perf_counter()-start;path=f'/sdcard/particle-sample-{i}.png';adb('shell','screencap','-p',path)
    samples.append({'time_before_capture':t,'time_after_capture':time.perf_counter()-start,'index':i})
    if time.perf_counter()-start>2.2:break
for row in samples:adb('pull',f'/sdcard/particle-sample-{row["index"]}.png',str(root/f'{a.name}-{row["index"]}.png'))
(root/f'{a.name}-samples.json').write_text(json.dumps(samples,indent=2),encoding='utf-8')
(root/f'{a.name}-log.txt').write_bytes(adb('logcat','-d','-s','ParticleMicroflake:I','*:S'))
print(json.dumps(samples))
