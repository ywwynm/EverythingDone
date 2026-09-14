"""真实系统输入性能回归。只读观察器采样；不发送业务 Intent 或广播。"""
import argparse
import json
import re
import subprocess
import threading
import os
import time
import device_ui as ui

BASE = '/sdcard/Android/data/' + ui.PACKAGE + '/files/thing-animation-performance/'
ROOT = ui.ROOT / 'performance'
MEMORY_ONLY = False
GPU_CHECK = False


def control(data):
    # 仅传递采样标签。数据通过 stdin 写入固定诊断文件，不调用应用业务。
    command = 'cat > files/thing-animation-performance-control.tmp && mv files/thing-animation-performance-control.tmp files/thing-animation-performance-control.json'
    subprocess.run([ui.ADB,'-s',ui.SERIAL,'shell','run-as',ui.PACKAGE,'sh','-c',"'"+command+"'"],
                   input=json.dumps(data).encode(),capture_output=True,check=True)


def remote_json(path):
    r=subprocess.run([ui.ADB,'-s',ui.SERIAL,'shell','cat',path],capture_output=True)
    if r.returncode: return None
    try: return json.loads(r.stdout)
    except json.JSONDecodeError: return None


def wait_progress(run, key, seconds=16):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        data=remote_json(BASE+run+'/progress.json')
        if data and data.get(key): return data
        time.sleep(.2)
    raise RuntimeError(f'Observer {run}: {key} did not become true; last={data}')


def begin(run,kind,title,mode):
    folder=ROOT/run; folder.mkdir(parents=True,exist_ok=True)
    settings={'run_id':run,'kind':kind,'title':title,'mode':mode,'driver':'system-input','memoryOnly':MEMORY_ONLY,
              'gpuCheck':GPU_CHECK,'diagnosticOnly':GPU_CHECK}
    if GPU_CHECK and os.environ.get('THING_ANIMATION_GPU_SEED'):
        settings['gpuCheckSeed']=int(os.environ['THING_ANIMATION_GPU_SEED'])
    if GPU_CHECK and os.environ.get('THING_ANIMATION_GPU_FRAMES'):
        settings['gpuCheckFrames']=[int(f) for f in os.environ['THING_ANIMATION_GPU_FRAMES'].split(',')]
    control(settings)
    wait_progress(run,'ready')
    print('Measuring',run,flush=True)
    return folder


def end(run):
    wait_progress(run,'settled')
    control({})
    until=time.monotonic()+8
    while time.monotonic()<until:
        data=remote_json(BASE+run+'/result.json')
        if data:
            assert data['schema']==2 and data['driver']=='system-input-observed'
            (ROOT/run/'result.json').write_text(json.dumps(data),encoding='utf-8')
            return data
        time.sleep(.2)
    raise RuntimeError('Observer did not finish '+run)


def tap(attr,value,expect=None):
    tree=ui.dump()
    ui.adb('shell','input','tap',*ui.center(ui.find(tree,attr,value)))
    return ui.wait_for(expect) if expect else None


def tap_label(attr, values, expect=None):
    tree=ui.dump()
    candidates=[n for n in tree.iter('node') if n.get(attr) in values]
    assert len(candidates)==1, (attr,values,len(candidates))
    ui.adb('shell','input','tap',*ui.center(candidates[0]))
    return ui.wait_for(expect) if expect else None


def select(kind,mode):
    tap_label('content-desc',['Open Navigation Drawer','打开导航抽屉'])
    tree=tap_label('text',['Settings','设置'],'ll_app_language_as_bt')
    target='rl_swipe_complete_animation_as_bt' if kind.startswith('swipe') else 'rl_create_animation_style_as_bt'
    for _ in range(5):
        if any(n.get('resource-id','').endswith('/'+target) for n in tree.iter('node')): break
        scroll=next(n for n in tree.iter('node') if n.get('scrollable')=='true')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll.get('bounds')))
        ui.adb('shell','input','swipe',(x1+x2)//2,round(y1+(y2-y1)*.8),(x1+x2)//2,round(y1+(y2-y1)*.2),450)
        tree=ui.dump()
    tap('resource-id',target)
    values=([['Slide','Slide and fade','滑动','滑动渐隐'],['Particles','粒子']] if kind.startswith('swipe')
            else [['Ripple','涟漪'],['Border light','边框光效'],['Particles','粒子']])[mode]
    tap_label('text',values)
    tap_label('text',['CONFIRM','确定'],target)
    tap_label('content-desc',['Navigate up','向上导航','转到上一层级'],'fab_create')


def status(folder,name):
    for cmd in [('meminfo',ui.PACKAGE),('thermalservice',),('cpuinfo',)]:
        (folder/(name+'-'+cmd[0]+'.txt')).write_bytes(ui.adb('shell','dumpsys',*cmd))


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--run',required=True)
    p.add_argument('--kind',choices=['create','open-close','swipe','swipe-complete'],default='create')
    p.add_argument('--mode',type=int,default=2)
    p.add_argument('--rounds',type=int,default=3)
    p.add_argument('--title',required=True)
    p.add_argument('--tall',action='store_true')
    p.add_argument('--set-mode',action='store_true')
    p.add_argument('--memory-sample',action='store_true')
    p.add_argument('--gpu-check',action='store_true')
    a=p.parse_args()
    MEMORY_ONLY=a.memory_sample
    GPU_CHECK=a.gpu_check
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}',a.run)
    assert re.fullmatch(r'CodexAnimation[a-zA-Z0-9_-]{1,80}',a.title)
    if a.set_mode: select(a.kind,a.mode)
    batch=ROOT/a.run; batch.mkdir(parents=True,exist_ok=True)
    status(batch,'before')
    memory_stop=threading.Event()
    def sample_memory():
        rows=[]
        while not memory_stop.is_set():
            start=time.monotonic()
            raw=ui.adb('shell','dumpsys','meminfo',ui.PACKAGE).decode(errors='replace')
            fields={}
            for key in ['TOTAL PSS','TOTAL RSS','Java Heap','Native Heap','Graphics','Activities','Views']:
                values=re.findall(re.escape(key)+r':\s*(\d+)',raw)
                if values: fields[key]=int(values[-1])
            rows.append({'time':start,'values':fields})
            (batch/'memory-samples.json').write_text(json.dumps(rows),encoding='utf-8')
            memory_stop.wait(.5)
    if a.memory_sample:
        memory_thread=threading.Thread(target=sample_memory,daemon=True)
        memory_thread.start()
    for i in range(a.rounds):
        if a.kind in ('create','open-close'):
            title=a.title+str(i)
            assert not any(n.get('text')==title for n in ui.dump().iter('node')), 'Duplicate test title'
            opening=f'{a.run}-open-{i}'
            begin(opening,'open',title,a.mode)
            tap('resource-id','fab_create')
            wait_progress(opening,'editorReady')
            end(opening)
            ui.wait_for('et_title')
            if a.kind=='create':
                tap('resource-id','et_title')
                ui.adb('shell','input','text',title)
                ui.wait_for(title)
                if a.tall:
                    tap('resource-id','et_content')
                    for line in range(12):
                        ui.adb('shell','input','text','Performance%ssample%sline%s'+str(line))
                        ui.adb('shell','input','keyevent','66')
            saving=f'{a.run}-save-{i}'
            use_system_back=i%2==1
            begin(saving,'save-system-back' if use_system_back else 'save-button',title,a.mode)
            if use_system_back:
                ui.adb('shell','input','keyevent','4')
                tree=ui.dump()
                if any(n.get('resource-id','').endswith('/ib_back') for n in tree.iter('node')):
                    ui.adb('shell','input','keyevent','4')
            else: tap('resource-id','ib_back')
            ui.wait_for('fab_create')
            if a.kind=='create': ui.wait_for(title)
            end(saving)
        else:
            run=f'{a.run}-swipe-{i}'
            tree=ui.dump()
            before=ui.find(tree,'resource-id','tv_header_subtitle').get('text')
            node=ui.find(tree,'text',a.title)
            parents={c:n for n in tree.iter() for c in n}
            while not node.get('resource-id','').endswith('/cv_thing'): node=parents[node]
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            begin(run,a.kind,a.title,a.mode)
            start=x2-60
            finish=12 if a.kind=='swipe-complete' else max(12,start-(x2-x1)*.36)
            ui.adb('shell','input','swipe',start,(y1+y2)//2,round(finish),(y1+y2)//2,
                   90 if a.kind=='swipe-complete' else 1200)
            end(run)
            tree=ui.dump()
            present=any(n.get('text')==a.title for n in tree.iter('node'))
            after=ui.find(tree,'resource-id','tv_header_subtitle').get('text')
            if a.kind=='swipe': assert present and before==after
            else: assert not present and before!=after
        print('Completed round',i,flush=True)
    status(batch,'after')
    memory_stop.set()
    if a.memory_sample: memory_thread.join(timeout=4)
