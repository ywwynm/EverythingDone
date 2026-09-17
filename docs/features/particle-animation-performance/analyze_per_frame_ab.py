"""逐帧成本专项第一批的逐项 A/B 汇总。

读 `ParticleSegmentBench.runPerFrame` 落下的 `per-frame-bench.json`：每个条件都紧挨着
它自己的基线跑一遍，所以按**配对**取比值，不跨条件比绝对值（DVFS 会漂）。

用法：python analyze_per_frame_ab.py <per-frame-bench.json> [...]
"""
import json
import statistics
import sys
from pathlib import Path

PATHS = (('dismiss', '现场消散', 'dismissFrames'), ('appear', '回放出现', 'appearSamples'),
         ('preread', '左滑预读', 'appearSamples'))


def summarise(path):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if 'error' in data:
        print(path, '出错：', data['error'].splitlines()[0])
        return
    print('#', path)
    print('  设备 %s  片数 %s  尺寸 %s' % (data.get('renderer'), data.get('count'), data.get('sizes')))
    for key, label, frames_key in PATHS:
        block = data.get(key)
        if not block:
            continue
        divisor = data.get(frames_key) or 1
        if key != 'dismiss':
            divisor += 1
        print('  %s（每帧基数 = 整段 / %d）' % (label, divisor))
        for name, values in block.items():
            if name.startswith('base@'):
                continue
            base = block.get('base@' + name)
            if not base:
                continue
            pairs = list(zip(base, values))
            ratios = [b / a for a, b in pairs]
            base_ms = statistics.median(a for a, _ in pairs)
            new_ms = statistics.median(b for _, b in pairs)
            print('    %-14s 基线 %7.2f ms → %7.2f ms   每帧 %5.3f → %5.3f ms   %+6.1f%%  (n=%d)'
                  % (name, base_ms, new_ms, base_ms / divisor, new_ms / divisor,
                     (statistics.median(ratios) - 1) * 100, len(pairs)))


if __name__ == '__main__':
    for target in sys.argv[1:]:
        summarise(target)
