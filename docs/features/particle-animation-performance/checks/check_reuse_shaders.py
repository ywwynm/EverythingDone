"""直接对照正式 GLSL：压力输入复用、记录生成与稳定压缩合并。"""
import json
import sys
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT/'docs/features/harmony-particle-android-port/checks'))
from check_drift_clarity import source, uniforms

PLAYBACK = ROOT/'app/src/main/assets/particle-playback'


def shader(name, macros='', playback=False):
    text = source(name, PLAYBACK) if playback else source(name)
    return text.replace('#version 430', '#version 430\n'+macros)


def pressure(ctx):
    rows = []
    rng = np.random.default_rng(1008129)
    for registered in (False, True):
        common = '#define ADAPTIVE_PRESSURE 1\n#define ACTIVE_REGION 1\n'
        if registered:
            common += '#define REGISTER_BANDS 1\n'
        solvers = [ctx.compute_shader(shader('pressure-batched.comp', common+extra, True))
                   for extra in ('', '#define PRESSURE_INPUT_REUSE 1\n')]
        tiles = [ctx.compute_shader(shader('pressure-tiles.comp', common+extra, True))
                 for extra in ('', '#define PRESSURE_INPUT_REUSE 1\n')]
        for width, height in ((48, 48), (47, 61)):
            gx, gy = (width+15)//16, (height+15)//16
            work = ctx.buffer(reserve=64+gx*gy*8)
            control = ctx.buffer(reserve=136)
            vb = ctx.buffer(reserve=width*height*16)
            inputs = ctx.buffer(reserve=width*height*8)
            buffers = [ctx.buffer(reserve=width*height*4) for _ in range(2)]
            for scene in ('empty', 'smooth', 'sparse', 'noisy'):
                y, x = np.mgrid[:height, :width]
                shape = np.exp(-((x-width/2)**2+(y-height/2)**2)/100).astype(np.float32)
                v = np.zeros((height, width, 4), np.float32)
                v[:, :, 0] = (width/2-x)*shape*.01
                v[:, :, 1] = (height/2-y)*shape*.01
                v[:, :, 2] = (shape>.05).astype(np.float32)
                if scene == 'empty':
                    v[:] = 0
                if scene == 'sparse':
                    v[:, :, 2] = rng.random((height, width))>.7
                if scene == 'noisy':
                    v[:, :, :2] = rng.normal(0, .05, (height, width, 2))
                vb.write(v.tobytes())
                # 缩小活动区域，但区域外速度保留非零，验证边带输入没有被提前退出遗漏。
                for cropped in (False, True):
                    region = [16, 16, 31, 47] if cropped else [0, 0, gx*16-1, gy*16-1]
                    for total in (1, 8, 33, 100, 200):
                        for tolerance in (0., .001):
                            outputs = []
                            for mode in (0, 1):
                                header = np.zeros(16, np.int32); header[4:8] = region
                                work.write(header.tobytes()); control.clear()
                                inputs.write(np.full((height, width, 2), np.nan, np.float32).tobytes())
                                buffers[0].write((shape*.2).tobytes()); buffers[1].clear()
                                for buffer, binding in ((vb, 0), (inputs, 3), (control, 6), (work, 7)):
                                    buffer.bind_to_storage_buffer(binding)
                                buffers[1].bind_to_storage_buffer(2)
                                uniforms(tiles[mode], dict(grid_shape=(width, height)))
                                tiles[mode]['pass'].value = 0; tiles[mode].run(1); ctx.memory_barrier()
                                tiles[mode]['pass'].value = 1; tiles[mode].run(gx, gy); ctx.memory_barrier()
                                active = int(np.frombuffer(work.read(4), np.uint32)[0])
                                if mode:
                                    padded = np.pad(v, ((1, 1), (1, 1), (0, 0)), mode='edge')
                                    expected = (padded[1:-1, 2:, 0]-padded[1:-1, :-2, 0]+
                                                padded[2:, 1:-1, 1]-padded[:-2, 1:-1, 1])*.5
                                    prepared = np.frombuffer(inputs.read(), np.float32).reshape(height, width, 2)
                                    needed = ((x>=region[0]-8)&(x<=region[2]+8)&(y>=region[1]-8)&(y<=region[3]+8))
                                    assert np.max(np.abs(prepared[:, :, 0][needed]-expected[needed])) < 1e-7
                                    assert np.array_equal(prepared[:, :, 1][needed], v[:, :, 2][needed])
                                current = 0
                                for first in range(0, total, 8):
                                    buffers[current].bind_to_storage_buffer(1)
                                    buffers[1-current].bind_to_storage_buffer(2)
                                    uniforms(solvers[mode], dict(grid_shape=(width, height), tolerance=tolerance,
                                                               first_iteration=first, iterations=min(8, total-first)))
                                    if active:
                                        solvers[mode].run(active)
                                    ctx.memory_barrier(); current = 1-current
                                    if first == 0:
                                        buffers[1-current].bind_to_storage_buffer(2)
                                        tiles[mode]['pass'].value = 2; tiles[mode].run(gx, gy); ctx.memory_barrier()
                                outputs.append(np.frombuffer(buffers[current].read(), np.float32).copy())
                            error = float(np.max(np.abs(outputs[0]-outputs[1])))
                            assert error < 1e-6, (scene, width, total, cropped, error)
                            rows.append(dict(registered=registered, shape=[width, height], scene=scene,
                                             cropped=cropped, total=total, tolerance=tolerance, maxError=error))
            for resource in (work, control, vb, inputs, *buffers):
                resource.release()
        for resource in (*solvers, *tiles):
            resource.release()
    return rows


def records(ctx):
    rows = []
    rng = np.random.default_rng(129)
    common = ('#define PARTICLE_AMOUNT 1\n#define ROUND_PARTICLES 1\n#define FLAKE_CONSTANTS 1\n'
              '#define PARTICLE_COLOR 1\n#define TRUE_CIRCLE 1\n')
    for srgb in (False, True):
        macros = common+('#define SRGB_TARGET 1\n' if srgb else '')
        draws = [ctx.compute_shader(shader('draw-record.comp', macros+extra))
                 for extra in ('', '#define FUSED_RECORDS 1\n')]
        compacts = [ctx.compute_shader(shader('compact-circles.comp', extra, True))
                    for extra in ('', '#define FUSED_RECORDS 1\n')]
        image = rng.random((32, 32, 4), dtype=np.float32)
        image[:8, :, 3] = 0.; image[8:16, :, 3] = 1.
        texture = ctx.texture((32, 32), 4, image.tobytes(), dtype='f4')
        texture.filter = (moderngl.LINEAR, moderngl.LINEAR); texture.repeat_x = texture.repeat_y = False
        for count in (1, 255, 256, 257, 10007):
            groups = (count+255)//256
            m = np.zeros((count, 12), np.float32)
            m[:, :2] = rng.uniform(0, 480, (count, 2)); m[:, 2] = rng.uniform(0, .6, count)
            m[:, 3] = np.arange(count); m[:, 4] = 1.; m[:, 6] = rng.uniform(.02, .8, count)
            m[:, 8:] = rng.random((count, 4))
            s = np.zeros((count, 8), np.float32); s[:, :2] = rng.uniform(-80, 560, (count, 2))
            s[:, 2] = rng.uniform(-50, 50, count); s[:, 4:6] = rng.uniform(-50, 50, (count, 2))
            mb = ctx.buffer(m.tobytes()); sb = ctx.buffer(s.tobytes())
            pb = ctx.buffer(rng.random(count, dtype=np.float32).tobytes())
            raw = ctx.buffer(reserve=count*32); packed = ctx.buffer(reserve=count*32)
            offsets = ctx.buffer(reserve=count*4); work = ctx.buffer(reserve=16+(groups+1)*4)
            for size in (.5, 3., 6.):
                mb.write(m.tobytes()); sb.write(s.tobytes()); texture.write(image.tobytes())
                # 倒序和零可见帧也使用同一缓冲，检查组计数和末尾记录不会残留。
                for case in (0., .001, .022, .2, .4, .8, 1.5, .4, 'full'):
                    time = .4 if case == 'full' else case
                    if case == 'full':
                        full_m = m.copy(); full_m[:, 2] = .1; full_m[:, 6] = .8; full_m[:, 4:6] = 0.
                        full_s = s.copy(); full_s[:, :2] = 240.
                        mb.write(full_m.tobytes()); sb.write(full_s.tobytes()); texture.write(np.ones_like(image).tobytes())
                    values = dict(frame=(480., 480.), card=(480., 480.), offset=(1., -2.), cell=(8., 8.),
                                  wind=(1., 0.), particle_scale=(size, size), time=time, extrapolate=.002,
                                  span=480., roll_gain=1., body_weight=1., panel_weight=1., release_spread=.1,
                                  nx=32, grid_count=max(1, count//2), count=count, foreground=0, light_gain=1.)
                    outputs = []
                    for mode in (0, 1):
                        for buffer, binding in ((mb, 0), (sb, 1), (pb, 2), (work, 4), (raw, 6)):
                            buffer.bind_to_storage_buffer(binding)
                        texture.use(0); uniforms(draws[mode], values); draws[mode].run(groups); ctx.memory_barrier()
                        for buffer, binding in ((raw, 0), (packed, 1), (offsets, 2), (work, 3)):
                            buffer.bind_to_storage_buffer(binding)
                        uniforms(compacts[mode], dict(count=count, group_count=groups, frame=(480., 480.), offset=(1., -2.)))
                        for phase in range(mode, 3):
                            compacts[mode]['pass'].value = phase
                            compacts[mode].run(1 if phase == 1 else groups); ctx.memory_barrier()
                        command = np.frombuffer(work.read(16), np.uint32)
                        visible = int(command[1]); assert command[[0, 2, 3]].tolist() == [4, 0, 0]
                        outputs.append(np.frombuffer(packed.read(), np.float32).reshape(count, 8)[:visible].copy())
                    assert outputs[0].shape == outputs[1].shape, (count, time, size)
                    if case == 'full':
                        assert len(outputs[0]) == count, (count, len(outputs[0]))
                    error = float(np.max(np.abs(outputs[0]-outputs[1]))) if len(outputs[0]) else 0.
                    assert error < 1e-5, (count, time, size, error)
                    rows.append(dict(count=count, srgb=srgb, size=size, time=time, full=case == 'full',
                                     visible=len(outputs[0]), maxError=error))
            for resource in (mb, sb, pb, raw, packed, offsets, work):
                resource.release()
        for resource in (*draws, *compacts, texture):
            resource.release()
    return rows


if __name__ == '__main__':
    out = ROOT/'tmp/particle-reuse-20261008/desktop'; out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    result = dict(pressure=pressure(ctx), records=records(ctx))
    (out/'checks.json').write_text(json.dumps(result, indent=2), 'utf-8')
    print(json.dumps({name: dict(cases=len(rows), maxError=max(r['maxError'] for r in rows))
                      for name, rows in result.items()}))
