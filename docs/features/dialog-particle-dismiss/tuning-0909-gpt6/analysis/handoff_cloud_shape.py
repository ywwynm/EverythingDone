"""颗粒群的轮廓、质心轨迹与形状随时间的变化（只作诊断）。

逐个原片独立帧（不抽样）取颗粒掩码（`Scene.observe` 的 particle：既非背景也非原位表面），按 0.03 短边高斯平滑成密度 D，
阈值 D>0.08 得到颗粒群轮廓 S。原片与模型各自计算：
- 轮廓交并比 IoU(S原片, S模型)，只在可观测区域内；
- 以 D 为权的质心（按卡片短边归一化的位移，相对卡片中心）、协方差主轴方向与长短轴标准差（短边为单位）。
模型帧默认按原片码率编码后再取掩码（recording_codec），与原片同一录像口径。
`--ref0`：颗粒掩码改为「既偏离背景、又偏离本序列开场画面」（开场画面取最早可用 0.04 进度内逐像素中位数，原片与模型各用自己的），
不再与素材源图比较——录像的颜色处理、对比增强与配准误差会让源图比较把整片表面误判成颗粒（科比、白衣人物、雪山）。
另可输出若干时刻的轮廓叠加图：原片轮廓绿、模型轮廓品红，底图为原片。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded
from match_compare import usable
from match_observe import OCC_T
from scipy.ndimage import maximum_filter, minimum_filter

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']


def shape(mask, roi, span, rect):
    sig = .03 * span
    D = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), sig)
    D[~roi] = 0
    S = D > .08
    w = D.sum()
    if w < 1: return D, S, None
    H, W = D.shape; yy, xx = np.mgrid[:H, :W].astype(np.float32)
    x, y, x1, y1 = rect; cx0, cy0 = (x + x1) / 2, (y + y1) / 2
    mx, my = float((D * xx).sum() / w), float((D * yy).sum() / w)
    cxx = float((D * (xx - mx) ** 2).sum() / w); cyy = float((D * (yy - my) ** 2).sum() / w); cxy = float((D * (xx - mx) * (yy - my)).sum() / w)
    ev, evec = np.linalg.eigh(np.array([[cxx, cxy], [cxy, cyy]]))
    major = evec[:, 1]; ang = math.degrees(math.atan2(-major[1], major[0])) % 180
    return D, S, dict(cx=(mx - cx0) / span, cy=(my - cy0) / span, angle=ang,
                      major=math.sqrt(max(ev[1], 0)) / span, minor=math.sqrt(max(ev[0], 0)) / span, mass=float(w))


def run(name, variants, codec=True, overlay=None, ref0=False):
    c = Comparison(name); s = c.scene; roi = s.roi
    rect = s.meta['rect']; span = s.span
    prog = np.asarray(c.ref.progress, float)
    use = [i for i, p in enumerate(prog) if .03 <= p <= .95]
    valid = s.meta['reference'].get('valid_progress', 1.)
    early = [i for i, p in enumerate(prog) if usable(name, p, valid)]
    p_first = float(prog[early[0]]); win = [i for i in early if prog[i] <= p_first + .04]

    def envelope(frames):
        f0 = np.median(np.stack([np.asarray(f, np.float32) for f in frames]), 0)
        return maximum_filter(f0, size=(3, 3, 1)), minimum_filter(f0, size=(3, 3, 1))

    def mask_of(frame, env):
        if env is None: return s.observe(frame)['particle']
        eb = s.excess(frame, s.bgmax, s.bgmin); e0 = s.excess(frame, env[0], env[1])
        return (eb > s.threshold) & (e0 > OCC_T)
    env_ref = envelope([c.ref.frames[i] for i in win]) if ref0 else None
    seqs = []
    for label, root, spec in variants:
        if codec:
            cf = coded(name, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        else:
            fr = render_all(name, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
    envs = {label: (envelope([get(i) for i in win]) if ref0 else None) for label, get in seqs}
    rows = []; shots = {}
    want = set()
    if overlay:
        for q in overlay: want.add(int(np.argmin(abs(prog - q))))
    for i in use:
        ref = np.asarray(c.ref.frames[i]); Dr, Sr, mr = shape(mask_of(ref, env_ref), roi, span, rect)
        row = dict(p=float(prog[i]), ref=mr, model={})
        for label, get in seqs:
            Dm, Sm, mm = shape(mask_of(get(i), envs[label]), roi, span, rect)
            inter = float((Sr & Sm).sum()); uni = float((Sr | Sm).sum())
            row['model'][label] = dict(iou=inter / uni if uni > 0 else float('nan'), m=mm)
            if i in want: shots.setdefault(i, {})[label] = Sm
        if i in want: shots.setdefault(i, {})['原片'] = (Sr, ref)
        rows.append(row)
    return rows, shots


def summarize(name, rows, labels):
    out = {}
    for lab in labels:
        iou = [r['model'][lab]['iou'] for r in rows if np.isfinite(r['model'][lab]['iou'])]
        dc, da, dmaj, dmin = [], [], [], []
        for r in rows:
            a, b = r['ref'], r['model'][lab]['m']
            if not a or not b: continue
            dc.append(math.hypot(b['cx'] - a['cx'], b['cy'] - a['cy']))
            d = abs(b['angle'] - a['angle']) % 180; da.append(min(d, 180 - d))
            dmaj.append(b['major'] / max(a['major'], 1e-6)); dmin.append(b['minor'] / max(a['minor'], 1e-6))
        out[lab] = dict(iou=float(np.mean(iou)) if iou else float('nan'), centroid=float(np.mean(dc)) if dc else float('nan'),
                        angle=float(np.median(da)) if da else float('nan'), major=float(np.median(dmaj)) if dmaj else float('nan'),
                        minor=float(np.median(dmin)) if dmin else float('nan'), frames=len(rows))
    return out


def draw(name, shots, labels, path):
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
    keys = sorted(shots); tiles = []
    for i in keys:
        Sr, ref = shots[i]['原片']
        for lab in labels:
            img = ref.copy().astype(np.float32) * .55
            for S, col in [(Sr, (60, 255, 90)), (shots[i][lab], (255, 60, 220))]:
                edge = S & ~cv2.erode(S.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
                img[edge] = col
            tiles.append((f'{lab} · 独立帧 {i}', np.clip(img, 0, 255).astype(np.uint8)))
    h, w = tiles[0][1].shape[:2]; sc = 360 / w; tw, th = 360, round(h * sc)
    per = len(labels) * min(len(keys), 4)
    rows_n = (len(tiles) + per - 1) // per
    sheet = Image.new('RGB', (per * (tw + 4), rows_n * (th + 22)), (14, 20, 30)); d = ImageDraw.Draw(sheet)
    for k, (t, im) in enumerate(tiles):
        X = (k % per) * (tw + 4); Y = (k // per) * (th + 22)
        sheet.paste(Image.fromarray(im).resize((tw, th)), (X, Y + 22)); d.text((X + 3, Y + 2), t, font=font, fill='white')
    sheet.save(path, quality=90)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（空变体为交接默认）')
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--ref0', action='store_true', help='颗粒掩码与本序列开场画面比较，而不是与素材源图比较')
    ap.add_argument('--overlay', type=float, nargs='*', default=[.2, .35, .5, .65])
    ap.add_argument('--out', default=str(OUT / 'cloud-shape.json'))
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        variants.append((label, root, spec))
    labels = [v[0] for v in variants]
    path = Path(a.out); allres = json.loads(path.read_text('utf-8')) if path.is_file() else {}
    for name in a.scenes:
        rows, shots = run(name, variants, codec=not a.raw, overlay=a.overlay, ref0=a.ref0)
        summ = summarize(name, rows, labels)
        allres.setdefault(name, {}).update({lab: dict(summary=summ[lab], rows=[dict(p=r['p'], ref=r['ref'], **r['model'][lab]) for r in rows]) for lab in labels})
        print(f'== {name}（{len(rows)} 帧）', flush=True)
        for lab in labels:
            q = summ[lab]
            print(f'  {lab:10s} 轮廓 IoU {q["iou"]:.3f}  质心偏差 {q["centroid"]:.3f} 短边  主轴角差 {q["angle"]:.1f}°  长轴比 {q["major"]:.2f}  短轴比 {q["minor"]:.2f}', flush=True)
        if shots:
            draw(name, shots, labels, str(OUT / f'cloud-shape{"-ref0" if a.ref0 else ""}-{name}.jpg'))
    path.write_text(json.dumps(allres, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
