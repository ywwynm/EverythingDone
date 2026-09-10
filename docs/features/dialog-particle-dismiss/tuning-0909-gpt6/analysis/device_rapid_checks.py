import argparse,subprocess,xml.etree.ElementTree as ET,json,re,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);a=p.parse_args()
root=Path(__file__).resolve().parents[1]/'device-r33'/a.serial;base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace')
def dump():
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml');return ET.fromstring(adb('shell','cat','/sdcard/particle-ui.xml'))
def window():
    w=adb('shell','dumpsys','window','windows')
    return [v for v in re.split(r'  Window #\d+',w) if 'com.ywwynm.everythingdone.activities.DetailActivity' in v and 'ty=APPLICATION ' in v]
def waitwindow(show):
    start=time.perf_counter()
    while time.perf_counter()-start<5:
        if bool(window())==show:return
    raise RuntimeError('弹窗窗口状态超时 '+str(show))
tree=dump();nodes=[n for n in tree.iter('node') if n.get('resource-id')=='com.ywwynm.everythingdone:id/act_add_attachment'];assert len(nodes)==1
b=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));xy=[str((b[0]+b[2])//2),str((b[1]+b[3])//2)]
assert not window()
rows=[]
for i in range(10):
    # 工具栏来自本次 UI 树；每次确认同一 Activity 的弹窗窗口已移除后立即重开。
    t=time.perf_counter();adb('shell','input','tap',*xy);waitwindow(True)
    elapsed=(time.perf_counter()-t)*1000
    adb('shell','input','keyevent','4');waitwindow(False)
    rows.append({'iteration':i,'openToBackMs':elapsed,'windowRemovedMs':(time.perf_counter()-t)*1000})
start=time.perf_counter()
while True:
    hierarchy=adb('shell','dumpsys','activity','com.ywwynm.everythingdone/.activities.DetailActivity')
    # DialogDimLayer 包装的是一个无 ID 的普通 View，必须按 Decor 直属子节点检查。
    dim_nodes=re.findall(r'^          android\.view\.View\{[^\n]+',hierarchy,re.M)
    if 'ParticleDismissOverlay{' not in hierarchy and not dim_nodes:break
    if time.perf_counter()-start>6:raise RuntimeError('动画层残留')
    time.sleep(.05)
tree=dump();assert any(n.get('resource-id')=='com.ywwynm.everythingdone:id/act_add_attachment' for n in tree.iter('node'))
assert not window()
(root/'rapid-after-hierarchy.txt').write_text(hierarchy,encoding='utf-8')
(root/'rapid-log.txt').write_text(adb('logcat','-d','-s','ParticleMicroflake:I','ParticleDismiss:E','AndroidRuntime:E','*:S'),encoding='utf-8')
report={'device':a.serial,'iterations':rows,'dialogWindowsAfter':0,'particleLayersAfter':0,'dimLayersAfter':0,'mainUiRestored':True}
(root/'rapid-checks.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
