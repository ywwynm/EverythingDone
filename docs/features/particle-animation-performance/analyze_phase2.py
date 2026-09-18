"""逐帧成本专项第二阶段：逐项墙钟 A/B 的汇总。

口径与第一批的 `analyze_per_frame_ab.py` 相同：**按配对取比值**（每个条件都紧挨着它自己的
基线跑一遍），不跨条件比绝对值——同一轮里条件之间的绝对值会被 DVFS 漂移拉开。

用法：
    python analyze_phase2.py <phase2-bench.json> [更多 json…]

每一行给出「基线每单位耗时 / 配对比值中位数 / 各次配对比值」；比值 < 1 即为提速。
"""
import json
import statistics
import sys
from pathlib import Path

PATHS = ('dismiss', 'appear', 'preread')


def rows(data):
    out = []
    for path in PATHS:
        bucket = data.get(path) or {}
        units = bucket.get('units') or 1
        names = [k for k in bucket if k not in ('units',) and not k.startswith('base@')]
        for name in names:
            base = bucket.get('base@' + name) or []
            test = bucket.get(name) or []
            if not base or not test:
                continue
            pairs = [t / b for b, t in zip(base, test)]
            out.append({
                'path': path, 'name': name, 'units': units,
                'basePerUnit': statistics.median(base) / units,
                'ratio': statistics.median(pairs),
                'pairs': pairs,
            })
    return out


def main():
    assert len(sys.argv) > 1, __doc__
    for arg in sys.argv[1:]:
        data = json.loads(Path(arg).read_text(encoding='utf-8'))
        print('==', arg)
        print('   renderer=%s count=%s' % (data.get('renderer'), data.get('count')))
        table = rows(data)
        for path in PATHS:
            group = [r for r in table if r['path'] == path]
            if not group:
                continue
            print('   -- %s（基线每单位 %.2f ms）' % (path, group[0]['basePerUnit']))
            for r in sorted(group, key=lambda r: r['ratio']):
                print('      %-22s %6.3f   %s' % (
                    r['name'], r['ratio'], ' '.join('%.3f' % p for p in r['pairs'])))


if __name__ == '__main__':
    main()
