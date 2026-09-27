"""截下「不支持碰一碰的设备」上的首页与分享界面（调试开关 simulate_unsupported，见 TapTransfer.isUnsupportedSimulated）。

两台测试机都有 NFC，用调试开关把这台当作不支持：先打开模拟开关导入几条样例记事（入口要可用才能导入），再打开
simulate_unsupported，截首页（下拉之后不应出现「接收记事」）、详情页的分享、选择模式里多条的分享。最后逐条删掉样例、
删掉调试开关文件与这次的会话记录。启动应用一律 am start。

用法（conda 环境 everythingdone 的 python）：
    SERIAL=<序列号> python unsupported_ui_check.py <输出目录>
"""
import os
import subprocess
import sys
import time

OUT = os.path.abspath(sys.argv[1])
SAMPLES = 'top@0.8+text,plain,list'
sys.argv = ['lift_card_check.py', OUT, SAMPLES]
import lift_card_check as L  # noqa: E402
import dev  # noqa: E402


def write_prefs(simulate, unsupported):
    dev.shell('am force-stop ' + dev.PKG)
    time.sleep(0.8)
    if not simulate and not unsupported:
        dev.shell('run-as %s rm -f %s' % (dev.PKG, L.PREFS))
        return
    text = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            '    <boolean name="simulate" value="%s" />\n'
            '    <boolean name="simulate_unsupported" value="%s" />\n'
            '    <boolean name="dump_fixtures" value="true" />\n'
            '    <string name="simulate_samples">%s</string>\n</map>\n'
            % ('true' if simulate else 'false', 'true' if unsupported else 'false', SAMPLES))
    subprocess.run([dev.ADB, '-s', dev.SERIAL, 'shell', 'run-as', dev.PKG, 'sh', '-c',
                    "'cat > %s.tmp && mv %s.tmp %s'" % (L.PREFS, L.PREFS, L.PREFS)],
                   input=text.encode('utf-8'), check=True)
    head = dev.shell('run-as %s od -An -tx1 -N 4 %s' % (dev.PKG, L.PREFS)).split()
    assert head[0] == '3c', head


def pull_down(width, height):
    """在列表顶部往下拉并按住：可以碰一碰时这里会拉出「接收记事」。按住时截图，再读界面树看按钮在不在。"""
    tree = dev.dump()
    bar = L.node_by_rid(tree, 'actionbar')
    start = max(int(height * 0.30), dev.bounds(bar)[3] + 60 if bar is not None else 0)
    end = min(start + int(height * 0.35), height - 200)
    x = width // 2
    dev.shell('input motionevent DOWN %d %d' % (x, start))
    for k in range(1, 21):
        dev.shell('input motionevent MOVE %d %d' % (x, start + (end - start) * k // 20))
        time.sleep(0.02)
    time.sleep(0.4)
    dev.screencap('home-pulled.png')
    found = L.node_by_rid(dev.dump(), 'tv_tap_receive') is not None
    dev.shell('input motionevent UP %d %d' % (x, end))
    time.sleep(1.0)
    return found


def main():
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    rot = L.rotation()
    L.log('自动旋转（开始）', rot)
    before = L.session_dirs()
    count = len(SAMPLES.split(','))
    write_prefs(True, False)
    try:
        L.launch()
        width, height = L.screen_size(dev.dump())
        L.log('屏幕', width, height)
        L.receive(width, height)
        write_prefs(True, True)
        L.launch()
        dev.screencap('home.png')
        L.log('下拉之后出现了「接收记事」' if pull_down(width, height) else '下拉之后没有「接收记事」')
        for k in range(count, 0, -1):
            if L.locate(width, height, lambda cards: k if k in cards else None) is None:
                raise SystemExit('第 %d 条样例找不到' % k)
            L.reveal(k, width, height)
            L.open_detail(k, width, height)
            L.share_from_detail()
            dev.screencap('share-%02d.png' % k)
            L.close_share_and_detail(k)
            L.log('详情页分享：第 %d 条已截图' % k)
        stack = L.stack_check(list(range(count, 0, -1)), width, height)
        L.log('多选分享：', stack['picked'])
    finally:
        try:
            if 'ThingsActivity' not in L.top_activity():
                L.launch()
            L.log('清理完成，共', L.cleanup(*L.screen_size(dev.dump())), '条')
        finally:
            write_prefs(False, False)
            L.remove_sessions(before)
            L.launch()
            L.log('自动旋转（结束）', L.rotation(), '（开始时', rot, '）')


if __name__ == '__main__':
    main()
