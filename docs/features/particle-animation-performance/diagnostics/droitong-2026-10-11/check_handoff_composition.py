"""用实际位图着色器校验交接各阶段的透明合成；不模拟 Android 同步或卓易通屏幕。"""

import json
import re
from pathlib import Path

import moderngl
import numpy as np


def main():
    assets = Path(__file__).resolve().parents[5] / 'app/src/main/assets/particle-dismiss'

    def source(name):
        shader = (assets / name).read_text(encoding='utf-8').replace('#version 300 es', '#version 330')
        return re.sub(r'precision highp float;', '', shader)

    ctx = moderngl.create_standalone_context(require=330)
    program = ctx.program(vertex_shader=source('presentation-quad.vert'),
                          fragment_shader=source('presentation-quad.frag'))
    vao = ctx.vertex_array(program, [])
    target = ctx.texture((4, 2), 4, dtype='f1')
    fbo = ctx.framebuffer([target])
    fbo.use()
    ctx.viewport = (0, 0, 4, 2)
    program['viewportSize'].value = (4, 2)
    program['rectangle'].value = (0, 0, 4, 2)
    program['sourceImage'].value = 0
    ctx.enable(moderngl.BLEND)
    ctx.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA

    def compose(layers):
        fbo.clear(1, 1, 1, 1)
        for pixels, alpha in layers:
            texture = ctx.texture((4, 2), 4, pixels.tobytes(), dtype='f1')
            texture.filter = (moderngl.LINEAR, moderngl.LINEAR)
            texture.repeat_x = texture.repeat_y = False
            texture.use(0)
            program['opacity'].value = alpha
            vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
            texture.release()
        return np.frombuffer(fbo.read(components=4, alignment=1), np.uint8).reshape(2, 4, 4)[::-1]

    # 半透明阴影、副标题、抗锯齿边缘及不透明区域，同一层只应贡献一次。
    foreground = np.array([[[0, 0, 0, 51], [0, 0, 0, 138], [24, 12, 0, 48], [90, 120, 150, 255]]]*2,
                          dtype=np.uint8)
    single = compose([(foreground, 1)])
    overlap = compose([(foreground, 1), (foreground, 1)])
    darkening = (single[0, :2, 0].astype(int) - overlap[0, :2, 0].astype(int)).tolist()
    assert min(darkening) > 0, '旧交接的两份半透明内容应能复现变深'
    errors = {}
    for stage, native, gl in [('隐藏 Surface 准备缓冲', 1, 0), ('GL 显示且原 View 隐藏', 0, 1),
                              ('原 View 恢复且 Surface 隐藏', 1, 0), ('清空后恢复空 Surface', 1, 0)]:
        actual = compose([(foreground, native), (foreground, gl)])
        errors[stage] = int(np.abs(actual.astype(int) - single.astype(int)).max())
        assert errors[stage] == 0, stage

    colors = np.array([[[255, 170, 60, 255]]*2 + [[35, 106, 210, 255]]*2]*2, dtype=np.uint8)
    gray = np.full((2, 4, 4), [55, 55, 55, 255], dtype=np.uint8)
    assert not np.array_equal(compose([(gray, 1)]), colors), '未覆盖的灰缓冲必须能被像素对照发现'
    for stage, layers in [('恢复期间局部覆盖', [(gray, 1), (colors, 1)]),
                          ('有效色块提交后撤盖', [(colors, 1)])]:
        errors[stage] = int(np.abs(compose(layers).astype(int) - colors.astype(int)).max())
        assert errors[stage] == 0, stage

    output = {'renderer': ctx.info['GL_RENDERER'], 'checks': 8,
              'old_overlap_rgb_darkening': darkening, 'stage_max_pixel_errors': errors,
              'meaning': '执行实际 GLSL，验证给定可见性与覆盖状态的像素；未验证 Android 同步事务或卓易通屏幕'}
    (Path(__file__).parent / 'handoff-composition-check.json').write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(output, ensure_ascii=False))
    vao.release(); program.release(); fbo.release(); target.release(); ctx.release()


if __name__ == '__main__':
    main()
