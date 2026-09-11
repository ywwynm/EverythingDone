"""在指定设备独立建材并渲染；只写本功能的专用验证目录。"""
from pathlib import Path
import argparse,subprocess,time,json
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',default='device-unified');p.add_argument('--scenes',nargs='+');p.add_argument('--direction',type=float);p.add_argument('--touch-gap',type=float);p.add_argument('--seed',type=int);a=p.parse_args()
base=Path(__file__).resolve().parents[1];repo=next(x for x in base.parents if (x/'gradlew.bat').is_file())
out=base/a.output/a.serial;out.mkdir(parents=True,exist_ok=True)
adb=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def run(*args,check=True):
    r=subprocess.run(adb+list(args),capture_output=True,check=check)
    return r.stdout.decode('utf-8','replace').strip()

apk=repo/'app/build/outputs/apk/debug/app-debug.apk'
(out/'install.txt').write_text(run('install','-r',str(apk)),encoding='utf-8')
# 三星会冻结后台进程；让验证运行于正常前台应用，避免后台限制混入渲染结果。
run('shell','am','start','-W','-n','com.ywwynm.everythingdone/.activities.ThingsActivity')
remote='/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-unified'
run('shell','mkdir','-p',remote)
inputs=sorted((base/'android-unified-fixtures').glob('*.json'))+sorted((base/'android-unified-fixtures').glob('*.png'))
if a.scenes:inputs=[f for f in inputs if f.stem in a.scenes]
assert inputs and (not a.scenes or len(inputs)==2*len(a.scenes))
if a.direction is not None or a.touch_gap is not None or a.seed is not None:
    overrides=out/'inputs';overrides.mkdir(exist_ok=True)
    for i,path in enumerate(inputs):
        if path.suffix!='.json':continue
        meta=json.loads(path.read_text('utf-8'))
        if a.direction is not None:meta['direction']=a.direction
        if a.seed is not None:meta['seed']=a.seed
        if a.touch_gap is not None:meta['touch_gap']=a.touch_gap
        target=overrides/path.name;target.write_text(json.dumps(meta,ensure_ascii=False),'utf-8');inputs[i]=target
run('push',*[str(x) for x in inputs],remote+'/')
started=time.monotonic();replies=[]
for scene in a.scenes or [None]:
    run('shell','rm','-f',remote+'/generated/done.json',remote+'/generated/error.txt')
    extra=['--es','scene',scene] if scene else []
    replies.append(run('shell','am','broadcast','--include-stopped-packages','-n','com.ywwynm.everythingdone/.views.particledismiss.ParticleMicroflakeProbeReceiver',*extra))
    print(a.serial,'已开始独立建材',scene or '全部',flush=True)
    stage=time.monotonic()
    while time.monotonic()-stage<300:
        if run('shell','cat',remote+'/generated/error.txt',check=False):
            raise RuntimeError(run('shell','cat',remote+'/generated/error.txt'))
        done=run('shell','cat',remote+'/generated/done.json',check=False)
        if done.startswith('{'):
            r=json.loads(done);assert r['ok'] and (r['scenes']==[scene] if scene else len(r['scenes'])==len(inputs)//2);break
        time.sleep(2)
    else:raise TimeoutError(a.serial)
    if scene:
        files=run('shell','ls',remote+'/generated').splitlines()
        (out/'generated').mkdir(exist_ok=True)
        for name in files:
            if name.startswith(scene+'-') or name==scene+'.json':run('pull',remote+'/generated/'+name,str(out/'generated'))
    else:run('pull',remote+'/generated',str(out))
(out/'broadcast.txt').write_text('\n'.join(replies),encoding='utf-8')
print(a.serial,len(inputs)//2,'个素材独立生成与 GPU 渲染完成',round(time.monotonic()-started,1),'秒',flush=True)
