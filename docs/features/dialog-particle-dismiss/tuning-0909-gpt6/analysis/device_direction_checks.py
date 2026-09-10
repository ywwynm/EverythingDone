import argparse,subprocess,xml.etree.ElementTree as ET,json,re,time,math
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',default='device-unified');a=p.parse_args()
root=Path(__file__).resolve().parents[1]/a.output/a.serial;root.mkdir(parents=True,exist_ok=True);base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout
def dump():
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml');return ET.fromstring(adb('shell','cat','/sdcard/particle-ui.xml'))
def find(tree,id):return [n for n in tree.iter('node') if n.get('resource-id')=='com.ywwynm.everythingdone:id/'+id]
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def waitfor(id):
    for _ in range(5):
        t=dump()
        if find(t,id):return t
        time.sleep(.1)
    raise RuntimeError('未到达 '+id)
def completed():
    log=adb('logcat','-d','-s','ParticleMicroflake:I','*:S').decode('utf-8','replace')
    return [line for line in log.splitlines() if '完成 count=' in line]
results=[]
tree=dump()
if find(tree,'tv_take_photo_as_bt'):
    adb('shell','input','keyevent','4');waitfor('act_add_attachment')
for direction in [0,45,90,135,180,225,270,315]:
    tree=waitfor('act_add_attachment');node=find(tree,'act_add_attachment');assert len(node)==1
    b=bounds(node[0]);adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
    tree=waitfor('tv_take_photo_as_bt');dialog=bounds(tree.find('node'))
    l,t,r,b=dialog;cx=(l+r)/2;cy=(t+b)/2;angle=math.radians(direction);dx=math.cos(angle);dy=-math.sin(angle)
    # 40 px 小于这些高密度设备的 windowTouchSlop，仍属系统容差区。
    radius=min((r-l)/2/max(abs(dx),1e-8),(b-t)/2/max(abs(dy),1e-8))+128
    x=round(cx+dx*radius);y=round(cy+dy*radius)
    old=completed();start=time.perf_counter();adb('shell','input','tap',str(x),str(y))
    last=[]
    for _ in range(35):
        last=completed()
        if last and last[-1] not in old:break
        time.sleep(.1)
    else:raise RuntimeError(f'{direction} 关闭未完成')
    row={'requested':direction,'touch':[x,y],'dialog':dialog,'elapsedMs':(time.perf_counter()-start)*1000,'render':last[-1]}
    actual=float(re.search(r'direction=([-\d.]+)',last[-1]).group(1));error=abs((actual-direction+180)%360-180)
    row.update(actual=actual,error=error);assert error<1.,row
    waitfor('act_add_attachment');results.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
    (root/'direction-checks.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
