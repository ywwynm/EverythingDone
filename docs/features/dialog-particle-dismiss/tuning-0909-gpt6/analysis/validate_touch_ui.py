"""在真实语言弹窗背景点击两个距离，核对传到渲染器的距离；不更改语言选项。"""
from pathlib import Path
import argparse,subprocess,time,re,json,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',default='device-flow-shaping');p.add_argument('--record',action='store_true');a=p.parse_args()
HERE=Path(__file__).resolve().parents[1];out=HERE/a.output/a.serial
adb=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def run(*args):return subprocess.run(adb+list(args),capture_output=True,check=True,timeout=20).stdout.decode('utf-8','replace')
def dump(name):
    run('shell','uiautomator','dump','/sdcard/particle-ui.xml');raw=run('shell','cat','/sdcard/particle-ui.xml')
    (out/f'{name}.xml').write_text(raw,'utf-8');return ET.fromstring(raw)
def bounds(node):return list(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def find(tree,rid):
    rows=[n for n in tree.iter('node') if n.get('resource-id')==rid];assert len(rows)==1,(rid,len(rows));return rows[0]
def tap(node):
    x0,y0,x1,y1=bounds(node);run('shell','input','tap',str((x0+x1)//2),str((y0+y1)//2))
pid=run('shell','pidof','com.ywwynm.everythingdone').split()[0]
def completions():return [l for l in run('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','*:S').splitlines() if '完成 count=' in l]
settings=ET.fromstring((out/'touch-settings.xml').read_bytes());content=bounds(find(settings,'android:id/content'))
content[1]=max(content[1],bounds(find(settings,'com.ywwynm.everythingdone:id/actionbar'))[1])
results=[]
for label in ['near','far','back']:
    if label!='near':
        settings=dump('touch-between');tap(find(settings,'com.ywwynm.everythingdone:id/ll_app_language_as_bt'))
    dialog=dump('touch-'+label+'-before');x0,y0,x1,y1=bounds(find(dialog,'android:id/content'))
    available=y0-content[1];assert available>64,available
    short=min(x1-x0,y1-y0)
    distance=min(short*.08,available*.35) if label=='near' else available*.82
    x=(x0+x1)//2;y=round(y0-distance)
    # logcat 环形缓冲可能同时淘汰旧行，新增完成记录不能只用总行数判断。
    before=set(completions())
    recording=None
    if a.record:
        # 系统允许录屏时保留完整过程；不调整设备权限来绕过录屏限制。
        remote=f'/sdcard/particle-touch-{label}.mp4'
        dimensions=re.findall(r'(\d+)x(\d+)',run('shell','wm','size'))[-1]
        pw,ph=map(int,dimensions);rw=min(960,pw);rh=round(ph*rw/pw)//2*2
        record_log=(out/f'touch-{label}-recording.log').open('wb')
        recording=subprocess.Popen(adb+['shell','screenrecord','--size',f'{rw}x{rh}','--time-limit','6','--bit-rate','8000000',remote],
            stdout=subprocess.DEVNULL,stderr=record_log,creationflags=subprocess.CREATE_NO_WINDOW)
        record_log.close()
        ready=time.monotonic()
        while time.monotonic()-ready<3:
            if recording.poll() is not None:raise RuntimeError((out/f'touch-{label}-recording.log').read_text('utf-8'))
            if subprocess.run(adb+['shell','pidof','screenrecord'],capture_output=True,timeout=5).stdout.strip():break
            time.sleep(.1)
        else:raise RuntimeError('录屏未启动')
        dump('touch-'+label+'-recording')
    if label=='back':run('shell','input','keyevent','4')
    else:run('shell','input','tap',str(x),str(y))
    start=time.monotonic()
    while time.monotonic()-start<8:
        lines=[line for line in completions() if line not in before]
        if lines:line=lines[-1];break
        time.sleep(.12)
    else:raise RuntimeError('本次关闭没有完成日志')
    angle=float(re.search(r'direction=([-\d.]+)',line).group(1));gap=float(re.search(r'gap=([-\d.]+)',line).group(1))
    strength=float(re.search(r'strength=([-\d.]+)',line).group(1))
    if label=='back':assert abs(strength-.5)<1e-6
    if label=='back':assert abs(angle-135)<.02 and abs(gap-.65)<1e-5,(angle,gap)
    else:
        expected=(y0-y)/short
        assert abs(angle-90)<.2 and abs(gap-expected)<.006,(label,angle,gap,expected)
    current=dump('touch-'+label+'-closed');find(current,'com.ywwynm.everythingdone:id/ll_app_language_as_bt')
    hierarchy=run('shell','dumpsys','activity','com.ywwynm.everythingdone/.activities.SettingsActivity')
    assert 'ParticleDismissOverlay{' not in hierarchy,'动画层残留'
    results.append(dict(case=label,point=[x,y] if label!='back' else None,direction=angle,gap=gap,strength=strength,log=line))
    if recording is not None:
        assert recording.wait(timeout=10)==0,(out/f'touch-{label}-recording.log').read_text('utf-8')
        run('pull',remote,str(out/f'touch-{label}.mp4'))
    print(a.serial,label,angle,gap,flush=True)
assert results[1]['gap']>results[0]['gap']*1.7
assert results[1]['strength']>results[0]['strength']+.3
run('shell','input','keyevent','4');final=dump('touch-final');find(final,'com.ywwynm.everythingdone:id/act_search')
(out/'touch-ui-checks.json').write_text(json.dumps(dict(serial=a.serial,cases=results,final='ThingsActivity'),ensure_ascii=False,indent=2),'utf-8')
