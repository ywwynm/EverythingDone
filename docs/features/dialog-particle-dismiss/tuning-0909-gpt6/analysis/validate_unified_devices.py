"""在指定设备独立建材并渲染；只写本功能的专用验证目录。"""
from pathlib import Path
import argparse,subprocess,time,json
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',default='device-unified');a=p.parse_args()
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
run('push',*[str(x) for x in inputs],remote+'/')
run('shell','rm','-f',remote+'/generated/done.json',remote+'/generated/error.txt')
reply=run('shell','am','broadcast','--include-stopped-packages','-n','com.ywwynm.everythingdone/.views.particledismiss.ParticleMicroflakeProbeReceiver')
(out/'broadcast.txt').write_text(reply,encoding='utf-8');print(a.serial,'已开始独立建材',flush=True)
started=time.monotonic()
while time.monotonic()-started<300:
    if run('shell','cat',remote+'/generated/error.txt',check=False):
        raise RuntimeError(run('shell','cat',remote+'/generated/error.txt'))
    done=run('shell','cat',remote+'/generated/done.json',check=False)
    if done.startswith('{'):
        r=json.loads(done);assert r['ok'] and len(r['scenes'])==len(inputs)//2;break
    time.sleep(2)
else:raise TimeoutError(a.serial)
run('pull',remote+'/generated',str(out))
print(a.serial,len(r['scenes']),'个素材独立生成与 GPU 渲染完成',round(time.monotonic()-started,1),'秒',flush=True)
