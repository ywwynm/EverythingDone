"""生成终点整形的跨语言对照数据：模拟器（本目录 sim_model.py）算出的结果，Kotlin 的
`ParticleSinkCrossLanguageTest` 必须逐项算出同样的值。两边任何一边改了算法而另一边没跟上，单测就失败。

输出两份（纯文本，JUnit 端不依赖任何 JSON 库；所有数按 float32 的完整精度写出，repr 可逐位还原）：

`fixtures/sink-shape.txt`：旧版的终点牵引（`ParticleSink.shape` / `target`）
  geometry x y left top right bottom share jitter
  span v
  tuning release_scale arrival_end flight_base flight_per_span flight_jitter min_life
  row <12 个输入> | <12 个输出> | <终点 x y>

`fixtures/sink-field.txt`：第三版的柔性终点（`ParticleSink.shapeForField` / `fieldTarget`）
  geometry x y left top right bottom share jitter
  flow a b c d / band a b c d
  group l t r b shift swirl share born（每组一行）
  card w h / seed s / blend b（材料单位）
  field arrival_first arrival_last release_scale arrival_end min_life free_life
  row <12 个输入> <是否可见 0/1> | <12 个输出> | <流向边框 0/1> <被吸入 0/1> | <落点 x y>
"""
from pathlib import Path

import numpy as np

from sim_model import (DP_UNITS, Params, SinkGeometry, assign_sources, field_targets, shape_for_field,
                       shape_for_sink, um)

OUT = Path(__file__).resolve().parent / 'fixtures' / 'sink-shape.txt'
OUT_FIELD = Path(__file__).resolve().parent / 'fixtures' / 'sink-field.txt'


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
    field()


def field():
    """第三版：接收的几何——天线在两张卡片之间，屏幕边框，两张卡片分组，中间一道透明的空隙。
    几何取能被 float32 精确表示的数，Kotlin 端按同样的 float32 值计算，来源分配可以逐位对上。"""
    rng = np.random.default_rng(20260925)
    n = 400
    card = (600.0, 700.0)
    values = np.zeros((n, 12), dtype='float32')
    values[:, 0] = rng.uniform(0, card[0], n)
    values[:, 1] = rng.uniform(0, card[1], n)
    values[:, 2] = rng.uniform(.008, .78, n)
    values[:, 3] = np.arange(n)
    values[:, 4:6] = rng.normal(size=(n, 2))
    values[:, 6] = rng.uniform(.11, .6, n)
    values[:, 7] = rng.uniform(-.05, .05, n)
    values[:, 8:12] = um.random_values(n, 24680)
    values = values.astype('float32')
    # 两张卡片：左 0–280（高 400），右 320–600（高 700）；空隙与左卡下方看不见，另外随机藏掉一成
    x, y = values[:, 0], values[:, 1]
    visible = ((x < 280) & (y < 400)) | (x >= 320)
    visible &= rng.random(n) > .1
    params = Params.load()
    unit = DP_UNITS
    band_dp = params['receive_dust_band_dp'] + params['receive_dust_spread_dp'] * 1.3
    groups = [((0., 0., 280., 400.), (0., 1., .25, .125)), ((320., 0., 600., 700.), (-.0625, 1.75, .25, 0.))]
    geometry = SinkGeometry(300., 250., -60., -600., 700., 1100., .25, 273.5,
                            flow=(0., .8, 75., .875), band=(float(np.float32(band_dp * unit)), .5, 1.25, 0.),
                            groups=groups)
    blend = float(np.float32(params['receive_frame_blend_dp'] * unit))
    seed = 1158988655
    before = values.copy()
    assign_sources(values, geometry, card, blend, seed, visible)
    target, frame_bound, absorbed = field_targets(values, geometry, card)
    shape_for_field(values, geometry, min(card), params, card)
    lines = [
        '# 由 make_sink_fixture.py 生成；与 ParticleSink.shapeForField / fieldTarget 逐项对照',
        'geometry ' + ' '.join(f32(v) for v in (geometry.x, geometry.y, geometry.left, geometry.top, geometry.right,
                                                   geometry.bottom, geometry.share, geometry.jitter)),
        'flow ' + ' '.join(f32(v) for v in geometry.flow),
        'band ' + ' '.join(f32(v) for v in geometry.band),
    ]
    for rect, (shift, swirl, share, born) in groups:
        lines.append('group ' + ' '.join(f32(v) for v in (*rect, shift, swirl, share, born)))
    lines += [
        f'card {f32(card[0])} {f32(card[1])}',
        f'seed {seed}',
        f'blend {f32(blend)}',
        'field ' + ' '.join(f32(v) for v in (params['sink_arrival'][0], params['sink_arrival'][1],
                                             params['sink_release_scale'], params['sink_arrival_end'],
                                             params['sink_min_life'], params['sink_free_life'])),
    ]
    for i in range(n):
        lines.append('row ' + ' '.join(f32(v) for v in before[i]) + f' {int(visible[i])} | ' +
                     ' '.join(f32(v) for v in values[i]) + f' | {int(frame_bound[i])} {int(absorbed[i])} | ' +
                     ' '.join(f32(v) for v in target[i]))
    OUT_FIELD.write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    moved = int((before[:, 8:12] != values[:, 8:12]).any(axis=1).sum())
    print(f'{OUT_FIELD} · {n} 片 · 看得见 {int(visible.sum())} · 流向边框 {int(frame_bound.sum())} '
          f'（看得见的里 {int((frame_bound & visible).sum())}）· 随机向量换过的 {moved}')


if __name__ == '__main__':
    main()
