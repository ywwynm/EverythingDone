"""核对远端完整 APK，安装相同字节，再测真实关闭链；不发布、不改设备设置。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import urllib.request
import zipfile

p = argparse.ArgumentParser()
p.add_argument('serial', choices=['9018f404', 'R5CW20BLNKL'])
a = p.parse_args()
here = Path(__file__).resolve().parent
base = here.parent
repo = next(p for p in base.parents if (p / 'gradlew.bat').is_file())
output = 'analysis/startup-latency/published'
out = base / output / a.serial
out.mkdir(parents=True, exist_ok=True)
meta = json.loads((repo / 'app/build/outputs/update-debug-apk/latest.json').read_text('utf-8'))
url = meta['apkUrl'].split('/debug/apk/')[0] + '/debug/latest.json'
with urllib.request.urlopen(urllib.request.Request(url, headers={'Cache-Control': 'no-cache'}), timeout=30) as response:
    remote = json.load(response)
assert remote == meta, '远端更新通道与本地发布记录不一致'
local = repo / 'app/build/outputs/update-debug-apk' / meta['apkUrl'].rsplit('/', 1)[1]
apk = out / local.name
with urllib.request.urlopen(meta['apkUrl'], timeout=60) as response, apk.open('wb') as sink:
    shutil.copyfileobj(response, sink)
assert apk.stat().st_size == local.stat().st_size == meta['sizeBytes']
assert hashlib.sha256(apk.read_bytes()).hexdigest() == hashlib.sha256(local.read_bytes()).hexdigest() == meta['sha256']
with zipfile.ZipFile(apk) as archive:
    for resource in (repo / 'shared/particle-dismiss').iterdir():
        if resource.is_file() and not resource.name.startswith('.'):
            assert archive.read('assets/particle-dismiss/' + resource.name) == resource.read_bytes(), resource.name
    for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
        assert len(archive.read(f'lib/{abi}/libparticle_material.so')) > 0


def adb(*args):
    return subprocess.check_output(['E:/AndroidSDK/platform-tools/adb.exe', '-s', a.serial, *args],
                                   timeout=60).decode('utf-8', 'replace').strip()


(out / 'install.txt').write_text(adb('install', '-r', str(apk)), 'utf-8')
path = adb('shell', 'pm', 'path', 'com.ywwynm.everythingdone').splitlines()[0].removeprefix('package:')
assert re.fullmatch(r'/data/app/[A-Za-z0-9_\-/+=.~]+', path)
assert adb('shell', 'sha256sum', path).split()[0] == meta['sha256']
subprocess.run([sys.executable, '-X', 'utf8', str(here / 'measure_dismiss_startup.py'),
                a.serial, '--output', output, '--repeat', '1'], check=True)
rows = json.loads((out / 'startup.json').read_text('utf-8'))
assert {r['trigger'] for r in rows} == {'outside', 'back', 'cancel', 'confirm'}
report = dict(device=a.serial, debugUpdateCode=meta['debugUpdateCode'], apkUrl=meta['apkUrl'],
              sha256=meta['sha256'], sizeBytes=meta['sizeBytes'], remoteApkVerified=True,
              installedApkVerified=True, sharedResourcesVerified=True,
              requestToFirstMeanMs=statistics.mean(r['requestToFirstMs'] for r in rows),
              requestToVisibleMeanMs=statistics.mean(r['requestToVisibleMs'] for r in rows))
(out / 'published-checks.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
(out / 'published-metadata.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2), 'utf-8')
print(json.dumps(report, ensure_ascii=False), flush=True)
