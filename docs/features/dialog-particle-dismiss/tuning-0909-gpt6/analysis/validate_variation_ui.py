"""同一真实弹窗连续六次返回关闭：输入尺寸和方向相同，实例种子与起始区域变化。"""
from pathlib import Path
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET,sys,io,zipfile,hashlib
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import release_field,model_fingerprint

p=argparse.ArgumentParser();p.add_argument('serial',choices=['9018f404','R5CW20BLNKL']);p.add_argument('--count',type=int,default=6);p.add_argument('--output',default='device-variation');p.add_argument('--stamp-only',action='store_true');a=p.parse_args()
out=HERE/a.output/a.serial;out.mkdir(parents=True,exist_ok=True)
base=['E:/AndroidSDK/platform-tools/adb.exe','-s',a.serial]
prefix='com.ywwynm.everythingdone:id/'

def adb(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout.decode('utf-8','replace')
apk_path=next(p.split(':',1)[1] for p in adb('shell','pm','path','com.ywwynm.everythingdone').splitlines() if p.endswith('/base.apk'))
apk_bytes=subprocess.run(base+['exec-out','cat',apk_path],capture_output=True,check=True).stdout
with zipfile.ZipFile(io.BytesIO(apk_bytes)) as apk:
    installed_model=json.loads(apk.read('assets/particle-dismiss/model.json'))['model_hash']
identity=dict(model_hash=installed_model,installed_apk_sha256=hashlib.sha256(apk_bytes).hexdigest())
del apk_bytes
if a.stamp_only:
    path=out/'variation-ui.json';existing=json.loads(path.read_text('utf-8'))
    existing.update(identity);path.write_text(json.dumps(existing,ensure_ascii=False,indent=2),'utf-8')
    print(a.serial,identity,flush=True);sys.exit(0)
def nodes(tree,value):return [n for n in tree.iter('node') if n.get('resource-id')==prefix+value]
def bounds(node):return list(map(int,re.findall(r'-?\d+',node.get('bounds'))))
def dump():
    adb('shell','uiautomator','dump','/sdcard/particle-variation-ui.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/particle-variation-ui.xml'))
def wait(value):
    for _ in range(8):
        tree=dump()
        if nodes(tree,value):return tree
        time.sleep(.12)
    raise RuntimeError('未到达 '+value)
def tap(value,expected):
    tree=wait(value);node=nodes(tree,value);assert len(node)==1
    l,t,r,b=bounds(node[0]);adb('shell','input','tap',str((l+r)//2),str((t+b)//2))
    return wait(expected)

pid=adb('shell','pidof','com.ywwynm.everythingdone').split()[0]
def completed():return [s for s in adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','*:S').splitlines() if '完成 count=' in s]

wait('fab_create');tap('fab_create','act_add_attachment');rows=[];fields=[]
for i in range(a.count):
    tree=tap('act_add_attachment','tv_take_photo_as_bt')
    rect=bounds(tree.find('node'));before=completed()
    adb('shell','input','keyevent','4');start=time.monotonic()
    while time.monotonic()-start<10:
        lines=completed()
        if lines and lines[-1] not in before:break
        time.sleep(.1)
    else:raise RuntimeError('消散未完成')
    render=lines[-1];seed=int(re.search(r'seed=(-?\d+)',render).group(1))
    direction=float(re.search(r'direction=([-\d.]+)',render).group(1));assert direction==135
    field=release_field(96,96,direction,rect[2]-rect[0],rect[3]-rect[1],seed)
    fields.append(field)
    log=adb('logcat','-d','--pid',pid,'-s','ParticleMicroflake:I','*:S')
    preparation=re.findall(r'准备耗时 ([\d.]+) ms',log)
    stages=[s for s in log.splitlines() if '启动阶段 ' in s]
    row=dict(iteration=i+1,seed=seed,direction=direction,rect=rect,render=render,
             prepare_ms=float(preparation[-1]) if preparation else None,
             startup_stages=stages[-1] if stages else None)
    rows.append(row);wait('act_add_attachment');print(a.serial,'连续同向关闭',i+1,'种子',seed,flush=True)
tap('ib_back','fab_create')
assert len({r['seed'] for r in rows})==a.count and len({tuple(r['rect']) for r in rows})==1
differences=[float(np.mean(np.abs(fields[i]-fields[j]))) for i in range(a.count) for j in range(i)]
assert min(differences)>.002
report=dict(**identity,desktop_model_hash=model_fingerprint(),cases=rows,unique_seeds=a.count,source_release_mean_differences=differences,
    scope='真实关闭日志验证每次独立种子、相同尺寸和返回方向；释放场差异由这些实测种子按已跨端校验的共同规则重建，并非实屏像素差。')
(out/'variation-ui.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
print(a.serial,a.count,'次同方向、同弹窗变化通过，已回到主列表。',flush=True)
