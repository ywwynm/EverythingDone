"""粒子动画设置页：飘散样式的「粒子数量」（0.2×～1.6×）与「粒子大小」（0.5×～2.0×）。

依次截三张图：缺省（两项都标「默认」）、数量拉到最低且大小拉到最高、示例对话框按新设置播放的一帧；
从统计行读粒子总数，核对数量倍率生效。最后点「恢复默认」，并读回偏好文件确认与开始前相同。

用法（Bash 工具里跑，输出在 tmp/harmony-particle-android-port/<run>/）：
    python docs/features/harmony-particle-android-port/device/settings_size_check.py --run z1
"""
import argparse
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402

PREFS = 'shared_prefs/particle_tuning.xml'


def prefs():
    return d.soft('shell', 'run-as %s cat %s' % (d.PACKAGE, PREFS)).stdout.decode('utf-8', 'replace')


def find_rid(rid, tries=16):
    """参数区在预览下面：在参数区里小步往上推，直到控件完整出现在屏幕里。"""
    width, height = d.screen_size()
    for _ in range(tries):
        # 页面上一直有动画，dump 可能缺一支：同一位置多取几次再决定要不要滚
        node = None
        for _ in range(6):
            node = d.find(d.dump(), rid=rid)
            if node is not None:
                break
            time.sleep(.3)
        if node is not None and d.bounds(node)[3] < height - 160:
            return node
        # 参数区在屏幕下半部；起点离左右边缘都远于 140 px
        d.adb('shell', 'input', 'swipe', width // 2, int(height * .85), width // 2, int(height * .72), 500)
        time.sleep(1.0)
    raise RuntimeError('参数区找不到 ' + rid)


def text_of(rid):
    node = d.find(d.dump(), rid=rid)
    return node.get('text') if node is not None else None


def set_slider(rid, to_max):
    """在滑杆两端各点一下：点到轨道最左 / 最右即落在最低 / 最高一档（点完会触发 onStopTrackingTouch 落盘）。"""
    bar = find_rid(rid)
    x1, y1, x2, y2 = d.bounds(bar)
    x = x2 - 6 if to_max else x1 + 6
    d.adb('shell', 'input', 'tap', x, (y1 + y2) // 2)
    time.sleep(.8)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    result = {'rotation_before': d.rotation_state(), 'prefs_before': prefs()}
    d.open_tuning_page()
    time.sleep(1.5)
    find_rid('sb_particle_tuning_drift_size')
    result['default'] = {'amount': text_of('tv_particle_tuning_amount_value'),
                         'size': text_of('tv_particle_tuning_drift_size_value'),
                         'stats': text_of('tv_particle_tuning_stats')}
    d.shot(folder, 'settings-default')
    set_slider('sb_particle_tuning_amount', to_max=False)
    set_slider('sb_particle_tuning_drift_size', to_max=True)
    result['changed'] = {'amount': text_of('tv_particle_tuning_amount_value'),
                         'size': text_of('tv_particle_tuning_drift_size_value'),
                         'prefs': prefs()}
    d.shot(folder, 'settings-changed')
    # 示例对话框按新设置补播一轮：等统计行刷新出粒子总数再截一帧
    stats = None
    for _ in range(30):
        stats = text_of('tv_particle_tuning_stats')
        if stats and re.search(r'\d', stats):
            break
        time.sleep(.5)
    result['changed']['stats'] = stats
    time.sleep(3.0)
    d.shot(folder, 'settings-changed-later')
    reset = find_rid('tv_particle_tuning_reset')
    d.tap(reset)
    time.sleep(1.0)
    result['after_reset'] = {'amount': text_of('tv_particle_tuning_amount_value'),
                             'size': text_of('tv_particle_tuning_drift_size_value'),
                             'prefs': prefs()}
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    result['rotation_after'] = d.rotation_state()
    (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=1), 'utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
