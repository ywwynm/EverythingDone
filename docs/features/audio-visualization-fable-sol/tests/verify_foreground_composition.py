"""使用真实 present.frag 的离屏 GPU 回归；不代替卓易通窗口、输入与闪烁验收。

运行环境：everythingdone。可用 --baseline 指定改前 shader，验证关闭前景时逐位一致。
"""
import argparse
import json
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]


def desktop(source):
    return source.replace('#version 300 es', '#version 330').replace('precision highp float;', '')


def run(baseline=None):
    ctx = moderngl.create_standalone_context(require=330)
    shaders = ROOT / 'shared/fablesol/glsl'
    vertex = desktop((shaders / 'fullscreen.vert').read_text(encoding='utf8'))
    source = (shaders / 'present.frag').read_text(encoding='utf8')
    program = ctx.program(vertex_shader=vertex, fragment_shader=desktop(source))
    old = ctx.program(vertex_shader=vertex, fragment_shader=desktop(Path(baseline).read_text(encoding='utf8'))) if baseline else None
    w = h = 64
    rng = np.random.default_rng(170554)
    scene = rng.uniform(0, 4, (h, w, 4)).astype('f4')
    scene[:, :, 3] = 1
    ui = np.zeros((h, w, 4), dtype='f4')
    ui[4:12, 24:40] = [1, 1, 1, 1]  # 顶部白色控件
    ui[24:32, 24:40] = [.1, .3, .2, .5]  # 预乘彩色抗锯齿边缘
    ui[48:56, 24:40] = [1, 0, 0, 1]  # 底部红色按钮，区分 Y 翻转
    a = ctx.texture((w, h), 4, scene.tobytes(), dtype='f4')
    b = ctx.texture((w, h), 4, ui.tobytes(), dtype='f4')
    a.filter = b.filter = (moderngl.NEAREST, moderngl.NEAREST)
    target = ctx.texture((w, h), 4, dtype='f4')
    framebuffer = ctx.framebuffer([target])

    def render(p, foreground, linear=True, pq=False, snapshot=False, alpha=1, headroom=9.6):
        uniforms = dict(uScene=0, uForeground=1, uHasForeground=foreground,
                        uBackdropColor=(.2, .3, .4), uPresentationAlpha=alpha,
                        uViewportPx=(w, h), uCornerRadiusPx=12, uCornerRadiusBottomDeltaPx=0,
                        uSceneLinear=linear, uOutputPq=pq, uOutputSdr=snapshot,
                        uPqRgba8=pq, uHdrHeadroom=headroom)
        for name, value in uniforms.items():
            if name in p:
                p[name].value = value
        a.use(0); b.use(1); framebuffer.use()
        ctx.vertex_array(p, []).render(vertices=3)
        return np.frombuffer(framebuffer.read(components=4, dtype='f4'), dtype='f4').reshape(h, w, 4)

    checks = []
    if old:
        for linear, pq, snapshot in [(False, False, False), (True, False, False), (True, True, False), (True, False, True)]:
            for alpha in [.16, 1]:
                np.testing.assert_array_equal(render(program, False, linear, pq, snapshot, alpha),
                                              render(old, False, linear, pq, snapshot, alpha))
                checks.append(f'disabled_parity_{linear}_{pq}_{snapshot}_{alpha}')
    plain = render(program, False)
    mixed = render(program, True)
    flipped_ui = ui[::-1]
    transparent = (flipped_ui[:, :, 3] == 0)
    np.testing.assert_array_equal(mixed[transparent], plain[transparent])
    checks.append('transparent_ui_preserves_wave')
    np.testing.assert_array_equal(mixed[52:60, 24:40], np.ones((8, 16, 4), dtype='f4'))
    np.testing.assert_array_equal(mixed[8:16, 24:40], np.broadcast_to([1, 0, 0, 1], (8, 16, 4)))
    checks.append('top_down_white_and_red_position')
    straight = np.array([.2, .6, .4])
    linear_ui = np.where(straight <= .04045, straight / 12.92, ((straight + .055) / 1.055) ** 2.4)
    expected = plain[32:40, 24:40, :3] * .5 + linear_ui * .5
    np.testing.assert_allclose(mixed[32:40, 24:40, :3], expected, atol=2e-6)
    checks.append('unpremultiply_before_linear_blend')
    for alpha in [.16, 1]:
        for headroom in [1, 4, 9.6]:
            hdr = render(program, True, pq=True, alpha=alpha, headroom=headroom)
            white = hdr[52:60, 24:40, :3]
            # ST2084 的 203 nit 参考白，允许每像素半个 8-bit 量化级抖动。
            y = (203 / 10000) ** .1593017578125
            pq_white = ((.8359375 + 18.8515625 * y) / (1 + 18.6875 * y)) ** 78.84375
            np.testing.assert_allclose(white, pq_white, atol=.5 / 255 + 2e-5)
            np.testing.assert_allclose(white[:, :, 0], white[:, :, 1], atol=2e-5)
            np.testing.assert_allclose(white[:, :, 0], white[:, :, 2], atol=2e-5)
            np.testing.assert_array_equal(hdr[0, 0], np.zeros(4))
            checks.append(f'neutral_white_and_corner_{alpha}_{headroom}')
    # 运行时的粒子调用显式关闭前景纹理；同一场景快照必须和普通海浪一致。
    if old:
        np.testing.assert_array_equal(render(program, False, snapshot=True), render(old, False, snapshot=True))
        checks.append('particle_wave_only_parity')
    return dict(passed=True, count=len(checks), checks=checks, gpu=ctx.info['GL_RENDERER'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline')
    parser.add_argument('--output')
    args = parser.parse_args()
    result = run(args.baseline)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(text, encoding='utf8')
    print(text)
