"""只操作明确命名的回归记事，通过系统输入完成拖动／回拖。"""
import argparse
import importlib.util
import json
import re
import time
from pathlib import Path
from PIL import Image
import numpy as np

spec = importlib.util.spec_from_file_location('device_ui', Path(__file__).resolve().parents[1] / 'thing-creation-animation/device_ui.py')
ui = importlib.util.module_from_spec(spec); spec.loader.exec_module(ui)
p = argparse.ArgumentParser()
p.add_argument('kind', choices=['cancel', 'complete', 'reverse', 'slow-complete', 'system-cancel'])
p.add_argument('--title', default='CodexAnimation0913')
p.add_argument('--name', default='swipe')
a = p.parse_args()
assert a.title.startswith('CodexAnimation'), 'Only dedicated test things may be changed'
tree = ui.dump()
before_count = ui.find(tree, 'resource-id', 'tv_header_subtitle').get('text')
node = ui.find(tree, 'text', a.title)
parents = {c: n for n in tree.iter() for c in n}
while not node.get('resource-id', '').endswith('/cv_thing'):
    node = parents[node]
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
x, y = x2 - 60, (y1 + y2)//2
ui.capture(a.name + '-before')
events = []
start = time.perf_counter()
def send(kind, at):
    ui.adb('shell', 'input', 'touchscreen', 'motionevent', kind, round(at), y)
    events.append([kind, round(at), y, time.perf_counter()-start])
def move(at, steps=8):
    global x
    before = x
    for i in range(1, steps+1):
        send('MOVE', before+(at-before)*i/steps)
    x = at

if a.kind == 'complete':
    ui.adb('shell', 'input', 'swipe', x, y, 12, y, 90)
elif a.kind == 'slow-complete':
    ui.adb('shell', 'input', 'swipe', x, y, 12, y, 1400)
else:
    send('DOWN', x)
    move(max(15, x-(x2-x1)*.6))
    time.sleep(.8)
    ui.capture(a.name + '-held')
    time.sleep(.4)
    ui.capture(a.name + '-held-again')
if a.kind == 'reverse':
    move(x2-60-(x2-x1)*.2)
    time.sleep(.5)
    ui.capture(a.name + '-reversed')
    move(max(15, x2-60-(x2-x1)*.6))
    time.sleep(.5)
    ui.capture(a.name + '-forward-again')
if a.kind in ('cancel', 'reverse'):
    move(x2-60)
    send('UP', x)
elif a.kind == 'system-cancel':
    send('CANCEL', x)
time.sleep(1.6)
ui.capture(a.name + '-after')
after = ui.dump(a.name + '-after')
present = any(n.get('text') == a.title for n in after.iter('node'))
after_count = ui.find(after, 'resource-id', 'tv_header_subtitle').get('text')
result = {'kind':a.kind, 'title':a.title, 'bounds':[x1,y1,x2,y2], 'events':events, 'presentAfter':present,
          'countBefore':before_count, 'countAfter':after_count}
if a.kind not in ('complete', 'slow-complete'):
    def crop(suffix):
        return np.array(Image.open(ui.ROOT / f'{a.name}-{suffix}.png').convert('RGB').crop((x1,y1,x2,y2)), dtype=np.int16)
    before, held, held_again, final = map(crop, ['before','held','held-again','after'])
    result['heldStableMaxDifference'] = int(abs(held-held_again).max())
    result['afterMeanDifference'] = float(abs(before-final).mean())
    result['heldMeanDifference'] = float(abs(before-held).mean())
    if a.kind == 'reverse':
        result['reversedMeanDifference'] = float(abs(before-crop('reversed')).mean())
        result['sameProgressMaxDifference'] = int(abs(held-crop('forward-again')).max())
        assert result['sameProgressMaxDifference'] == 0, '回拖后继续前进改变了同一进度的粒子状态'
(ui.ROOT / (a.name + '.json')).write_text(json.dumps(result, indent=2))
print(json.dumps(result))
if a.kind in ('cancel', 'reverse', 'system-cancel') and (not present or before_count != after_count):
    raise RuntimeError('Cancelled gesture removed the test thing')
if a.kind in ('complete', 'slow-complete') and (present or before_count == after_count):
    raise RuntimeError('Quick fling did not finish the test thing')
