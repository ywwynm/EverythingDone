"""复用已安装 debug 回归入口，测 Doing 的驱动呈现及窗口刷新率；不改应用设置。"""
import argparse
import concurrent.futures
import json
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[4]
OUT = ROOT / 'tmp/doing-refresh-rate-20261014'
ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
PKG = 'com.ywwynm.everythingdone'
SERIALS = {'samsung': 'R5CW20BLNKL', 'tablet': '9018f404', 'meizu': '192.168.100.67:40807',
           'oppo': '3B1629006YC00000'}


def adb(serial, *args, check=True, timeout=30):
    proc = subprocess.run([ADB, '-s', serial, *map(str, args)], capture_output=True, timeout=timeout)
    output = proc.stdout.decode('utf-8', errors='replace').strip()
    if check and proc.returncode:
        raise RuntimeError(output + proc.stderr.decode('utf-8', errors='replace'))
    return output


def poll(serial, path, predicate=lambda value: True, seconds=12):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        text = adb(serial, 'shell', 'cat', path, check=False)
        try:
            data = json.loads(text)
            if predicate(data):
                return data
        except json.JSONDecodeError:
            pass
        time.sleep(.08)
    raise RuntimeError(f'等待文件超时：{path}')


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def run(name, style, fixture='text'):
    serial = SERIALS[name]
    run_id = f'refresh-{fixture}-{style}-{time.time_ns()}'
    folder = OUT / name / run_id
    folder.mkdir(parents=True)
    remote = f'/sdcard/Android/data/{PKG}/files/presentation-regression/{run_id}'
    command = [ADB, '-s', serial, 'shell', 'am', 'instrument', '-w',
               '-e', 'run_id', run_id, '-e', 'scene', 'Doing', '-e', 'mode', 'unified',
               '-e', 'style', style, '-e', 'doing_long_card', 'true',
               '-e', 'doing_fixture', fixture,
               '-e', 'images', 'false', '-e', 'particle_seed', '20261012',
               f'{PKG}/.views.particledismiss.ParticlePresentationRegressionProbe']
    if args.beat_trace:
        command[-1:-1] = ['-e', 'beat_trace', 'true']
    if args.method_trace:
        command[-1:-1] = ['-e', 'method_trace', 'true']
    instrument = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        ready = poll(serial, remote + '/ready.json', seconds=25)
        save(folder / 'harness-ready.json', ready)

        def send_file(filename, payload):
            path = folder / filename
            path.write_text(payload, encoding='utf-8')
            adb(serial, 'push', path, remote + '/' + filename + '-next')
            adb(serial, 'shell', 'mv', remote + '/' + filename + '-next', remote + '/' + filename)

        def action(value):
            request = {'action': value, 'nonce': time.time_ns()}
            send_file('command.json', json.dumps(request))
            poll(serial, remote + '/command-done.json', lambda data: data == request)

        def ui():
            nonce = str(time.time_ns())
            send_file('ui-request', nonce)
            data = poll(serial, remote + '/ui.json', lambda value: value['request'] == nonce)
            save(folder / 'ui.json', data)
            if any(n.get('text')=='Android 应用兼容性' for n in data['nodes']) and any(
                    '16 KB' in str(n.get('text','')) for n in data['nodes']):
                button=next(n for n in data['nodes'] if n.get('text')=='确定')
                save(folder/'compatibility-dialog.json',data)
                a,b,c,d=button['bounds'];adb(serial,'shell','input','tap',(a+c)//2,(b+d)//2)
                return {}
            return {node['resource-id'].rsplit('/', 1)[-1]: node['bounds']
                    for node in data['nodes'] if node.get('resource-id')}

        action('business')
        # 真实入场动画需结束，避免把入场滚动和按钮缩放算作手势帧率。
        time.sleep(3.5)
        def media(label):
            action('media')
            value = poll(serial, remote + '/media.json')
            save(folder / f'media-{label}.json', value)
            return value

        if fixture in ('image', 'video'):
            deadline = time.monotonic() + 45
            while True:
                value = media('loading')
                if fixture == 'image':
                    loaded = value.get('sourceExtension') == 'png' and any(
                        item.get('id', '').startswith('iv_thing') and item.get('type') == 'BitmapDrawable'
                        for item in value['images'])
                else:
                    loaded = (value.get('sourceExtension') == 'mp4' and value.get('derivedPreviewReady')
                              and any(item.get('type') == 'GifDrawable' and item.get('running')
                                      and item.get('frames', 0) > 1 for item in value['animated']))
                if loaded:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError(f'媒体未就绪：{fixture} {value}')
                time.sleep(.5)
            before = media('before')
            if fixture == 'video':
                time.sleep(.24)
                after = media('advancing')
                assert before['animated'] != after['animated'], 'GIF 帧号没有变化'
        deadline = time.monotonic() + 8
        while True:
            nodes = ui()
            if 'cv_thing' in nodes and 'rv_thing_doing' in nodes:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError('系统 UI 树未暴露卡片，不能派发或统计手势')
            time.sleep(.2)
        x, y, right, bottom = nodes['cv_thing']
        vx, vy, vr, vb = nodes['rv_thing_doing']
        assert right > x and vb - vy > 120, nodes
        cx, cy = (x + right) // 2, (vy + vb) // 2
        save(folder / 'geometry.json', {'card': [x, y, right, bottom], 'viewport': [vx, vy, vr, vb]})
        for label, dump_args in {
            'window': ('dumpsys', 'window', 'windows'),
            'display': ('dumpsys', 'display'),
            'battery': ('dumpsys', 'battery'),
            'thermal': ('dumpsys', 'thermalservice'),
            'layers': ('dumpsys', 'SurfaceFlinger', '--list'),
        }.items():
            (folder / (label + '-active.txt')).write_text(adb(serial, 'shell', *dump_args), encoding='utf-8')
        timeline = []

        def swipe(label, x0, y0, x1, y1, duration):
            began = time.monotonic_ns()
            adb(serial, 'shell', 'input', 'swipe', x0, y0, x1, y1, duration)
            timeline.append({'label': label, 'hostStartNs': began, 'hostEndNs': time.monotonic_ns(),
                             'from': [x0, y0], 'to': [x1, y1], 'durationMs': duration})

        for index in range(3):
            swipe(f'horizontal-{index + 1}', x + (right - x) * 3 // 4, cy,
                  x + (right - x) // 2, cy, 1800)
            time.sleep(1.2)
        action('scroll_top')
        swipe('vertical-down', cx, vy + (vb - vy) * 4 // 5, cx, vy + (vb - vy) // 5, 1800)
        time.sleep(.5)
        swipe('vertical-up', cx, vy + (vb - vy) // 5, cx, vy + (vb - vy) * 4 // 5, 1800)
        time.sleep(.5)
        save(folder / 'timeline.json', timeline)
        if fixture in ('image', 'video'):
            media('after')
        (folder / 'display-after.txt').write_text(adb(serial, 'shell', 'dumpsys', 'display'), encoding='utf-8')
        action('stop')
        adb(serial, 'shell', 'touch', remote + '/finish')
        output = instrument.communicate(timeout=20)[0].decode('utf-8', errors='replace')
        (folder / 'instrument.txt').write_text(output, encoding='utf-8')
        adb(serial, 'pull', remote + '/report.json', folder / 'report.json')
        if args.beat_trace:
            adb(serial, 'pull', remote + '/beat-trace.json', folder / 'beat-trace.json')
        if args.method_trace:
            adb(serial, 'pull', remote + '/methods.trace', folder / 'methods.trace')
        assert 'INSTRUMENTATION_CODE: 0' in output and 'result=完成' in output, output
        print(json.dumps({'device': name, 'style': style, 'fixture': fixture,
                          'folder': str(folder), 'status': 'complete'}), flush=True)
        return str(folder)
    except Exception as failure:
        save(folder / 'error.json', {'error': repr(failure)})
        print(json.dumps({'device': name, 'fixture': fixture, 'style': style,
                          'status': 'failed', 'error': repr(failure)}), flush=True)
        raise
    finally:
        if instrument.poll() is None:
            adb(serial, 'shell', 'touch', remote + '/finish', check=False)
            try:
                instrument.wait(timeout=10)
            except subprocess.TimeoutExpired:
                instrument.terminate()


def run_device(name):
    return [run(name, style, fixture) for fixture in args.fixtures for style in args.styles]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', choices=SERIALS)
    parser.add_argument('--devices', nargs='+', choices=SERIALS)
    parser.add_argument('--fixtures', nargs='+', choices=('text', 'image', 'video', 'checklist'), default=['text'])
    parser.add_argument('--styles', nargs='+', choices=('drift', 'flow'), default=['drift', 'flow'])
    parser.add_argument('--output', type=pathlib.Path)
    parser.add_argument('--beat-trace', action='store_true', help='单独采集阶段取证，不与普通 FPS 样本混合')
    parser.add_argument('--method-trace', action='store_true', help='单独采集方法采样，不能用于 FPS 对比')
    args = parser.parse_args()
    if args.output:
        OUT = args.output.resolve()
    targets = args.devices or ([args.device] if args.device else list(SERIALS))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(targets)) as pool:
        results = list(pool.map(run_device, targets))
    save(OUT / 'runs.json', results)
