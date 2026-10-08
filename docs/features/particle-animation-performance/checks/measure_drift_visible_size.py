"""复现 2026-10-08 修正前的软点尺寸问题；执行固定历史版本的 GLSL，不连接设备。

报告采用峰值覆盖率 50% 的横截面直径，另列 10% 边界；它们是可复核的
软边尺寸指标，不等同于任意背景下的人眼绝对可见边界。现行轮廓回归见
docs/features/harmony-particle-android-port/checks/check_drift_coverage.py。
"""
import argparse
import json
import re
import subprocess
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
SHADERS = ROOT / 'shared/particle-dismiss'
KOTLIN = ROOT / 'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss'
LEGACY_REVISION = '3b9b3d59018d72f74d94173a65e4055bfeb9ce44'


def legacy_sprite_alpha():
    """历史软点，只供旧版测量／回归对照；生产已改用解析轮廓。"""
    return np.array([
        0,0,1,5,7,4,1,0,0,0,3,14,29,36,29,14,3,0,2,14,46,83,100,83,46,14,1,
        4,30,82,149,183,149,82,29,5,6,37,100,184,255,184,100,36,6,
        4,29,83,149,184,148,83,30,4,1,14,46,83,100,82,45,14,0,
        0,3,15,30,37,29,15,2,0,0,0,1,5,6,3,0,0,0],np.uint8).reshape(9,9)


def source(name, revision=None):
    # 画布裁剪回归仍读取现行文件；本脚本主程序明确传入历史版本。
    def read(filename):
        if revision is None:
            return (SHADERS/filename).read_text('utf-8')
        return subprocess.run(['git','show',f'{revision}:shared/particle-dismiss/{filename}'],
                              cwd=ROOT,capture_output=True,check=True).stdout.decode('utf-8')
    text = re.sub(r'#include "([^"/]+)"', lambda m: read(m[1]), read(name))
    return text.replace('#version 310 es', '#version 430')


def uniforms(program, values):
    for key, value in values.items():
        if key in program:
            program[key].value = value


def profile_width(alpha, fraction, units_per_pixel):
    # 取覆盖率质心所在行；流体圆点中部是平台，最大值的第一行不一定经过圆心。
    row_weights = alpha.sum(axis=1)
    total = float(row_weights.sum())
    if total <= 0:
        return 0.
    center_y = int(round(float(np.dot(np.arange(len(row_weights)), row_weights) / total)))
    row = alpha[min(max(center_y, 0), alpha.shape[0]-1)]
    threshold = row.max() * fraction
    selected = np.flatnonzero(row >= threshold)
    if not selected.size:
        return 0.
    lo, hi = selected[0], selected[-1]
    left = lo-1 + (threshold-row[lo-1]) / (row[lo]-row[lo-1]) if lo > 0 else lo-.5
    right = hi + (row[hi]-threshold) / (row[hi]-row[hi+1]) if hi < len(row)-1 else hi+.5
    return float((right-left) * units_per_pixel)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    shader = lambda name: source(name, LEGACY_REVISION)
    # 只测修正前的 9×9 软点；现行片元不再读取此纹理。
    alpha = legacy_sprite_alpha()
    rgba = np.full((9, 9, 4), 255, np.uint8)
    rgba[:, :, 3] = alpha[::-1]
    sprite = ctx.texture((9, 9), 4, rgba.tobytes())
    sprite.filter = moderngl.LINEAR, moderngl.LINEAR
    sprite.repeat_x = sprite.repeat_y = False
    sprite.use(0)
    program = ctx.program(vertex_shader=shader('drift-particle.vert'),
                          fragment_shader=shader('drift-particle.frag'))
    particle = ctx.buffer(reserve=32)
    vao = ctx.vertex_array(program, [(particle, '4f 4f /i', 'a_geometry', 'a_color')])
    target = ctx.texture((1024, 1024), 4, dtype='f4')
    fbo = ctx.framebuffer([target])
    density = 4.
    setting_dp = 2.4
    half_extent = setting_dp * density / 4.
    work_units = 16.
    center = 8. + work_units / 1024 * .5
    uniforms(program, {'u_work': (work_units, work_units), 'u_sprite': 0})
    fbo.use()
    measurements = []
    for label, factor in [('neutral', 1.), ('panel_body', .74), ('emphasized_content', 1.2)]:
        particle.write(np.array([center, center, half_extent*factor, 1., 1., 1., 1., 1.], np.float32).tobytes())
        fbo.clear()
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
        pixels = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(1024, 1024, 4)
        # 工作面单位放大为 2 个设备像素，再除以设备密度得到 dp。
        dp_per_pixel = work_units / 1024 * 2 / density
        measurements.append(dict(case=label, geometry_dp=setting_dp*factor,
                                 width_50_dp=profile_width(pixels[:, :, 3], .5, dp_per_pixel),
                                 width_10_dp=profile_width(pixels[:, :, 3], .1, dp_per_pixel)))

    # 单变量对照：只把点贴图的 alpha 改为全实心；几何与正式绘制着色器不变。
    rgba[:, :, 3] = 255
    sprite.write(rgba.tobytes())
    particle.write(np.array([center, center, half_extent, 1., 1., 1., 1., 1.], np.float32).tobytes())
    fbo.clear()
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
    pixels = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(1024, 1024, 4)
    opaque_width = profile_width(pixels[:, :, 3], .5, dp_per_pixel)
    rgba[:, :, 3] = alpha[::-1]
    sprite.write(rgba.tobytes())

    # 只改变设备密度；同一 dp 在超采样测量下应保持同样的可见直径。
    density_checks = []
    for test_density in (1., 1.5, 2., 3., 4., 4.5):
        h = setting_dp * test_density / 4.
        particle.write(np.array([center, center, h, 1., 1., 1., 1., 1.], np.float32).tobytes())
        fbo.clear()
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
        pixels = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(1024, 1024, 4)
        width = profile_width(pixels[:, :, 3], .5, work_units/1024*2/test_density)
        density_checks.append(dict(density=test_density, width_50_dp=width))

    # 正式半分辨率工作面与合成着色器：穷举一格内的亚像素位置，量化实际小点的取整波动。
    sprite.use(0)
    composite = ctx.program(vertex_shader=shader('drift-quad.vert'), fragment_shader=shader('drift-composite.frag'))
    quad = ctx.vertex_array(composite, [])
    work = ctx.texture((16, 16), 4, dtype='f4')
    work_fbo = ctx.framebuffer([work])
    screen = ctx.texture((32, 32), 4, dtype='f4')
    screen_fbo = ctx.framebuffer([screen])
    uniforms(composite, dict(u_rect=(0., 0., 32., 32.), u_viewport=(0., 0., 32., 32.), u_work=1, u_workSize=(16, 16)))
    device_widths = []
    for dx in (0., .25, .5, .75):
        for dy in (0., .25, .5, .75):
            particle.write(np.array([8.+dx, 8.+dy, half_extent, 1., 1., 1., 1., 1.], np.float32).tobytes())
            work_fbo.use(); work_fbo.clear()
            vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
            screen_fbo.use(); screen_fbo.clear(); work.use(1)
            quad.render(moderngl.TRIANGLE_STRIP, vertices=4)
            pixels = np.frombuffer(screen_fbo.read(components=4, dtype='f4'), np.float32).reshape(32, 32, 4)[:, :, 3]
            row = pixels[pixels.max(axis=1).argmax()]
            hit = np.flatnonzero(row >= row.max()*.5)
            device_widths.append(float((hit[-1]-hit[0]+1)/density))

    # 生产计算着色器：让所有粒子同时出生，测稳定阶段及早晚时刻的实际四边形尺寸分布。
    compute = ctx.compute_shader(shader('drift-update.comp'))
    count = 4096
    output = ctx.buffer(reserve=count*32)
    output.bind_to_storage_buffer(0)
    cells = ctx.buffer(np.array([1.], np.float32).tobytes())
    cells.bind_to_storage_buffer(1)
    snapshot = ctx.texture((32, 32), 4, np.full((32, 32, 4), 255, np.uint8).tobytes())
    snapshot.use(0)
    uniforms(compute, dict(u_image=0, u_count=count, u_baseCount=count, u_grid=(1, 1),
        u_emitPos=(0., 0.), u_emitSize=(16., 16.), u_half=(16, 16), u_full=(32, 32),
        u_hz=120., u_wind=(0., -20000.), u_add=0, u_alphaMul=1.15,
        u_fadeSizePower=.5, u_fadeAlphaPower=.5, u_sizeScale=setting_dp*density/9.8,
        u_touchGap=-1., u_turbOffset=(0., 0.), u_turbScale=(1., 1.), u_turbShift=(0., 0.),
        u_panel=(1., 1., 1.), u_total=2., u_latticeW=1, u_latticeH=1, u_scaleU=.002))
    compute['u_lattice'].write(np.zeros(64, np.float32).tobytes())
    distributions = []
    for panel_weight in (0., 1.):
        for time in (.10, .30, .55):
            uniforms(compute, dict(u_panelWeight=panel_weight, u_time=time))
            compute.run((count+255)//256)
            ctx.memory_barrier()
            data = np.frombuffer(output.read(), np.float32).reshape(count, 8)
            diameters = data[data[:, 2] > 0, 2] * 4 / density
            distributions.append(dict(panel_weight=panel_weight, time=time, alive=len(diameters),
                geometry_dp_percentiles=np.percentile(diameters, [0, 50, 100]).tolist()))
    result = dict(setting_dp=setting_dp, density=density, shader_revision=LEGACY_REVISION,
                  method='historical production GLSL; supersampled isolated dot; relative peak coverage thresholds',
                  measurements=measurements, compute_distributions=distributions,
                  opaque_texture_width_dp=opaque_width, density_checks=density_checks,
                  actual_half_resolution_width_50_dp_range=[min(device_widths), max(device_widths)],
                  reproduces_smaller_visible_core=measurements[0]['width_50_dp'] < setting_dp*.5)
    (args.out/'result.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
    assert result['reproduces_smaller_visible_core'], 'Current diagnosis did not reproduce'
    assert abs(opaque_width-setting_dp) < .02, 'Unexpected geometry conversion error'
    assert max(v['width_50_dp'] for v in density_checks)-min(v['width_50_dp'] for v in density_checks) < .04


if __name__ == '__main__':
    main()
