"""实际 present shader 的离屏回归；不等同于卓易通合成器／面板验证。"""
import argparse
import json
import subprocess
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument("--baseline-ref", default="f98097520")
parser.add_argument("--output", type=Path)
args = parser.parse_args()
baseline = subprocess.check_output(
    ["git", "show", f"{args.baseline_ref}:shared/fablesol/glsl/present.frag"], cwd=ROOT
).decode("utf-8")
updated = (ROOT / "shared/fablesol/glsl/present.frag").read_text(encoding="utf-8")
vertex = (ROOT / "shared/fablesol/glsl/fullscreen.vert").read_text(encoding="utf-8")

def desktop(source):
    return source.replace("#version 300 es", "#version 330").replace("precision highp float;", "")

ctx = moderngl.create_standalone_context(require=330)
programs = [ctx.program(vertex_shader=desktop(vertex), fragment_shader=desktop(s))
            for s in (baseline, updated)]
vaos = [ctx.vertex_array(p, []) for p in programs]
width, height = 96, 80
y, x = np.mgrid[0:height, 0:width].astype(np.float64)
# 确定性的彩色／灰度与高光输入；同一份 FP16 纹理用于两版 shader。
scene = np.stack((x / width * 9.6, y / height * 3.6, (x + y) / (width + height)), axis=-1)
scene = scene.astype(np.float16)
texture = ctx.texture((width, height), 3, scene.tobytes(), dtype="f2")
texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
texture.use(0)
target = ctx.texture((width, height), 4, dtype="f2")
frame = ctx.framebuffer([target])
frame.use()
ctx.viewport = (0, 0, width, height)
backdrop = np.array([0.18, 0.31, 0.52])

def draw(index, linear, alpha, headroom, bottom_delta, pq=False, snapshot=False, rgba8_pq=False):
    p = programs[index]
    uniforms = {"uScene": 0, "uBackdropColor": tuple(backdrop), "uPresentationAlpha": alpha,
                "uViewportPx": (float(width), float(height)), "uCornerRadiusPx": 18.0,
                "uCornerRadiusBottomDeltaPx": bottom_delta, "uSceneLinear": linear,
                "uHdrHeadroom": headroom}
    for key, value in uniforms.items():
        p[key] = value
    if "uOutputPq" in p:
        p["uOutputPq"] = pq
    if "uOutputSdr" in p:
        p["uOutputSdr"] = snapshot
        p["uPqRgba8"] = rgba8_pq
    vaos[index].render(moderngl.TRIANGLES, vertices=3)
    return np.frombuffer(frame.read(components=4, dtype="f4"), dtype=np.float32).reshape(height, width, 4)

parity_cases = 0
for linear in (False, True):
    for alpha in (0.0, 0.12, 0.6, 1.0):
        for headroom in (1.0, 3.6, 9.6):
            for bottom_delta in (0.0, -18.0):
                before = draw(0, linear, alpha, headroom, bottom_delta)
                after = draw(1, linear, alpha, headroom, bottom_delta)
                np.testing.assert_array_equal(after, before)
                parity_cases += 1

matrix = np.array([[0.62740390, 0.32928304, 0.04331307],
                   [0.06909729, 0.91954040, 0.01136231],
                   [0.01639144, 0.08801331, 0.89559525]])
linear_backdrop = np.where(backdrop <= 0.04045, backdrop / 12.92, ((backdrop + 0.055) / 1.055) ** 2.4)

def reference(alpha, headroom, bottom_delta):
    color = linear_backdrop * (1 - alpha) + np.clip(scene.astype(np.float64), 0, headroom) * alpha
    wide = color @ matrix.T
    v = np.clip(wide * 203 / 10000, 0, 1) ** (2610 / 16384)
    pq = ((3424 / 4096 + 2413 / 128 * v) / (1 + 2392 / 128 * v)) ** (2523 / 32)
    radius = np.where(y + 0.5 > height / 2, 18.0, 18.0 + bottom_delta)
    qx = abs(x + 0.5 - width / 2) - (width / 2 - radius)
    qy = abs(y + 0.5 - height / 2) - (height / 2 - radius)
    distance = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - radius
    t = np.clip((distance + 0.75) / 1.5, 0, 1)
    coverage = 1 - t * t * (3 - 2 * t)
    return np.concatenate((pq * coverage[..., None], coverage[..., None]), axis=-1).astype(np.float16).astype(np.float32)

pq_cases = 0
maximum_error = 0.0
alpha_levels = 0
for alpha in (0.0, 0.12, 0.6, 1.0):
    for headroom in (1.0, 3.6, 9.6):
        for bottom_delta in (0.0, -18.0):
            actual = draw(1, True, alpha, headroom, bottom_delta, pq=True)
            expected = reference(alpha, headroom, bottom_delta)
            maximum_error = max(maximum_error, float(np.max(abs(actual - expected))))
            np.testing.assert_allclose(actual, expected, atol=0.0006, rtol=0.0006)
            # 浮点参考保持完整覆盖率；下方另外验证实际 RGBA8 PQ 输出。
            scrgb = draw(0, True, alpha, headroom, bottom_delta)
            np.testing.assert_array_equal(actual[..., 3], scrgb[..., 3])
            assert np.all(actual[actual[..., 3] == 0, :3] == 0)
            alpha_levels = max(alpha_levels, len(np.unique(actual[..., 3])))
            pq_cases += 1
assert alpha_levels > 8

# 三条物理输出：浮点 PQ 公式、实际 RGBA8 PQ 屏幕目标、实际 RGBA8 sRGB 快照。
# 不经过 Android 的 PixelCopy，也不把输出相等解释为卓易通系统合成已验证。
float_frame = frame
packed_target = ctx.texture((width, height), 4, dtype="f1")  # GL_RGBA8：RGB 与 alpha 都是 8 位
packed_frame = ctx.framebuffer([packed_target])
sdr_target = ctx.texture((width, height), 4, dtype="f1")
sdr_frame = ctx.framebuffer([sdr_target])
packed_cases = 0
capture_cases = 0
neutral_cases = 0
capture_max_error = 0.0
opaque_cases = 0
rgba8_alpha_levels = 0
for bottom_delta in (0.0, -18.0):
    # 全视口的圆角位置不能改变；alpha=0 时所有颜色必须精确为零。
    frame = float_frame
    frame.use()
    full_alpha = draw(1, True, 1.0, 9.6, bottom_delta, pq=True)
    quantized = draw(1, True, 1.0, 9.6, bottom_delta, pq=True, rgba8_pq=True)
    frame = packed_frame
    frame.use()
    packed = draw(1, True, 1.0, 9.6, bottom_delta, pq=True, rgba8_pq=True)
    np.testing.assert_allclose(packed[..., 3], np.floor(full_alpha[..., 3] * 255 + 0.5) / 255, atol=0.00001)
    np.testing.assert_allclose(packed[..., :3], quantized[..., :3], atol=1 / 255 + 0.0005)
    assert np.all(packed[packed[..., 3] == 0, :3] == 0)
    assert np.all(packed[..., :3] <= packed[..., 3, None] + 1 / 255)
    assert np.all(packed[full_alpha[..., 3] == 1, 3] == 1)
    packed_cases += 1
    rgba8_alpha_levels = max(rgba8_alpha_levels, len(np.unique(packed[..., 3])))
    for alpha in (0.0, .16, .6, 1.0):
        for headroom in (1.0, 3.6, 9.6):
            # 实际 RGBA8 缓冲与两个不同后景合成：内部颜色不能受后景影响。
            frame = packed_frame
            frame.use()
            opaque = draw(1, True, alpha, headroom, bottom_delta, pq=True, rgba8_pq=True)
            inside = full_alpha[..., 3] == 1
            outside = full_alpha[..., 3] == 0
            np.testing.assert_array_equal(opaque[inside, 3], 1)
            np.testing.assert_array_equal(opaque[outside], 0)
            checker = ((x.astype(int) // 8 + y.astype(int) // 8) % 2)[..., None]
            first = opaque[..., :3] + checker * (1 - opaque[..., 3, None])
            second = opaque[..., :3] + (1 - checker) * (1 - opaque[..., 3, None])
            np.testing.assert_array_equal(first[inside], second[inside])
            opaque_cases += 1
    assert rgba8_alpha_levels > 4

    for color in ([0, 0, 0], [.18, .18, .18], [1, 1, 1], [3.6, 3.6, 3.6],
                  [1, .04, .1], [.05, 1, .2], [.12, .08, 1]):
        texture.write(np.broadcast_to(np.array(color, dtype=np.float16), (height, width, 3)).tobytes())
        for alpha in (0.0, .16, 1.0):
            frame = sdr_frame
            frame.use()
            actual = draw(1, True, alpha, 9.6, bottom_delta, snapshot=True)
            linear = np.clip(linear_backdrop * (1 - alpha) + np.array(color, dtype=np.float16).astype(np.float64) * alpha, 0, 1)
            srgb = np.where(linear <= .0031308, linear * 12.92, 1.055 * linear ** (1 / 2.4) - .055)
            expected = srgb * full_alpha[..., 3, None]
            capture_max_error = max(capture_max_error, float(abs(actual[..., :3] - expected).max()))
            np.testing.assert_allclose(actual[..., :3], expected, atol=1.2 / 255)
            np.testing.assert_allclose(actual[..., 3], full_alpha[..., 3], atol=1 / 255)
            assert np.all(actual[actual[..., 3] == 0, :3] == 0)
            capture_cases += 1
        if color[0] == color[1] == color[2]:
            frame = packed_frame
            frame.use()
            neutral = draw(1, True, 1.0, 9.6, bottom_delta, pq=True, rgba8_pq=True)
            np.testing.assert_allclose(neutral[..., 0], neutral[..., 1], atol=1 / 255)
            np.testing.assert_allclose(neutral[..., 1], neutral[..., 2], atol=1 / 255)
            neutral_cases += 1

texture.write(scene.tobytes())
frame = float_frame
frame.use()
result = {"status": "passed", "baselineRef": args.baseline_ref,
          "renderer": ctx.info["GL_RENDERER"], "unchangedSdrScrgbCases": parity_cases,
          "pqCases": pq_cases, "maximumAbsoluteError": maximum_error,
          "roundedAlphaLevels": alpha_levels, "transparentPixelsRgbZero": True,
          "pqWindowFormat": "RGBA8", "packedPqCases": packed_cases,
          "opaqueBackdropIndependentCases": opaque_cases, "rgba8CornerAlphaLevels": rgba8_alpha_levels, "neutralPqCases": neutral_cases,
          "sdrSnapshotCases": capture_cases, "sdrSnapshotMaxError": capture_max_error,
          "packedAlphaHasNoRgbLeak": True, "deviceValidation": False}
if args.output:
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=True))
