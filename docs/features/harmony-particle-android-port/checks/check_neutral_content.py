"""执行 Android 正式 GLSL，验证黑白灰撤去强调及已有行为；不连接设备。

用法：python check_neutral_content.py --baseline-ref 4ee756ce2 --out tmp/drift-neutral/result.json
--source-ref 可指定待测版本，用修改前版本执行应在黑白灰检查处失败。
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
SHADERS = 'shared/particle-dismiss/'
BASE = 4096
COUNT = BASE * 2


def shader(name, ref=None):
    if ref:
        return subprocess.check_output(['git', 'show', f'{ref}:{SHADERS}{name}'], cwd=ROOT).decode('utf-8')
    return (ROOT / SHADERS / name).read_text('utf-8')


def compute_source(ref=None):
    return shader('drift-update.comp', ref).replace(
        '#include "drift-common.glsl"', shader('drift-common.glsl', ref)
    ).replace('#version 310 es', '#version 430')


class Harness:
    def __init__(self, baseline, current):
        self.ctx = moderngl.create_standalone_context(require=430)
        self.programs = [self.ctx.compute_shader(current), self.ctx.compute_shader(baseline)]
        self.output = self.ctx.buffer(reserve=COUNT * 32)
        self.output.bind_to_storage_buffer(0)
        self.cells = self.ctx.buffer(np.array([1.], np.float32).tobytes())
        self.cells.bind_to_storage_buffer(1)
        self.image = self.ctx.texture((32, 32), 4)
        self.image.use(0)
        self.dispatches = 0
        for program in self.programs:
            values = dict(u_image=0, u_count=COUNT, u_baseCount=BASE, u_grid=(1, 1),
                          u_emitPos=(32., 48.), u_emitSize=(16., 16.), u_half=(16, 16),
                          u_full=(32, 32), u_alphaMul=1.15, u_total=2.,
                          u_turbOffset=(129., 72.), u_turbScale=(1., 1.), u_turbShift=(0., 0.),
                          u_latticeW=1, u_latticeH=1, u_scaleU=.002)
            for key, value in values.items():
                program[key].value = value
            program['u_lattice'].write(np.full(64, .3, np.float32).tobytes())

    def run(self, rgb, panel, *, old=False, weight=1., time=.5, add=0, size=1.,
            wind=(19800., -19800.), hz=120., touch=-1., fade=.5, alpha=255):
        if isinstance(rgb, np.ndarray):
            pixels = rgb
        else:
            pixels = np.empty((32, 32, 4), np.uint8)
            pixels[:] = (*rgb, alpha)
        self.image.write(pixels.tobytes())
        program = self.programs[int(old)]
        values = dict(u_panel=tuple(c / 255. for c in panel), u_panelWeight=weight,
                      u_time=time, u_add=add, u_sizeScale=size, u_wind=wind,
                      u_hz=hz, u_touchGap=touch, u_fadeSizePower=.5, u_fadeAlphaPower=fade)
        for key, value in values.items():
            program[key].value = value
        program.run((COUNT + 255) // 256)
        self.ctx.memory_barrier()
        result = np.frombuffer(self.output.read(), np.float32).reshape(COUNT, 8).copy()
        assert np.isfinite(result).all(), '粒子状态出现非有限值'
        self.dispatches += 1
        return result


def same(actual, expected, label):
    # 同一 GPU 上不同着色器编译结果允许极小的浮点舍入差，不放宽尺寸或颜色差异。
    np.testing.assert_allclose(actual[:, :2], expected[:, :2], rtol=0, atol=1e-5, err_msg=label)
    np.testing.assert_allclose(actual[:, 2:], expected[:, 2:], rtol=0, atol=1e-6, err_msg=label)


def visible(data):
    return int(np.count_nonzero(data[:, 2] > 0))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-ref', required=True)
    parser.add_argument('--source-ref')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    baseline = compute_source(args.baseline_ref)
    current = compute_source(args.source_ref)
    h = Harness(baseline, current)
    themes = [('light', (244, 244, 244), (0, 96, 160)),
              ('dark', (28, 28, 28), (255, 160, 112))]
    times = (.30, .36, .50, .62, .83, 1.20)
    cases = 0
    neutral_counts = []
    for theme, panel, grays in themes:
        for gray in grays:
            for time in times:
                rgb = (gray,) * 3
                before = h.run(rgb, panel, old=True, time=time)
                after = h.run(rgb, panel, time=time)
                plain = h.run(rgb, panel, weight=0., time=time)
                # 与面板充分反差的黑白灰应回到未加强调的基础状态，不能被当作本体额外削弱。
                same(after[:BASE], plain[:BASE], f'{theme}/{gray}/{time}: 基础颗粒')
                assert not after[BASE:].any(), f'{theme}/{gray}/{time}: 仍有黑白灰副本'
                if time == .50:
                    assert visible(after[:BASE]) == BASE
                    assert visible(before[BASE:]) == BASE, '对照没有触发额外强调'
                    assert not np.array_equal(before[:BASE], after[:BASE]), '未撤去原有增益'
                    neutral_counts.append(dict(theme=theme, gray=gray, base=visible(after[:BASE]),
                                               old_copies=visible(before[BASE:]), new_copies=0))
                cases += 1

        # 2026-10-08 仅把本体尺寸衰减 26% 减为 16%；其余状态及彩色强调保持。
        for rgb in (panel, (234, 49, 75), (32, 130, 240)):
            for time in times:
                after = h.run(rgb, panel, time=time)
                before = h.run(rgb, panel, old=True, time=time)
                if rgb == panel:
                    before[:BASE, 2] *= .84 / .74
                    same(after[:BASE], before[:BASE], f'{theme}: 本体基础颗粒')
                else:
                    same(after, before, f'{theme}: 彩色强调')
                cases += 1

        # 没有明确本体色时，即使传入副本候选也不得改动原输出。
        for rgb in ((0, 0, 0), (255, 255, 255), (120, 136, 145), (30, 140, 230)):
            for time in (.36, .62, .83):
                same(h.run(rgb, panel, weight=0., time=time),
                     h.run(rgb, panel, old=True, weight=0., time=time), f'{theme}: 无强调')
                cases += 1

        # 覆盖两个合成方式、极端大小、相反风向、真实触点、旧参考不淡出的规则及高刷新率。
        rgb = (grays[0],) * 3
        for add in (0, 1):
            for size in (.5, 1., 6.):
                for direction in (-1., 1.):
                    config = dict(add=add, size=size, wind=(direction*19800., direction*19800.),
                                  touch=.1 if direction > 0 else -1.,
                                  hz=144. if direction > 0 else 120., fade=0. if add else .5)
                    after = h.run(rgb, panel, **config)
                    same(after[:BASE], h.run(rgb, panel, weight=0., **config)[:BASE],
                         f'{theme}: 合成/大小/风向/触点/帧率')
                    assert not after[BASE:].any()
                    accent = (32, 130, 240)
                    same(h.run(accent, panel, **config), h.run(accent, panel, old=True, **config),
                         f'{theme}: 配置变动后的强调色')
                    cases += 1

    # 检验阈值和连续过渡：在生长结束、衰减尚未开始的时刻，尺寸/不透明度可独立测量。
    ramps = []
    for theme, panel, _ in themes:
        kept = []
        for delta in (0, 10, 11, 20, 31, 40, 50, 51, 64):
            low = 32 if theme == 'light' else 180
            rgb = (low, low+delta, low)
            after = h.run(rgb, panel, add=1)
            before = h.run(rgb, panel, add=1, old=True)
            t = np.clip((delta / 255. - .04) / .16, 0., 1.)
            retained = t*t*(3.-2.*t)
            np.testing.assert_allclose(after[:BASE, 2] / before[:BASE, 2],
                                       (1.+.20*retained)/1.20, rtol=0, atol=2e-6)
            np.testing.assert_allclose(after[:BASE, 4:] / before[:BASE, 4:],
                                       (1.+.25*retained)/1.25, rtol=0, atol=2e-6)
            live = visible(after[BASE:])
            kept.append(live)
            # 二项分布的宽裕界限，用于检查副本筛选与增益使用同一连续范围。
            assert abs(live-BASE*retained) < 6*np.sqrt(BASE*retained*(1-retained))+2
            if delta <= 10:
                assert live == 0
            if delta >= 51:
                same(after, before, f'{theme}: 彩度阈值上限')
                assert live == BASE
            ramps.append(dict(theme=theme, rgb=rgb, retained=float(retained), copies=live))
            cases += 1
        assert kept == sorted(kept), '副本数量未随彩度单调增加'

    # 同一纹理混合本体、文字、强调色；非均匀 CDF 和空间遮罩不应导致副本重新分配。
    mixed = np.empty((32, 32, 4), np.uint8)
    for column, rgb in enumerate(((244, 244, 244), (32, 32, 32), (32, 130, 240), (60, 88, 60))):
        mixed[:, column*8:(column+1)*8] = (*rgb, 255)
    h.cells.orphan(16)
    h.cells.write(np.array([.05, .55, .85, 1.], np.float32).tobytes())
    h.cells.bind_to_storage_buffer(1)
    for program in h.programs:
        program['u_grid'].value = (4, 1)
        program['u_latticeW'].value = 4
        program['u_latticeH'].value = 4
        program['u_lattice'].write(np.random.default_rng(1234).uniform(0, 1, 64).astype('f4').tobytes())
        program['u_scaleU'].value = .075
    saved = {}
    mixed_accent_samples = 0
    for time in (.15, .3, .45, .6, .75, .9, 1.1, 1.4):
        after = h.run(mixed, (244, 244, 244), time=time)
        before = h.run(mixed, (244, 244, 244), old=True, time=time)
        blue = (before[:, 6] > before[:, 5]) & (before[:, 5] > before[:, 4])
        mixed_accent_samples += int(blue.sum())
        same(after[blue], before[blue], '混合纹理中的强调色及副本')
        saved[time] = after
        cases += 1
    assert mixed_accent_samples > 1000, '混合对照覆盖不足'
    for time in (1.4, 1.1, .9, .75, .6, .45, .3, .15, .9, .3, .75, .3):
        np.testing.assert_array_equal(h.run(mixed, (244, 244, 244), time=time), saved[time])
        cases += 1
    for alpha in (0, 128, 254):
        assert not h.run((0, 0, 0), (244, 244, 244), alpha=alpha).any()
        cases += 1

    result = dict(cases=cases, dispatches=h.dispatches, baseline_ref=args.baseline_ref,
                  shader_sha256=hashlib.sha256(shader('drift-update.comp', args.source_ref).encode()).hexdigest(),
                  gpu=h.ctx.info['GL_RENDERER'], neutral_counts=neutral_counts,
                  chroma_ramps=ramps, mixed_accent_samples=mixed_accent_samples,
                  device_connected=False)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
