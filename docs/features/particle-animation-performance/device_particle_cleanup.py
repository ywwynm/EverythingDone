"""收尾：把本轮专用测试记事移入可恢复的回收站，恢复档位，删除诊断开关。"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import device_particle_perf as d
import device_ui as ui
import device_performance as perf

# 909170xx 是华为那台的专用标题：它的 `input text` 把拉丁字母交给中文输入法改写，
# 而输入法属于设备设置、不得代为切换，所以那台只用数字。
TEST_TITLE = (r'(?:Perf(?:P0|B[0-9])-[0-9]{8}-[0-9]+|PROBE-[A-Z]+-[0-9]{8}|909170[0-9]{2}'
              r'|P儿0260917－1|CodexAnimation[A-Za-z0-9_-]+)')


def trash(titles):
    assert titles and len(titles) == len(set(titles)) and all(re.fullmatch(TEST_TITLE, t) for t in titles)
    tree = d.dump('fab_create')
    x, y = d.center(next(n for n in tree.iter('node') if n.get('text') == titles[0]))
    ui.adb('shell', 'input', 'swipe', x, y, x, y, 700)
    for index, title in enumerate(titles):
        if index:
            d.tap(d.node(d.dump(), text=title))
        tree = d.dump()
        assert any(re.fullmatch(str(index + 1) + r'\s*/\s*\d+', n.get('text', '')) for n in tree.iter('node')), '选择数量不符'
    labels = ['Delete selected things', '删除所选记事']
    if any(n.get('content-desc') in labels for n in tree.iter('node')):
        perf.tap_label('content-desc', labels)
    else:
        perf.tap_label('content-desc', ['More options', '更多选项'])
        perf.tap_label('text', labels)
    tree = d.dump()
    body = ' '.join(n.get('text', '') for n in tree.iter('node'))
    assert (str(len(titles)) + ' in total' in body or '共' + str(len(titles)) + '件' in body), body
    assert ('restored from the recycle bin' in body.lower() or '可在回收站恢复' in body), body
    perf.tap_label('text', ['CONFIRM', '确定'], 'fab_create')
    tree = d.dump('fab_create')
    assert not any(n.get('text') in titles for n in tree.iter('node'))
    return d.node(tree, rid='tv_header_subtitle').get('text')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--titles', nargs='+', required=True)
    p.add_argument('--expected-count', required=True)
    p.add_argument('--create-mode', type=int, default=-1)
    p.add_argument('--swipe-mode', type=int, default=-1)
    a = p.parse_args()
    perf.control({})
    d.probe(None)
    d.launch()
    subtitle = trash(a.titles)
    if a.create_mode >= 0:
        perf.select('create', a.create_mode)
    if a.swipe_mode >= 0:
        perf.select('swipe', a.swipe_mode)
    d.observer(False)
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE, 'rm', '-f',
                    'files/thing-animation-performance-control.json'], capture_output=True)
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'rm', '-f',
                    '/data/local/tmp/particle-perf-input.jar'], capture_output=True)
    time.sleep(1)
    prefs = ui.adb('shell', 'run-as', ui.PACKAGE, 'cat',
                   'shared_prefs/EverythingDone_preferences.xml').decode('utf-8', 'replace')
    files = ui.adb('shell', 'run-as', ui.PACKAGE, 'ls', 'files').decode('utf-8', 'replace').split()
    threads = ui.adb('shell', 'ps', '-T', '-A').decode('utf-8', 'replace')
    result = {'serial': ui.SERIAL, 'subtitle': subtitle, 'trashed': a.titles,
              'animation': [l.strip() for l in prefs.splitlines() if 'animation' in l],
              'switchFiles': [f for f in files if 'performance' in f or 'particle-perf' in f],
              # 第二批起 ParticleGl 与 ParticleClock 是进程级常驻线程（D4），各恰好一条是正常的；
              # 每次动画一条的 ParticleDismissGl 已经不存在，出现即为残留。
              'particleThreads': [l for l in threads.splitlines()
                                  if 'ParticleDismissGl' in l or 'ThingAnimation' in l],
              'residentThreads': [l for l in threads.splitlines()
                                  if 'ParticleGl' in l or 'ParticleClock' in l]}
    (d.ROOT / 'cleanup.json').write_text(json.dumps(result, indent=1, ensure_ascii=False), encoding='utf-8')
    assert result['subtitle'] == a.expected_count, result
    print(json.dumps(result, ensure_ascii=False))
