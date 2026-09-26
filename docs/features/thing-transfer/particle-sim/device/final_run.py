"""最终演示录屏：只录屏、不在录制期间截图（截图会拖慢录屏帧率），静帧事后从视频里抽。

用法：SERIAL=<序列号> python final_run.py <场景> <标签>
场景：receive / receive_fail / share_detail / share_fail / share_selection
失败场景要求设备上已打开 simulate_failure；本脚本不改任何设置。
"""
import sys
import time

import dev

tag = sys.argv[2] if len(sys.argv) > 2 else 'run'


def require_home():
    top = dev.shell('dumpsys activity activities')
    line = [l for l in top.splitlines() if 'ResumedActivity' in l][:1]
    if not line or 'ThingsActivity' not in line[0]:
        raise SystemExit('前台不是首页，停下：%s' % line)


def sample_cards():
    tree = dev.dump()
    out = []
    for n in tree.iter('node'):
        if (n.get('resource-id') or '').endswith('/tv_thing_title') and '模拟' in (n.get('text') or ''):
            out.append(n)
    return out


def wait_until(t0, at):
    delay = t0 + at - time.time()
    if delay > 0:
        time.sleep(delay)


def relaunch():
    """重启应用：按钮收起、列表回到顶部，每段录像从同一个起点开始。"""
    dev.shell('am force-stop ' + dev.PKG)
    time.sleep(1.0)
    # 不用 monkey：它启动时注入旋转事件，会把用户的自动旋转打开（.claude/rules/adb.md）
    dev.shell('am start -n %s/.activities.ThingsActivity' % dev.PKG)
    time.sleep(4.5)
    require_home()


def probe_receive_button():
    """先拉一次记下按钮位置（拉出后的位置是固定的），录制时就不必在中途 dump。"""
    relaunch()
    dev.drag(720, 1400, 720, 1900, steps=28, step_ms=18)
    node = dev.poll(rid='tv_tap_receive')
    center = dev.center(node)
    relaunch()
    return center


def receive(seconds, tap_at, exit_at):
    x, y = probe_receive_button()
    proc, remote = dev.record_start(tag, seconds)
    t0 = time.time()
    wait_until(t0, 0.6)
    dev.drag(720, 1400, 720, 1900, steps=28, step_ms=18)
    wait_until(t0, tap_at)
    dev.tap(x, y)
    wait_until(t0, exit_at)
    dev.tap(x, y)
    print('video', dev.record_finish(proc, remote, tag))


def open_sample_detail():
    cards = sample_cards()
    if not cards:
        raise SystemExit('找不到样例记事')
    dev.tap(*dev.center(cards[0]))
    time.sleep(1.6)


def share_detail(seconds, back_at):
    open_sample_detail()
    more = dev.poll(desc='更多选项')
    dev.tap(*dev.center(more))
    share = dev.poll(text='分享')
    proc, remote = dev.record_start(tag, seconds)
    t0 = time.time()
    wait_until(t0, 0.5)
    dev.tap(*dev.center(share))
    t_tap = time.time()
    wait_until(t_tap, back_at)
    dev.shell('input keyevent 4')
    print('video', dev.record_finish(proc, remote, tag))
    time.sleep(0.8)
    dev.shell('input keyevent 4')
    time.sleep(1.2)
    require_home()


def share_selection(seconds, back_at):
    cards = sample_cards()
    if len(cards) < 2:
        raise SystemExit('样例记事不足两条')
    (x1, y1), (x2, y2) = dev.center(cards[0]), dev.center(cards[1])
    proc, remote = dev.record_start(tag, seconds)
    t0 = time.time()
    wait_until(t0, 0.6)
    dev.shell('input motionevent DOWN %d %d' % (x1, y1))
    time.sleep(0.9)
    dev.shell('input motionevent UP %d %d' % (x1, y1))
    time.sleep(0.7)
    dev.tap(x2, y2)
    time.sleep(0.7)
    more = dev.poll(desc='更多选项')
    dev.tap(*dev.center(more))
    share = dev.poll(text='分享')
    time.sleep(0.4)
    dev.tap(*dev.center(share))
    t_tap = time.time()
    wait_until(t_tap, back_at)
    dev.shell('input keyevent 4')
    print('video', dev.record_finish(proc, remote, tag))
    time.sleep(1.0)
    require_home()


if __name__ == '__main__':
    scene = sys.argv[1]
    require_home()
    if scene == 'receive':
        receive(seconds=17, tap_at=2.2, exit_at=13.8)
    elif scene == 'receive_fail':
        receive(seconds=14, tap_at=2.2, exit_at=11.0)
    elif scene == 'share_detail':
        share_detail(seconds=17, back_at=12.0)
    elif scene == 'share_fail':
        share_detail(seconds=14, back_at=9.5)
    elif scene == 'share_selection':
        share_selection(seconds=24, back_at=11.5)
    else:
        raise SystemExit('未知场景 ' + scene)
