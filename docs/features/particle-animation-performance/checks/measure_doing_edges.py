"""Doing 卡片底部回弹与真实急停；系统输入，不修改记事或计时记录。"""
import argparse,json,pathlib,subprocess,sys,time
from measure_doing_refresh_rate import ADB,PKG,adb,poll,save
p=argparse.ArgumentParser();p.add_argument('--mode',default='unified');p.add_argument('--tag',default='baseline')
p.add_argument('--record',action='store_true');p.add_argument('--trace',action='store_true');p.add_argument('--device',default='oppo')
p.add_argument('--bottom-only',action='store_true',default=True)
p.add_argument('--impact',action='store_true',default=True,help='每次从底部上方重新甩到底部，避免只测已在底部的拉伸')
p.add_argument('--fixture',choices=['real','text','image','video','checklist'],default='real')
p.add_argument('--repeats',type=int,default=6)
p.add_argument('--stops',type=int,default=0)
p.add_argument('--scheduler-check',action='store_true')
p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('tmp/doing-edge-scroll-20261014'))
p.add_argument('--input-jar',type=pathlib.Path,default=pathlib.Path('tmp/doing-edge-scroll-20261014/driver/input.jar'))
args=p.parse_args()
serial,thing={'oppo':('3B1629006YC00000',1257),'samsung':('R5CW20BLNKL',1259),'meizu':('192.168.100.67:40807',780)}[args.device]
root=args.output.resolve()
rid=f'edge-{args.fixture}-{args.mode}-{time.time_ns()}'
folder=root/args.tag/args.device/rid;folder.mkdir(parents=True)
remote=f'/sdcard/Android/data/{PKG}/files/presentation-regression/{rid}'
cmd=[ADB,'-s',serial,'shell','am','instrument','-w','-e','run_id',rid,'-e','scene','Doing','-e','mode',args.mode,
    '-e','style','drift','-e','existing_thing_id',str(thing if args.fixture=='real' else -1),'-e','images','false']
if args.fixture!='real':cmd+=['-e','doing_long_card','true','-e','doing_fixture',args.fixture]
if args.scheduler_check:cmd+=['-e','scheduler_check','true']
if args.trace:cmd+=['-e','beat_trace','true']
cmd+=[f'{PKG}/.views.particledismiss.ParticlePresentationRegressionProbe']
proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);rec=None
try:
    save(folder/'ready.json',poll(serial,remote+'/ready.json',seconds=25))
    def send(name,data):
        path=folder/name;path.write_text(data,encoding='utf-8');adb(serial,'push',path,remote+'/'+name+'-next')
        adb(serial,'shell','mv',remote+'/'+name+'-next',remote+'/'+name)
    def action(value):
        request={'action':value,'nonce':time.time_ns()};send('command.json',json.dumps(request))
        poll(serial,remote+'/command-done.json',lambda d:d==request)
    def ui():
        nonce=str(time.time_ns());send('ui-request',nonce);data=poll(serial,remote+'/ui.json',lambda d:d['request']==nonce)
        save(folder/'ui.json',data)
        if any(n.get('text')=='Android 应用兼容性' for n in data['nodes']):
            n=next(n for n in data['nodes'] if n.get('text')=='确定');a,b,c,d=n['bounds']
            adb(serial,'shell','input','tap',(a+c)//2,(b+d)//2);return {}
        return {n['resource-id'].rsplit('/',1)[-1]:n['bounds'] for n in data['nodes'] if n.get('resource-id')}
    action('business');time.sleep(3.8);action('scroll_top')
    deadline=time.monotonic()+8
    while True:
        nodes=ui()
        if 'cv_thing' in nodes and 'rv_thing_doing' in nodes:break
        assert time.monotonic()<deadline
    x,y,r,b=nodes['cv_thing'];vx,vy,vr,vb=nodes['rv_thing_doing'];cx=x+(r-x)*3//4
    low=vy+int((vb-vy)*(.82 if args.bottom_only else .59));high=vy+int((vb-vy)*(.18 if args.bottom_only else .41))
    save(folder/'geometry.json',{'card':[x,y,r,b],'viewport':[vx,vy,vr,vb],'thingId':thing if args.fixture=='real' else None,'fixture':args.fixture})
    deadline=time.monotonic()+30
    while True:
        action('media');media=poll(serial,remote+'/media.json')
        if args.fixture!='video' or (media.get('derivedPreviewReady') and any(d.get('running') for d in media['animated'])):break
        assert time.monotonic()<deadline,media
        time.sleep(.3)
    save(folder/'media-before.json',media)
    helper='/data/local/tmp/doing_real_checklist_input.jar'
    adb(serial,'push',args.input_jar,helper)
    def gesture(start,end,duration,release='UP',stop_after=None):
        env=['PARTICLE_INPUT_ASYNC=1','CLASSPATH='+helper]
        if stop_after is not None:env+=['HLS_STOP_AFTER_MS='+str(stop_after)]
        return adb(serial,'shell',*env,'app_process','/system/bin',
            'HlsGestureInput',cx,start,release,cx,end,duration)
    # 长样本先同向甩到底部。后续从底部上方重新撞边；此处不计入测量。
    if args.fixture!='real':
        for _ in range(5):gesture(low,high,80);time.sleep(1.4)
    if args.record:
        movie='/sdcard/Download/'+rid+'.mp4'
        rec=subprocess.Popen([ADB,'-s',serial,'shell','screenrecord','--size','608x1320','--bit-rate','10000000',
            '--time-limit','45',movie],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        save(folder/'record-start.json',{'hostNs':time.monotonic_ns()});time.sleep(.4)
    if args.trace:action('trace_reset')
    for i in range(args.repeats):
        near_bottom=args.impact and (i>0 or args.fixture!='real')
        if near_bottom:
            gesture(vy+int((vb-vy)*.3),vy+int((vb-vy)*.7),320,'CANCEL')
            time.sleep(.3)
        for direction,start,end in ([('bottom',low,high)] if args.bottom_only else [('bottom',low,high),('top',high,low)]):
            label=f'edge_{direction}_{i+1}';action('measure_'+label)
            if near_bottom:start,end=vy+int((vb-vy)*.59),vy+int((vb-vy)*.41)
            (folder/(label+'-input.txt')).write_text(gesture(start,end,80))
            time.sleep(1.5);action('end_'+label)
    # 手指按下停止惯性后发送 CANCEL，避免触发真实清单的点击。
    for i in range(args.stops):
        # 回到距底部约半屏处；UP 后 40ms 重新按下，必须在状态行中验证仍处于惯性。
        gesture(vy+int((vb-vy)*.2),vy+int((vb-vy)*.8),320,'CANCEL');time.sleep(.3)
        label=f'stop_{i+1}';action('measure_'+label)
        out=gesture(vy+int((vb-vy)*.59),vy+int((vb-vy)*.41),80,stop_after=40)
        (folder/(label+'-input.txt')).write_text(out)
        time.sleep(.8);action('end_'+label)
        # 再次向底部甩动，验证急停后仍可恢复；不计入急停窗口。
        gesture(low,high,80);time.sleep(1.1)
    if rec:
        data=rec.communicate(timeout=50)[0];(folder/'record.txt').write_bytes(data)
        assert rec.returncode==0,data
        adb(serial,'pull',movie,folder/'screen.mp4')
    action('media');save(folder/'media.json',poll(serial,remote+'/media.json'))
    action('stop');adb(serial,'shell','input','keyevent','4')
    deadline=time.monotonic()+6
    while 'rv_thing_doing' in ui():
        assert time.monotonic()<deadline,'返回后 Doing 页面仍可见'
        time.sleep(.1)
    time.sleep(.7)
    adb(serial,'shell','touch',remote+'/finish');out=proc.communicate(timeout=20)[0]
    (folder/'instrument.txt').write_bytes(out)
    assert b'INSTRUMENTATION_CODE: 0' in out and 'result=完成'.encode() in out,out
    adb(serial,'pull',remote+'/report.json',folder/'report.json')
    if args.trace:adb(serial,'pull',remote+'/beat-trace.json',folder/'beat-trace.json')
    report=json.loads((folder/'report.json').read_text(encoding='utf-8'))
    events={e['event'] for e in report['events']}
    assert {'doing_scene_closed','doing_scene_capture_resources_released','doing_scene_window_detached'}<=events if args.mode=='unified' else True
    if args.scheduler_check:assert 'doing_frame_callbacks_verified' in events
    print(json.dumps({'folder':str(folder),'complete':True}),flush=True)
finally:
    if proc.poll() is None:
        adb(serial,'shell','touch',remote+'/finish',check=False)
        try:proc.wait(timeout=15)
        except subprocess.TimeoutExpired:proc.terminate()
    if rec and rec.poll() is None:rec.terminate()
