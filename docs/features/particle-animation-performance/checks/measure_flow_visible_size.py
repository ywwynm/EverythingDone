"""用流体正式着色器核对设置尺寸、可见圆边和生命周期尺寸分布。

只做离屏诊断，不修改应用参数，不连接设备。统计使用同一位置、相同寿命的
受控粒子；默认档网格示例另按正式模型公式计算，不能视为用户设备取样。
"""
import argparse
import json
import math
import re
from pathlib import Path

import moderngl
import numpy as np

from check_round_particles import ROOT, source, uniforms, attributes
from measure_drift_visible_size import profile_width

DP_UNITS = 1.875
RESOLUTION = 512
FRAME = 16 * DP_UNITS


def random_values(count, seed=129):
    # ParticleMicroflakeModel.randomValue 的整数算法；高 24 位转成 [0,1)。
    values = np.arange(count*4, dtype=np.uint32) + np.uint32(seed)
    values = (values ^ (values >> 16)) * np.uint32(0x7feb352d)
    values = (values ^ (values >> 15)) * np.uint32(0x846ca68b)
    values ^= values >> 16
    return ((values >> 8).astype(np.float32) / 16777216.).reshape(count, 4)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    defines = '#define PARTICLE_AMOUNT 1\n#define ROUND_PARTICLES 1\n#define FLAKE_CONSTANTS 1\n'
    compute = ctx.compute_shader(source('draw-record.comp', defines))
    program = ctx.program(vertex_shader=source('material.vert', defines+'#define DRAW_RECORDS 1\n'),
                          fragment_shader=source('material.frag', defines+'#define DRAW_RECORDS 1\n'))
    foreground = ctx.texture((2, 2), 4, np.ones((2, 2, 4), np.float32).tobytes(), dtype='f4')
    foreground.use(0)
    target = ctx.texture((RESOLUTION, RESOLUTION), 4, dtype='f4')
    fbo = ctx.framebuffer([target])
    fbo.use()
    count = 4096
    material = np.zeros((count, 12), np.float32)
    material[:, 2] = .3
    material[:, 4] = 1.
    material[:, 6] = 1.
    material[:, 8:] = random_values(count)
    state = np.zeros((count, 8), np.float32)
    mb, sb = ctx.buffer(material.tobytes()), ctx.buffer(state.tobytes())
    pb = ctx.buffer(reserve=count*4)
    rb = ctx.buffer(reserve=count*64)
    mb.bind_to_storage_buffer(0); sb.bind_to_storage_buffer(1)
    pb.bind_to_storage_buffer(2); rb.bind_to_storage_buffer(6)
    specs = [(rb, f'{i*16}x 4f {64-(i+1)*16}x /i' if i else '4f 48x /i', name)
             for i, name in enumerate(('record_xform', 'record_place', 'record_shade', 'record_mask'))]
    specs = [(b, fmt.replace(' 0x', ''), name) for b, fmt, name in specs]
    specs.append((mb, '4f 32x /i', 'material_src'))
    vao = ctx.vertex_array(program, attributes(program, specs))
    shared = dict(frame=(FRAME, FRAME), material_pass=1, diagnostic=0, foreground=0,
                  light_gain=0., wind=(1., 0.), extrapolate=0., release_spread=.1, count=count)
    uniforms(program, shared); uniforms(compute, shared)

    def draw(record, values):
        rb.write(np.asarray(record, np.float32).tobytes(), offset=0)
        uniforms(program, values)
        fbo.use(); fbo.clear()
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
        alpha = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(RESOLUTION, RESOLUTION, 4)[:, :, 3]
        return dict(width_50_dp=profile_width(alpha, .5, FRAME/RESOLUTION/DP_UNITS),
                    width_10_dp=profile_width(alpha, .1, FRAME/RESOLUTION/DP_UNITS),
                    peak_alpha=float(alpha.max()))

    # 第一组只隔离圆边：真实顶点、片元程序，构造不缩放的单粒子绘制记录。
    setting_file = ROOT/'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleTuning.kt'
    # 复核曾发布的绝对值口径；倍率改版后该表保留在旧设置迁移中。
    levels_text = re.search(r'LEGACY_DP_LEVELS = floatArrayOf\((.*?)\)', setting_file.read_text('utf-8'), re.S).group(1)
    levels = [float(v) for v in re.findall(r'([\d.]+)f', levels_text)]
    contours = []
    for dp in levels:
        size = dp*DP_UNITS
        center = FRAME/2 + FRAME/RESOLUTION*.5
        record = np.zeros(16, np.float32)
        record[:4] = [size, 0, 0, size]
        record[4:8] = [center-size/2, center-size/2, 0, 0]
        record[8:12] = [1, 0, 1, 0]
        record[12:16] = [0, 1, 0, 0]  # surface=0, alive=1, replica=0
        measured = draw(record, dict(offset=(0., 0.), card=(size, size), cell=(size, size), panel_weight=0.))
        contours.append(dict(setting_dp=dp, **measured))

    # 第二组执行完整绘制记录计算：内容/年龄/数量分别受控，只统计普通粒子。
    distributions, samples = [], []
    dp = 2.4
    for name, panel, body, pigment in [('neutral', 0., 0., 0.), ('panel_body', 1., 1., 0.),
                                      ('emphasized_content', 1., 1., 1.)]:
        for amount in (.1, 1., 1.6):
            spacing = dp*DP_UNITS/math.sqrt(amount)
            nx, ny = math.ceil(300*DP_UNITS/spacing), math.ceil(200*DP_UNITS/spacing)
            cx, cy = 300*DP_UNITS/nx, 200*DP_UNITS/ny
            column, row = nx//2, ny//2
            center = np.array([(column+.5)*cx, (row+.5)*cy], np.float32)
            material[:, :2] = center
            material[:, 3] = row*nx+column
            state[:, :2] = center
            mb.write(material.tobytes()); sb.write(state.tobytes())
            pb.write(np.full(count, pigment, np.float32).tobytes())
            values = dict(card=(300*DP_UNITS, 200*DP_UNITS), cell=(cx, cy),
                          particle_scale=(dp*DP_UNITS/cx, dp*DP_UNITS/cy), span=200*DP_UNITS,
                          roll_gain=.85, body_weight=body, panel_weight=panel, nx=nx, grid_count=nx*ny,
                          offset=(FRAME/2-float(center[0]), FRAME/2-float(center[1])))
            for age in (.012, .035, .075, .15, .25):
                values['time'] = .3+age
                uniforms(compute, values)
                compute.run((count+255)//256); ctx.memory_barrier()
                records = np.frombuffer(rb.read(), np.float32).reshape(count, 16).copy()
                xform = records[:, :4]
                # 圆边的 50% 等值线直径为 0.9；行列式给轻微椭圆的等面积直径。
                geometry = np.sqrt(np.abs(xform[:, 0]*xform[:, 3]-xform[:, 1]*xform[:, 2]))/DP_UNITS
                visible = geometry*.9
                distributions.append(dict(case=name, amount=amount, age=age,
                    visible_50_dp_percentiles=np.percentile(visible, [10, 50, 90]).tolist()))
                if amount == 1. and age in (.012, .035, .075):
                    index = int(np.argsort(visible)[count//2])
                    # 将中位数样本移到第一条，片元测量检查解析的软边比例。
                    mb.write(material[index].tobytes(), offset=0)
                    measured = draw(records[index], values)
                    samples.append(dict(case=name, age=age, area_equivalent_50_dp=float(visible[index]), **measured))
                    mb.write(material.tobytes())

    # 默认未选档的几何基准，按 ParticleMicroflakeModel.build 的数量预算公式计算。
    rules = {}
    for line in (ROOT/'shared/particle-dismiss/rules.properties').read_text('utf-8').splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            rules[key] = float(value)
    defaults = []
    for width_dp, height_dp in [(300., 200.), (300., 600.), (384., 768.)]:
        width, height = width_dp*DP_UNITS, height_dp*DP_UNITS
        # 纯色面板 budget=1，materialAreaScale=1；内容补充粒子会另占预算。
        cell = max(rules['cell'], math.sqrt(width*height/(rules['max_cells']-1000)))
        nx, ny = math.ceil(width/cell), math.ceil(height/cell)
        defaults.append(dict(panel_dp=[width_dp, height_dp], displayed_default_dp=.84,
                             actual_geometry_dp=math.sqrt(width/nx*height/ny)/DP_UNITS))
    result = dict(method='production GLSL; relative peak coverage; isolated controlled particles',
                  contours=contours, distributions=distributions, rendered_samples=samples,
                  default_geometry_examples=defaults)
    (args.out/'result.json').write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    summary = dict(contour_cases=len(contours), distribution_cases=len(distributions),
                   neutral_contour_2_4_dp=next(v for v in contours if v['setting_dp']==2.4),
                   rendered_samples=samples, default_geometry_examples=defaults)
    print(json.dumps(summary, indent=2))
    assert all(abs(row['width_50_dp']-.9*row['setting_dp']) < .025 for row in contours)
    for name in ('neutral', 'panel_body', 'emphasized_content'):
        for age in (.012, .035, .075, .15, .25):
            medians = [v['visible_50_dp_percentiles'][1] for v in distributions if v['case']==name and v['age']==age]
            assert max(medians)/min(medians) < 1.015, 'Amount unexpectedly changes particle diameter'


if __name__ == '__main__':
    main()
