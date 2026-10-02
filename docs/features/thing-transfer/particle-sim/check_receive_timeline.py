"""倒放时间轴的自检：`sim_timeline.ReceivePlan.reverse_tau` 与 Android `TapReceiveTimeline` 是否仍然一致。

核对三件事：
1. 软着陆的拐点：Kotlin 源码里 `KNEE` 的声明与 `sim_timeline.SOFT_CAP_KNEE` 相同；
2. 几个采样点：按 Kotlin 的公式逐行手算的值（下表，参数固定为 0.22, 0.02, 0.34, 0.5，与参数文件的当前取值无关），
   模拟器算出来的要在 2e-6 以内（手机上是单精度，表里保留 6 位小数）；
3. 形状：碰上到拼好单调不减、流入之前不越过上限、越过拐点时斜率连续、流入时长结束时到 1。

Kotlin 端的公式改了（不只是常数），按新公式重算下表。在本目录执行：

    python -X utf8 check_receive_timeline.py
"""
from __future__ import annotations

import re
import sys

from sim_model import ROOT
from sim_timeline import SOFT_CAP_KNEE, ReceivePlan

KOTLIN = ROOT / 'app/src/main/java/com/ywwynm/everythingdone/taptransfer/ui/receive/TapReceiveTimeline.kt'
PACE = (0.22, 0.02, 0.34, 0.5)   # scale, floor, cap, gamma
APPEAR_S = 1.3
TOLERANCE = 2e-6

# 手算（knee = 0.34 × 0.8 = 0.272，拐点之上的余量 0.068）：
# A 很慢的传输：碰上 0 s、到齐 12 s、流入 13 s。
#   t ≤ 12：raw = 0.22 × (t / 12)^0.8 + 0.02 × t，超过 0.272 之后 softCap = 0.272 + 0.068 × (1 − e^(−(raw − 0.272) / 0.068))
#     t = 3：raw = 0.22 × 0.329877 + 0.06 = 0.132573（在拐点之下，原样）
#     t = 9：raw = 0.22 × 0.794418 + 0.18 = 0.354772 → 0.272 + 0.068 × (1 − e^(−1.217234)) = 0.319869
#     t = 12：raw = 0.46 → 0.272 + 0.068 × (1 − e^(−2.764706)) = 0.335716
#   到齐：速度 = max(e^(−2.764706) × (0.22 × 0.8 / 12 + 0.02), 0.02) = max(0.002184, 0.02) = 0.02，余量 0.34 − 0.335716 = 0.004284
#     t = 12.5：0.335716 + 0.004284 × (1 − e^(−0.02 × 0.5 / 0.004284)) = 0.339585
#     t = 13（流入开始）：tau_h = 0.339960，速度 rate_h = 0.02 × e^(−4.668932) = 0.000188
#   流入：三次 Hermite，u = 0.5 时 0.5 × 0.339960 + 0.125 × 1.3 × 0.000188 + 0.5 − 0.125 = 0.545010
# B 录下的三星接收：碰上 3.530 s、到齐 5.735 s、流入 7.014 s（一两秒的传输，落不到软着陆那一段）
#   到齐：raw = 0.22 + 0.02 × 2.205 = 0.264100 < 0.272；速度 0.22 × 0.8 / 2.205 + 0.02 = 0.099819，余量 0.075900
#     t = 6.5：0.2641 + 0.0759 × (1 − e^(−0.099819 × 0.765 / 0.0759)) = 0.312247
CASES = {
    'A 很慢的传输': ((0.0, 12.0, 13.0), [(3.0, 0.132573), (6.0, 0.246357), (9.0, 0.319869), (12.0, 0.335716),
                                         (12.5, 0.339585), (13.0, 0.339960), (13.65, 0.545010), (14.3, 1.0)]),
    'B 录下的三星接收': ((3.53, 5.735, 7.014), [(4.5, 0.133455), (5.735, 0.264100), (6.5, 0.312247),
                                              (7.014, 0.325883), (7.664, 0.540959)]),
}


def plan(touched: float, complete: float, inflow: float) -> ReceivePlan:
    """只带倒放时间轴用得到的几项的 ReceivePlan（不读会话）。"""
    p = ReceivePlan.__new__(ReceivePlan)
    p.p = {'receive_emerge_pace': list(PACE)}
    p.t_touched, p.t_complete, p.t_inflow = touched, complete, inflow
    p.appear_s = APPEAR_S
    return p


def main() -> int:
    failures = []

    # 1. 拐点常数
    text = KOTLIN.read_text(encoding='utf-8')
    found = re.search(r'const\s+val\s+KNEE\s*=\s*([0-9]*\.?[0-9]+)f', text)
    if found is None:
        failures.append(f'{KOTLIN.name} 里找不到 KNEE 的声明：改名或挪走了，按 Kotlin 端的新写法更新本脚本')
    elif abs(float(found.group(1)) - SOFT_CAP_KNEE) > 1e-9:
        failures.append(f'拐点不一致：Kotlin KNEE = {found.group(1)}，模拟器 SOFT_CAP_KNEE = {SOFT_CAP_KNEE}')
    else:
        print(f'拐点：Kotlin KNEE = {found.group(1)}，模拟器 {SOFT_CAP_KNEE}')

    # 2. 采样点
    for name, ((touched, complete, inflow), samples) in CASES.items():
        timeline = plan(touched, complete, inflow)
        print(name)
        for t, expected in samples:
            got = timeline.reverse_tau(t)
            ok = abs(got - expected) <= TOLERANCE
            print(f'  t = {t:<6}  手算 {expected:.6f}  模拟器 {got:.6f}  {"一致" if ok else "不一致"}')
            if not ok:
                failures.append(f'{name} t = {t}：手算 {expected:.6f}，模拟器 {got:.6f}')

    # 3. 形状
    scale, floor, cap, gamma = PACE
    for name, ((touched, complete, inflow), _) in CASES.items():
        timeline = plan(touched, complete, inflow)
        end = inflow + APPEAR_S
        steps = 6000
        grid = [touched + (end - touched) * k / steps for k in range(steps + 1)]
        taus = [timeline.reverse_tau(t) for t in grid]
        if any(b < a - 1e-12 for a, b in zip(taus, taus[1:])):
            failures.append(f'{name}：倒放时间轴有回退')
        before = [tau for t, tau in zip(grid, taus) if t <= inflow]
        if max(before) >= cap:
            failures.append(f'{name}：流入之前越过了上限 {cap}（{max(before):.6f}）')
        if abs(timeline.reverse_tau(end) - 1.0) > 1e-9:
            failures.append(f'{name}：流入时长结束时没有到 1')
    # 越过拐点的那一刻斜率连续（A：raw 在 6.7 秒附近越过 0.272）
    timeline = plan(*CASES['A 很慢的传输'][0])
    knee = cap * SOFT_CAP_KNEE
    lo, hi = 0.0, 12.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if scale * (mid / 12.0) ** (1.6 * gamma) + floor * mid < knee:
            lo = mid
        else:
            hi = mid
    h = 1e-4
    left = (timeline.reverse_tau(lo) - timeline.reverse_tau(lo - h)) / h
    right = (timeline.reverse_tau(hi + h) - timeline.reverse_tau(hi)) / h
    print(f'越过拐点（t = {lo:.4f} s）：左侧斜率 {left:.6f}，右侧斜率 {right:.6f}')
    if abs(left - right) > 1e-3 * max(abs(left), 1e-6):
        failures.append(f'越过拐点时斜率不连续：{left:.6f} → {right:.6f}')

    if failures:
        print('\n不一致：')
        for f in failures:
            print('  - ' + f)
        return 1
    print('\n全部一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())
