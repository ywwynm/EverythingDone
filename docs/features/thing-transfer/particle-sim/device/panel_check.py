"""截下分享面板（决策 2026-09-27：标题「分享记事」、悬浮卡片、小图标与圆形波纹）。

导入两条样例后：详情页分享（碰一碰可用）、按住「文本与图片」时的圆形波纹（按住时截图，手指移出格子再松开，不触发
分享）、选择模式里两条一起分享；再打开调试开关 simulate_unsupported，截不能碰一碰时的面板。最后删样例、删调试开关
文件与会话记录。启动应用一律 am start。

用法：SERIAL=<序列号> python panel_check.py <输出目录>
"""
import os
import sys
import time

OUT = os.path.abspath(sys.argv[1])
SAMPLES = 'plain,top@2'
sys.argv = ['lift_card_check.py', OUT, SAMPLES]
import lift_card_check as L  # noqa: E402
import dev  # noqa: E402
import unsupported_ui_check as U  # noqa: E402


def press_option_and_capture(name):
    """按住「文本与图片」那一格的图标，截图，再把手指移到屏幕顶部松开（不算点击）。"""
    icon = dev.poll(rid='iv_share_text_image')
    x, y = dev.center(icon)
    dev.shell('input motionevent DOWN %d %d' % (x, y))
    time.sleep(0.35)
    dev.screencap(name)
    dev.shell('input motionevent MOVE %d %d' % (x, y - 300))
    dev.shell('input motionevent MOVE %d %d' % (x, 200))
    dev.shell('input motionevent UP %d %d' % (x, 200))
    time.sleep(0.6)


def share_detail(k, width, height, shot, press=None):
    if L.locate(width, height, lambda cards: k if k in cards else None) is None:
        raise SystemExit('第 %d 条样例找不到' % k)
    L.reveal(k, width, height)
    L.open_detail(k, width, height)
    L.share_from_detail()
    dev.screencap(shot)
    if press:
        press_option_and_capture(press)
    L.close_share_and_detail(k)


def main():
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    rot = L.rotation()
    L.log('自动旋转（开始）', rot)
    before = L.session_dirs()
    U.write_prefs(True, False)
    try:
        L.launch()
        width, height = L.screen_size(dev.dump())
        L.receive(width, height)
        share_detail(2, width, height, 'panel-detail-02.png', press='panel-pressed.png')
        share_detail(1, width, height, 'panel-detail-01.png')
        L.stack_check([2, 1], width, height)
        U.write_prefs(True, True)
        L.launch()
        share_detail(2, width, height, 'panel-unsupported-02.png')
        L.log('截图完成')
    finally:
        try:
            if 'ThingsActivity' not in L.top_activity():
                L.launch()
            L.log('清理完成，共', L.cleanup(*L.screen_size(dev.dump())), '条')
        finally:
            U.write_prefs(False, False)
            L.remove_sessions(before)
            L.launch()
            L.log('自动旋转（结束）', L.rotation(), '（开始时', rot, '）')


if __name__ == '__main__':
    main()
