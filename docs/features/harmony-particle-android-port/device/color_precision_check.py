"""真实设置页验收独立颜色精度、重启持久化与恢复默认；结束后恢复原偏好。"""
import json
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import drift_driver as d
import creation_launch_perf as observer


def main():
    folder = d.OUT/'color-precision-ui'
    folder.mkdir(parents=True, exist_ok=True)
    paths = ('shared_prefs/particle_tuning.xml', 'files/particle-perf-tuning.json')
    original = {}
    for path in paths:
        result = d.soft('shell', 'run-as', d.PACKAGE, 'cat', path)
        original[path] = result.stdout if result.returncode == 0 else None
    records = []
    def prefs():
        raw = d.adb('shell', 'run-as', d.PACKAGE, 'cat', paths[0])
        return {n.get('name'): n.get('value') if n.get('value') is not None else n.text for n in ET.fromstring(raw)}
    def scroll(direction):
        tree = d.dump('sv_particle_tuning_params')
        left, top, right, bottom = d.bounds(d.find(tree, rid='sv_particle_tuning_params'))
        x = (left+right)//2
        a, b = top+(bottom-top)//5, bottom-(bottom-top)//5
        if direction == 'down':
            a, b = b, a
        d.adb('shell', 'input', 'swipe', x, a, x, b, 250)
    def find(rid):
        direction = 'up' if '_style_' in rid else 'down'
        for _ in range(9):
            tree = d.dump()
            node = d.find(tree, rid=rid)
            if node is not None and d.bounds(node)[3]-d.bounds(node)[1] >= 80:
                return node
            scroll(direction)
        raise AssertionError(rid)
    def click(rid):
        d.tap(find(rid))
        # 读回设置，等待 apply 的异步落盘；不得只以点击成功作为断言。
        time.sleep(.3)
    def check(label, drift, flow):
        for _ in range(20):
            values = prefs()
            if (values.get('drift_color_precision_standard', 'false') == str(drift).lower() and
                    values.get('color_precision_standard', 'false') == str(flow).lower()):
                records.append(dict(step=label, preferences=values))
                return
            time.sleep(.1)
        raise AssertionError((label, values))
    started = int(d.adb('shell', 'date', '+%s').decode())
    try:
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
        observer.write_private(paths[0], b'<?xml version="1.0" encoding="utf-8"?><map/>')
        observer.write_private(paths[1], None)
        d.open_tuning_page()
        click('tv_particle_tuning_mode_dialog')
        find('tv_particle_tuning_color_high')
        check('default', False, False)
        click('tv_particle_tuning_color_standard')
        check('drift-standard', True, False)
        d.shot(folder, 'drift-standard')
        click('tv_particle_tuning_style_flow')
        find('tv_particle_tuning_color_high')
        check('flow-keeps-high', True, False)
        click('tv_particle_tuning_color_standard')
        check('both-standard', True, True)
        click('tv_particle_tuning_style_drift')
        click('tv_particle_tuning_color_high')
        check('drift-high-independent', False, True)
        click('tv_particle_tuning_color_standard')
        check('both-before-restart', True, True)
        d.open_tuning_page()
        find('tv_particle_tuning_color_standard')
        check('restart', True, True)
        click('tv_particle_tuning_reset')
        check('reset', False, False)
        assert prefs().get('particle_style', 'drift') == 'drift'
        logs = d.adb('logcat', '-d', '-v', 'epoch', '-s', 'ParticleDrift:I', 'ParticleMicroflake:I').decode('utf-8', 'replace')
        recent = [line for line in logs.splitlines() if line.split() and
                  line.split()[0].replace('.', '', 1).isdigit() and float(line.split()[0]) >= started]
        (folder/'logcat.txt').write_text('\n'.join(recent), 'utf-8')
        (folder/'result.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), 'utf-8')
        print('颜色精度 UI 检查通过：默认、独立保存、重启与恢复默认。', flush=True)
    finally:
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
        for path, value in original.items():
            observer.write_private(path, value)


if __name__ == '__main__':
    main()
