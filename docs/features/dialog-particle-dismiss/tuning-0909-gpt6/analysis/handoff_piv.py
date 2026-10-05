"""可见颗粒图案的速度场（PIV）：相邻帧高通亮度按块做归一化互相关，得到每块的位移与匹配度。

原片取相邻独立帧（间隔 gap 帧），模型取 1/120 为步长、进度间隔最接近的帧；位移换算成
「像素 / 0.01 进度」。只保留匹配度 ≥ min_corr 且高频能量足够的块。输出：
- 每个进度一张图：原片帧（压暗）上叠原片速度（绿）与模型速度（红）；
- 控件内外的速度大小中位数、方向差、模型／原片速度比。
只作诊断，不进入运行。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT

LUM = np.float32([.2126, .7152, .0722])
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)


def hp(frame):
    l = np.asarray(frame).astype(np.float32) @ LUM
    return l - cv2.GaussianBlur(l, (0, 0), 3.)


def piv(a, b, roi, block, step, radius, min_corr, min_energy):
    H, W = a.shape; out = []
    for y in range(radius, H - block - radius, step):
        for x in range(radius, W - block - radius, step):
            if roi[y + block // 2, x + block // 2] == 0: continue
            t = a[y:y + block, x:x + block]
            if t.std() < min_energy: continue
            win = b[y - radius:y + block + radius, x - radius:x + block + radius]
            if win.std() < min_energy: continue
            res = cv2.matchTemplate(win, t, cv2.TM_CCOEFF_NORMED)
            _, mx, _, loc = cv2.minMaxLoc(res)
            if mx < min_corr: continue
            px, py = loc
            # 抛物线亚像素
            def sub(v0, v1, v2):
                d = v0 - 2 * v1 + v2
                return 0. if abs(d) < 1e-6 else .5 * (v0 - v2) / d
            dx = px - radius + (sub(res[py, px - 1], res[py, px], res[py, px + 1]) if 0 < px < res.shape[1] - 1 else 0.)
            dy = py - radius + (sub(res[py - 1, px], res[py, px], res[py + 1, px]) if 0 < py < res.shape[0] - 1 else 0.)
            out.append((x + block / 2, y + block / 2, dx, dy, mx))
    return np.array(out, np.float32).reshape(-1, 5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--phases', type=float, nargs='+', default=[.3, .4, .5, .6])
    ap.add_argument('--gap', type=int, default=2, help='原片相隔的独立帧数')
    ap.add_argument('--spec', default='')
    ap.add_argument('--label', default='piv')
    ap.add_argument('--block', type=int, default=24)
    ap.add_argument('--step', type=int, default=16)
    ap.add_argument('--radius', type=int, default=16)
    ap.add_argument('--min-corr', type=float, default=.45)
    ap.add_argument('--min-energy', type=float, default=6.)
    ap.add_argument('--scale', type=float, default=.6)
    a = ap.parse_args()
    c = Comparison(a.scene); s = c.scene
    frames = render_all(a.scene, HERE, a.label, spec=a.spec)
    x0, y0, x1, y1 = s.rect
    inside = np.zeros(s.roi.shape, bool); inside[y0:y1, x0:x1] = True
    roi = s.roi.astype(np.uint8)
    tiles = []; report = {}
    for p in a.phases:
        i = int(np.argmin(abs(c.ref.progress - p)))
        j = min(i + a.gap, len(c.ref.progress) - 1)
        pa, pb = float(c.ref.progress[i]), float(c.ref.progress[j]); dp = pb - pa
        R = piv(hp(c.ref.frames[i]), hp(c.ref.frames[j]), roi, a.block, a.step, a.radius, a.min_corr, a.min_energy)
        k0 = round(pa * 120); k1 = k0 + max(1, round(dp * 120)); dm = (k1 - k0) / 120
        M = piv(hp(frames[k0]), hp(frames[min(k1, 120)]), roi, a.block, a.step, a.radius, a.min_corr, a.min_energy)
        # 速度：像素 / 0.01 进度
        vr = R[:, 2:4] / dp * .01; vm = M[:, 2:4] / dm * .01
        def split(P, V):
            ins = inside[P[:, 1].astype(int), P[:, 0].astype(int)] if len(P) else np.zeros(0, bool)
            moving = np.hypot(*V.T) > .3 if len(V) else np.zeros(0, bool)
            ins = ins & moving; P_out = ~inside[P[:, 1].astype(int), P[:, 0].astype(int)] & moving if len(P) else np.zeros(0, bool)
            f = lambda m: (float(np.median(np.hypot(*V[m].T))) if m.sum() >= 5 else np.nan, int(m.sum()),
                           np.median(V[m], 0).round(2).tolist() if m.sum() >= 5 else None)
            return f(ins), f(P_out)
        ri, ro = split(R, vr); mi, mo = split(M, vm)
        # 同一块位置上两边都有值时的逐块比较
        common = {(int(x), int(y)): (dx, dy) for x, y, dx, dy in np.c_[R[:, :2], vr]}
        # 只比较至少一边在动的块（>0.3 像素 / 0.01 进度），静止的表面与背景纹理不计入。
        pairs = [(common[(int(x), int(y))], (dx, dy)) for x, y, dx, dy in np.c_[M[:, :2], vm] if (int(x), int(y)) in common
                 and max(np.hypot(*common[(int(x), int(y))]), np.hypot(dx, dy)) > .3]
        if pairs:
            A = np.array([q[0] for q in pairs]); Bv = np.array([q[1] for q in pairs])
            ratio = float(np.median(np.hypot(*Bv.T) / np.maximum(np.hypot(*A.T), 1e-3)))
            ang = np.degrees(np.arctan2(Bv[:, 1], Bv[:, 0]) - np.arctan2(A[:, 1], A[:, 0])); ang = (ang + 180) % 360 - 180
            angle = float(np.median(np.abs(ang)))
        else:
            ratio = angle = np.nan
        # 控件 3×3 分区（外扩 20%）的中位速度向量：只用在动的块
        pad = .2 * min(x1 - x0, y1 - y0); gx = np.linspace(x0 - pad, x1 + pad, 4); gy = np.linspace(y0 - pad, y1 + pad, 4)
        cells = []
        for r_ in range(3):
            for c_ in range(3):
                def med(P, V):
                    if not len(P): return None
                    m = (P[:, 0] >= gx[c_]) & (P[:, 0] < gx[c_ + 1]) & (P[:, 1] >= gy[r_]) & (P[:, 1] < gy[r_ + 1]) & (np.hypot(*V.T) > .3)
                    return (np.median(V[m], 0), int(m.sum())) if m.sum() >= 4 else None
                o, mm = med(R, vr), med(M, vm)
                cells.append(('上中下'[r_] + '左中右'[c_], o, mm))
        print('   分区 ' + '  '.join(f'{nm}:原({o[0][0]:+.1f},{o[0][1]:+.1f})/{o[1]} 模({mm[0][0]:+.1f},{mm[0][1]:+.1f})/{mm[1]}'
                                    if o and mm else f'{nm}:-' for nm, o, mm in cells))
        report[f'{p:.2f}'] = dict(dp_ref=dp, ref_in=ri, ref_out=ro, model_in=mi, model_out=mo, pairs=len(pairs), speed_ratio=ratio, angle_abs=angle)
        print(f'{p:.2f} Δp原片{dp:.4f} 模型{dm:.4f} | 原片 内{ri[0]:.1f}({ri[1]}) 外{ro[0]:.1f}({ro[1]}) 中位向量内{ri[2]} | '
              f'模型 内{mi[0]:.1f}({mi[1]}) 外{mo[0]:.1f}({mo[1]}) 中位向量内{mi[2]} | 同块{len(pairs)} 速度比{ratio:.2f} 方向差{angle:.0f}°', flush=True)
        base = Image.fromarray((np.asarray(c.ref.frames[i]).astype(np.float32) * .45).astype(np.uint8))
        d = ImageDraw.Draw(base)
        for P, V, col in [(R, vr, (80, 255, 120)), (M, vm, (255, 90, 80))]:
            for (x, y, *_), (vx, vy) in zip(P, V):
                if np.hypot(vx, vy) < .3: continue
                d.line([(x, y), (x + vx * 3, y + vy * 3)], fill=col, width=2)
                d.ellipse([x - 1.5, y - 1.5, x + 1.5, y + 1.5], fill=col)
        d.rectangle(s.rect, outline=(120, 120, 140))
        pad = round(min(x1 - x0, y1 - y0) * .3)
        base = base.crop((max(0, x0 - pad), max(0, y0 - pad), min(base.width, x1 + pad), min(base.height, y1 + pad)))
        im = base.resize((round(base.width * a.scale), round(base.height * a.scale)), Image.Resampling.LANCZOS)
        ImageDraw.Draw(im).text((4, 4), f'{p:.2f} 绿=原片 红=模型 (×3/0.01进度)', font=FONT, fill='white')
        tiles.append(im)
    W = sum(t.width + 4 for t in tiles); H = max(t.height for t in tiles)
    canvas = Image.new('RGB', (W, H), (14, 20, 30)); x = 0
    for t in tiles: canvas.paste(t, (x, 0)); x += t.width + 4
    out = OUT / f'piv-{a.scene}-{a.label}.jpg'; canvas.save(out, quality=90); print(out)
    (OUT / f'piv-{a.scene}-{a.label}.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
