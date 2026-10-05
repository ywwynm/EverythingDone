"""预渲染与实时预览共用的输入边界；参考观测不进入模型规则。"""
from dataclasses import asdict
from pathlib import Path
import json
import math
import random
from desktop_candidate import DesktopCandidate, SparseRenderer, Origin, sample_origins
from desktop_material import MaterialCandidate
from renderer import Renderer
from unified_model import touch_gap_from_point
from reference_initial_conditions import SITES,NOTES
from harmony_core import HarmonyCoreRenderer
from harmony_origins import HarmonyOriginsRenderer
from harmony_native import NativeLive, live_inputs
from android_drift import AndroidDriftLive, build_android_renderer, BACK_KEY_DEGREES

ROOT = Path(__file__).resolve().parent
OWN = ['attachment', 'attachment-image', 'language', 'color']
REFS = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]
SCENES = OWN + REFS
META = {n: json.loads((ROOT / 'assets' / n / 'scene.json').read_text('utf-8')) for n in SCENES}
MODELS = {'baseline': ('Android 同规则基准', Renderer), 'sparse': ('基准 · 仅减少粒子', SparseRenderer),
          'candidate': ('10 月 4 日 · 多起点候选', DesktopCandidate),
          'material': ('材质实验 · 保色颗粒（未验收）', MaterialCandidate),
          'harmony-core': ('HarmonyOS 7 原生公式对照', HarmonyCoreRenderer),
          'harmony-origins': ('华为核心 · 起点与方向核对', HarmonyOriginsRenderer),
          'harmony-native': ('华为原生 · 逐项等价', NativeLive),
          'harmony-android': ('华为原生 · Android 移植口径', AndroidDriftLive)}
GAP = 12


def scene_info(name):
    m = META[name]
    w, h = m['frame']
    ref = name in REFS
    return dict(name=name, title=m.get('title', name), frame=[w, h], rect=m['rect'],
                touch_rect=m.get('touch_rect', [16, 80, w-16, h-60]), ref=ref,
                view=[2*w+GAP, h] if ref else [w, h], panel_x=w+GAP if ref else 0,
                background_truth=m.get('background_truth', False))


def animation_inputs(name, kind, seed, tap=None):
    m = META[name]
    x0, y0, x1, y1 = m['rect']
    w, h = x1-x0, y1-y0
    reference = kind == 'ref' and name in REFS
    if reference:
        direction = float(m['direction'])
        origins = tuple(Origin(*o) for o in SITES[name])
        gap = None
    else:
        direction, gap = random.Random(int(seed)^0x49e76).uniform(40.,145.), None
        if tap is not None:
            x, y = tap
            direction = math.degrees(math.atan2((y0+y1)/2-y, x-(x0+x1)/2)) % 360
            gap = touch_gap_from_point(x-x0, y-y0, w, h)
        origins = sample_origins(seed, w, h)
    return dict(scene=name, kind='ref' if reference else ('tap' if tap is not None else 'back'),
                seed=int(seed), direction=direction, touch_gap=gap, tap=tap,
                origins=[asdict(o) for o in origins],
                source=('参考早段观测起点；'+NOTES.get(name,'细微坐标和启动时刻仍有录像测量误差。')) if reference else
                       ('按点击位置确定方向，按种子生成起点' if tap is not None else '同一分布按种子生成起点和方向'))


def make_renderer(inputs, model, ctx, quality=2):
    _, cls = MODELS[model]
    if cls is AndroidDriftLive:
        # 输入按 Android 的规则生成（与设备逐位相同）：参考样式按方向与概率出现（左上更常是旧钢铁侠一族）、风转到消散方向、
        # 粒子数按面积、内容强调、背景亮度定加光或 src-over；随机播放即返回键关闭（左上 135°），点击按触点方向。
        # 参考复刻仍按原片估计的输入播放华为原生实现（Android 用同一份输入时逐帧一致，见 harmony-particle-android-port/parity）。
        kind = inputs['kind']
        if kind == 'ref':
            native, entry = live_inputs(inputs['scene'], META[inputs['scene']], kind, inputs['seed'], None)
            return NativeLive(inputs['scene'], native, entry=entry, ctx=ctx, dim_alpha=META[inputs['scene']].get('dim_alpha', 0.0))
        direction = inputs['direction'] if kind == 'tap' else BACK_KEY_DEGREES
        return build_android_renderer(inputs['scene'], META[inputs['scene']], inputs['seed'], direction, ctx=ctx)
    if cls is NativeLive:
        # 参考序列用原片估计的一次动画输入；随机与点击按种子生成，点击只决定横风。
        kind = inputs['kind']
        direction = inputs['direction'] if kind == 'tap' else None
        native, entry = live_inputs(inputs['scene'], META[inputs['scene']], kind, inputs['seed'], direction)
        return NativeLive(inputs['scene'], native, entry=entry, ctx=ctx, dim_alpha=META[inputs['scene']].get('dim_alpha', 0.0))
    kwargs = dict(ctx=ctx, quality=quality, seed=inputs['seed'], direction=inputs['direction'], touch_gap=inputs['touch_gap'])
    if issubclass(cls, DesktopCandidate):
        kwargs['origins'] = [Origin(**o) for o in inputs['origins']]
    if issubclass(cls, HarmonyOriginsRenderer):
        kwargs['origins'] = inputs['origins']
    return cls(inputs['scene'], **kwargs)
