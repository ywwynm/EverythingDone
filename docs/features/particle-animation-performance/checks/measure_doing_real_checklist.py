"""指定真实清单的只读纵向测量；无图像采集，默认沿用两机当前的飘散样式。"""
import argparse,concurrent.futures,json,pathlib,subprocess,time
from measure_doing_refresh_rate import ADB,PKG,adb,poll,save

ROOT=pathlib.Path(__file__).resolve().parents[4]
DEVICES={'samsung':('R5CW20BLNKL',1259),'meizu':('192.168.100.67:40807',780),
         'oppo':('3B1629006YC00000',1257)}

def run(name,repeat):
    serial,thing=DEVICES[name]
    assert args.fixture!='real' or thing is not None, '该设备未指定只读真实记事，使用隔离卡片'
    if args.fixture in ('image','video'):
        adb(serial,'shell','test','-s',f'/sdcard/Android/data/{PKG}/files/doing-refresh-fixtures/'+
            ('image.png' if args.fixture=='image' else 'video.mp4'))
    rid=f'real-checklist-{repeat}-{time.time_ns()}'
    folder=args.output/name/rid;folder.mkdir(parents=True)
    remote=f'/sdcard/Android/data/{PKG}/files/presentation-regression/{rid}'
    cmd=[ADB,'-s',serial,'shell','am','instrument','-w','-e','run_id',rid,
         '-e','scene','Doing','-e','mode','unified','-e','style','drift',
         '-e','existing_thing_id',str(thing if args.fixture=='real' else -1),'-e','images','false']
    if args.fixture!='real':cmd+=['-e','doing_fixture',args.fixture,'-e','doing_long_card','true']
    if args.beat_trace:cmd+=['-e','beat_trace','true']
    cmd+=[f'{PKG}/.views.particledismiss.ParticlePresentationRegressionProbe']
    proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    timeline=[]
    try:
        save(folder/'ready.json',poll(serial,remote+'/ready.json',seconds=25))
        def send(file,text):
            path=folder/file;path.write_text(text,encoding='utf-8')
            adb(serial,'push',path,remote+'/'+file+'-next')
            adb(serial,'shell','mv',remote+'/'+file+'-next',remote+'/'+file)
        def action(value):
            request={'action':value,'nonce':time.time_ns()}
            send('command.json',json.dumps(request));poll(serial,remote+'/command-done.json',lambda d:d==request)
        def ui():
            nonce=str(time.time_ns());send('ui-request',nonce)
            data=poll(serial,remote+'/ui.json',lambda d:d['request']==nonce)
            save(folder/'ui.json',data)
            if any(n.get('text')=='Android 应用兼容性' for n in data['nodes']) and any(
                    '16 KB' in str(n.get('text','')) for n in data['nodes']):
                button=next(n for n in data['nodes'] if n.get('text')=='确定')
                save(folder/'compatibility-dialog.json',data)
                a,b,c,d=button['bounds'];adb(serial,'shell','input','tap',(a+c)//2,(b+d)//2)
                return {}
            return {n['resource-id'].rsplit('/',1)[-1]:n['bounds'] for n in data['nodes'] if n.get('resource-id')}
        action('business');time.sleep(3.8);action('scroll_top')
        deadline=time.monotonic()+10
        while True:
            nodes=ui()
            if 'cv_thing' in nodes and 'rv_thing_doing' in nodes:break
            assert time.monotonic()<deadline,'未取得实际卡片边界'
            time.sleep(.2)
        x,y,r,b=nodes['cv_thing'];vx,vy,vr,vb=nodes['rv_thing_doing']
        # 避开左侧勾选框，始终进行有距离的竖向手势，不点击清单内容。
        cx=x+(r-x)*3//4;high=vy+(vb-vy)//5;low=vy+(vb-vy)*4//5
        save(folder/'geometry.json',{'card':[x,y,r,b],'viewport':[vx,vy,vr,vb],
            'thingId':thing if args.fixture=='real' else None,'fixture':args.fixture})
        for label,service in [('display','display'),('thermal','thermalservice'),('battery','battery'),('window','window')]:
            (folder/(label+'-before.txt')).write_text(adb(serial,'shell','dumpsys',service),encoding='utf-8')
        deadline=time.monotonic()+45
        while True:
            action('media');media=poll(serial,remote+'/media.json')
            save(folder/'media-before.json',media)
            loaded=args.fixture not in ('image','video') or (args.fixture=='image' and any(
                i.get('id','').startswith('iv_thing') and i.get('type')=='BitmapDrawable' for i in media['images'])) or (
                args.fixture=='video' and media.get('derivedPreviewReady') and any(
                    i.get('type')=='GifDrawable' and i.get('running') and i.get('frames',0)>1 for i in media['animated']))
            if loaded:break
            assert time.monotonic()<deadline, f'媒体未就绪：{media}'
            time.sleep(.5)
        if args.fixture=='video':
            time.sleep(.24);action('media');after=poll(serial,remote+'/media.json')
            save(folder/'media-advancing.json',after)
            assert media['animated']!=after['animated'], 'GIF 帧号没有变化'
        def swipe(label,up,duration):
            began=time.monotonic_ns()
            adb(serial,'shell','input','swipe',cx,low if up else high,cx,high if up else low,duration)
            timeline.append({'label':label,'up':up,'durationMs':duration,'hostStartNs':began,'hostEndNs':time.monotonic_ns()})
        if args.contact_compare or args.fling:
            helper=args.input_jar
            assert helper.is_file(), '缺少已编译的 HlsGestureInput 系统输入工具'
            remote_helper='/data/local/tmp/doing_real_checklist_input.jar'
            adb(serial,'push',helper,remote_helper)
            high=vy+(vb-vy)*3//10;low=vy+(vb-vy)*7//10
            def path(points,start):
                return adb(serial,'shell','PARTICLE_INPUT_ASYNC=1','CLASSPATH='+remote_helper,
                    'app_process','/system/bin','HlsGestureInput',cx,start,'UP',*points,timeout=35)
        if args.fling:
            # 短距离甩动后放开，留出实际惯性滚动距离；4 ms MOVE 与明确的 ACTION_UP。
            high=vy+int((vb-vy)*(1-args.fling_distance)/2)
            low=vy+int((vb-vy)*(1+args.fling_distance)/2)
            for block,duration in [('fling_medium',140),('fling_fast',80)]:
                action('scroll_top');time.sleep(.3);action('measure_'+block)
                logs=[]
                for i in range(args.flings_per_block):
                    start,end=(low,high) if i%2==0 else (high,low)
                    logs.append(path([cx,end,duration],start))
                    timeline.append({'label':block,'index':i+1,'up':i%2==0,'durationMs':duration,
                        'from':[cx,start],'to':[cx,end]})
                    time.sleep(args.fling_wait)
                (folder/(block+'-input.txt')).write_text('\n'.join(logs),encoding='utf-8')
                action('end_'+block)
        elif args.contact_compare:
            # 两种路径均为 4ms 异步输入、相同距离与速度；轮次交换顺序。
            for block in (['contact','lifted'] if repeat%2 else ['lifted','contact']):
                action('scroll_top');time.sleep(.3);action('measure_'+block)
                if block=='contact':
                    points=[]
                    for i in range(20):points.extend([cx,high if i%2==0 else low,700])
                    output=path(points,low)
                else:
                    logs=[]
                    for i in range(20):
                        logs.append(path([cx,high if i%2==0 else low,700],low if i%2==0 else high))
                        time.sleep(.10)
                    output='\n'.join(logs)
                (folder/(block+'-input.txt')).write_text(output,encoding='utf-8')
                time.sleep(.4);action('end_'+block)
        else:
            # 实际可滚动范围若不足一屏，按速度分组，不能把名称视为真实位置分层。
            for block,duration in [('top',1200),('middle',700),('bottom',180)]:
                action('nav_'+block)
                if block!='top':
                    for index in range(5):swipe('navigation_'+block,True,100)
                    time.sleep(.6)
                action('measure_'+block)
                for i in range(8):
                    up=i%2==0 if block!='bottom' else i%2!=0
                    swipe(f'{block}_{i+1}',up,duration)
                    time.sleep(.10 if duration>500 else .35)
                action('end_'+block)
        time.sleep(.5)
        action('media');save(folder/'media-after.json',poll(serial,remote+'/media.json'))
        for label,service in [('display','display'),('thermal','thermalservice'),('battery','battery')]:
            (folder/(label+'-after.txt')).write_text(adb(serial,'shell','dumpsys',service),encoding='utf-8')
        save(folder/'timeline.json',timeline)
        action('stop')
        if args.verify_exit:
            adb(serial,'shell','input','keyevent','4')
            deadline=time.monotonic()+5
            while 'rv_thing_doing' in ui():
                assert time.monotonic()<deadline, '退出后仍停留在 Doing 页面'
                time.sleep(.1)
            time.sleep(.7)
        adb(serial,'shell','touch',remote+'/finish')
        result=proc.communicate(timeout=20)[0].decode('utf-8',errors='replace')
        (folder/'instrument.txt').write_text(result,encoding='utf-8')
        adb(serial,'pull',remote+'/report.json',folder/'report.json')
        if args.beat_trace:adb(serial,'pull',remote+'/beat-trace.json',folder/'beat-trace.json')
        assert 'INSTRUMENTATION_CODE: 0' in result and 'result=完成' in result,result
        report=json.loads((folder/'report.json').read_text(encoding='utf-8'))
        assert report['droppedFrameEvents']==report['droppedLifecycleEvents']==0
        assert not any(any(w in e['event'] for w in ('failed','timeout','aborted')) for e in report['events'])
        if args.verify_exit:
            events={e['event'] for e in report['events']}
            assert {'doing_scene_closed','doing_scene_capture_resources_released','doing_scene_window_detached'}<=events
        print(json.dumps({'device':name,'round':repeat,'folder':str(folder),'status':'complete'}),flush=True)
        return str(folder)
    except Exception as error:
        save(folder/'error.json',{'error':repr(error)})
        raise
    finally:
        if proc.poll() is None:
            adb(serial,'shell','touch',remote+'/finish',check=False)
            try:proc.wait(timeout=12)
            except subprocess.TimeoutExpired:proc.terminate()
        if not (folder/'instrument.txt').exists():
            (folder/'instrument.txt').write_bytes(proc.communicate(timeout=15)[0])

def device(name):return [run(name,i+1) for i in range(args.rounds)]

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--devices',nargs='+',choices=DEVICES,default=list(DEVICES))
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--output',type=pathlib.Path,default=ROOT/'tmp/doing-real-checklist-20261014/baseline')
    parser.add_argument('--beat-trace',action='store_true')
    parser.add_argument('--verify-exit',action='store_true',help='测量结束后真实返回并核验页面资源释放')
    parser.add_argument('--contact-compare',action='store_true',help='同一速度／距离对照连续接触与每次抬手')
    parser.add_argument('--fling',action='store_true',help='短距离快速甩动后松手，分离惯性阶段')
    parser.add_argument('--fixture',choices=['real','text','checklist','video','image'],default='real',
        help='默认指定真实记事；其它值使用已有只读虚构长卡片')
    parser.add_argument('--fling-distance',type=float,default=.18,help='手指位移占视口比例')
    parser.add_argument('--flings-per-block',type=int,default=12)
    parser.add_argument('--fling-wait',type=float,default=1.1,help='UP 后等待秒数，长卡片使用更长等待')
    parser.add_argument('--input-jar',type=pathlib.Path,default=ROOT/'tmp/home-list-scroll-20261009/driver/input.jar',
        help='已编译的 HlsGestureInput；接触方式对照或 fling 需要')
    args=parser.parse_args();args.output=args.output.resolve()
    assert not (args.contact_compare and args.fling), '两种输入方案需要分别测量'
    assert 0<args.fling_distance<.9 and args.flings_per_block>0 and args.flings_per_block%2==0
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(args.devices)) as pool:
        results=list(pool.map(device,args.devices))
    save(args.output/'runs.json',results)
