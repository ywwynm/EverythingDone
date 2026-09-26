"""核对分享时抬起的卡片与首页卡片一致。

用碰一碰模拟生成各种封面与内容的样例记事（`simulate_samples`，写法见 SimulatedReceiverTransport.Spec），
逐条：在首页截下这张卡片 → 打开详情页的分享 → 抬起之后截屏 → 在截屏里按模板匹配找首页那张卡片（先按原尺寸，
找不到再按缩小的比例找：卡片太高时会整体缩小），记下匹配分数、缩放与位置，并存一张左右对照图。
最后把样例逐条从详情页删除（移入回收站）、删掉调试开关文件。

用法（需要 OpenCV，用 conda 环境 everythingdone 的 python）：
    SERIAL=<序列号> python share_card_check.py <输出目录> [samples]
    SERIAL=<序列号> python share_card_check.py <输出目录> --cleanup-only    # 只删残留的样例

启动应用一律用 am start：monkey 会注入旋转事件、打开自动旋转（见 .claude/rules/adb.md）。开始与结束各读一次
自动旋转，变了就报。
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import cv2
import numpy as np

import dev

DEFAULT_SAMPLES = ','.join([
    'background@0.6', 'background@1.5', 'background@2.2!', 'background@0.8!',
    'left@0.8/35', 'right@1.6/55', 'left@1.2',
    'top@2', 'top@0.8!', 'bottom@1.3',
    'list', 'list!', 'text', 'text!',
])
TITLE = '碰一碰模拟 · 样例记事 %d'
PREFS = 'shared_prefs/tap_transfer_debug.xml'

CLEANUP_ONLY = '--cleanup-only' in sys.argv
args = [a for a in sys.argv[1:] if not a.startswith('--')]
out_dir = os.path.abspath(args[0])
samples = args[1] if len(args) > 1 else DEFAULT_SAMPLES
count = len(samples.split(','))
os.makedirs(out_dir, exist_ok=True)
dev.OUT = out_dir


def log(*args):
    print(*args, flush=True)


def top_activity():
    lines = [l for l in dev.shell('dumpsys activity activities').splitlines() if 'ResumedActivity' in l]
    return lines[0] if lines else ''


def wait_activity(name, tries=30):
    for _ in range(tries):
        time.sleep(0.3)
        if name in top_activity():
            return True
    return False


def rotation():
    return dev.shell('settings get system accelerometer_rotation').strip()


def launch():
    dev.shell('am start -n %s/.activities.ThingsActivity' % dev.PKG)
    if not wait_activity('ThingsActivity'):
        raise SystemExit('首页没有起来：%s' % top_activity())
    time.sleep(2.0)


def write_prefs(simulate):
    dev.shell('am force-stop ' + dev.PKG)
    time.sleep(0.8)
    if not simulate:
        dev.shell('run-as %s rm -f %s' % (dev.PKG, PREFS))
        return
    text = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <boolean name="simulate" value="true" />\n'
            '    <string name="simulate_samples">%s</string>\n</map>\n' % samples)
    subprocess.run([dev.ADB, '-s', dev.SERIAL, 'shell', 'run-as', dev.PKG, 'sh', '-c',
                    "'cat > %s.tmp && mv %s.tmp %s'" % (PREFS, PREFS, PREFS)], input=text.encode('utf-8'), check=True)
    head = dev.shell('run-as %s od -An -tx1 -N 4 %s' % (dev.PKG, PREFS)).split()
    assert head[0] == '3c', head


def parents(tree):
    return {c: p for p in tree.iter() for c in p}


def screen_size(tree):
    return (max(dev.bounds(n)[2] for n in tree.iter('node')), max(dev.bounds(n)[3] for n in tree.iter('node')))


def node_by_rid(tree, rid):
    for n in tree.iter('node'):
        if (n.get('resource-id') or '').endswith('/' + rid):
            return n
    return None


SAMPLE = re.compile(TITLE.replace('%d', r'(\d+)'))


def sample_cards(tree):
    """屏幕上的样例卡片：{k: (卡片 bounds, 标题 bounds)}。bounds 被裁到屏幕以内。"""
    up = parents(tree)
    out = {}
    for n in tree.iter('node'):
        if not (n.get('resource-id') or '').endswith('/tv_thing_title'):
            continue
        m = SAMPLE.fullmatch(n.get('text') or '')
        if not m:
            continue
        p = n
        while p is not None and not (p.get('resource-id') or '').endswith('/cv_thing'):
            p = up.get(p)
        if p is not None:
            out[int(m.group(1))] = (dev.bounds(p), dev.bounds(n))
    return out


def swipe(x, y1, y2):
    dev.shell('input swipe %d %d %d %d 900' % (x, y1, x, y2))
    time.sleep(0.9)


def receive(width, height):
    tree = dev.dump()
    bar = node_by_rid(tree, 'actionbar')
    start = max(int(height * 0.30), dev.bounds(bar)[3] + 60 if bar is not None else 0)
    end = min(start + int(height * 0.55), height - 200)
    dev.drag(width // 2, start, width // 2, end, steps=26, step_ms=18)
    button = dev.poll(rid='tv_tap_receive')
    x, y = dev.center(button)
    dev.tap(x, y)
    log('进入接收，等样例导入')
    time.sleep(28)
    dev.tap(x, y)
    time.sleep(2.0)


def limits(tree, height):
    """卡片完整可见的上下沿：顶栏之下、手势条之上。"""
    bar = node_by_rid(tree, 'actionbar')
    return (dev.bounds(bar)[3] if bar is not None else 0) + 12, height - 110


def at_top(tree):
    """列表在顶部附近：展开的头部标题整个露在顶栏之下（滚动之后它会收进顶栏）。"""
    title = node_by_rid(tree, 'tv_header_title')
    bar = node_by_rid(tree, 'actionbar')
    return title is not None and bar is not None and dev.bounds(title)[1] > dev.bounds(bar)[3]


def locate(width, height, want):
    """找样例：先看当前这一屏，再往下翻最多 12 屏，再往上翻到列表顶部为止。want(样例卡片) 返回要找的 k 或 None。

    不靠「界面树没变」判断到底：首页上自动播放的视频封面会让界面一直不空闲，读到的树可能是旧的。"""
    tree = dev.dump()
    k = want(sample_cards(tree))
    if k is not None:
        return k
    for _ in range(12):
        swipe(width // 2, int(height * 0.75), int(height * 0.40))
        k = want(sample_cards(dev.dump()))
        if k is not None:
            return k
    for _ in range(30):
        tree = dev.dump()
        k = want(sample_cards(tree))
        if k is not None:
            return k
        if at_top(tree):
            return None
        swipe(width // 2, int(height * 0.40), int(height * 0.75))
    return None


def stable_cards(k):
    """连续读两次界面树，第 k 张样例的位置一致才认（界面不空闲时读到的树可能是旧的）。"""
    for _ in range(6):
        a = sample_cards(dev.dump()).get(k)
        time.sleep(0.6)
        b = sample_cards(dev.dump()).get(k)
        if a is not None and a == b:
            return a
    raise SystemExit('第 %d 条样例的位置读不稳' % k)


def reveal(k, width, height):
    """让第 k 张样例卡片完整地露在顶栏之下、手势条之上（到了列表末尾或比可见范围还高时尽量露出）。
    返回 (卡片 bounds, 标题 bounds, 可见上沿, 可见下沿)。"""
    for _ in range(6):
        tree = dev.dump()
        top_limit, bottom_limit = limits(tree, height)
        card, title = stable_cards(k)
        if card[1] >= top_limit and card[3] <= bottom_limit:
            break
        if card[3] - card[1] >= bottom_limit - top_limit - 2 and card[1] >= top_limit:
            break
        if card[1] < top_limit:
            shift = min(top_limit - card[1] + 60, int(height * 0.35))
            swipe(width // 2, int(height * 0.40), int(height * 0.40) + shift)
        else:
            shift = min(card[3] - bottom_limit + 60, int(height * 0.35))
            swipe(width // 2, int(height * 0.75), int(height * 0.75) - shift)
        if stable_cards(k)[0] == card:
            break  # 滚不动了（列表末尾）
    tree = dev.dump()
    top_limit, bottom_limit = limits(tree, height)
    card, title = stable_cards(k)
    return card, title, top_limit, bottom_limit


def match(home, shot):
    """在 shot 里找 home（抬起的卡片放不下或要让开天线时整体缩小，横屏时可以缩到一成多）：先在四分之一分辨率上
    按 1.00–0.10、步长 0.02 粗找缩放，再回原分辨率在它附近 ±0.03 细找。返回 (分数, 缩放, 左上角)。"""
    def best_at(scales, template, image, factor):
        best = (-1.0, 1.0, (0, 0))
        for sc in scales:
            f = sc * factor
            t = template if f == 1.0 else cv2.resize(template, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)
            if min(t.shape[:2]) < 8 or t.shape[0] > image.shape[0] or t.shape[1] > image.shape[1]:
                continue
            r = cv2.matchTemplate(image, t, cv2.TM_CCOEFF_NORMED)
            _, score, _, loc = cv2.minMaxLoc(r)
            if score > best[0]:
                best = (score, sc, loc)
        return best

    quarter = 0.25
    small = cv2.resize(shot, None, fx=quarter, fy=quarter, interpolation=cv2.INTER_AREA)
    coarse = best_at([round(1.0 - 0.02 * i, 2) for i in range(46)], home, small, quarter)
    around = sorted({round(min(1.0, max(0.1, coarse[1] + d / 100.0)), 2) for d in range(-3, 4)}, reverse=True)
    return best_at(around, home, shot, 1.0)


def open_detail(k, title):
    """点标题打开（清单样例点卡片中间可能点在清单项上），核对详情页的标题。"""
    dev.tap((title[0] + title[2]) // 2, (title[1] + title[3]) // 2)
    if not wait_activity('DetailActivity'):
        raise SystemExit('第 %d 条没有打开详情页' % k)
    time.sleep(1.0)
    detail = dev.dump()
    titles = [n.get('text') for n in detail.iter('node') if (n.get('resource-id') or '').endswith('/et_title')]
    if titles != [TITLE % k]:
        dev.shell('input keyevent 4')
        raise SystemExit('第 %d 条打开的详情页不对：%s' % (k, titles))


def check(k, width, height, results):
    if locate(width, height, lambda cards: k if k in cards else None) is None:
        raise SystemExit('第 %d 条样例找不到' % k)
    card, title, top_limit, bottom_limit = reveal(k, width, height)
    complete = card[1] >= top_limit and card[3] <= bottom_limit
    home_path = dev.screencap('home-%02d.png' % k)
    home = cv2.imread(home_path)[card[1]:card[3], card[0]:card[2]]
    cv2.imwrite(os.path.join(out_dir, 'home-card-%02d.png' % k), home)
    open_detail(k, title)
    dev.tap(*dev.center(dev.poll(desc='更多选项')))
    dev.tap(*dev.center(dev.poll(text='分享')))
    time.sleep(1.9)
    lift_path = dev.screencap('lift-%02d.png' % k)
    dev.shell('input keyevent 4')
    time.sleep(1.2)
    dev.shell('input keyevent 4')
    if not wait_activity('ThingsActivity'):
        raise SystemExit('第 %d 条之后没有回到首页' % k)
    time.sleep(1.0)
    shot = cv2.imread(lift_path)
    score, scale, loc = match(home, shot)
    h, w = home.shape[:2]
    lw, lh = int(round(w * scale)), int(round(h * scale))
    lifted = shot[loc[1]:loc[1] + lh, loc[0]:loc[0] + lw]
    pair = np.zeros((max(h, lh), w + lw + 24, 3), np.uint8)
    pair[:, :] = (60, 60, 60)
    pair[:h, :w] = home
    pair[:lh, w + 24:w + 24 + lw] = lifted
    cv2.imwrite(os.path.join(out_dir, 'pair-%02d.png' % k), pair)
    row = {'k': k, 'home': [card[2] - card[0], card[3] - card[1]], 'complete': complete,
           'score': round(float(score), 4), 'scale': scale, 'lifted_at': [int(loc[0]), int(loc[1]), lw, lh]}
    log(json.dumps(row, ensure_ascii=False))
    results.append(row)


def cleanup(width, height):
    """样例逐条从详情页删除（移入回收站）；每删一条都从列表顶部重新找，核对详情页标题。"""
    removed = 0
    for _ in range(count + 20):
        k = locate(width, height, lambda cards: min(cards) if cards else None)
        if k is None:
            break
        _, title, _, _ = reveal(k, width, height)
        open_detail(k, title)
        dev.tap(*dev.center(dev.poll(desc='更多选项')))
        dev.tap(*dev.center(dev.poll(text='删除')))
        if not wait_activity('ThingsActivity'):
            raise SystemExit('清理：删除之后没有回到首页')
        time.sleep(1.2)
        removed += 1
        log('清理：已删除', TITLE % k)
    return removed


def main():
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    rot = rotation()
    log('自动旋转（开始）', rot)
    results = []
    if CLEANUP_ONLY:
        launch()
        width, height = screen_size(dev.dump())
        log('清理完成，共', cleanup(width, height), '条')
        write_prefs(False)
        launch()
        log('自动旋转（结束）', rotation(), '（开始时', rot, '）')
        return
    write_prefs(True)
    try:
        launch()
        tree = dev.dump()
        width, height = screen_size(tree)
        log('屏幕', width, height)
        receive(width, height)
        # 后收到的排在前面：从列表上方往下依次是最后一条到第一条
        for k in range(count, 0, -1):
            check(k, width, height, results)
    finally:
        with open(os.path.join(out_dir, 'result.json'), 'w', encoding='utf-8') as f:
            json.dump({'samples': samples, 'results': results}, f, ensure_ascii=False, indent=1)
        try:
            if 'ThingsActivity' not in top_activity():
                launch()
            tree = dev.dump()
            width, height = screen_size(tree)
            log('清理完成，共', cleanup(width, height), '条')
        finally:
            write_prefs(False)
            launch()
            log('自动旋转（结束）', rotation(), '（开始时', rot, '）')


main()
