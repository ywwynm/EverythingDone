# -*- coding: utf-8 -*-
"""按参考的释放时刻场，反解我们着色器里几个常量该取多少。

逐帧重渲一轮要 306 帧×2，扫参数完全跑不动。释放场不必从画面里检测——
`CurtainRenderer.release_probe()` 直接把着色器算出的释放时刻画成一张浮点图，
一次 draw call 就够。于是可以真的做网格搜索。

拟合基：线性扫描 + 椭圆中心度 + 常数。三项的系数分别对应我们的
「斜坡」「CURTAIN_CENTRE_LIFT」「起步偏置」，残差的**低通部分**对应
CURTAIN_SWAY_*，高通部分对应 CURTAIN_DITHER。逐项对齐，不用整体凑一个总分。

参考那一侧仍然来自画面检测（没有别的来源），检测器的运行长度会让它整体偏晚
约 4 帧，比较幅值时这点偏置不影响。
"""
import math
import re
import sys
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent
KOTLIN = (HERE.parent.parent / "app" / "src" / "main" / "java" / "com" / "ywwynm"
          / "everythingdone" / "views" / "particledismiss"
          / "ParticleDismissRenderer.kt")
# 有桌面草稿时，调参写的也是草稿，不碰 Android 源码
if (HERE / "shader-draft" / "ParticleDismissRenderer.kt").exists():
    KOTLIN = HERE / "shader-draft" / "ParticleDismissRenderer.kt"
sys.path.insert(0, str(HERE))


def patch(constants):
    """把一组常量写回 Kotlin，返回旧值以便还原。"""
    text = KOTLIN.read_text(encoding="utf-8")
    old = {}
    for name, value in constants.items():
        pat = re.compile(r"(const float %s = )([0-9.]+)(;)" % name)
        m = pat.search(text)
        if not m:
            raise SystemExit("找不到常量 %s" % name)
        old[name] = float(m.group(2))
        text = pat.sub(lambda mm: "%s%s%s" % (mm.group(1), value, mm.group(3)), text, 1)
    KOTLIN.write_text(text, encoding="utf-8", newline="\n")
    return old


def basis(h, w):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    s = max(w, h) / 2.0
    X = (xx - w / 2) / s
    Y = (yy - h / 2) / s
    return X, Y, 1.0 - np.clip(np.hypot(X, Y), 0, 1)


def decompose(field, label, k=21):
    """拆成：线性扫描、中心推后、相干起伏、逐格抖动。"""
    h, w = field.shape
    X, Y, cent = basis(h, w)
    m = np.isnan(field)
    f = np.nan_to_num(field, nan=0.0).astype(np.float32)
    den = cv2.blur((~m).astype(np.float32), (k, k))
    sm = np.where(den > 0.15, cv2.blur(f, (k, k)) / np.maximum(den, 1e-6), np.nan)
    sm = np.where(m, np.nan, sm)
    ok = ~np.isnan(sm)
    A = np.stack([X[ok], Y[ok], cent[ok], np.ones(ok.sum())], 1)
    c, _, _, _ = np.linalg.lstsq(A, sm[ok], rcond=None)
    coh = sm[ok] - (c[0] * X + c[1] * Y + c[2] * cent + c[3])[ok]
    fine = (field - sm)
    fine = fine[~np.isnan(fine)]
    out = {
        "sweep": math.hypot(c[0], c[1]),
        "angle": math.degrees(math.atan2(-c[1], -c[0])) % 360,
        "centre": float(c[2]),
        "offset": float(c[3]),
        "coherent": float(coh.std()),
        "dither": float(fine.std()),
    }
    if label:
        print("  %-10s 扫描%.3f@%.1f° 中心%+.3f 截距%+.3f 相干起伏%.4f 逐格抖动%.4f"
              % (label, out["sweep"], out["angle"], out["centre"], out["offset"],
                 out["coherent"], out["dither"]))
    return out


def our_field(angle):
    """从着色器探针直接取释放时刻场（不渲画面）。"""
    for mod in [m for m in list(sys.modules) if m.startswith("render_curtain_model")]:
        del sys.modules[mod]
    import render_curtain_model as R
    R.VIEW_W, R.VIEW_H = 580, 660
    R.SNAP_W, R.SNAP_H = 480, 472
    src = (HERE / "render_reference_content_scene.py").read_text(encoding="utf-8")
    ns = {"__file__": str(HERE / "render_reference_content_scene.py")}
    exec(compile(src.replace("main()\n", ""), "refcontent", "exec"), ns)
    r = R.CurtainRenderer()
    r.configure(R.Scenario("refcontent", angle, 42))
    _pure, release, _raw = r.release_probe()
    return np.asarray(release, np.float32)


def main():
    target = np.load(HERE / "perframe" / "rf.npy")
    ref = decompose(target, None)
    ok = ~np.isnan(target)
    rp = [float(np.percentile(target[ok], q)) for q in (10, 50, 90)]
    print("参考：分位10/50/90 = %.3f/%.3f/%.3f  相干起伏%.4f  中心%+.3f"
          % (rp[0], rp[1], rp[2], ref["coherent"], ref["centre"]))
    print("（参考侧来自画面检测，整体偏晚约 4 帧 ~ 0.013）")
    print("")

    names = ("CURTAIN_RELEASE_END", "CURTAIN_RATE_EXPONENT",
             "CURTAIN_RELEASE_ONSET", "CURTAIN_SWAY_BIG", "CURTAIN_SWAY_MID",
             "CURTAIN_SWAY_FINE", "CURTAIN_SWAY_GRAIN", "CURTAIN_CENTRE_LIFT")
    text = KOTLIN.read_text(encoding="utf-8")
    saved = {n: float(re.search(r"const float %s = ([0-9.]+)" % n, text).group(1))
             for n in names}

    best = None
    for end in (0.64, 0.74, 0.82):
        for expo in (0.60, 0.80, 1.00):
            for sway in (1.25, 1.75):
                ratio = sway / 1.25
                consts = {
                    "CURTAIN_RELEASE_END": "%.3f" % end,
                    "CURTAIN_RATE_EXPONENT": "%.3f" % expo,
                    "CURTAIN_RELEASE_ONSET": "0.030",
                    "CURTAIN_SWAY_BIG": "%.3f" % sway,
                    "CURTAIN_SWAY_MID": "%.3f" % (0.29 * ratio),
                    "CURTAIN_SWAY_FINE": "%.3f" % (0.112 * ratio),
                    "CURTAIN_SWAY_GRAIN": "%.3f" % (0.040 * ratio),
                    "CURTAIN_CENTRE_LIFT": "0.44",
                }
                patch(consts)
                f = our_field(ns_angle())
                got = decompose(f, None)
                q = [float(np.percentile(f, x)) for x in (10, 50, 90)]
                score = (abs(q[0] - rp[0]) + abs(q[1] - rp[1]) + abs(q[2] - rp[2])
                         + 2.0 * abs(got["coherent"] - ref["coherent"]))
                print("  end=%.2f expo=%.2f sway=%.2f -> 分位 %.3f/%.3f/%.3f "
                      "相干%.4f 中心%+.3f  得分%.4f"
                      % (end, expo, sway, q[0], q[1], q[2], got["coherent"],
                         got["centre"], score))
                if best is None or score < best[0]:
                    best = (score, dict(consts))
    patch({k: "%.3f" % v for k, v in saved.items()})
    print("")
    print("最好的一组：%s" % best[1])
    print("（已还原为改动前的常量，待确认后再写入）")


def ns_angle():
    src = (HERE / "render_reference_content_scene.py").read_text(encoding="utf-8")
    m = re.search(r"SCENARIO_ANGLE = ([0-9.]+)", src)
    return float(m.group(1)) if m else 242.0


if __name__ == "__main__":
    main()
