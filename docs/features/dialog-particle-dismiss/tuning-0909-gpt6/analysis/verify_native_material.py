"""在实际 Android 运行时对照本地批量计算与原 Kotlin 路径，不改写材料基准。"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

p=argparse.ArgumentParser()
p.add_argument('serial',choices=['9018f404','R5CW20BLNKL'])
p.add_argument('--output',required=True)
a=p.parse_args()
out=Path(__file__).resolve().parents[1]/a.output/a.serial
out.mkdir(parents=True,exist_ok=True)
adb=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def run(*args):
    return subprocess.check_output(adb+list(args),timeout=25).decode('utf-8','replace')
run('shell','am','start','-W','-n','com.ywwynm.everythingdone/.activities.ThingsActivity')
check_id=str(uuid.uuid4())
run('shell','am','broadcast','-n','com.ywwynm.everythingdone/.views.particledismiss.ParticleMicroflakeProbeReceiver',
    '--ez','nativeCheck','true','--es','checkId',check_id)
remote='/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-unified/generated/native-material-parity.json'
deadline=time.monotonic()+120
while time.monotonic()<deadline:
    try:
        data=json.loads(run('shell','cat',remote))
        if data.get('checkId')==check_id:
            (out/'native-material-parity.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
            assert data['ok'],data.get('error')
            print('通过',len(data['cases']),'组；最大材料差值',max(max(x['maxErrors']) for x in data['cases']))
            break
    except (json.JSONDecodeError,subprocess.CalledProcessError):
        pass
    time.sleep(.5)
else:
    raise TimeoutError('本次本地材料对照未完成')
