import argparse,subprocess,xml.etree.ElementTree as ET,json,re,time
from pathlib import Path
P=argparse.ArgumentParser();P.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);P.add_argument('action',choices=['dump','tap','back','capture']);P.add_argument('--text');P.add_argument('--desc');P.add_argument('--id');P.add_argument('--expect');P.add_argument('--name',default='current');P.add_argument('--output',default='device-unified');A=P.parse_args()
ROOT=Path(__file__).resolve().parents[1]/A.output/A.serial;ROOT.mkdir(exist_ok=True,parents=True)
def adb(*args):return subprocess.run(['E:/AndroidSDK/platform-tools/adb.exe','-s',A.serial,*args],capture_output=True,check=True).stdout
def dump():
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml')
    raw=adb('shell','cat','/sdcard/particle-ui.xml');(ROOT/f'{A.name}.xml').write_bytes(raw)
    return ET.fromstring(raw)
if A.action=='back':adb('shell','input','keyevent','4')
if A.action=='tap':
    tree=dump();wanted={'text':A.text,'content-desc':A.desc,'resource-id':A.id};matches=[n for n in tree.iter('node') if all(v is None or n.get(k)==v for k,v in wanted.items())]
    if len(matches)!=1:raise RuntimeError(f'需要唯一目标，找到 {len(matches)} 个')
    bounds=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))));x=(bounds[0]+bounds[2])//2;y=(bounds[1]+bounds[3])//2
    adb('shell','input','tap',str(x),str(y));print('tap',x,y)
tree=dump()
if A.expect:
    for _ in range(8):
        if any(A.expect in n.get('text','') or A.expect in n.get('resource-id','') or A.expect in n.get('content-desc','') for n in tree.iter('node')):break
        time.sleep(.15);tree=dump()
    else:raise RuntimeError('没有到达 '+A.expect)
if A.action=='capture':
    adb('shell','screencap','-p','/sdcard/particle-screen.png');adb('pull','/sdcard/particle-screen.png',str(ROOT/f'{A.name}.png'))
for n in tree.iter('node'):
    if n.get('clickable')=='true' or n.get('checkable')=='true':
        print(json.dumps({k:n.get(k) for k in ['text','resource-id','content-desc','bounds','checked']},ensure_ascii=False))
