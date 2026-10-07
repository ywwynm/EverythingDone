"""使用既有观察器记录飘散弹窗逐帧节拍；动画期间不运行 UI 层级导出或录屏。"""
import argparse
import json
import re
import sys
import time

import drift_driver as d
import perf_compare as pc
import stage_b_probes as probes
import creation_launch_perf as observer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--rounds', type=int, default=2)
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,60}', args.run)
    folder = d.OUT / args.run
    folder.mkdir(parents=True, exist_ok=True)
    original = {}
    for path in (observer.ENABLED, observer.CONTROL):
        result = d.soft('shell', 'run-as', d.PACKAGE, 'cat', path)
        original[path] = result.stdout if result.returncode == 0 else None
    rotation = d.rotation_state()
    sys.path.insert(0, str(d.REPO / 'docs/features/particle-animation-performance'))
    import analyze_beats
    rows = []
    try:
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
        observer.write_private(observer.ENABLED, b'1')
        observer.control({})
        probes.start('ThingListRemovalProbeActivity', run_id=args.run, case='single')
        tree = d.dump(require='contextual_toolbar')
        width, height = d.screen_size(tree)
        for index in range(args.rounds):
            d.tap(d.find(tree, rid='contextual_toolbar'))
            probes.wait_text(('确定', 'OK'))
            # 确认对话框已经完整出现，记录仅覆盖随后一段消散。
            time.sleep(1.0)
            run = '%s-%d-%d' % (args.run, index, time.time_ns() // 1000000)
            observer.control(dict(run_id=run, kind='dialog', beatTrace=True, stallSampling=False))
            observer.await_json(observer.REMOTE + run + '/progress.json', lambda value: value.get('ready'))
            d.adb('shell', 'input', 'tap', width // 2, int(height * .12))
            # 固定取证窗口：期间不 dump，不录屏，不主动改帧率；结束后才验证 UI 状态。
            time.sleep(1.8)
            observer.control({})
            result = observer.await_json(observer.REMOTE + run + '/result.json')
            path = folder / ('observer-%d.json' % index)
            path.write_text(json.dumps(result), 'utf-8')
            rows.append(analyze_beats.report(path))
            print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
            tree = d.dump(require='contextual_toolbar')
            if d.find(tree, text=('确定', 'OK')) is not None:
                raise RuntimeError('消散完成后弹窗仍在')
        (folder / 'logcat.txt').write_bytes(d.adb('logcat', '-d', '-v', 'time', '-s',
                                               'ParticlePacing:I', 'ParticleDrift:I'))
        (folder / 'display.txt').write_bytes(d.adb('shell', 'dumpsys', 'display'))
    finally:
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
        for path, value in original.items():
            observer.write_private(path, value)
    result = dict(rows=rows, rotationBefore=rotation, rotationAfter=d.rotation_state())
    (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
