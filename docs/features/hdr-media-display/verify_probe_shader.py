"""离屏验证实际探针着色器的像素计算；不代表 Android / 卓易通 HDR 显示验证。"""
from pathlib import Path
import re
import numpy as np
import moderngl


ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "app/src/main/java/com/ywwynm/everythingdone/hdr/HdrProbeSurface.kt"
text = SOURCE.read_text(encoding="utf-8")


def shader(name):
    source = re.search(r'const val ' + name + r' = """(.*?)"""', text, re.S).group(1)
    # 桌面驱动验证相同数学与纹理采样，仅替换版本声明和 ES precision 语法。
    return source.replace("#version 300 es", "#version 330").replace("precision highp float;", "")


context = moderngl.create_standalone_context(require=330)
program = context.program(vertex_shader=shader("VERTEX"), fragment_shader=shader("FRAGMENT"))
vao = context.vertex_array(program, [])
program["imageTransform"].write(np.array([[2, 0, -1], [0, -2, 1], [0, 0, 1]], dtype="f4").T.copy().tobytes())
base = np.ones((2, 3, 4), dtype=np.float16)
base[0, :, :3] = 0.5  # 原图第一行；GL 读回第一行应对应原图最后一行。
gain = np.full((2, 3, 4), 255, dtype=np.uint8)
gain[:, 0, :3] = 0
gain[:, 1, :3] = 128
base_texture = context.texture((3, 2), 4, base.tobytes(), dtype="f2")
gain_texture = context.texture((3, 2), 4, gain.tobytes(), dtype="f1")
base_texture.filter = gain_texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
base_texture.use(0)
gain_texture.use(1)
program["baseTexture"] = 0
program["gainTexture"] = 1
program["logMin"] = (0.0,) * 3
program["logMax"] = (float(np.log(4)),) * 3
program["gainGamma"] = (1.0,) * 3
program["hdrStrength"] = 1.0
program["epsilonSdr"] = (0.0,) * 3
program["epsilonHdr"] = (0.0,) * 3
program["baseToGain"].write(np.eye(3, dtype="f4").tobytes())
program["colorTransform"].write(np.eye(3, dtype="f4").tobytes())
target = context.texture((3, 2), 4, dtype="f4")
frame = context.framebuffer([target])
frame.use()
context.viewport = (0, 0, 3, 2)
expected = base[::-1, :, :3].astype(np.float32)
gains = np.power(4.0, gain[::-1, :, :3].astype(np.float32) / 255)
checks = 0
for output_mode, weight in [(0, 1.0), (0, 0.5), (0, 0.0), (1, 1.0), (2, 1.0)]:
    program["outputMode"] = output_mode
    program["weight"] = weight
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
    actual = np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32).reshape(2, 3, 4)
    reference = expected if output_mode == 2 else expected * np.power(gains, weight)
    if output_mode == 1:
        value = np.power(np.clip(reference * 203 / 10000, 0, 1), 2610 / 16384)
        reference = np.power((3424 / 4096 + 2413 / 128 * value) / (1 + 2392 / 128 * value), 2523 / 32)
    elif output_mode == 2:
        reference = np.where(reference <= 0.0031308, 12.92 * reference,
                             1.055 * np.power(reference, 1 / 2.4) - 0.055)
    np.testing.assert_allclose(actual[:, :, :3], reference, rtol=0.0003, atol=0.00003)
    np.testing.assert_allclose(actual[:, :, 3], 1.0)
    checks += 1
# 用非平凡基色变换验证增益前后矩阵的顺序，并覆盖负值保留。
to_gain = np.array([[1.1, -0.2, 0.1], [-0.3, 1.2, 0.1], [0.1, -0.1, 1.0]], dtype="f4")
to_output = np.linalg.inv(to_gain).astype("f4")
program["baseToGain"].write(to_gain.T.copy().tobytes())
program["colorTransform"].write(to_output.T.copy().tobytes())
base[:, :, :3] = [0.1, 0.8, 0.2]
base_texture.write(base.tobytes())
gain[:, :, :3] = [255, 128, 0]
gain_texture.write(gain.tobytes())
gains = np.power(4.0, gain[::-1, :, :3].astype(np.float32) / 255)
program["outputMode"] = 0
program["weight"] = 1.0
vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
actual = np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32).reshape(2, 3, 4)
reference = ((base[::-1, :, :3].astype("f4") @ to_gain.T) * gains) @ to_output.T
np.testing.assert_allclose(actual[:, :, :3], reference, rtol=0.0003, atol=0.00003)
checks += 1
# APP5 实验分支：私有元数据不参与计算，真实灰度图使用显式参考余量。
# 单通道倍率应保持线性 RGB 的比例，选择 2/4/8 倍时最大增强严格受限。
program["logMax"] = (float(np.log(8)),) * 3
base[:, :, :3] = [0.2, 0.4, 0.6]
base[0, 0, :3] = [0.01, 0.02, 0.03]
base_texture.write(base.tobytes())
gray = np.array([[0, 64, 128], [141, 188, 255]], dtype="u1")
gain[:, :, :3] = gray[:, :, None]
gain_texture.write(gain.tobytes())
for headroom in (2.0, 4.0, 8.0):
    reference = base[::-1, :, :3].astype("f4") * np.power(headroom, gray[::-1, :, None] / 255)
    program["weight"] = float(np.log(headroom) / np.log(8))
    for mode in (0, 1, 2):
        program["outputMode"] = mode
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        actual = np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32).reshape(2, 3, 4)
        expected_app5 = reference
        if mode == 1:
            value = np.power(np.clip(reference * 203 / 10000, 0, 1), 2610 / 16384)
            expected_app5 = np.power((3424 / 4096 + 2413 / 128 * value) / (1 + 2392 / 128 * value), 2523 / 32)
        elif mode == 2:
            c = base[::-1, :, :3].astype("f4")
            expected_app5 = np.where(c <= 0.0031308, 12.92 * c, 1.055 * np.power(c, 1 / 2.4) - 0.055)
        np.testing.assert_allclose(actual[:, :, :3], expected_app5, rtol=0.0003, atol=0.00003)
        checks += 1
# SDR 端点必须忽略不同 epsilon 的偏移；逐帧增益在两个端点之间连续且单调。
program["outputMode"] = 0
program["weight"] = 1.0
program["logMin"] = (0.0,) * 3
program["logMax"] = (float(np.log(4)),) * 3
program["epsilonSdr"] = (0.01, 0.02, 0.03)
program["epsilonHdr"] = (0.02, 0.01, 0.015)
program["baseToGain"].write(np.eye(3, dtype="f4").tobytes())
program["colorTransform"].write(np.eye(3, dtype="f4").tobytes())
gain[:, :, :3] = 255
gain_texture.write(gain.tobytes())
last = None
for strength in (0.0, 0.1, 0.25, 0.5, 0.75, 1.0):
    program["hdrStrength"] = strength
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
    actual = np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32).reshape(2, 3, 4)[:, :, :3]
    reference = (base[::-1, :, :3].astype("f4") + np.array([0.01, 0.02, 0.03]) * strength) * 4**strength - np.array([0.02, 0.01, 0.015]) * strength
    np.testing.assert_allclose(actual, reference, rtol=0.0003, atol=0.00003)
    if last is not None:
        assert np.all(actual >= last)
    last = actual.copy()
    checks += 1

# 常见基图的 sRGB 纹理路径：实际硬件 EOTF 与旧 FP16 线性化结果对照，包括 P3 原色矩阵。
encoded = np.array([[[18, 100, 220, 255], [0, 255, 30, 255], [200, 50, 100, 255]],
                    [[250, 220, 120, 255], [5, 10, 40, 255], [255, 255, 255, 255]]], dtype="u1")
compact = context.texture((3, 2), 4, encoded.tobytes(), dtype="f1", internal_format=0x8C43)
compact.filter = (moderngl.NEAREST, moderngl.NEAREST)
program["epsilonSdr"] = program["epsilonHdr"] = (0.0,) * 3
p3_to_2020 = np.array([[0.7538351, 0.19860266, 0.047562808], [0.0457429, 0.9417801, 0.01247685],
                       [-0.0012105135, 0.01760469, 0.98360604]], dtype="f4")
value = encoded[:, :, :3].astype("f4") / 255
linear = np.where(value <= 0.04045, value / 12.92, ((value + 0.055) / 1.055)**2.4)
base[:, :, :3] = linear
base_texture.write(base.tobytes())
for transform in (np.eye(3, dtype="f4"), p3_to_2020):
    program["colorTransform"].write(transform.T.copy().tobytes())
    for strength in (0.0, 0.5, 1.0):
        program["hdrStrength"] = strength
        program["outputMode"] = 1
        base_texture.use(0)
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        fp16 = np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32).copy()
        compact.use(0)
        vao.render(moderngl.TRIANGLE_STRIP, vertices=4)
        srgb8 = np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32)
        # GPU 的标准 sRGB 解码允许小量实现误差，差异小于一个 10 位 PQ 编码级。
        np.testing.assert_allclose(srgb8, fp16, rtol=0, atol=1/1023)
        checks += 1
print(f"PASS: {checks} GPU cases; gain endpoints/transition, compact sRGB/P3, PQ, SDR, orientation, primary transforms, APP5")
print("APP5 checks validate the stated experimental formula, not the undocumented Huawei reconstruction curve.")
print("Renderer:", context.info["GL_RENDERER"])
context.release()
