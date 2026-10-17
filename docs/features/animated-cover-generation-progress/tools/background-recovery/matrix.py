import concurrent.futures
import json
import pathlib
import re
import sys
import time
from run import ROOT, DEVICES, PKG, REMOTE, ACT, adb, launch, read_events, summary, dismiss_compatibility

MODE=sys.argv[1] if len(sys.argv)>1 else 'switch-green'

def run(name, mode=MODE):
    kind=mode.removeprefix('wm2105-')
    s=DEVICES[name]; out=ROOT/(mode+'-'+name); out.mkdir(exist_ok=True)
    crash_before=set(adb(s,'logcat','-b','crash','-d').splitlines())
    actions=[]
    def record(action,detail=None):
        actions.append({'time':time.time(),'action':action,'detail':detail})
        (out/'actions.json').write_text(json.dumps(actions,ensure_ascii=False,indent=2),encoding='utf-8')
    def fg(check_ui=False):
        record('foreground',launch(s,mode,12000))
        if check_ui:dismiss_compatibility(s)
    def settings():
        record('settings',adb(s,'shell','am','start','-W','-a','android.settings.SETTINGS'))
    def background(seconds):
        settings(); record('background-start',seconds)
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            adb(s,'shell','input','keyevent','KEYCODE_DPAD_DOWN')
            time.sleep(min(10,max(0,end-time.monotonic())))
        record('background-end'); fg(True)
    def until(predicate,seconds=300):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            ev=read_events(s,mode)
            if predicate(ev):return ev
            time.sleep(.5)
        raise RuntimeError('timeout: '+json.dumps(summary(ev)))
    if kind in ('switch-green', 'foreground-green', 'final-switch'):
        if not mode.startswith('wm2105-'):
            baseline=read_events(s,'baseline-red')
            ready=next(e for e in baseline if e['event']=='ready')['data']['file']
            adb(s,'pull',f'/sdcard/Android/data/{PKG}/cache/video-cover-previews/'+ready,str(out/'baseline.gif'))
        apk='wm2105-isolated.apk' if mode.startswith('wm2105-') else {'switch-green':'fixed.apk','foreground-green':'foreground.apk','final-switch':'final-isolated.apk'}[mode]
        record('install',adb(s,'install','-r',str(ROOT/apk),timeout=120))
        adb(s,'shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS',check=False)
    adb(s,'shell','am','force-stop',PKG)
    # 仅改变隔离夹具的时间戳，使同样视频和同样起始帧真正重新生成。
    adb(s,'shell','touch',REMOTE+'/complex-4k.mp4')
    fg(True)
    until(lambda ev:any(e['event']=='state' and (e.get('data') or {}).get('phase')=='GENERATING'
        and (e.get('data') or {}).get('percent',0)>=1 for e in ev))
    if kind in ('switch-green', 'foreground-green', 'final-switch'):
        for i in range(12):
            settings(); time.sleep(2); fg(); time.sleep(1)
        print(name,'12 switches completed',flush=True)
        (out/'services-background.txt').write_text(adb(s,'shell','dumpsys','activity','services',PKG),encoding='utf-8')
        background(180)
    elif kind.startswith('death'):
        settings()
        pid=adb(s,'shell','pidof',PKG).strip()
        assert re.fullmatch(r'\d+',pid),pid
        record('kill-own-test-process',pid)
        adb(s,'shell','run-as',PKG,'kill','-9',pid)
        background(180)
    elif kind.startswith('stop'):
        settings()
        record('scheduler-stop',adb(s,'shell','cmd','jobscheduler','timeout','-u','0',PKG,check=False))
        background(60)
    elif kind=='cancel-finalizing':
        until(lambda ev:any(e['event']=='state' and (e.get('data') or {}).get('phase')=='FINALIZING' for e in ev),360)
        adb(s,'shell','screencap','-p',REMOTE+'/finalizing.png')
        adb(s,'pull',REMOTE+'/finalizing.png',str(out/'finalizing.png'))
        record('cancel-during-native-close',adb(s,'shell','am','start','-n',ACT,'--es','command','cancel'))
        until(lambda ev:any(e['event']=='state' and (e.get('data') or {}).get('phase')=='FAILED' for e in ev))
        record('explicit-retry',adb(s,'shell','am','start','-W','-n',ACT,'--es','scenario',mode,'--el','frame',12000,'--ez','retry','true'))
        background(60)
    ev=until(lambda ev:any(e['event']=='ui' and (e.get('data') or {}).get('gif') for e in ev),360)
    time.sleep(2)
    ev=read_events(s,mode)
    (out/'events.json').write_text(json.dumps(ev,ensure_ascii=False,indent=2),encoding='utf-8')
    result=summary(ev)
    final=[e for e in ev if e['event']=='state' and (e.get('data') or {}).get('phase')=='FINALIZING']
    result['finalizingTimesMs']=[e['time']-ev[0]['time'] for e in final]
    result['has99']=any(e['event']=='state' and (e.get('data') or {}).get('phase')=='GENERATING'
        and (e.get('data') or {}).get('percent')==99 for e in ev)
    result['failures']=[e for e in ev if e['event']=='state' and (e.get('data') or {}).get('phase')=='FAILED']
    (out/'summary.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    ready=next(e for e in reversed(ev) if e['event']=='ready')['data']['file']
    adb(s,'pull',f'/sdcard/Android/data/{PKG}/cache/video-cover-previews/'+ready,str(out/'output.gif'))
    (out/'generation.log').write_text(adb(s,'shell','cat',REMOTE+'/debug_logs/video-cover-preview.log',check=False),encoding='utf-8')
    (out/'exit-info.txt').write_text(adb(s,'shell','dumpsys','activity','exit-info',PKG,check=False),encoding='utf-8')
    (out/'jobs.txt').write_text(adb(s,'shell','dumpsys','jobscheduler',PKG,check=False),encoding='utf-8')
    adb(s,'shell','screencap','-p',REMOTE+'/matrix-final.png')
    adb(s,'pull',REMOTE+'/matrix-final.png',str(out/'returned.png'))
    crash_lines=[line for line in adb(s,'logcat','-b','crash','-d').splitlines() if line not in crash_before]
    own_pids={m.group(1) for line in crash_lines if (m:=re.search(r'Process: '+re.escape(PKG)+r', PID: (\d+)',line))}
    own_crashes=[line for line in crash_lines if any(re.search(r'\s'+pid+r'\s',line) for pid in own_pids)]
    (out/'crashes.txt').write_text('\n'.join(own_crashes),encoding='utf-8')
    assert not own_crashes, 'New isolated-app crash; inspect crashes.txt'
    print(name,json.dumps(result),flush=True)
    assert not result['has99'] and (kind=='cancel-finalizing' or not result['failures'])
    if not kind.startswith('death'): assert final, 'Missing finalizing state'
    if kind.startswith('death'): assert len(result['pids'])>=2, 'Process death was not observed'

if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures={pool.submit(run,name):name for name in DEVICES}
        for f in concurrent.futures.as_completed(futures):
            try:f.result()
            except Exception as e:print(futures[f],'FAILED',repr(e),flush=True)
