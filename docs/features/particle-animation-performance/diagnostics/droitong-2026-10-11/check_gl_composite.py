"""在桌面 OpenGL 执行实际位图着色器，校验方向、位置和预乘透明叠加；不替代设备显示验收。"""

import json
import re
from pathlib import Path

import moderngl
import numpy as np


def main():
    root = Path(__file__).resolve().parents[5]
    assets = root / 'app/src/main/assets/particle-dismiss'

    def source(name):
        shader = (assets / name).read_text(encoding='utf-8').replace('#version 300 es', '#version 330')
        return re.sub(r'precision highp float;', '', shader)

    ctx = moderngl.create_standalone_context(require=330)
    program = ctx.program(vertex_shader=source('presentation-quad.vert'),
                          fragment_shader=source('presentation-quad.frag'))
    vao = ctx.vertex_array(program, [])
    width, height = 96, 80
    target = ctx.texture((width, height), 4, dtype='f1')
    fbo = ctx.framebuffer([target])
    fbo.use()
    ctx.viewport = (0, 0, width, height)
    program['viewportSize'].value = (width, height)
    program['sourceImage'].value = 0
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA

    def render(pixels, x, y, alpha=1.0):
        texture = ctx.texture((pixels.shape[1], pixels.shape[0]), 4, pixels.tobytes(), dtype='f1')
        texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
        texture.repeat_x = texture.repeat_y = False
        texture.use(0)
        program['rectangle'].value = (x, y, pixels.shape[1], pixels.shape[0])
        program['opacity'].value = alpha
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        texture.release()

    def read():
        return np.frombuffer(fbo.read(components=4, alignment=1), np.uint8).reshape(height, width, 4)[::-1]

    # 四角互异，防止上下翻转、横向镜像或像素原点偏移被纯色色块掩盖。
    pixels = np.array([[[255, 0, 0, 255], [0, 255, 0, 255]],
                       [[0, 0, 255, 255], [255, 255, 0, 255]]], dtype=np.uint8)
    fbo.clear(0, 0, 0, 0)
    render(pixels, 12, 9)
    result = read()
    expected = np.zeros_like(result)
    expected[9:11, 12:14] = pixels
    assert np.array_equal(result, expected), '四角方向或窗口坐标不一致'

    # 以预乘 RGB 上传半透明前景，期望只乘一次 alpha，影子不应被重复预乘。
    background = np.array([[[30, 60, 90, 255]]], dtype=np.uint8)
    front = np.array([[[64, 32, 16, 128]]], dtype=np.uint8)
    errors = []
    for opacity in [0.0, 0.25, 0.5, 1.0]:
        fbo.clear(0, 0, 0, 0)
        render(background, 20, 21)
        render(front, 20, 21, opacity)
        actual = read()[21, 20].astype(float)
        want = front[0, 0] * opacity + background[0, 0] * (1 - front[0, 0, 3] / 255.0 * opacity)
        error = float(np.abs(actual - np.rint(want)).max())
        assert error <= 1, (opacity, actual, want)
        errors.append(error)
    output = {'renderer': ctx.info['GL_RENDERER'], 'checks': 5,
              'orientation_and_position': 'passed', 'premultiplied_blending_max_error': max(errors),
              'meaning': '桌面实际 GLSL 绘制，未验证 Android GLUtils 上传及卓易通屏幕合成'}
    (Path(__file__).parent / 'gl-composite-check.json').write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(output, ensure_ascii=False))
    vao.release(); program.release(); fbo.release(); target.release(); ctx.release()


if __name__ == '__main__':
    main()
