"""手持屏摄的相机自动曝光：逐帧拟合并写入参考输入表（`exposure`）。

背景图取自动画结束帧（曝光 E_end），卡片图取自动画前（曝光 E_0），原片第 t 帧的曝光是 E_t。
逐帧在「整段都没有颗粒经过」的屏幕像素（卡片外）上按通道拟合 原片 ≈ A_t·背景 + O_t，即 T_t = E_t∘E_end⁻¹。
  split：背景按 T_t，卡片与颗粒按 T_t∘T_0⁻¹（T_0 取动画开始前几帧的平均）；
  global：整帧都按 T_t（卡片图与背景图取自曝光相近的帧时用这一种）。
按粗尺度画面差在 none / global / split 三种里选（与资源的提取方式有关，每段一次）。
产物：表内 exposure = {mode, points: [[进度, A×3, O×3, Ac×3, Oc×3], ...]}

用法：python analysis/fit_exposure.py <段名> [...]
"""
from pathlib import Path
import json
import os
import sys
import time

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from harmony_native import NativeRenderer, LIVE_RASTER, inputs_from_entry
from match_compare import Comparison

TABLE = ROOT / 'native_reference_inputs.json'


def fit_points(name, c, bg):
    s = c.scene
    d = ROOT / 'assets' / name
    x0, y0, x1, y1 = s.rect
    valid = s.meta['reference'].get('valid_progress', 1.0)
    prog = c.ref.progress
    scr_p = d / 'screen-mask.png'
    if scr_p.exists():
        scr = np.asarray(Image.open(scr_p).convert('L')) > 127
    else:
        scr = np.zeros(bg.shape[:2], bool); scr[120:1080, 80:640] = True
    scr = cv2.erode(scr.astype(np.uint8), np.ones((15, 15), np.uint8)) > 0
    card = np.zeros(bg.shape[:2], bool); card[max(0, y0 - 40):y1 + 40, max(0, x0 - 40):x1 + 40] = True
    dev = np.zeros(bg.shape[:2], np.float32)
    for i in range(len(prog)):
        if prog[i] <= valid:
            f = np.asarray(c.ref.frames[i]).astype(np.float32)
            dev = np.maximum(dev, np.abs(cv2.GaussianBlur(f, (0, 0), 3) - cv2.GaussianBlur(bg, (0, 0), 3)).max(2))
    cand = scr & ~card
    quiet = cand & (dev < np.percentile(dev[cand], 50))
    fits = []
    for i in range(len(prog)):
        if prog[i] <= valid:
            f = np.asarray(c.ref.frames[i]).astype(np.float32)
            A = np.zeros(3); O = np.zeros(3)
            for ch in range(3):
                A[ch], O[ch] = np.polyfit(bg[..., ch][quiet], f[..., ch][quiet], 1)
            fits.append((float(prog[i]), A, O))
    # 逐帧拟合有抖动：沿时间做 3 帧中值
    P = np.array([q[0] for q in fits])
    AO = np.array([np.r_[q[1], q[2]] for q in fits])
    sm = np.stack([np.median(AO[max(0, k - 1):k + 2], 0) for k in range(len(AO))])
    pre = P < 0
    T0 = sm[pre].mean(0) if pre.any() else sm[0]
    return P, sm, T0


def points(P, sm, T0, mode):
    out = []
    for p, v in zip(P, sm):
        A, O = v[:3], v[3:]
        if mode == 'global':
            Ac, Oc = A, O
        else:
            Ac = A / T0[:3]; Oc = O - Ac * T0[3:]
        out.append([round(float(p), 4)] + [round(float(x), 4) for x in np.r_[A, O, Ac, Oc]])
    return out


def score(name, e, c, ctx):
    s = c.scene
    x0, y0, x1, y1 = s.rect
    m = int(0.3 * (x1 - x0))
    box = (slice(max(0, y0 - m), min(s.H, y1 + m)), slice(max(0, x0 - m), min(s.W, x1 + m)))
    valid = s.meta['reference'].get('valid_progress', 1.0)
    prog = c.ref.progress
    idx = sorted({int(np.argmin(abs(prog - t))) for t in np.arange(0.2, min(0.85, valid - 0.02) + 1e-9, 0.05)})
    r = NativeRenderer(name, inputs_from_entry(e), raster=LIVE_RASTER, ctx=ctx, ss=2)
    blur = lambda a: cv2.GaussianBlur(a.astype(np.float32), (0, 0), 4.0)
    v = float(np.mean([np.abs(blur(r.render(float(prog[i])))[box] - blur(np.asarray(c.ref.frames[i]))[box]).mean() for i in idx]))
    r.close()
    return v


def main():
    import moderngl
    ctx = moderngl.create_standalone_context(require=430)
    for name in sys.argv[1:]:
        table = json.loads(TABLE.read_text('utf-8'))
        e0 = dict(table[name]); e0.pop('exposure', None)
        c = Comparison(name)
        d = ROOT / 'assets' / name
        bgp = d / 'background-clean.png'
        bg = np.asarray(Image.open(bgp if bgp.exists() else d / 'background.png').convert('RGB')).astype(np.float32)
        P, sm, T0 = fit_points(name, c, bg)
        res = {'none': (score(name, e0, c, ctx), None)}
        for mode in ('global', 'split'):
            ex = dict(mode=mode, points=points(P, sm, T0, mode))
            res[mode] = (score(name, dict(e0, exposure=ex), c, ctx), ex)
        best = min(res, key=lambda k: res[k][0])
        print(name, {k: round(v[0], 3) for k, v in res.items()}, '→', best, flush=True)
        lock = TABLE.with_suffix('.lock')
        for _ in range(600):
            try:
                fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY); break
            except FileExistsError:
                time.sleep(0.5)
        try:
            t = json.loads(TABLE.read_text('utf-8'))
            if best == 'none':
                t[name].pop('exposure', None)
            else:
                t[name]['exposure'] = res[best][1]
                t[name]['exposure_note'] = ('相机自动曝光（逐帧拟合于卡片外始终无颗粒的屏幕像素）；粗尺度画面差 none %.2f / global %.2f / split %.2f'
                                            % (res['none'][0], res['global'][0], res['split'][0]))
            TABLE.write_text(json.dumps(t, ensure_ascii=False, indent=1), 'utf-8')
        finally:
            os.close(fd); os.unlink(str(lock))


if __name__ == '__main__':
    main()
