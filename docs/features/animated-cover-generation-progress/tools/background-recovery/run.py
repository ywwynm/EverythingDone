import argparse
import concurrent.futures
import json
import pathlib
import subprocess
import time
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent
ADB = r'E:\AndroidSDK\platform-tools\adb.exe'
PKG = 'com.ywwynm.everythingdone.covervalidation'
ACT = PKG + '/com.ywwynm.everythingdone.covergeneration.CoverRecoveryProbeActivity'
REMOTE = '/sdcard/Android/data/' + PKG + '/files'
DEVICES = {'oppo':'3B1629006YC00000', 'samsung':'R5CW20BLNKL', 'meizu':'192.168.100.67:40807'}

def adb(serial, *args, timeout=60, check=True):
    p = subprocess.run([ADB, '-s', serial, *map(str,args)], capture_output=True, timeout=timeout)
    out = p.stdout.decode('utf-8', errors='replace')
    if check and p.returncode:
        raise RuntimeError(out + p.stderr.decode('utf-8', errors='replace'))
    return out

def launch(serial, scenario, frame):
    return adb(serial, 'shell', 'am', 'start', '-W', '-n', ACT, '--es', 'scenario', scenario, '--el', 'frame', frame)

def dismiss_compatibility(serial):
    adb(serial,'shell','uiautomator','dump',REMOTE+'/window.xml',timeout=20,check=False)
    xml=adb(serial,'shell','cat',REMOTE+'/window.xml',check=False)
    if 'Android 应用兼容性' not in xml and 'Android app compatibility' not in xml:return
    for n in ET.fromstring(xml).iter('node'):
        if n.get('text') in ('确定','OK'):
            a=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
            if len(a)==4:
                adb(serial,'shell','input','tap',(a[0]+a[2])//2,(a[1]+a[3])//2)
                return

def read_events(serial, scenario):
    text = adb(serial, 'shell', 'cat', REMOTE + '/recovery-' + scenario + '.jsonl', check=False)
    events = []
    for line in text.splitlines():
        try: events.append(json.loads(line))
        except json.JSONDecodeError: pass
    return events

def summary(events):
    if not events: return {'events':0}
    start=events[0]['time']
    points={}
    for e in events:
        d=e.get('data') or {}
        if e['event']=='state' and d.get('phase')=='GENERATING':
            for threshold in (0,25,50,75,90,95,99):
                if d.get('percent',0)>=threshold: points.setdefault(str(threshold),e['time']-start)
        if e['event']=='ready': points.setdefault('ready',e['time']-start)
        if e['event']=='ui' and d.get('gif'): points.setdefault('gif',e['time']-start)
    return {'events':len(events), 'timesMs':points, 'pids':list(dict.fromkeys(e['pid'] for e in events))}

def run(name,args):
    s=DEVICES[name]
    out=ROOT/(args.scenario+'-'+name)
    out.mkdir(exist_ok=True)
    if args.install:
        print(name,adb(s,'install','-r',str(ROOT/'baseline.apk'), timeout=120).strip(),flush=True)
        adb(s,'shell','mkdir','-p',REMOTE)
        adb(s,'push',str(ROOT/'complex-4k.mp4'),REMOTE+'/complex-4k.mp4',timeout=120)
    adb(s,'shell','am','force-stop',PKG)
    print(name,launch(s,args.scenario,args.frame).strip(),flush=True)
    deadline=time.monotonic()+args.timeout
    events=[]
    while time.monotonic()<deadline:
        events=read_events(s,args.scenario)
        if any(e['event']=='ui' and (e.get('data') or {}).get('gif') for e in events): break
        time.sleep(1)
    (out/'events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2),encoding='utf-8')
    log=adb(s,'shell','run-as',PKG,'cat','files/debug_logs/video-cover-preview.log',check=False)
    (out/'generation.log').write_text(log,encoding='utf-8')
    adb(s,'shell','screencap','-p',REMOTE+'/recovery.png')
    adb(s,'pull',REMOTE+'/recovery.png',str(out/'final.png'))
    result=summary(events)
    (out/'summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(name,json.dumps(result),flush=True)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--scenario',default='baseline-red')
    p.add_argument('--frame',type=int,default=12000)
    p.add_argument('--install',action='store_true')
    p.add_argument('--timeout',type=int,default=180)
    p.add_argument('--devices',nargs='+',default=list(DEVICES))
    args=p.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures={pool.submit(run,n,args):n for n in args.devices}
        for future in concurrent.futures.as_completed(futures):
            try: future.result()
            except Exception as e: print(futures[future],repr(e),flush=True)
