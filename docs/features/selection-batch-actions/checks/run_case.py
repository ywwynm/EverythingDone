from pathlib import Path
import subprocess, time, sys, json, threading
sys.stdout.reconfigure(encoding='utf-8')

ADB = 'E:/AndroidSDK/platform-tools/adb.exe'
PKG = 'com.ywwynm.everythingdone.batchvalidation'
ROOT = next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').is_file()) / 'tmp/selection-transition-20261013'
serial, run = sys.argv[1:3]
assert serial in ['R5CW20BLNKL', '3B1629006YC00000', '192.168.100.67:40807']
options = dict(arg.split('=', 1) for arg in sys.argv[3:])
out = ROOT / run
out.mkdir(exist_ok=True)
def adb(*args, **kw):
    kw.setdefault('timeout', 30)
    return subprocess.run([ADB, '-s', serial, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw)

# 唯一允许清数据的包为此脚本常量，不接受用户包名参数。
if serial == '3B1629006YC00000':
    assert adb('shell', 'am', 'force-stop', PKG).returncode == 0
else:
    assert adb('shell', 'pm', 'clear', PKG).stdout.strip() == b'Success'
if options.get('audit_appearance') == 'true' and serial != '3B1629006YC00000':
    for permission in ['READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO']:
        granted = adb('shell', 'pm', 'grant', PKG, 'android.permission.' + permission)
        assert granted.returncode == 0, granted.stderr.decode(errors='replace')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
remote = f'/sdcard/Android/data/{PKG}/files/batch-transition/{run}'
command = [ADB, '-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'run_id', run, '-e', 'wait_start', 'true']
for key, value in options.items(): command += ['-e', key, value]
command += [f'{PKG}/com.ywwynm.everythingdone.views.particledismiss.SelectionBatchTransitionProbe']
with (out / 'instrument.log').open('wb') as log:
    proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 55
    ready = False
    while proc.poll() is None and time.monotonic() < deadline:
        if adb('shell', 'test', '-f', remote + '/ready.json').returncode == 0:
            ready = True
            break
        time.sleep(.25)
    video = None
    metrics_stop = threading.Event()
    metrics_thread = None
    if ready:
        if options.get('measure_gfx') == 'true':
            adb('shell', 'dumpsys', 'gfxinfo', PKG, 'reset')
            def collect_metrics():
                while not metrics_stop.wait(2):
                    try:
                        data = adb('shell', 'dumpsys', 'gfxinfo', PKG, 'framestats').stdout
                        if b'Total frames rendered' in data:
                            (out / 'gfxinfo.txt').write_bytes(data)
                    except subprocess.TimeoutExpired:
                        return
            metrics_thread = threading.Thread(target=collect_metrics, daemon=True)
            metrics_thread.start()
        if options.get('record_video', 'true') == 'true':
            video = subprocess.Popen([ADB, '-s', serial, 'shell', 'screenrecord', '--time-limit', '12', '--bit-rate', '14000000', f'/sdcard/{run}.mp4'], stdout=subprocess.DEVNULL, stderr=(out/'screenrecord.log').open('wb'))
        time.sleep(.25)
        adb('shell', 'touch', remote + '/start')
    try: proc.wait(timeout=int(options.get('probe_timeout', '40')))
    except subprocess.TimeoutExpired:
        proc.kill()
        print('instrument timeout')
    metrics_stop.set()
    if metrics_thread: metrics_thread.join(timeout=31)
    if video:
        try: video.wait(timeout=15)
        except subprocess.TimeoutExpired:
            for pid in adb('shell','pidof','screenrecord').stdout.decode().split():
                cmdline=adb('shell','cat',f'/proc/{pid}/cmdline').stdout
                if f'/sdcard/{run}.mp4'.encode() in cmdline: adb('shell','kill','-2',pid)
            video.wait(timeout=5)
        adb('pull', f'/sdcard/{run}.mp4', str(out/'screen.mp4'))
        adb('shell', 'rm', f'/sdcard/{run}.mp4')
adb('pull', remote + '/.', str(out))
print((out/'instrument.log').read_text(encoding='utf-8', errors='replace'))
if (out/'result.json').exists():
    data = json.loads((out/'result.json').read_text())
    print({k:v for k,v in data.items() if k!='frames'})
    print('frames',len(data.get('frames',[])))
    sys.exit(0 if data.get('ok') else 1)
raise RuntimeError('探针没有生成 result.json')
