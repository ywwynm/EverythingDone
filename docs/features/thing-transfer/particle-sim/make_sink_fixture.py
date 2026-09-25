"""生成终点整形的跨语言对照数据：模拟器（本目录 sim_model.py）算出的结果，Kotlin 的
`ParticleSinkCrossLanguageTest` 必须逐项算出同样的值。两边任何一边改了算法而另一边没跟上，单测就失败。

输出 `fixtures/sink-shape.txt`（纯文本，JUnit 端不依赖任何 JSON 库）：
  geometry x y left top right bottom share jitter
  span v
  tuning release_scale arrival_end flight_base flight_per_span flight_jitter min_life
  row <12 个输入> | <12 个输出> | <终点 x y>
所有数都按 float32 的完整精度写出（repr 可逐位还原）。
"""
from pathlib import Path

import numpy as np

from sim_model import Params, SinkGeometry, shape_for_sink, um

OUT = Path(__file__).resolve().parent / 'fixtures' / 'sink-shape.txt'


def f32(v) -> str:
    return repr(float(np.float32(v)))


def main():
    rng = np.random.default_rng(20260924)
    n = 240
    values = np.zeros((n, 12), dtype='float32')
    values[:, 0] = rng.uniform(0, 300, n)
    values[:, 1] = rng.uniform(0, 200, n)
    values[:, 2] = rng.uniform(.008, .78, n)
    values[:, 3] = np.arange(n)
    values[:, 4:6] = rng.normal(size=(n, 2))
    values[:, 6] = rng.uniform(.11, .6, n)
    values[:, 7] = rng.uniform(-.05, .05, n)
    values[:, 8:12] = um.random_values(n, 97531)
    # 平局：到左右两边、上下两边的距离相同时，按 左 → 右 → 上 → 下 的顺序取
    values[0, :2] = (150, 100)
    values[1, :2] = (150, 40)
    values[2, :2] = (0, 0)
    values[3, :2] = (300, 200)
    # 贴近上下两条边、以及沿边错开后要夹回边框范围内的样本；随机数固定成一定流向边框（pick = 0.1048）
    crafted = [(100, -490), (200, -495), (50, 690), (250, 695), (-35, 650), (339, -480), (-38, -498), (338, 699)]
    for k, (x, y) in enumerate(crafted, start=4):
        values[k, :2] = (x, y)
        values[k, 8] = values[k, 11] = .01
    values = values.astype('float32')
    geometry = SinkGeometry(150., -400., -40., -500., 340., 700., .5, .36 * min(380., 1200.))
    params = Params.load()
    span = 200.
    before = values.copy()
    targets = shape_for_sink(values, geometry, span, params)
    lines = [
        '# 由 make_sink_fixture.py 生成；与 ParticleSink.shape / target 逐项对照',
        'geometry ' + ' '.join(f32(v) for v in (geometry.x, geometry.y, geometry.left, geometry.top, geometry.right,
                                                   geometry.bottom, geometry.share, geometry.jitter)),
        f'span {f32(span)}',
        'tuning ' + ' '.join(f32(params[k]) for k in ('sink_release_scale', 'sink_arrival_end', 'sink_flight_base',
                                                     'sink_flight_per_span', 'sink_flight_jitter', 'sink_min_life')),
    ]
    frame_hits = 0
    for i in range(n):
        lines.append('row ' + ' '.join(f32(v) for v in before[i]) + ' | ' + ' '.join(f32(v) for v in values[i]) +
                     ' | ' + ' '.join(f32(v) for v in targets[i]))
        frame_hits += not (targets[i, 0] == np.float32(geometry.x) and targets[i, 1] == np.float32(geometry.y))
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    print(f'{OUT} · {n} 片 · 流向边框 {frame_hits}')


if __name__ == '__main__':
    main()
