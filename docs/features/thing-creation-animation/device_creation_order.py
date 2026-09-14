"""真实保存的逐帧顺序回归。仅操作 9018f404，测试标题必须以 CodexAnimation 开头。"""
import argparse
import json
import re
import subprocess
import sys
import time
import device_ui as ui

p = argparse.ArgumentParser()
p.add_argument('--run', required=True)
p.add_argument('--title', required=True)
p.add_argument('--select-particles', action='store_true')
p.add_argument('--scroll-before-create', action='store_true')
p.add_argument('--expect-failure', action='store_true')
a = p.parse_args()
assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', a.run)
assert re.fullmatch(r'CodexAnimation[a-zA-Z0-9_-]{1,80}', a.title)


def tap(attr, value, expect=None):
    node = ui.find(ui.dump(), attr, value)
    ui.adb('shell', 'input', 'tap', *ui.center(node))
    return ui.wait_for(expect) if expect else ui.dump()


if a.select_particles:
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
    tap('text', 'Particles')
    tree = tap('text', 'CONFIRM', 'rl_create_animation_style_as_bt')
    assert ui.find(tree, 'resource-id', 'tv_create_animation_value').get('text') == 'Particles'
    tap('content-desc', 'Navigate up', 'fab_create')

tree = ui.dump()
assert not any(n.get('text') == a.title for n in tree.iter('node')), '测试标题已存在'
initial_count = ui.find(tree, 'resource-id', 'tv_header_subtitle').get('text')
print('Arming real-save observer:', a.run, flush=True)
ui.adb('shell', 'am', 'start', '-n', ui.PACKAGE + '/.views.particledismiss.NewItemAppearanceProbeActivity',
       '--es', 'run_id', a.run, '--es', 'title', a.title)
ui.wait_for('fab_create')
if a.scroll_before_create:
    tree = ui.dump()
    rv = ui.find(tree, 'resource-id', 'rv_things')
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', rv.get('bounds')))
    ui.adb('shell', 'input', 'swipe', (x1+x2)//2, y1+(y2-y1)*3//4,
           (x1+x2)//2, y1+(y2-y1)//3, 600)
    tree = ui.dump()
    if not any(n.get('resource-id', '').endswith('/fab_create') for n in tree.iter('node')):
        # 向上浏览会自动收起首页工具栏；小幅回滚恢复按钮，仍保留列表滚动位置。
        rv = ui.find(tree, 'resource-id', 'rv_things')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', rv.get('bounds')))
        ui.adb('shell', 'input', 'swipe', (x1+x2)//2, (y1+y2)//2,
               (x1+x2)//2, (y1+y2)//2+(y2-y1)//10, 600)
    ui.wait_for('fab_create')
    ui.capture(a.run + '-scrolled-start')
tap('resource-id', 'fab_create', 'et_title')
tap('resource-id', 'et_title')
ui.adb('shell', 'input', 'text', a.title)
ui.wait_for(a.title)
print('Saving through DetailActivity toolbar', flush=True)
tap('resource-id', 'ib_back', 'fab_create')
tree = ui.wait_for(a.title)
final_count = ui.find(tree, 'resource-id', 'tv_header_subtitle').get('text')
remote = '/sdcard/Android/data/' + ui.PACKAGE + '/files/new-item-appearance/' + a.run + '/result.json'
for _ in range(50):
    result = subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'cat', remote], capture_output=True)
    if result.returncode == 0:
        break
    time.sleep(.2)
else:
    raise RuntimeError('保存完成后没有拿到探针结果')
data = json.loads(result.stdout)
data.update(initialCount=initial_count, finalCount=final_count)
(ui.ROOT / (a.run + '-order.json')).write_text(json.dumps(data, indent=2), encoding='utf-8')
ui.capture(a.run + '-final')
print(json.dumps({k:v for k,v in data.items() if k != 'samples'}, ensure_ascii=True), flush=True)
assert data['passed'] != a.expect_failure, '动画顺序断言不符合预期'
