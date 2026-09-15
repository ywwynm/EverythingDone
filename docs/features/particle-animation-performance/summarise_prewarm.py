"""第五批追加项：把观察器产物里创建页那一行的「抬手 → 首帧」与命中来源打出来。

用法（在 tmp/particle-perf/phase0/<serial>/runs 下）：

    python <本文件> b5pcold-0 b5ptrim2-0 ...

「抬手」取的是观察器记下的**真实触摸 ACTION_UP**。输入行的形状是
`[时间, 种类(0=触摸 / 1=按键), action, ...]`——按 `r[1] == 1` 过滤拿到的是按键事件，
不是抬手（2026-09-15 实际踩过，算出来的数字整体偏小一半）。
"""
import io
import json
import sys


def summarise(path, label):
    data = json.load(io.open(path + '/result.json', encoding='utf-8'))
    inputs = data.get('inputs') or []
    ups = [r[0] for r in inputs if len(r) > 2 and r[1] == 0 and r[2] == 1]
    for overlay in data.get('overlays', []):
        if not overlay.get('creationPage'):
            continue
        prewarm = overlay.get('prewarm') or {}
        requested = overlay['requestAt']
        up = max([t for t in ups if t <= requested], default=None)
        committed = overlay.get('committed') or []
        submitted = overlay.get('submitted') or []
        # committed 的第 4 位是驱动报的真实呈现时刻（拿不到写 0），见 ParticleSession。
        presented = next((r[3] for r in committed if len(r) > 3 and r[3]), None)
        base = up or requested
        def ms(value):
            return round((value - base) / 1e6, 1) if value else None
        disk = prewarm.get('disk') or {}
        print('%-12s outcome=%-5s source=%-6s disk=%s/%sB | base=%s '
              '| first_submit=%s first_confirm=%s first_present=%s precomputeMs=%s' % (
                  label, prewarm.get('outcome'), prewarm.get('sourceKind'),
                  disk.get('entries'), disk.get('bytes'),
                  'up' if up else 'request',
                  ms(submitted[0][1] if submitted else None),
                  ms(committed[0][1] if committed else None), ms(presented),
                  round(prewarm.get('precomputeMs') or 0, 1)))


if __name__ == '__main__':
    for argument in sys.argv[1:]:
        summarise(argument, argument.replace('\\', '/').rstrip('/').split('/')[-1])
