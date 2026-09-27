"""核对扩散圈落在分享面板上的那一段改用记事颜色（决策 2026-09-27）。

三星 SM-S9180 的天线在屏幕正中，横屏左右排时正好落在面板左缘，一个圈一半在暗层上、一半在面板上：暗层上应是白色，
面板上应是记事的颜色。详情页分享用这条记事的颜色（纯色样例），选择模式里多条分享用应用的强调色（渐变），两种都截。

先竖屏用模拟导入样例，再把朝向固定成横屏（先读原值，跑完一定改回并读回核对：先开自动旋转、再写 user_rotation，
见 .claude/rules/adb.md），截图，最后删样例、删调试开关文件与会话记录。启动应用一律 am start。

用法：SERIAL=<序列号> python ring_color_check.py <输出目录>
"""
import os
import sys
import time

OUT = os.path.abspath(sys.argv[1])
SAMPLES = 'plain,top@2'
sys.argv = ['lift_card_check.py', OUT, SAMPLES]
import lift_card_check as L  # noqa: E402
import dev  # noqa: E402


def settings_get(key):
    return dev.shell('settings get system %s' % key).strip()


def current_rotation():
    out = dev.shell('dumpsys window displays', check=False)
    for token in ('ROTATION_0', 'ROTATION_90', 'ROTATION_180', 'ROTATION_270'):
        if 'mCurrentRotation=' + token in out:
            return token
    return '?'


def lock_landscape():
    dev.shell('settings put system accelerometer_rotation 0')
    dev.shell('settings put system user_rotation 1')
    for _ in range(20):
        time.sleep(0.3)
        if current_rotation() == 'ROTATION_90':
            return
    raise SystemExit('没能固定成横屏：%s' % current_rotation())


def restore_rotation(accel, user):
    # 先开自动旋转、再写 user_rotation，隔一秒读回；系统的旋转按钮可能在朝向变化时又锁回去，读回不对就再写一次
    for _ in range(3):
        dev.shell('settings put system accelerometer_rotation %s' % accel)
        dev.shell('settings put system user_rotation %s' % user)
        time.sleep(1.0)
        if settings_get('accelerometer_rotation') == accel and settings_get('user_rotation') == user:
            return True
    return False


def main():
    if 'isKeyguardShowing=true' in dev.shell('dumpsys window'):
        raise SystemExit('设备锁屏中，请先解锁（密码不代输）')
    accel, user = settings_get('accelerometer_rotation'), settings_get('user_rotation')
    L.log('旋转设置（开始）', accel, user)
    before = L.session_dirs()
    L.write_prefs(True)
    try:
        L.launch()
        width, height = L.screen_size(dev.dump())
        L.receive(width, height)
        lock_landscape()
        time.sleep(1.5)
        width, height = L.screen_size(dev.dump())
        L.log('横屏', width, height)
        for k in (2, 1):
            if L.locate(width, height, lambda cards: k if k in cards else None) is None:
                raise SystemExit('第 %d 条样例找不到' % k)
            L.reveal(k, width, height)
            L.open_detail(k, width, height)
            L.share_from_detail()
            dev.screencap('ring-detail-%02d.png' % k)
            L.close_share_and_detail(k)
            L.log('详情页分享：第 %d 条已截图' % k)
        stack = L.stack_check([2, 1], width, height)
        L.log('多选分享：', stack['picked'], [s['size'] for s in stack['slots']])
    finally:
        # 先恢复朝向再清理：横屏下详情页的溢出菜单一屏放不下，「删除」可能不在界面树里（2026-09-27 实际发生）
        ok = restore_rotation(accel, user)
        L.log('旋转设置（结束）', settings_get('accelerometer_rotation'), settings_get('user_rotation'),
              '已恢复' if ok else '没有恢复成原值，请手动恢复')
        try:
            L.launch()
            time.sleep(1.0)
            L.log('清理完成，共', L.cleanup(*L.screen_size(dev.dump())), '条')
        finally:
            L.write_prefs(False)
            L.remove_sessions(before)
            L.launch()


if __name__ == '__main__':
    main()
