"""原片与当前模型的解除时刻图并排（release_integrity 同一测量），检查释放时序偏差的空间分布。

模型端使用完整继承链的真实渲染；原片端复用 release-integrity 缓存。只作诊断。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2, moderngl
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from handoff_variants import build

OUT = HERE / 'analysis/match-cache/handoff-review'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)


def colorize(T, valid, size):
    v = np.clip(np.nan_to_num(T, nan=1.) / .9, 0, 1)
    im = cv2.applyColorMap(np.uint8(v * 255), cv2.COLORMAP_TURBO)[:, :, ::-1].copy()
    im[~valid] = (60, 60, 70)
    return Image.fromarray(im).resize(size, Image.Resampling.NEAREST)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'ironman-up-reference', 'reference-0926-2'])
    ap.add_argument('--spec', default='')
    ap.add_argument('--label', default='current')
    a = ap.parse_args()
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = build(HERE, a.spec)
    tiles = []; report = {}
    from handoff_review import render_all
    import hashlib
    for name in a.scenes:
        probe = Probe(name)
        huawei = probe.huawei()
        # 与其它接手工具共用帧缓存与解除时刻缓存（按帧缓存文件名区分配置与变体）。
        frames = render_all(name, HERE, a.label, spec=a.spec)
        ident = hashlib.sha256(str(getattr(frames, 'filename', a.label)).encode()).hexdigest()[:12]
        cache = OUT / 'release-maps' / f'{name}-{ident}.npy'
        if cache.is_file():
            model = np.load(cache)
        else:
            class Cached:
                def render(self, p): return np.asarray(frames[round(np.clip(p, 0, 1) * 120)])
            model = probe.model(Cached())
            cache.parent.mkdir(parents=True, exist_ok=True); np.save(cache, model)
        valid = probe.valid & np.isfinite(huawei) & np.isfinite(model)
        diff = model - huawei
        report[name] = dict(mean=float(np.mean(diff[valid])), mae=float(np.mean(np.abs(diff[valid]))),
                            q10=float(np.quantile(diff[valid], .1)), q90=float(np.quantile(diff[valid], .9)))
        print(name, {k: round(v, 3) for k, v in report[name].items()}, flush=True)
        h, w = huawei.shape; scale = max(1, 240 // max(h, w)); size = (w * scale * 1, h * scale * 1)
        d = np.clip(diff / .2, -1, 1)
        dimg = np.zeros((h, w, 3), np.uint8)
        dimg[..., 0] = np.uint8(np.clip(d, 0, 1) * 255); dimg[..., 2] = np.uint8(np.clip(-d, 0, 1) * 255)
        dimg[~valid] = (60, 60, 70)
        tiles.append((name, [colorize(huawei, probe.valid, size), colorize(model, probe.valid, size),
                             Image.fromarray(dimg).resize(size, Image.Resampling.NEAREST)]))
    ctx.release()
    cw = max(t.width for _, ts in tiles for t in ts); ch = max(t.height for _, ts in tiles for t in ts)
    canvas = Image.new('RGB', (3 * (cw + 8) + 8, len(tiles) * (ch + 30) + 8), (14, 20, 30)); dr = ImageDraw.Draw(canvas)
    for i, (name, ts) in enumerate(tiles):
        for j, (t, title) in enumerate(zip(ts, ['原片解除时刻', '模型解除时刻', '模型−原片（红=晚，蓝=早，±0.2）'])):
            x0 = 8 + j * (cw + 8); y0 = 8 + i * (ch + 30)
            dr.text((x0, y0), f'{name} · {title}', font=FONT, fill=(235, 242, 250))
            canvas.paste(t, (x0, y0 + 22))
    out = OUT / f'release-maps-{a.label}.jpg'; canvas.save(out, quality=92); print(out)
    (OUT / f'release-maps-{a.label}.json').write_text(json.dumps(report, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
