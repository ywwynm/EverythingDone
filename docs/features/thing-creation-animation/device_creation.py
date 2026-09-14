"""从真实设置和首页入口验证新建、保存；每轮只创建一个专用测试记事。"""
import argparse
import json
import re
import subprocess
import sys
import device_ui as ui

p = argparse.ArgumentParser()
p.add_argument('mode', choices=['Ripple', 'Border light', 'Particles'])
p.add_argument('--name', required=True)
p.add_argument('--title', required=True)
a = p.parse_args()
assert a.title.startswith('CodexAnimation')

def tap(attr, value, expect=None):
    node = ui.find(ui.dump(), attr, value)
    ui.adb('shell', 'input', 'tap', *ui.center(node))
    return ui.wait_for(expect) if expect else ui.dump()

def record(resource, name, expect):
    result = subprocess.run([sys.executable, str(ui.Path(__file__).with_name('device_ui.py')), 'record-tap',
                             '--id', resource, '--name', name, '--expect', expect], capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))

tap('content-desc', 'Open Navigation Drawer', 'Settings')
tree = tap('text', 'Settings', 'll_app_language_as_bt')
for _ in range(5):
    if any(n.get('resource-id', '').endswith('/rl_create_animation_style_as_bt') for n in tree.iter('node')):
        break
    scroll = next(n for n in tree.iter('node') if n.get('scrollable') == 'true')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', scroll.get('bounds')))
    ui.adb('shell', 'input', 'swipe', (x1+x2)//2, y2-80, (x1+x2)//2, y1+80, 450)
    tree = ui.dump()
tap('resource-id', 'rl_create_animation_style_as_bt', 'Particles')
tap('text', a.mode)
tree = tap('text', 'CONFIRM', 'rl_create_animation_style_as_bt')
selected = ui.find(tree, 'resource-id', 'tv_create_animation_value').get('text')
assert selected == a.mode, selected
ui.capture(a.name + '-settings')
tap('content-desc', 'Navigate up', 'fab_create')
record('fab_create', a.name + '-open', 'et_title')
tap('resource-id', 'et_title')
ui.adb('shell', 'input', 'text', a.title)
ui.wait_for(a.title)
record('ib_back', a.name + '-save', 'fab_create')
tree = ui.wait_for(a.title)
result = {'mode':a.mode, 'title':a.title, 'selected':selected,
          'count':ui.find(tree, 'resource-id', 'tv_header_subtitle').get('text'), 'saved':True}
(ui.ROOT / (a.name + '.json')).write_text(json.dumps(result))
print(json.dumps(result), flush=True)
