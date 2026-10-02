"""分享界面在手机横屏下的样子，以及打开之后旋转屏幕的就地重排（决策 2026-09-28）真机核对，只在三星上跑。

阶段（子命令，输出目录与 folder_transfer_check.py 共用 state.json）：
  setup     调 folder_transfer_check 的 receive 阶段：打开碰一碰模拟，接收两个样例文件夹（「大」形态带缩略图 /
            「正常」形态通栏）与一条散的记事。
  run       临时固定横屏（先记下 accelerometer_rotation 与 user_rotation 的原值），截四处分享：选择模式混选、
            只选文件夹、文件夹页「分享此文件夹」、详情页；再在分享界面开着时横竖来回转，截重排之后的样子，
            读会话记录里打开与每次重排的几何（面板、卡片区里的卡片）；最后录一段来回转的过程。
            跑完（含失败）把两项设置改回原值并读回核对：原来开着自动旋转时先开它、再写 user_rotation（见 adb.md）。
  cleanup   调 folder_transfer_check 的 cleanup 阶段：样例移入回收站，删掉调试开关、本轮的会话记录。

启动应用一律 am start。用法：SERIAL=<序列号> python landscape_share_check.py <输出目录> <阶段>
"""
import json
import os
import re
import sys
import time

import dev
import folder_transfer_check as ftc
from folder_transfer_check import (FOLDER_TITLE, THING_TITLE, PREFS, find_card, last_session, launch,
                                   load_state, log, long_press_select, overflow, panel_title, save_state,
                                   screen_size, selection_counter, sessions, top_activity, wait_activity,
                                   write_prefs)

STAGE = sys.argv[2]


# ---------------------------------------------------------------- 屏幕方向

def rotation_settings():
    return (dev.shell('settings get system accelerometer_rotation').strip(),
            dev.shell('settings get system user_rotation').strip())


def current_rotation():
    out = dev.shell('dumpsys window', check=False)
    m = re.search(r'mCurrentRotation=ROTATION_(\d+)', out)
    return int(m.group(1)) if m else None


def fix_rotation(user_rotation):
    """固定到 [user_rotation]（0 竖屏、1 横屏）：关掉自动旋转再写方向，等屏幕真的转过去、应用重排完。"""
    dev.shell('settings put system accelerometer_rotation 0')
    dev.shell('settings put system user_rotation %d' % user_rotation)
    want = user_rotation * 90
    for _ in range(40):
        time.sleep(0.25)
        if current_rotation() == want:
            break
    else:
        raise SystemExit('屏幕没有转到 %d 度（现在 %s）' % (want, current_rotation()))
    time.sleep(1.8)


def restore_rotation(original):
    accelerometer, user = original
    for _ in range(3):
        if accelerometer == '1':
            dev.shell('settings put system accelerometer_rotation 1')
            dev.shell('settings put system user_rotation %s' % user)
        else:
            dev.shell('settings put system user_rotation %s' % user)
            dev.shell('settings put system accelerometer_rotation %s' % accelerometer)
        time.sleep(1.0)
        now = rotation_settings()
        if now == tuple(original):
            log('方向设置已改回：accelerometer_rotation=%s user_rotation=%s' % now)
            return
        log('方向设置读回不对，重设：', now)
    raise SystemExit('方向设置没能改回原值 %s，现在 %s' % (original, rotation_settings()))


# ---------------------------------------------------------------- 分享界面

def size():
    return screen_size(dev.dump())


def panel_bounds():
    for n in dev.dump().iter('node'):
        if (n.get('resource-id') or '').endswith('/ll_tap_share_panel'):
            return dev.bounds(n)
    return None


def describe_session(label, before):
    """这一轮分享的会话记录：打开与每次重排时的面板、卡片（尺寸、目标中心与缩放）、是否左右排。"""
    name, session = last_session(before)
    if not session:
        log(label, '没有会话记录')
        return None
    rows = []
    for e in session['events']:
        if e['type'] in ('start', 'relayout'):
            rows.append({'type': e['type'], 'side_by_side': e.get('side_by_side'), 'panel': e.get('panel'),
                         'stage': e.get('stage'), 'antenna': e.get('antenna'),
                         'slots': [{'size': s['size'], 'target': [round(v, 1) for v in s['target']],
                                    'origin': s.get('origin')} for s in e['slots']]})
        elif e['type'] in ('relayout-begin', 'close', 'phase'):
            rows.append({'type': e['type'], **{k: v for k, v in e.items() if k in ('side_by_side', 'name')}})
    log(label, json.dumps(rows, ensure_ascii=False))
    return {'session': name, 'events': rows}


def back_to_home():
    for _ in range(4):
        if 'ThingsActivity' in top_activity() and selection_counter() is None:
            return
        dev.shell('input keyevent 4')
        time.sleep(1.4)


def select_share(titles, shot, report, rotate_to=None):
    """选择模式里依次选中 [titles] 分享，截 [shot]；[rotate_to] 给出时开着分享界面依次转到这些方向并各截一张。"""
    width, height = size()
    _, t = find_card(titles[0], width, height)
    long_press_select(t)
    if 'DetailActivity' in top_activity():
        raise SystemExit('长按变成了点击')
    for title in titles[1:]:
        _, t = find_card(title, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        time.sleep(0.8)
    log('选择模式计数：', selection_counter())
    before = sessions()
    overflow('分享')
    time.sleep(2.2)
    dev.screencap(shot + '.png')
    entry = {'titles': titles, 'panel_title': panel_title(), 'panel': panel_bounds()}
    for k, r in enumerate(rotate_to or []):
        fix_rotation(r)
        dev.screencap('%s-rot%d-%d.png' % (shot, k, r))
        entry.setdefault('rotated', []).append({'to': r, 'panel': panel_bounds(), 'panel_title': panel_title()})
    dev.shell('input keyevent 4')
    time.sleep(1.6)
    dev.screencap(shot + '-closed.png')
    entry['after_close_counter'] = selection_counter()
    entry['session'] = describe_session(shot, before)
    report[shot] = entry
    back_to_home()


def run():
    state = load_state()
    original = rotation_settings()
    state['rotation_original'] = list(original)
    save_state(state)
    log('方向设置（开始）：accelerometer_rotation=%s user_rotation=%s' % original)
    report = {}
    # 调试开关打开（会话记录要落盘），但不模拟：分享界面里不要自动碰上来
    write_prefs(True)
    dev.shell("run-as %s sed -i 's|<boolean name=\"simulate\" value=\"true\" />|<boolean name=\"simulate\" value=\"false\" />|' %s"
              % (dev.PKG, PREFS))
    try:
        launch()
        fix_rotation(1)
        log('横屏尺寸：', size())
        # 一、选择模式：「大」文件夹 + 散的记事，开着转到竖屏再转回横屏
        select_share([FOLDER_TITLE % 1, THING_TITLE % 3], 'L-mixed', report, rotate_to=[0, 1])
        # 二、只选「大」文件夹（标题「分享文件夹」）
        select_share([FOLDER_TITLE % 1], 'L-folder', report)
        # 三、文件夹页「分享此文件夹」：卡片从页面里升起，开着转到竖屏
        width, height = size()
        _, t = find_card(FOLDER_TITLE % 2, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        time.sleep(1.6)
        before = sessions()
        overflow('分享此文件夹')
        time.sleep(2.2)
        dev.screencap('L-overflow.png')
        entry = {'panel_title': panel_title(), 'panel': panel_bounds()}
        fix_rotation(0)
        dev.screencap('L-overflow-rot-0.png')
        entry['rotated'] = [{'to': 0, 'panel': panel_bounds()}]
        dev.shell('input keyevent 4')
        time.sleep(1.6)
        dev.screencap('L-overflow-closed.png')
        entry['session'] = describe_session('L-overflow', before)
        report['L-overflow'] = entry
        dev.shell('input keyevent 4')
        time.sleep(1.2)
        # 四、详情页：竖屏打开、开着转到横屏（与第三项方向相反）
        width, height = size()
        _, t = find_card(THING_TITLE % 3, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        if not wait_activity('DetailActivity'):
            raise SystemExit('没有打开详情页')
        time.sleep(1.2)
        before = sessions()
        overflow('分享')
        time.sleep(2.2)
        dev.screencap('P-detail.png')
        entry = {'panel_title': panel_title(), 'panel': panel_bounds()}
        fix_rotation(1)
        dev.screencap('P-detail-rot-1.png')
        entry['rotated'] = [{'to': 1, 'panel': panel_bounds()}]
        dev.shell('input keyevent 4')
        time.sleep(1.6)
        dev.screencap('P-detail-closed.png')
        entry['session'] = describe_session('P-detail', before)
        report['P-detail'] = entry
        dev.shell('input keyevent 4')
        wait_activity('ThingsActivity')
        time.sleep(1.2)
        # 五、录一段：横屏打开混选分享，开着转到竖屏、再转回横屏
        width, height = size()
        _, t = find_card(FOLDER_TITLE % 1, width, height)
        long_press_select(t)
        _, t = find_card(THING_TITLE % 3, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        time.sleep(0.8)
        overflow('分享')
        time.sleep(2.2)
        p, remote = dev.record_start('rotate-while-open', 14)
        time.sleep(1.5)
        fix_rotation(0)
        time.sleep(1.5)
        fix_rotation(1)
        time.sleep(1.0)
        dev.shell('input keyevent 4')
        p.wait()
        log('录屏：', dev.record_finish(p, remote, 'rotate-while-open'))
        back_to_home()
    finally:
        with open(os.path.join(ftc.OUT, 'landscape.json'), 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
        try:
            back_to_home()
        finally:
            restore_rotation(original)


def panel():
    """只看横屏下的面板：选择模式混选分享、详情页分享各截一张（状态栏图标是否露在暗层上）。"""
    original = rotation_settings()
    log('方向设置（开始）：accelerometer_rotation=%s user_rotation=%s' % original)
    report = {}
    write_prefs(True)
    dev.shell("run-as %s sed -i 's|<boolean name=\"simulate\" value=\"true\" />|<boolean name=\"simulate\" value=\"false\" />|' %s"
              % (dev.PKG, PREFS))
    try:
        launch()
        fix_rotation(1)
        select_share([FOLDER_TITLE % 1, THING_TITLE % 3], 'L2-mixed', report)
        width, height = size()
        _, t = find_card(THING_TITLE % 3, width, height)
        dev.tap((t[0] + t[2]) // 2, (t[1] + t[3]) // 2)
        if not wait_activity('DetailActivity'):
            raise SystemExit('没有打开详情页')
        time.sleep(1.2)
        overflow('分享')
        time.sleep(2.2)
        dev.screencap('L2-detail.png')
        report['L2-detail'] = {'panel': panel_bounds()}
        log('详情页横屏面板：', report['L2-detail'])
        dev.shell('input keyevent 4')
        time.sleep(1.6)
        dev.shell('input keyevent 4')
        wait_activity('ThingsActivity')
        time.sleep(1.0)
    finally:
        with open(os.path.join(ftc.OUT, 'landscape-panel.json'), 'w', encoding='utf-8') as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
        try:
            back_to_home()
        finally:
            restore_rotation(original)


if __name__ == '__main__':
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    {'setup': ftc.stage_receive, 'run': run, 'panel': panel, 'cleanup': ftc.stage_cleanup}[STAGE]()
