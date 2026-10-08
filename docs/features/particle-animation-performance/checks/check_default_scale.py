"""同输入比较飘散旧基准、只放大和面积补偿；正式片元核对两种样式的 1.6 dp。

读取已保存的三星素材，只运行离屏 GLSL，不连接设备、不改偏好。面积补偿是候选，
须结合生成的连续画面决定是否采用，不能将覆盖面积相同当成画面相同。
"""
import argparse
import json
import sys
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw

from check_round_particles import ROOT, source as flow_source, uniforms, attributes
from measure_drift_visible_size import profile_width

sys.path.insert(0, str(ROOT / 'docs/features/harmony-particle-android-port/checks'))
from check_drift_clarity import Renderer, source as drift_source, montage


def contours(ctx):
    """隔离生命周期和随机尺寸；测量正式片元在半峰值处的直径。"""
    resolution = 1024
    frame = 16.
    target = ctx.texture((resolution, resolution), 4, dtype='f4')
    fbo = ctx.framebuffer([target])
    data = ctx.buffer(reserve=64)
    dp = ctx.program(vertex_shader=drift_source('drift-particle.vert'),
                     fragment_shader=drift_source('drift-particle.frag'))
    dv = ctx.vertex_array(dp, [(data, '4f 4f /i', 'a_geometry', 'a_color')])
    flags = '#define DRAW_RECORDS 1\n#define FLAKE_CONSTANTS 1\n#define ROUND_PARTICLES 1\n'
    fp = ctx.program(vertex_shader=flow_source('material.vert', flags),
                     fragment_shader=flow_source('material.frag', flags))
    source_data = ctx.buffer(np.zeros(12, np.float32).tobytes())
    specs = [(data, f'{i*16}x 4f {64-(i+1)*16}x /i' if i else '4f 48x /i', name)
             for i, name in enumerate(('record_xform', 'record_place', 'record_shade', 'record_mask'))]
    specs = [(b, fmt.replace(' 0x', ''), name) for b, fmt, name in specs]
    specs.append((source_data, '4f 32x /i', 'material_src'))
    fv = ctx.vertex_array(fp, attributes(fp, specs))
    tex = ctx.texture((2, 2), 4, np.ones((2, 2, 4), np.float32).tobytes(), dtype='f4')
    tex.use(0)
    rows = []
    for style in ('drift', 'flow'):
        for multiplier in (.5, 1., 3., 6.):
            wanted = 1.6 * multiplier
            if style == 'drift':
                # density=2 时一个工作像素对应 1 dp；圆边半峰直径等于 halfExtent。
                data.write(np.array([frame/2, frame/2, wanted, 1, 1, 1, 1, 1], np.float32).tobytes())
                uniforms(dp, dict(u_work=(frame, frame), u_workOffset=(0., 0.), u_tightQuad=1))
                vao = dv
            else:
                geometry = wanted / .9
                record = np.zeros(16, np.float32)
                record[:4] = [geometry, 0, 0, geometry]
                record[4:8] = [frame/2-geometry/2, frame/2-geometry/2, 0, 0]
                record[8:12] = [1, 0, 1, 0]
                record[12:16] = [0, 1, 0, 0]
                data.write(record.tobytes())
                uniforms(fp, dict(frame=(frame, frame), card=(geometry, geometry), cell=(geometry, geometry),
                    offset=(0., 0.), material_pass=1, diagnostic=0, foreground=0, light_gain=0., panel_weight=0.))
                vao = fv
            ctx.disable(moderngl.BLEND)
            fbo.use(); ctx.viewport = (0, 0, resolution, resolution); fbo.clear()
            vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=1)
            alpha = np.frombuffer(fbo.read(components=4, dtype='f4'), np.float32).reshape(resolution, resolution, 4)[:, :, 3]
            measured = profile_width(alpha, .5, frame/resolution)
            rows.append(dict(style=style, multiplier=multiplier, expectedDp=wanted, measuredDp=measured))
            assert abs(measured-wanted) < .025, rows[-1]
    for obj in (dv, fv, dp, fp, data, source_data, tex, fbo, target):
        obj.release()
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    widths = contours(ctx)
    base = ROOT / 'tmp/harmony-particle-android-port/parity/clarity-20261008-after'
    variants = [('old-1.4', 1., 1.), ('size-1.6', 1.6/1.4, 1.),
                ('size-1.6-area-count', 1.6/1.4, (1.4/1.6)**2)]
    results = []
    for scene in ('dialog-light', 'dialog-dark', 'ironman-ref'):
        folder = args.out / scene
        folder.mkdir(exist_ok=True)
        frames = {}
        for label, size, amount in variants:
            r = Renderer(ctx, base / scene)
            r.result['count'] = round(r.result['count'] * amount)
            replicas = round(r.result['content']['replicas'] * amount)
            r.result['total'] = r.result['count'] + replicas
            r.values.update(u_count=r.result['total'], u_baseCount=r.result['count'])
            captured = []
            for t in np.round(np.arange(.25, 1.251, .05), 2):
                frame = r.render(float(t), size=size)
                captured.append(frame)
                if t in (.45, .6, .75, .9, 1.05, 1.2):
                    Image.fromarray(frame).save(folder/f'{label}-{t:.2f}.png')
                if t >= r.result['bodyGoneSeconds']:
                    difference = np.max(np.abs(frame.astype(float)-np.array(r.spec['background'])), axis=2)
                    results.append(dict(scene=scene, variant=label, time=float(t), count=r.result['total'],
                                        contrastSum=float(difference.sum()), coveredPixels=int((difference>12).sum())))
            frames[label] = captured
            r.close()
        for t in (.45, .6, .75, .9, 1.05, 1.2):
            index = round((t-.25)/.05)
            montage([(label, frames[label][index]) for label, _, _ in variants], folder/f'{t:.2f}-sheet.png', width=420)
        preview = []
        for index in range(len(frames[variants[0][0]])):
            tiles = []
            for label, _, _ in variants:
                image = Image.fromarray(frames[label][index])
                image.thumbnail((360, 600), Image.Resampling.LANCZOS)
                tile = Image.new('RGB', (360, image.height+24), '#ddd')
                tile.paste(image, (0, 24)); ImageDraw.Draw(tile).text((5, 5), label, fill='black')
                tiles.append(tile)
            sheet = Image.new('RGB', (1080, max(t.height for t in tiles)), '#ddd')
            for i, tile in enumerate(tiles): sheet.paste(tile, (i*360, 0))
            preview.append(sheet)
        preview[0].save(folder/'compare.gif', save_all=True, append_images=preview[1:], duration=100, loop=0)
        print(scene, flush=True)
    (args.out/'result.json').write_text(json.dumps(dict(contours=widths, visualMetrics=results), indent=2), 'utf-8')
    print(json.dumps(widths), flush=True)


if __name__ == '__main__':
    main()
