"""不用长按（魅族上长按会被系统的识屏浮层截走），逐条从详情页删除「碰一碰模拟 · 样例记事」。

每一条：点开 → 核对详情页的标题确实是样例 → 菜单「删除」→ 如有确认框核对文案 → 回到首页核对条数减一。
任何一步对不上就停下。
"""
import re
import time

import dev
import final_run

TITLE = '碰一碰模拟 · 样例记事'


def count_things(tree):
    for n in tree.iter('node'):
        m = re.match(r'^(\d+)件记事$', (n.get('text') or '').strip())
        if m:
            return int(m.group(1))
    return None


def top_activity():
    lines = [l for l in dev.shell('dumpsys activity activities').splitlines() if 'topResumedActivity' in l]
    return lines[0] if lines else ''


final_run.relaunch()
for round_ in range(14):
    tree = dev.dump()
    total = count_things(tree)
    height = max(dev.bounds(n)[3] for n in tree.iter('node'))
    target = None
    for n in tree.iter('node'):
        if (n.get('resource-id') or '').endswith('/tv_thing_title') and TITLE in (n.get('text') or ''):
            if dev.bounds(n)[1] > 250 and dev.bounds(n)[3] < height - 200:
                target = n
                break
    if target is None:
        print('首页上没有样例了，共', total, '条')
        break
    dev.tap(*dev.center(target))
    for _ in range(20):
        time.sleep(0.3)
        if 'DetailActivity' in top_activity():
            break
    else:
        raise SystemExit('没有进入详情页，停下')
    time.sleep(0.8)
    detail = dev.dump()
    shown = [n.get('text') or '' for n in detail.iter('node') if TITLE in (n.get('text') or '')]
    if not shown:
        dev.shell('input keyevent 4')
        raise SystemExit('详情页里找不到样例标题，停下')
    dev.tap(*dev.center(dev.poll(desc='更多选项')))
    dev.tap(*dev.center(dev.poll(text='删除')))
    time.sleep(1.0)
    tree = dev.dump()
    confirm = dev.find(tree, text='确定')
    if confirm is not None and 'DetailActivity' in top_activity():
        texts = [n.get('text') for n in tree.iter('node') if n.get('text')]
        print('confirm dialog:', texts[:4])
        dev.tap(*dev.center(confirm))
        time.sleep(1.0)
    for _ in range(20):
        time.sleep(0.3)
        if 'ThingsActivity' in top_activity():
            break
    else:
        raise SystemExit('删除之后没有回到首页，停下')
    time.sleep(1.2)
    after = count_things(dev.dump())
    print('round', round_, 'deleted', shown[0], 'count', total, '->', after)
    if total is not None and after is not None and after != total - 1:
        raise SystemExit('条数没有减一，停下')
print('done')
