"""真实首页小幅横滑后回到原点并 CANCEL；不会触发完成业务。"""
import argparse
import concurrent.futures
import json
import subprocess
from verify_optimization_20261009 import r


def run(name):
    s = r.DEVICES[name]
    original = (r.OUT / name / 'original-particle_tuning.xml').read_bytes()
    try:
        for style in ['flow', 'drift']:
            out = r.OUT / name / ('home-cancel-' + style)
            out.mkdir(exist_ok=True)
            r.sh(s, 'am force-stop ' + r.PKG)
            r.write_pref(s, 'particle_tuning.xml', r.preference(original, 'particle_style', style))
            r.sh(s, 'am start -W -n ' + r.PKG + '/.activities.ThingsActivity')
            for _ in range(4):
                tree = r.dump(s, out / 'before.xml')
                cards = r.cards(tree)
                if cards:
                    break
            assert cards, '真实首页尚未就绪'
            # 首次启动的卡片可能在入场动画中；确认连续两份层级几何稳定，再开始手势。
            previous = [r.bounds(n) for n in cards]
            stable = False
            for _ in range(4):
                tree = r.dump(s, out / 'ready.xml')
                cards = r.cards(tree)
                current = [r.bounds(n) for n in cards]
                if current == previous:
                    stable = True
                    break
                previous = current
            assert stable and cards
            candidates = [n for n in cards if r.bounds(n)[3] - r.bounds(n)[1] >= 220]
            card = candidates[0]
            x1, y1, x2, y2 = r.bounds(card)
            x, y = round(x1 + (x2 - x1) * .78), round((y1 + y2) / 2)
            target = round(x - (x2 - x1) * .32)
            pid = int(r.sh(s, 'pidof ' + r.PKG).strip())
            with (out / 'logcat.txt').open('wb') as log:
                lp = subprocess.Popen([r.ADB, '-s', s, 'logcat', '--pid=' + str(pid), '-v', 'monotonic', '-T', '1'], stdout=log, stderr=subprocess.DEVNULL)
                try:
                    raw = r.sh(s, 'PARTICLE_INPUT_ASYNC=1 CLASSPATH=/data/local/tmp/hls09_input.jar '
                               'app_process /system/bin HlsGestureInput ' + ' '.join(map(str,
                               [x, y, 'CANCEL', target, y, 350, target, y, 1200, x, y, 350, x, y, 160])))
                    r.save(out / 'driver.txt', raw)
                    end = r.dump(s, out / 'after.xml')
                    assert r.cards(end), '手势结束后首页卡片缺失'
                    r.save(out / 'surface-after.txt', r.sh(s, 'dumpsys SurfaceFlinger'))
                finally:
                    lp.terminate()
                    lp.wait(timeout=5)
            text = (out / 'logcat.txt').read_text(encoding='utf-8', errors='replace')
            ok = '首帧上屏' in text
            assert ok, (name, style, '真实宿主没有播放粒子')
            assert 'FATAL EXCEPTION' not in text and '样式回退' not in text
            print(json.dumps(dict(device=name, style=style, home_particles=True, cancelled=True)), flush=True)
    finally:
        r.sh(s, 'am force-stop ' + r.PKG)
        r.write_pref(s, 'particle_tuning.xml', original)
        r.sh(s, 'am start -W -n ' + r.PKG + '/.activities.ThingsActivity')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('devices', nargs='+', choices=list(r.DEVICES))
    a = p.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
        jobs = [ex.submit(run, n) for n in a.devices]
        for job in jobs:
            job.result()
