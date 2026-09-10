import argparse,subprocess,xml.etree.ElementTree as ET,json,re,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--output',default='device-unified');a=p.parse_args()
root=Path(__file__).resolve().parents[1]/a.output/a.serial;root.mkdir(parents=True,exist_ok=True);base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace')
def dump():
    adb('shell','uiautomator','dump','/sdcard/particle-ui.xml');return ET.fromstring(adb('shell','cat','/sdcard/particle-ui.xml'))
def window():
    w=adb('shell','dumpsys','window','windows')
    return [v for v in re.split(r'  Window #\d+',w) if 'com.ywwynm.everythingdone.activities.DetailActivity' in v and 'ty=APPLICATION ' in v]
def waitwindow(show):
    start=time.perf_counter();ready_since=None
    while time.perf_counter()-start<5:
        windows=window()
        if not show and not windows:return
        if show and windows:
            # 窗口登记早于输入焦点转移；过早注入返回会落到编辑器或输入法。
            focus=adb('shell','dumpsys','window','displays')
            match=re.search(r'mCurrentFocus=Window\{([^ ]+)',focus)
            if match and any('Window{'+match.group(1)+' ' in w and 'isReadyForDisplay()=true' in w for w in windows):
                input_state=adb('shell','dumpsys','input')
                input_focus=re.search(r'FocusedWindows:\s*\n[^\n]*name=\x27([^ ]+)',input_state)
                if input_focus and input_focus.group(1)==match.group(1):
                    if ready_since is None:ready_since=time.perf_counter()
                    if time.perf_counter()-ready_since>=.10:return
                else:ready_since=None
            else:ready_since=None
        time.sleep(.02)
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
