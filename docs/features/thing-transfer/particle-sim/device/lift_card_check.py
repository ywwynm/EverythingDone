"""核对分享时抬起的卡片（决策 2026-09-26「抬起的卡片放大、按首页字号排，放不下删减而不缩小」）。

用碰一碰模拟生成各种封面与内容的样例记事（写法见 SimulatedReceiverTransport.Spec，`+text` / `+list` 给带封面的
样例加上长正文或长清单），逐条：在首页截下这张卡片 → 打开详情页的分享 → 抬起之后截屏 → 从这次会话的记录
（tap-fixtures/<时间>-send-session/session.json 的 start 事件）读出卡片的尺寸与目标缩放，从截屏里取出抬起的
卡片，与首页那张按原尺寸并排存一张对照图（字号应当一样大）。同时收集应用的 TapLift 日志：每张卡片不删减时的
高度、删减到几行或几条、封面压到多高、排版耗时。

--stack a,b,c,d：另外在首页进入选择模式、选中这几条样例一起分享，核对叠放顺序（面积小的在上，最大的在最下面）
与整叠的投影（截屏目检）。进入选择模式用 motionevent 长按再拖一下（One UI 上可用，Flyme 会被识屏截走，
见 .claude/rules/adb.md），只在三星上用。

--record k：另外录一段第 k 条样例从详情页分享的完整发送。调试开关打开时模拟发送方约 3.65 秒后碰上来，
录下抬起、投影压下、流出、面板进度条、已发送与重新抬起（录 10 秒）。与 --stack 同用时叠放那一次也录。

最后把样例逐条从详情页删除（移入回收站）、删掉这次生成的会话记录与调试开关文件。启动应用一律 am start。

用法（需要 OpenCV，用 conda 环境 everythingdone 的 python）：
    SERIAL=<序列号> python lift_card_check.py <输出目录> [samples] [--stack 1,2,3,4] [--record k]
    SERIAL=<序列号> python lift_card_check.py <输出目录> --cleanup-only
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
    'text', 'text!', 'list', 'list!',
    'top@0.8+text', 'top@0.6!+list', 'background@0.6+text', 'background@0.5!',
    'left@0.8/35+text', 'right@1.6/55+list', 'bottom@1.3+text',
    'top@2', 'plain', 'wide',
])
TITLE = '碰一碰模拟 · 样例记事 %d'
PREFS = 'shared_prefs/tap_transfer_debug.xml'
FIXTURES = '/sdcard/Android/data/%s/files/tap-fixtures' % dev.PKG

CLEANUP_ONLY = '--cleanup-only' in sys.argv
STACK = None
RECORD = None
argv = sys.argv[1:]
if '--stack' in argv:
    i = argv.index('--stack')
    STACK = [int(v) for v in argv[i + 1].split(',')]
    del argv[i:i + 2]
if '--record' in argv:
    i = argv.index('--record')
    RECORD = int(argv[i + 1])
    del argv[i:i + 2]
RECORD_S = 10
args = [a for a in argv if not a.startswith('--')]
out_dir = os.path.abspath(args[0])
samples = args[1] if len(args) > 1 else DEFAULT_SAMPLES
count = len(samples.split(','))
os.makedirs(out_dir, exist_ok=True)
dev.OUT = out_dir


def log(*a):
    print(*a, flush=True)


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
    dismiss_crash_dialog()


def dismiss_crash_dialog():
    """应用崩溃过的话，下次启动先弹崩溃反馈框：点「取消」，不点「现在反馈」（见 adb.md）。"""
    tree = dev.dump()
    if dev.find(tree, text='现在反馈') is not None:
        cancel = dev.find(tree, text='取消')
        if cancel is not None:
            dev.tap(*dev.center(cancel))
            time.sleep(1.0)


def write_prefs(simulate):
    dev.shell('am force-stop ' + dev.PKG)
    time.sleep(0.8)
    if not simulate:
        dev.shell('run-as %s rm -f %s' % (dev.PKG, PREFS))
        return
    text = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <boolean name="simulate" value="true" />\n'
            '    <boolean name="dump_fixtures" value="true" />\n'
            '    <string name="simulate_samples">%s</string>\n</map>\n' % samples)
    subprocess.run([dev.ADB, '-s', dev.SERIAL, 'shell', 'run-as', dev.PKG, 'sh', '-c',
                    "'cat > %s.tmp && mv %s.tmp %s'" % (PREFS, PREFS, PREFS)], input=text.encode('utf-8'), check=True)
    head = dev.shell('run-as %s od -An -tx1 -N 4 %s' % (dev.PKG, PREFS)).split()
    assert head[0] == '3c', head


def session_dirs():
    out = dev.shell('ls %s' % FIXTURES, check=False)
    return sorted(l.strip() for l in out.splitlines() if l.strip().endswith('-send-session'))


def read_session(before):
    """这次分享新写的会话记录（关闭分享界面之后才写）。"""
    for _ in range(30):
        new = [d for d in session_dirs() if d not in before]
        if new:
            text = dev.shell('cat %s/%s/session.json' % (FIXTURES, new[-1]), check=False)
            if text.strip().startswith('{'):
                try:
                    return new[-1], json.loads(text)
                except json.JSONDecodeError:
                    pass
        time.sleep(0.4)
    raise SystemExit('没有读到这次分享的会话记录')


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


_status_bar = None


def status_bar_bottom():
    """状态栏的下沿（dumpsys window 里 statusBars 的 frame），读一次缓存。"""
    global _status_bar
    if _status_bar is None:
        m = re.search(r'type=statusBars frame=\[\d+,\d+\]\[\d+,(\d+)\]', dev.shell('dumpsys window', check=False))
        _status_bar = int(m.group(1)) if m else 0
    return _status_bar


def limits(tree, height):
    """卡片完整可见、可以点的上下沿：顶栏与状态栏之下、手势条之上。列表滚动之后顶栏会收起，横屏时一张比屏幕
    还高的卡片就可能把标题停在状态栏底下，点下去点的是状态栏（2026-09-26 魅族横屏实际发生）。"""
    bar = node_by_rid(tree, 'actionbar')
    top = max(dev.bounds(bar)[3] if bar is not None else 0, status_bar_bottom())
    return top + 12, height - 110


def at_top(tree):
    title = node_by_rid(tree, 'tv_header_title')
    bar = node_by_rid(tree, 'actionbar')
    return title is not None and bar is not None and dev.bounds(title)[1] > dev.bounds(bar)[3]


def locate(width, height, want):
    """找样例：先看当前这一屏，再往下翻最多 12 屏，再往上翻到列表顶部为止。"""
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
            # 删除后的移除动画期间读到的树可能缺了列表那一支：到顶了也再读一次才下结论
            time.sleep(1.0)
            return want(sample_cards(dev.dump()))
        swipe(width // 2, int(height * 0.40), int(height * 0.75))
    return None


def stable_cards(k):
    for _ in range(6):
        a = sample_cards(dev.dump()).get(k)
        time.sleep(0.6)
        b = sample_cards(dev.dump()).get(k)
        if a is not None and a == b:
            return a
    raise SystemExit('第 %d 条样例的位置读不稳' % k)


def reveal(k, width, height):
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
            break
    tree = dev.dump()
    top_limit, bottom_limit = limits(tree, height)
    card, title = stable_cards(k)
    return card, title, top_limit, bottom_limit


def tappable_title(k, width, height):
    """第 k 条的标题完整落在可点范围里时的 bounds；不在就把列表往回或往前滚一点再读。"""
    for _ in range(6):
        tree = dev.dump()
        top_limit, bottom_limit = limits(tree, height)
        _, title = stable_cards(k)
        if title[1] >= top_limit and title[3] <= bottom_limit:
            return title
        if title[1] < top_limit:
            shift = min(top_limit - title[1] + 80, int(height * 0.35))
            swipe(width // 2, int(height * 0.40), int(height * 0.40) + shift)
        else:
            shift = min(title[3] - bottom_limit + 80, int(height * 0.35))
            swipe(width // 2, int(height * 0.75), int(height * 0.75) - shift)
    raise SystemExit('第 %d 条的标题一直不在可点范围里' % k)


def open_detail(k, width, height):
    """点标题打开（清单样例点卡片中间可能点在清单项上），核对详情页的标题。"""
    title = tappable_title(k, width, height)
    dev.tap((title[0] + title[2]) // 2, (title[1] + title[3]) // 2)
    if not wait_activity('DetailActivity'):
        raise SystemExit('第 %d 条没有打开详情页' % k)
    time.sleep(1.0)
    detail = dev.dump()
    titles = [n.get('text') for n in detail.iter('node') if (n.get('resource-id') or '').endswith('/et_title')]
    if titles != [TITLE % k]:
        dev.shell('input keyevent 4')
        raise SystemExit('第 %d 条打开的详情页不对：%s' % (k, titles))


def lifted_rects(session):
    """start 事件里每张卡片抬起后在屏幕上的范围（DecorView 坐标，未旋转的外框）、缩放与旋转，第 0 张在最上面。"""
    start = next(e for e in session['events'] if e['type'] == 'start')
    sx, sy = start['stage'][0], start['stage'][1]
    out = []
    for slot in start['slots']:
        w, h = slot['size']
        cx, cy, scale, rot = slot['target']
        out.append({'size': [w, h], 'scale': round(scale, 4), 'rotation': rot,
                    'rect': [int(round(sx + cx - w * scale / 2)), int(round(sy + cy - h * scale / 2)),
                             int(round(sx + cx + w * scale / 2)), int(round(sy + cy + h * scale / 2))]})
    return start, out


def share_from_detail():
    dev.tap(*dev.center(dev.poll(desc='更多选项')))
    dev.tap(*dev.center(dev.poll(text='分享')))
    time.sleep(1.9)


def close_share_and_detail(k):
    dev.shell('input keyevent 4')
    time.sleep(1.2)
    dev.shell('input keyevent 4')
    if not wait_activity('ThingsActivity'):
        raise SystemExit('第 %d 条之后没有回到首页' % k)
    time.sleep(1.0)


def record_send(k, width, height):
    """录第 k 条从详情页分享的完整发送（模拟发送方碰上来）。录屏期间不截图（见 adb.md）。"""
    if locate(width, height, lambda cards: k if k in cards else None) is None:
        raise SystemExit('录屏：第 %d 条样例找不到' % k)
    reveal(k, width, height)
    open_detail(k, width, height)
    name = 'send-%02d' % k
    p, remote = dev.record_start(name, RECORD_S)
    share_from_detail()
    time.sleep(RECORD_S - 1.9)
    path = dev.record_finish(p, remote, name)
    close_share_and_detail(k)
    log('录屏：', path)
    return path


def check(k, width, height, results):
    if locate(width, height, lambda cards: k if k in cards else None) is None:
        # 留下当时的画面与界面树：读到的树不完整还是真的不在，要看这两样才分得清
        dev.screencap('locate-fail-%02d.png' % k)
        with open(os.path.join(out_dir, 'locate-fail-%02d.xml' % k), 'w', encoding='utf-8') as f:
            f.write(ET.tostring(dev.dump(), encoding='unicode'))
        raise SystemExit('第 %d 条样例找不到' % k)
    card, title, top_limit, bottom_limit = reveal(k, width, height)
    complete = card[1] >= top_limit and card[3] <= bottom_limit
    home_path = dev.screencap('home-%02d.png' % k)
    home = cv2.imread(home_path)[card[1]:card[3], card[0]:card[2]]
    cv2.imwrite(os.path.join(out_dir, 'home-card-%02d.png' % k), home)
    open_detail(k, width, height)
    before = session_dirs()
    share_from_detail()
    lift_path = dev.screencap('lift-%02d.png' % k)
    close_share_and_detail(k)
    name, session = read_session(before)
    start, slots = lifted_rects(session)
    slot = slots[0]
    shot = cv2.imread(lift_path)
    x1, y1, x2, y2 = slot['rect']
    lifted = shot[max(0, y1):y2, max(0, x1):x2]
    cv2.imwrite(os.path.join(out_dir, 'lifted-card-%02d.png' % k), lifted)
    h1, w1 = home.shape[:2]
    h2, w2 = lifted.shape[:2]
    pair = np.zeros((max(h1, h2), w1 + w2 + 24, 3), np.uint8)
    pair[:, :] = (60, 60, 60)
    pair[:h1, :w1] = home
    pair[:h2, w1 + 24:w1 + 24 + w2] = lifted
    cv2.imwrite(os.path.join(out_dir, 'pair-%02d.png' % k), pair)
    row = {'k': k, 'spec': samples.split(',')[k - 1], 'home': [card[2] - card[0], card[3] - card[1]],
           'home_complete': complete, 'lifted': slot, 'panel': start.get('panel'), 'session': name}
    log(json.dumps(row, ensure_ascii=False))
    results.append(row)


# ---------------------------------------------------------------- 多选叠放

def dominant_color(img, rect):
    """卡片区域里出现最多的颜色（BGR）：卡片底色。"""
    x1, y1, x2, y2 = rect
    patch = img[y1:y2, x1:x2].reshape(-1, 3)
    colors, counts = np.unique(patch, axis=0, return_counts=True)
    return colors[counts.argmax()].astype(int)


def stack_check(ks, width, height, record=False):
    """选择模式里选中 ks 这几条一起分享：先逐条记下未选中时的底色，长按第一条（再拖一下）进入选择模式，
    其余逐条滚过去点选；分享之前核对顶栏的「N / M」，并逐条核对底色与进入选择模式之前相同（未选中的会
    换成浅色）。"""
    normal = {}
    for k in ks:
        if locate(width, height, lambda cards: k if k in cards else None) is None:
            raise SystemExit('叠放：第 %d 条找不到' % k)
        card, _, _, _ = reveal(k, width, height)
        normal[k] = dominant_color(cv2.imread(dev.screencap('stack-normal-%02d.png' % k)), card)
    first = ks[0]
    if locate(width, height, lambda cards: first if first in cards else None) is None:
        raise SystemExit('叠放：第 %d 条找不到' % first)
    reveal(first, width, height)
    title = tappable_title(first, width, height)
    cx, cy = (title[0] + title[2]) // 2, (title[1] + title[3]) // 2
    dev.shell('input motionevent DOWN %d %d' % (cx, cy))
    time.sleep(0.8)
    dev.shell('input motionevent MOVE %d %d' % (cx, cy + 80))
    time.sleep(0.25)
    dev.shell('input motionevent MOVE %d %d' % (cx, cy))
    time.sleep(0.25)
    dev.shell('input motionevent UP %d %d' % (cx, cy))
    time.sleep(1.2)
    if 'DetailActivity' in top_activity():
        dev.shell('input keyevent 4')
        raise SystemExit('叠放：长按变成了点击，打开了详情页')
    picked = [first]
    for k in ks[1:]:
        if locate(width, height, lambda cards: k if k in cards else None) is None:
            dev.shell('input keyevent 4')
            raise SystemExit('叠放：第 %d 条找不到' % k)
        reveal(k, width, height)
        t = tappable_title(k, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        time.sleep(0.8)
        picked.append(k)
    tree = dev.dump()
    texts = [n.get('text') or '' for n in tree.iter('node')]
    counter = next((t for t in texts if re.fullmatch(r'\d+\s*/\s*\d+', t.strip())), None)
    log('叠放：顶栏计数', counter)
    if counter is None or int(counter.split('/')[0]) != len(ks):
        dev.shell('input keyevent 4')
        raise SystemExit('叠放：选中的条数不对 %s' % counter)
    for k in ks:
        locate(width, height, lambda cards: k if k in cards else None)
        card, _, _, _ = reveal(k, width, height)
        after = dominant_color(cv2.imread(dev.screencap('stack-selected-%02d.png' % k)), card)
        log('叠放：第 %d 条卡片底色 %s → %s' % (k, normal[k].tolist(), after.tolist()))
        if int(np.abs(normal[k] - after).max()) > 6:
            dev.shell('input keyevent 4')
            raise SystemExit('叠放：第 %d 条的底色变了，看上去没被选中' % k)
    before = session_dirs()
    # 选择模式的「分享」在溢出菜单里
    dev.tap(*dev.center(dev.poll(desc='更多选项')))
    share = dev.poll(text='分享')
    if record:
        p, remote = dev.record_start('send-stack', RECORD_S)
    dev.tap(*dev.center(share))
    time.sleep(2.2)
    if record:
        time.sleep(RECORD_S - 2.2)
        log('录屏：', dev.record_finish(p, remote, 'send-stack'))
    else:
        dev.screencap('stack-lift.png')
    dev.shell('input keyevent 4')
    time.sleep(1.4)
    dev.shell('input keyevent 4')
    time.sleep(1.0)
    name, session = read_session(before)
    _, slots = lifted_rects(session)
    areas = [s['size'][0] * s['size'][1] for s in slots]
    log('叠放：自上而下的尺寸', [s['size'] for s in slots], '缩放', [s['scale'] for s in slots])
    log('叠放：面积自上而下不减', all(a <= b for a, b in zip(areas, areas[1:])))
    return {'picked': picked, 'slots': slots, 'session': name}


# ---------------------------------------------------------------- 清理

def cleanup(width, height):
    removed = 0
    for _ in range(count + 20):
        k = locate(width, height, lambda cards: min(cards) if cards else None)
        if k is None:
            break
        reveal(k, width, height)
        open_detail(k, width, height)
        dev.tap(*dev.center(dev.poll(desc='更多选项')))
        dev.tap(*dev.center(dev.poll(text='删除')))
        if not wait_activity('ThingsActivity'):
            raise SystemExit('清理：删除之后没有回到首页')
        time.sleep(1.2)
        removed += 1
        log('清理：已删除', TITLE % k)
    return removed


def remove_sessions(before):
    for d in session_dirs():
        if d not in before:
            dev.shell('rm -rf %s/%s' % (FIXTURES, d), check=False)


def main():
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    rot = rotation()
    log('自动旋转（开始）', rot)
    if CLEANUP_ONLY:
        launch()
        width, height = screen_size(dev.dump())
        log('清理完成，共', cleanup(width, height), '条')
        write_prefs(False)
        launch()
        log('自动旋转（结束）', rotation(), '（开始时', rot, '）')
        return
    sessions_before = session_dirs()
    results = []
    stack = None
    dev.adb('logcat', '-c', check=False)
    logcat_file = open(os.path.join(out_dir, 'logcat.txt'), 'wb')
    logcat = subprocess.Popen([dev.ADB, '-s', dev.SERIAL, 'logcat', '-v', 'time', '-s', 'TapLift:I'],
                              stdout=logcat_file, stderr=subprocess.DEVNULL)
    write_prefs(True)
    try:
        launch()
        width, height = screen_size(dev.dump())
        log('屏幕', width, height)
        receive(width, height)
        for k in range(count, 0, -1):
            check(k, width, height, results)
        if RECORD:
            record_send(RECORD, width, height)
        if STACK:
            stack = stack_check(STACK, width, height, record=RECORD is not None)
    finally:
        with open(os.path.join(out_dir, 'result.json'), 'w', encoding='utf-8') as f:
            json.dump({'samples': samples, 'results': results, 'stack': stack}, f, ensure_ascii=False, indent=1)
        try:
            if 'ThingsActivity' not in top_activity():
                launch()
            width, height = screen_size(dev.dump())
            log('清理完成，共', cleanup(width, height), '条')
        finally:
            write_prefs(False)
            remove_sessions(sessions_before)
            launch()
            logcat.terminate()
            logcat_file.close()
            log('自动旋转（结束）', rotation(), '（开始时', rot, '）')


if __name__ == '__main__':
    main()
