"""安装待测 APK 后，从实际桌面图标打开；不直接启动 Activity。"""
import argparse
import hashlib
import json
from pathlib import Path
import device_ui as ui

p=argparse.ArgumentParser()
p.add_argument('apk',type=Path)
a=p.parse_args()
assert a.apk.is_file()
print(ui.adb('install','-r',a.apk.resolve()).decode(),flush=True)
ui.adb('shell','input','keyevent','3')
tree=ui.dump()
icons=[n for n in tree.iter('node') if n.get('text')=='完事儿']
if not icons: icons=[n for n in tree.iter('node') if n.get('content-desc')=='完事儿' and n.get('clickable')=='true']
assert len(icons)==1, 'Launcher icon must be uniquely located in current UI'
ui.adb('shell','input','tap',*ui.center(icons[0]))
for _ in range(8):
    tree=ui.dump()
    cancel=[n for n in tree.iter('node') if n.get('resource-id','').endswith('/tv_cancel_as_bt_alert')]
    if cancel:
        ui.adb('shell','input','tap',*ui.center(cancel[0]))
        continue
    if any(n.get('resource-id','').endswith('/fab_create') for n in tree.iter('node')): break
else: raise RuntimeError('Actual launcher tap did not reach home')
path=ui.adb('shell','pm','path',ui.PACKAGE).decode().strip().removeprefix('package:')
installed=ui.adb('shell','sha256sum',path).decode().split()[0]
local=hashlib.sha256(a.apk.read_bytes()).hexdigest()
assert installed==local
print(json.dumps({'serial':ui.SERIAL,'sha256':local,'pid':ui.adb('shell','pidof',ui.PACKAGE).decode().strip()}),flush=True)
