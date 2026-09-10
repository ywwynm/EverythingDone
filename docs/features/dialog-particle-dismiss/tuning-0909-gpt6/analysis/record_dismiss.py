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
path=f'/sdcard/particle-{a.name}.mp4'
display=adb('shell','dumpsys','window','displays').decode('utf-8','replace')
match=re.search(r'\bcur=(\d+)x(\d+)',display);assert match,'缺少当前显示尺寸'
w,h=map(int,match.groups());scale=min(1,1920/max(w,h));size=f'{int(w*scale)//2*2}x{int(h*scale)//2*2}'
record=subprocess.Popen(base+['shell','screenrecord','--size',size,'--bit-rate','20000000','--time-limit','7',path],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
for _ in range(25):
    if subprocess.run(base+['shell','pidof','screenrecord'],capture_output=True).stdout.strip():break
    time.sleep(.05)
# 一段已启动的录制，留初始画面用于确定操作与首帧的真实间隔。
time.sleep(.25)
stamp=time.time();adb('shell','input','keyevent','4') if a.back else adb('shell','input','tap',*xy)
out,err=record.communicate(timeout=15)
if record.returncode:raise RuntimeError(err.decode('utf-8','replace'))
adb('pull',path,str(root/f'{a.name}.mp4'))
(root/f'{a.name}-log.txt').write_bytes(adb('logcat','-d','-s','ParticleMicroflake:I','*:S'))
print(json.dumps({'device':a.serial,'recording':str(root/f'{a.name}.mp4'),'tap_time':stamp,'record_error':err.decode('utf-8','replace')},ensure_ascii=False))
