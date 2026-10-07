"""用实际材料着色器检查出生轮廓、数量与尺寸、两条绘制路径的一致性。

使用 everythingdone 环境的 ModernGL，在桌面 GPU 上执行共享 GLSL；GLES 编译与真机另验。
"""
import argparse
import json
import math
from pathlib import Path

import cv2
import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
SHADERS = ROOT / 'shared/particle-dismiss'
# 以 4 倍分辨率量轮廓，避免末段约 9 像素的小圆被像素周长误判为方块。
SIZE = 1024
FRAME = 256


def source(name, defines=''):
    text = (SHADERS / name).read_text('utf-8')
    return text.replace('#version 310 es', '#version 430\n' + defines)


def uniforms(program, values):
    for key, value in values.items():
        if key in program:
            program[key].value = value


def attributes(program, specs):
    return [(buffer, layout, name) for buffer, layout, name in specs if name in program]


def check_surface(ctx, failures):
    clock = ctx.compute_shader(source('surface-clock.comp'))
    materials = np.zeros((5, 12), np.float32)
    for i in range(5):
        materials[i, :4] = [(i%2+.5)*64, ((i%4)//2+.5)*64, .3+.05*i, i]
        materials[i, 6] = 1
        materials[i, 11] = .6
    # 顺序打乱且放入一个补充粒子：必须按原网格编号写回，不能重复覆盖。
    data = ctx.buffer(materials[[3, 4, 1, 0, 2]].tobytes())
    data.bind_to_storage_buffer(0)
    times = ctx.texture((2, 2), 1, dtype='f4')
    times.filter = moderngl.NEAREST, moderngl.NEAREST
    times.bind_to_image(0, read=False, write=True)
    uniforms(clock, dict(card=(128., 128.), span=128., release_spread=.1, nx=2, grid_count=4, count=5))
    clock.run(1); ctx.memory_barrier()
    delta = float(np.max(np.abs(np.frombuffer(times.read(), np.float32) - np.array([.29, .34, .39, .44]))))
    if delta > 1e-6:
        failures.append(dict(reason='surface_clock_order', delta=delta))
    program = ctx.program(vertex_shader=source('surface.vert'), fragment_shader=source('surface.frag'))
    vao = ctx.vertex_array(program, [])
    foreground = ctx.texture((2, 2), 4, np.ones((2, 2, 4), np.float32).tobytes(), dtype='f4')
    target = ctx.texture((256, 256), 4, dtype='f4')
    fbo = ctx.framebuffer([target])
    uniforms(program, dict(frame=(128., 128.), card=(128., 128.), offset=(0., 0.), foreground=0, release_times=1))
    foreground.use(0); times.use(1)
    for t in (0., .36, 1., 0.):
        uniforms(program, dict(time=t)); fbo.use(); fbo.clear()
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        alpha = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(256, 256, 4)[:, :, 3]
        if t in (0., 1.):
            error = float(np.max(np.abs(alpha - (1-t))))
            if error > 1e-6:
                failures.append(dict(reason='surface_endpoint', time=t, delta=error))
        else:
            jump = float(max(np.abs(np.diff(alpha, axis=0)).max(), np.abs(np.diff(alpha, axis=1)).max()))
            if jump > .02:
                failures.append(dict(reason='surface_grid_seam', delta=jump))
    # t=0 必须保留快照的颜色、透明边缘和方向，避免聚回最后一帧出现接缝或翻转。
    rgba = np.array([[[.2, .7, .4, 1.], [.8, .1, .3, .5]],
                     [[.1, .3, .9, .25], [.9, .8, .2, 0.]]], np.float32)
    foreground.write(rgba.tobytes())
    foreground.filter = moderngl.NEAREST, moderngl.NEAREST
    uniforms(program, dict(time=0.)); fbo.clear()
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
    actual = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(256, 256, 4)
    rgb = rgba[:, :, :3]
    linear = np.where(rgb < .04045, rgb/12.92, ((rgb+.055)/1.055)**2.4)
    expected = np.concatenate([linear * rgba[:, :, 3:], rgba[:, :, 3:]], axis=2)
    expected = expected.repeat(128, axis=0).repeat(128, axis=1)[::-1]
    error = float(np.max(np.abs(actual - expected)))
    if error > 1e-6:
        failures.append(dict(reason='surface_snapshot_copy', delta=error))
    for resource in (vao, program, data, times, foreground, target, fbo, clock):
        resource.release()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    target = ctx.texture((SIZE, SIZE), 4, dtype='f4')
    fbo = ctx.framebuffer([target])
    texture = ctx.texture((2, 2), 4, np.ones((2, 2, 4), np.float32).tobytes(), dtype='f4')
    texture.use(0)
    defines = '#define PARTICLE_AMOUNT 1\n#define ROUND_PARTICLES 1\n'
    programs = {}
    for mode, extra in [('direct', ''), ('records', '#define DRAW_RECORDS 1\n'),
                        ('constants', '#define DRAW_RECORDS 1\n#define FLAKE_CONSTANTS 1\n'),
                        ('single', '#define DRAW_RECORDS 1\n#define FLAKE_CONSTANTS 1\n#define SINGLE_INSTANCE 1\n')]:
        programs[mode] = ctx.program(vertex_shader=source('material.vert', defines + extra),
                                     fragment_shader=source('material.frag', defines + extra))
    computes = {mode: ctx.compute_shader(source('draw-record.comp', defines +
                ('#define FLAKE_CONSTANTS 1\n' if mode in ('constants', 'single') else '') +
                ('#define SINGLE_INSTANCE 1\n' if mode == 'single' else '')))
                for mode in ('records', 'constants', 'single')}
    rows, images, failures = [], {}, []
    for size in (24., 48.):
        for amount in (.1, .2, 1., 1.6):
            for age in (.012, .035, .10, .25):
                cell = size / math.sqrt(amount)
                centre = cell * 10.5
                values = dict(frame=(FRAME, FRAME), card=(cell*21, cell*21),
                              offset=(FRAME/2-centre, FRAME/2-centre), cell=(cell, cell),
                              particle_scale=(math.sqrt(amount), math.sqrt(amount)),
                              wind=(1., 0.), time=.3+age, extrapolate=0., span=cell*21,
                              roll_gain=0., body_weight=1., panel_weight=1., release_spread=.1,
                              nx=21, grid_count=441, count=1, material_pass=1, diagnostic=0,
                              foreground=0, light_gain=0.)
                material = np.array([centre, centre, .3, 220, 1, 0, 1, 0,
                                     .4, .7, .33, .66], np.float32)
                state = np.array([centre, centre, 0, 0, 0, 0, 0, 0], np.float32)
                mb, sb, pb = (ctx.buffer(data.tobytes()) for data in
                              (material, state, np.array([0.], np.float32)))
                rb = ctx.buffer(reserve=80)
                mb.bind_to_storage_buffer(0); sb.bind_to_storage_buffer(1)
                pb.bind_to_storage_buffer(2); rb.bind_to_storage_buffer(6)
                outputs = {}
                for mode, program in programs.items():
                    if mode != 'direct':
                        uniforms(computes[mode], values)
                        computes[mode].run(1)
                        ctx.memory_barrier()
                        stride = 80 if mode == 'single' else 64
                        specs = [(rb, f'{i*16}x 4f {stride-(i+1)*16}x /i' if i else f'4f {stride-16}x /i', name)
                                 for i, name in enumerate(('record_xform', 'record_place', 'record_shade', 'record_mask'))]
                        # ModernGL 不接受 0x 的填充。
                        specs = [(b, fmt.replace(' 0x', ''), name) for b, fmt, name in specs]
                        specs += [(mb, '4f 32x /i', 'material_src'), (mb, '32x 4f /i', 'material_random'),
                                  (rb, '64x 4f /i', 'record_extra')]
                    else:
                        specs = [(mb, '4f 32x /i', 'material_src'), (mb, '16x 4f 16x /i', 'material_physical'),
                                 (mb, '32x 4f /i', 'material_random'), (sb, '4f 16x /i', 'state_pos'),
                                 (sb, '16x 4f /i', 'state_vel'), (pb, '1f /i', 'pigment')]
                    vao = ctx.vertex_array(program, attributes(program, specs))
                    uniforms(program, values)
                    fbo.use(); fbo.clear()
                    vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
                    alpha = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(SIZE, SIZE, 4)[:, :, 3].copy()
                    outputs[mode] = alpha
                    mask = np.uint8(alpha > alpha.max() * .2)
                    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                    contour = max(contours, key=cv2.contourArea)
                    area = cv2.contourArea(contour)
                    perimeter = cv2.arcLength(contour, True)
                    circularity = 4 * math.pi * area / perimeter**2
                    ellipse = cv2.fitEllipse(contour)
                    aspect = max(ellipse[1]) / min(ellipse[1])
                    diameter = math.sqrt(4 * np.count_nonzero(mask) / math.pi) * FRAME/SIZE
                    row = dict(size=size, amount=amount, age=age, mode=mode,
                               circularity=circularity, aspect=aspect, diameter=diameter)
                    rows.append(row)
                    if circularity < .83 or aspect > 1.18:
                        failures.append(row)
                    vao.release()
                # 逐片常量的半精度打包允许小量像素误差；几何与可见轮廓必须一致。
                for mode in ('records', 'constants', 'single'):
                    delta = float(np.max(np.abs(outputs['direct'] - outputs[mode])))
                    if delta > .003:
                        failures.append(dict(reason='path_difference', mode=mode, age=age, delta=delta))
                images[(size, amount, age)] = outputs['constants']
                for buffer in (mb, sb, pb, rb):
                    buffer.release()
    for size in (24., 48.):
        for age in (.012, .035, .10, .25):
            samples = [r['diameter'] for r in rows if r['size'] == size and r['age'] == age and r['mode'] == 'constants']
            if max(samples) / min(samples) > 1.03:
                failures.append(dict(reason='amount_changed_size', size=size, age=age, diameters=samples))
    # 将四个数量档位的出生期轮廓并列，便于人工核查；测试执行前后都可保存。
    strips = []
    for amount in (.1, .2, 1., 1.6):
        alpha = images[(48., amount, .012)]
        image = np.uint8(np.clip(alpha / alpha.max(), 0, 1) * 255)
        strips.append(cv2.resize(image, (256, 256), interpolation=cv2.INTER_AREA))
    cv2.imwrite(str(args.out / 'birth-contours.png'), np.concatenate(strips, axis=1))
    if (SHADERS / 'surface-clock.comp').exists():
        check_surface(ctx, failures)
    report = dict(renderer=ctx.info['GL_RENDERER'], cases=len(rows), failures=failures, results=rows)
    (args.out / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps(dict(cases=len(rows), failure_count=len(failures), first_failures=failures[:3]), ensure_ascii=False))
    raise SystemExit(bool(failures))


if __name__ == '__main__':
    main()
