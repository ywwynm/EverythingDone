"""2026-10-09 三设备复查；诊断工具，不修改应用代码。原始数据只存 tmp。"""
import argparse
import concurrent.futures
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = r'E:\AndroidSDK\platform-tools\adb.exe'
ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / 'tmp/home-list-scroll-20261009'
PKG = 'com.ywwynm.everythingdone'
DEVICES = {'samsung': 'R5CW20BLNKL', 'meizu': '192.168.100.67:42641', 'oppo': '9018f404'}
PREFS = 'EverythingDone_preferences.xml'
from analyze_framestats import parse_dump, stages, MAIN_WINDOW, pct

def adb(serial, *args, data=None, check=True, timeout=45):
    p = subprocess.run([ADB, '-s', serial, *map(str,args)], input=data,
                       capture_output=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError((args,p.returncode,p.stderr.decode('utf-8','replace')))
    return p.stdout

def sh(serial, command, **kw):
    return adb(serial,'shell',command,**kw).decode('utf-8','replace')

def save(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    if isinstance(data,bytes): path.write_bytes(data)
    else: path.write_text(data.replace('\r\n','\n').replace('\r',''),encoding='utf-8')

def dump(serial,path):
    remote='/data/local/tmp/hls09_window.xml'
    for i in range(3):
        sh(serial,'uiautomator dump '+remote,check=False)
        raw=adb(serial,'exec-out','cat',remote)
        if b'</hierarchy>' in raw:
            save(path,raw)
            return ET.fromstring(raw)
    raise RuntimeError('UI dump unavailable')

def inventory(name):
    s=DEVICES[name]; d=OUT/name; d.mkdir(parents=True,exist_ok=True)
    queries={
        'environment': 'getprop ro.product.model; getprop ro.soc.model; getprop ro.build.version.release; getprop ro.build.version.sdk; wm size; wm density; dumpsys thermalservice; dumpsys battery; cat /proc/meminfo; settings get secure long_press_timeout; settings get system accelerometer_rotation; settings get system user_rotation; settings get system peak_refresh_rate; settings get system min_refresh_rate; settings get global animator_duration_scale',
        'package': 'dumpsys package '+PKG,
        'display': 'dumpsys display',
        'surface': 'dumpsys SurfaceFlinger',
        'prefs-list': 'run-as '+PKG+' ls shared_prefs',
        'tuning': 'run-as '+PKG+' cat files/particle-perf-tuning.json',
    }
    for label,q in queries.items(): save(d/('initial-'+label+'.txt'),sh(s,q,check=False))
    for p in ['particle_tuning.xml',PREFS]:
        raw=adb(s,'exec-out','run-as',PKG,'cat','shared_prefs/'+p,check=False)
        if raw.startswith(b'<?xml'): save(d/('original-'+p),raw)
    apk=sh(s,'pm path '+PKG).strip().split('package:')[-1]
    save(d/'apk-sha256.txt',sh(s,'sha256sum '+apk))
    sh(s,'input keyevent KEYCODE_WAKEUP')
    sh(s,'am start -W -n '+PKG+'/.activities.ThingsActivity')
    tree=dump(s,d/'initial-ui.xml')
    remote='/data/local/tmp/hls09_initial.png'
    sh(s,'screencap -p '+remote); adb(s,'pull',remote,d/'initial.png')
    nodes=[]
    for n in tree.iter('node'):
        rid=n.get('resource-id','').split('/')[-1]
        if rid and ('card' in rid or 'recycler' in rid or 'thing' in rid or 'header' in rid):
            nodes.append({'id':rid,'bounds':n.get('bounds'),'class':n.get('class'),'children':len(n)})
    print(json.dumps({'device':name,'apk':(d/'apk-sha256.txt').read_text().split()[0], 'nodes':nodes},ensure_ascii=False),flush=True)

def backup(name):
    s=DEVICES[name]; d=OUT/name
    for p in ['particle_tuning.xml',PREFS]:
        dest=d/('original-'+p)
        if not dest.exists():
            raw=adb(s,'exec-out','run-as',PKG,'cat','shared_prefs/'+p)
            assert raw.startswith(b'<?xml')
            save(dest,raw)

def write_pref(s,p,raw):
    path='shared_prefs/'+p
    adb(s,'shell','run-as',PKG,'sh','-c',"'cat > "+path+".hls09 && chmod 660 "+path+".hls09 && mv "+path+".hls09 "+path+"'",data=raw)
    assert adb(s,'exec-out','run-as',PKG,'cat',path)==raw

def preference(raw,key,value,tag='string'):
    text=raw.decode('utf-8')
    text=re.sub(r'\s*<(?:string|int|boolean|float) name="'+key+r'"[^>]*(?:/>|>[^<]*</(?:string|int|boolean|float)>)','',text)
    node=('<string name="%s">%s</string>'%(key,value)) if tag=='string' else ('<%s name="%s" value="%s" />'%(tag,key,value))
    return text.replace('</map>','    '+node+'\n</map>').encode('utf-8')

def bounds(n): return list(map(int,re.findall(r'-?\d+',n.get('bounds',''))))

def cards(tree):
    out=[]
    def visit(n,in_card=False):
        card=n.get('resource-id','').endswith('/cv_thing')
        if card and not in_card: out.append(n)
        for c in n: visit(c,in_card or card)
    visit(tree)
    return out

def gesture(s,x,y1,y2,hold=0,duration=350,end='UP'):
    points=([x,y1,hold] if hold else [])+[x,y2,duration]
    args=['shell','PARTICLE_INPUT_ASYNC=1','CLASSPATH=/data/local/tmp/hls09_input.jar','app_process','/system/bin','HlsGestureInput',x,y1,end,*points]
    p=subprocess.Popen([ADB,'-s',s,*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    raw=''
    while 'HLS_UP' not in raw:
        line=p.stdout.readline()
        if not line: raise RuntimeError('Gesture driver ended early: '+raw)
        raw+=line.decode('utf-8','replace')
    down=re.search(r'HLS_DOWN (\d+) (\d+) (\d+)',raw)
    up=re.search(r'HLS_UP (\d+) (\d+)',raw)
    if not down or not up: raise RuntimeError(raw)
    # UP 标记出现后立即采帧，不等待 app_process 清理 Binder 线程；进程交给后台回收。
    concurrent.futures.ThreadPoolExecutor(max_workers=1).submit(p.communicate)
    return {'down':int(down[1]),'boot_down':int(down[2]),'up':int(up[1]),'x':x,'y1':y1,'y2':y2,'hold':hold,'duration':duration}

def summary(paths,g):
    merged={}
    for p in paths:
        windows=parse_dump(p)
        for w,d in windows.items():
            if 'ThingsActivity' not in w: continue
            for f in d['frames']:
                if isinstance(f.get('IntendedVsync'),int) and f.get('Flags',0)==0 and g['down']-20000000<=f['IntendedVsync']<=g['up']+1100000000:
                    merged[f['IntendedVsync']]=f
    rows=[dict(vsync=f['IntendedVsync'],t=(f['IntendedVsync']-g['down'])/1e6,**stages(f)) for f in merged.values()]
    totals=[r['total'] for r in rows if 0<r.get('total',0)<1000]
    return {'frames':len(totals),'p50':pct(totals,.5),'p90':pct(totals,.9),'p99':pct(totals,.99),'max':max(totals,default=0),
            'over16':sum(t>16.667 for t in totals),'over33':sum(t>33.334 for t in totals),
            'deadline_miss':sum(r['overdue'] for r in rows),'intervals':sorted(set(r['interval'] for r in rows)),
            'worst':sorted(rows,key=lambda r:r.get('total',0),reverse=True)[:4], 'rows':sorted(rows,key=lambda r:r['vsync'])}

def collect(name,style,run='clean',control=False,trace=False,count=6):
    s=DEVICES[name]; d=OUT/name/(run+'-'+style+('-slide' if control else '')); d.mkdir(parents=True,exist_ok=False)
    backup(name)
    orig=(OUT/name/'original-particle_tuning.xml').read_bytes()
    orig_main=(OUT/name/('original-'+PREFS)).read_bytes()
    adb(s,'push',OUT/'driver/input.jar','/data/local/tmp/hls09_input.jar')
    sh(s,'am force-stop '+PKG)
    write_pref(s,'particle_tuning.xml',preference(orig,'particle_style',style))
    if control: write_pref(s,PREFS,preference(orig_main,'swipe_complete_animation','0','int'))
    lp=None; trace_pid=None
    try:
        sh(s,'input keyevent KEYCODE_WAKEUP')
        sh(s,'am start -W -n '+PKG+'/.activities.ThingsActivity')
        tree=dump(s,d/'start.xml')
        time.sleep(1)
        pid=int(sh(s,'pidof '+PKG).strip())
        save(d/'pid.txt',str(pid))
        log_file=(d/'logcat.txt').open('wb')
        lp=subprocess.Popen([ADB,'-s',s,'logcat','--pid='+str(pid),'-v','monotonic','-T','1'],stdout=log_file,stderr=subprocess.DEVNULL)
        save(d/'surface-start.txt',sh(s,'dumpsys SurfaceFlinger'))
        sh(s,'dumpsys gfxinfo '+PKG+' reset')
        time.sleep(1)
        save(d/'idle-framestats.txt',sh(s,'dumpsys gfxinfo '+PKG+' framestats'))
        if trace:
            config='''buffers { size_kb: 65536 fill_policy: RING_BUFFER }
duration_ms: 60000
data_sources { config { name: "linux.ftrace" ftrace_config {
 ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
 ftrace_events: "power/cpu_frequency" ftrace_events: "power/cpu_idle"
 atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input"
 atrace_categories: "dalvik" atrace_categories: "wm" atrace_categories: "am"
 atrace_apps: "com.ywwynm.everythingdone"
 drain_period_ms: 250
} } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "android.packages_list" } }
'''
            save(d/'trace-config.pbtxt',config)
            raw=sh(s,'perfetto --background-wait --txt -c - -o /data/misc/perfetto-traces/hls09.perfetto-trace',data=config.encode('utf-8'))
            save(d/'trace-start.txt',raw)
            trace_pid=int(raw.strip().splitlines()[-1])
        results=[]
        for k in range(count):
            tree=dump(s,d/('g%d-before.xml'%k))
            app_nodes=[n for n in tree.iter('node') if n.get('resource-id','').endswith('/rv_things')]
            if not app_nodes: raise RuntimeError('Home list missing')
            _,_,w,h=bounds(app_nodes[0])
            candidates=[]
            up=k%2==0
            for card in cards(tree):
                x1,y1,x2,y2=bounds(card)
                lo=max(y1+70,int(h*.3)); hi=min(y2-70,int(h*.82))
                if hi<lo: continue
                y=min(hi,max(lo,int(h*(.65 if up else .38))))
                score=(x2-x1)*(y2-y1)
                candidates.append((score,(x1+x2)//2,y,[x1,y1,x2,y2]))
            if not candidates: raise RuntimeError('No safe visible card')
            _,x,y,card=max(candidates)
            delta=int(h*.21)
            to=max(int(h*.22),y-delta) if up else min(int(h*.85),y+delta)
            if abs(to-y)<120: raise RuntimeError('Insufficient scroll distance')
            time.sleep(.3)
            sh(s,'dumpsys gfxinfo '+PKG+' reset')
            hold=160 if k>=count-2 else 0
            g=gesture(s,x,y,to,hold)
            g.update(index=k,card=card)
            save(d/('g%d-mark.json'%k),json.dumps(g))
            time.sleep(.12)
            p1=d/('g%d-early-framestats.txt'%k)
            save(p1,sh(s,'dumpsys gfxinfo '+PKG+' framestats'))
            time.sleep(.65)
            p2=d/('g%d-tail-framestats.txt'%k)
            save(p2,sh(s,'dumpsys gfxinfo '+PKG+' framestats'))
            r=summary([p1,p2],g); r.update(gesture=g)
            results.append(r)
            save(d/'summary.json',json.dumps(results,ensure_ascii=False,indent=2))
            print(json.dumps({'device':name,'style':style,'run':run,'slide':control,'g':k,**{key:r[key] for key in ['frames','p90','max','over16','over33','deadline_miss']}},ensure_ascii=False),flush=True)
        save(d/'surface-end.txt',sh(s,'dumpsys SurfaceFlinger'))
        dump(s,d/'end.xml')
    finally:
        if trace_pid:
            sh(s,'kill -INT '+str(trace_pid),check=False)
            for _ in range(20):
                if not sh(s,'ps -p '+str(trace_pid)+' -o PID',check=False).strip().endswith(str(trace_pid)): break
                time.sleep(.2)
            adb(s,'pull','/data/misc/perfetto-traces/hls09.perfetto-trace',d/'trace.perfetto-trace')
        if lp: lp.terminate(); lp.wait(timeout=10); log_file.close()
        sh(s,'am force-stop '+PKG)
        write_pref(s,'particle_tuning.xml',orig)
        if control: write_pref(s,PREFS,orig_main)
        sh(s,'am start -W -n '+PKG+'/.activities.ThingsActivity')
        save(d/'restored.txt','particle_tuning restored byte-for-byte; main preferences restored if changed')

if __name__=='__main__':
    ap=argparse.ArgumentParser(); ap.add_argument('action',choices=['inventory','collect']); ap.add_argument('--device',choices=list(DEVICES)); ap.add_argument('--style',choices=['flow','drift'],default='drift'); ap.add_argument('--run',default='clean'); ap.add_argument('--control',action='store_true'); ap.add_argument('--trace',action='store_true'); ap.add_argument('--count',type=int,default=6); a=ap.parse_args()
    names=[a.device] if a.device else list(DEVICES)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
        fn=inventory if a.action=='inventory' else lambda n:collect(n,a.style,a.run,a.control,trace=a.trace,count=a.count)
        for result in ex.map(fn,names): pass
