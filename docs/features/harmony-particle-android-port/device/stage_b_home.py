"""阶段 B：真实首页上的飘散入口（会改数据，只动本脚本新建的两条测试记事，结束时两条都在回收站）。

1. 新建测试记事 A：点 FAB（新建转场，粒子从 FAB 汇入）→ 输入纯数字标题 → 返回保存（新卡片入场）；
2. 左滑完成 A（首页左滑消散）；
3. 新建测试记事 B，长按进入多选 →「删除所选记事」（确认弹窗的出现与消散 + 多选移除）；
4. 抽屉 →「已完成」→ 选中 A →「删除所选记事」（已完成列表里的多选移除）。

标题用纯数字（输入法会改写拉丁字母，见 .claude/rules/adb.md）。每一步之前核对屏幕上是不是预期的那张卡片，
对不上就停，不去点别的记事。

用法（Bash 工具里跑）：
    python docs/features/harmony-particle-android-port/device/stage_b_home.py --run h1 --tag 0510
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import stage_b_probes as b  # noqa: E402


def parents(tree):
    return {child: parent for parent in tree.iter() for child in parent}


def card_of(tree, title):
    """标题所在的那张卡片：标题控件是 `tv_thing_title`，卡片 `cv_thing` 不在另一张卡片里（文件夹缩略图）。"""
    up = parents(tree)
    for n in tree.iter('node'):
        if not (n.get('resource-id') or '').endswith('/tv_thing_title') or n.get('text') != title:
            continue
        p, card = up.get(n), None
        while p is not None:
            if (p.get('resource-id') or '').endswith('/cv_thing'):
                if card is not None:
                    card = None  # 嵌在文件夹卡片里的缩略图
                    break
                card = p
            p = up.get(p)
        if card is not None:
            return card
    return None


def wait_card(title, tries=30, scroll=True):
    width, height = d.screen_size()
    for k in range(tries):
        tree = d.dump()
        card = card_of(tree, title)
        if card is not None:
            x1, y1, x2, y2 = d.bounds(card)
            if y1 > 300 and y2 < height - 200:
                return card
        if scroll and k % 3 == 2:
            d.adb('shell', 'input', 'swipe', width // 2, int(height * .7), width // 2, int(height * .45), 400)
        time.sleep(.6)
    raise RuntimeError('首页上找不到测试记事 ' + title)


def counter(tree):
    up = parents(tree)
    for n in tree.iter('node'):
        m = re.fullmatch(r'(\d+)\s*/\s*(\d+)', (n.get('text') or '').strip())
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def create(folder, title, tag):
    tree = d.dump('fab_create')
    fab = d.find(tree, rid='fab_create')
    rec = r.Recording(5)
    d.tap(fab)
    d.dump('et_title')
    video = rec.finish(folder / ('%s-create-open.mp4' % tag))
    out = {'open': b.sheet(folder, '%s-create-open' % tag, video, count=24)}
    d.tap(d.find(d.dump('et_title'), rid='et_title'))
    time.sleep(.5)
    d.adb('shell', 'input', 'text', title)
    d.dump(text=title)
    time.sleep(.5)
    rec = r.Recording(5)
    d.tap(d.find(d.dump('ib_back'), rid='ib_back'))
    d.dump('fab_create')
    video = rec.finish(folder / ('%s-create-save.mp4' % tag))
    out['save'] = b.sheet(folder, '%s-create-save' % tag, video, count=24)
    return out


def select(card):
    """长按（拖一下再回原位松手）进入多选，见 .claude/rules/adb.md。"""
    cx, cy = d.center(card)
    d.adb('shell', 'input', 'motionevent', 'DOWN', cx, cy)
    time.sleep(.8)
    d.adb('shell', 'input', 'motionevent', 'MOVE', cx, cy + 80)
    time.sleep(.25)
    d.adb('shell', 'input', 'motionevent', 'MOVE', cx, cy)
    time.sleep(.25)
    d.adb('shell', 'input', 'motionevent', 'UP', cx, cy)
    for _ in range(20):
        time.sleep(.3)
        got = counter(d.dump())
        if got is not None:
            return got
    raise RuntimeError('没有进入多选')


def delete_selected(folder, name, overflow):
    tree = d.dump('contextual_toolbar')
    if overflow:
        more = d.find(tree, desc=('更多选项', 'More options'))
        d.tap(more)
        item = None
        for _ in range(15):
            item = d.find(d.dump(), text=('删除所选记事', 'Delete selected'))
            if item is not None:
                break
            time.sleep(.2)
    else:
        item = d.find(tree, desc=('删除所选记事', 'Delete selected'))
    if item is None:
        raise RuntimeError('找不到「删除所选记事」')
    rec = r.Recording(4)
    d.tap(item)
    confirm = None
    for _ in range(30):
        tree = d.dump()
        confirm = d.find(tree, text=('确定', 'OK', 'Confirm'))
        if confirm is not None:
            break
        time.sleep(.2)
    video = rec.finish(folder / ('%s-confirm-appear.mp4' % name))
    out = {'confirm_appear': b.sheet(folder, '%s-confirm-appear' % name, video, count=24)}
    texts = ' '.join(n.get('text') or '' for n in tree.iter('node'))
    out['confirm_text'] = texts
    if not re.search(r'共\s*1\s*件', texts):
        raise RuntimeError('确认框里的件数不是 1：' + texts)
    rec = r.Recording(6)
    d.tap(confirm)
    video = rec.finish(folder / ('%s-run.mp4' % name))
    out['run'] = b.sheet(folder, '%s-run' % name, video, count=32)
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--tag', required=True, help='四位数字，拼进两条测试记事的标题')
    p.add_argument('--only-finished', action='store_true', help='只补跑最后一步：已完成列表里删 A')
    a = p.parse_args()
    assert re.fullmatch(r'\d{4}', a.tag)
    title_a, title_b = '9051%s1' % a.tag, '9051%s2' % a.tag
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    result = {'rotation_before': d.rotation_state(), 'titles': [title_a, title_b]}
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time'], stdout=log, stderr=subprocess.DEVNULL)
    if a.only_finished:
        try:
            d.launch()
            finished_delete(folder, result, title_a)
        finally:
            finish(folder, cat, log, result)
        return
    try:
        tree = d.launch()
        for title in (title_a, title_b):
            if card_of(tree, title) is not None:
                raise RuntimeError('首页上已经有同名记事 %s，换一个 --tag' % title)
        d.shot(folder, '00-home')
        result['create_a'] = create(folder, title_a, 'a')
        card = wait_card(title_a)
        d.shot(folder, '01-a-created')

        # 左滑完成 A
        width, _ = d.screen_size()
        x1, y1, x2, y2 = d.bounds(card)
        cy = (y1 + y2) // 2
        sx, ex = min(x2 - 60, width - 140), max(40, x1 - 40)
        rec = r.Recording(5)
        subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'input', 'swipe', str(sx), str(cy), str(ex), str(cy), '700'])
        video = rec.finish(folder / 'a-swipe-complete.mp4')
        result['swipe_a'] = b.sheet(folder, 'a-swipe-complete', video, count=32)
        time.sleep(1.0)
        result['a_left_underway'] = card_of(d.dump(), title_a) is None

        # 新建 B，多选删除
        result['create_b'] = create(folder, title_b, 'b')
        card = wait_card(title_b)
        got = select(card)
        result['select_b'] = got
        if got[0] != 1 or card_of(d.dump(), title_b) is None:
            raise RuntimeError('多选状态不对：%s' % (got,))
        d.shot(folder, '02-b-selected')
        result['delete_b'] = delete_selected(folder, 'b-delete', overflow=True)
        time.sleep(1.0)
        result['b_gone'] = card_of(d.dump(), title_b) is None

        finished_delete(folder, result, title_a)
    finally:
        finish(folder, cat, log, result)


def finished_delete(folder, result, title_a):
    """已完成列表里删 A。"""
    d.drawer_segment(1)
    time.sleep(1.0)
    # 切换列表后抽屉不会自己收起：点右侧遮罩收起，核对收起了再往下走。
    width, height = d.screen_size()
    for _ in range(5):
        if d.find(d.dump(), desc=('关闭导航抽屉', 'Close navigation drawer')) is None:
            break
        d.adb('shell', 'input', 'tap', width - 40, height // 2)
        time.sleep(.8)
    else:
        raise RuntimeError('抽屉收不起来')
    card = wait_card(title_a)
    got = select(card)
    result['select_a'] = got
    if got[0] != 1 or card_of(d.dump(), title_a) is None:
        raise RuntimeError('多选状态不对：%s' % (got,))
    result['delete_a'] = delete_selected(folder, 'a-delete', overflow=False)
    time.sleep(1.0)
    result['a_gone'] = card_of(d.dump(), title_a) is None
    d.shot(folder, '03-finished-after')


def finish(folder, cat, log, result):
    cat.terminate()
    log.close()
    result['rotation_after'] = d.rotation_state()
    text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    result['drift_first_frames'] = re.findall(r'飘散首帧上屏[^\n]*', text)
    result['drift_failures'] = re.findall(r'飘散样式回退[^\n]*\n[^\n]*', text)
    r.save_json(result, folder / 'result.json')
    for k, v in result.items():
        print(k, v if not isinstance(v, dict) else {kk: (vv if not isinstance(vv, dict) else vv.get('sheet')) for kk, vv in v.items()})


if __name__ == '__main__':
    main()
