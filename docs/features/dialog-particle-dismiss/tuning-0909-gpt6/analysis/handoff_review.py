"""接手复核：按完整继承链渲染固定输入，缓存 120 Hz 帧，与原片按进度并排查看。

- `--reproduce`：把当前代码渲染的原版钢铁侠与已导出的原尺寸局部成片逐帧对照，核对成片可复现。
- `--sheet`：原片／各配置按相同进度裁出控件附近区域，拼成逐帧联系表。
- `--root` 可指定另一组八份配置（例如接手时的快照），用于同口径对照。

只读配置、不写默认文件；缓存与图片放在忽略的 analysis/match-cache/handoff-review/。
"""
from pathlib import Path
import argparse, hashlib, json, sys
import numpy as np, cv2, moderngl
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from export_v2_suite import Truth

OUT = HERE / 'analysis/match-cache/handoff-review'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 18)
CONFIG_FILES = ['conditional-release-weights.json', 'conditional-transport-weights.json', 'conditional-material-rules.json',
                'surface-transfer-rules.json', 'particle-coverage-rules.json', 'particle-cohort-weights.json',
                'surface-coherence-weights.json', 'particle-scatter-weights.json']


def meta(name):
    return json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))


def config_identity(root, extra=b''):
    """配置与渲染代码共同决定缓存身份；换代码或配置都会重新渲染。"""
    h = hashlib.sha256(extra)
    for n in CONFIG_FILES:
        p = root / n
        if p.is_file():
            h.update(n.encode()); h.update(p.read_bytes())
    # 只并入完整继承链实际使用的源码，避免无关研究文件改变默认缓存身份。
    chain = ['renderer.py', 'pressure_grid.py', 'unified_model.py', 'coupled_transport.py', 'learned_release.py',
             'conditional_material.py', 'shared_transport.py', 'surface_transfer.py', 'particle_coverage.py',
             'particle_cohorts.py', 'surface_coherence.py', 'particle_fission.py', 'scatter_material.py',
             'dissolve_v2.py', 'v2_inputs.py']
    if b'edge-' in extra: chain.append('edge_generalization.py')
    if b'spark' in extra: chain.append('spark_material.py')
    if b'tex-' in extra: chain.append('release_texture.py')
    for n in chain:
        p = HERE / n; h.update(n.encode()); h.update(p.read_bytes())
    from unified_model import SHARED
    for n in ['rules.properties', 'common-release.f32', 'common-flow.f16', 'flow-confidence.u8']:
        h.update((SHARED / n).read_bytes())
    return h.hexdigest()[:16]


def render_all(name, root, label, ctx=None, count=121, sites=None, direction=None, seed=None, extra_options=None, renderer_class=None, spec=''):
    """渲染 0..1 共 count 帧（含首尾），按配置身份缓存；spec 为 handoff_variants 的消融变体。
    spec 以 cf: 开头时直接读重建模型（analysis/rebuild_cf.py）导出的帧：analysis/match-cache/rebuild/frames/<名>/<段>.npy。"""
    if spec.startswith('cf:'):
        return np.load(HERE / 'analysis/match-cache/rebuild/frames' / spec[3:] / f'{name}.npy', mmap_mode='r')
    fields = dict(sites=sites, direction=direction, seed=seed, extra=sorted((extra_options or {}).keys()),
                  cls=getattr(renderer_class, '__name__', None))
    if spec: fields['spec'] = spec
    tag = json.dumps(fields, sort_keys=True).encode()
    # 默认完整链的缓存身份与消融工具无关；只有变体才并入变体源码。
    if spec: tag += (HERE / 'analysis/handoff_variants.py').read_bytes()
    ident = config_identity(root, tag)
    path = OUT / label / f'{name}-{ident}.npy'
    if path.is_file():
        return np.load(path, mmap_mode='r')
    # 同一身份可能已在别的标签目录下渲染过；身份相同即帧相同，直接复用。
    for other in OUT.glob(f'*/{name}-{ident}.npy'):
        return np.load(other, mmap_mode='r')
    path.parent.mkdir(parents=True, exist_ok=True)
    own = ctx is None
    ctx = ctx or moderngl.create_standalone_context(require=430)
    from handoff_variants import build
    cls, options = build(root, spec)
    if renderer_class is not None:
        cls = renderer_class
    if extra_options:
        options.update(extra_options)
    use_sites = [Site(*s) for s in (sites if sites is not None else OBSERVED_SITES[name])]
    kwargs = dict(ctx=ctx, quality=2, sites=use_sites, **options)
    if direction is not None: kwargs['direction'] = direction
    if seed is not None: kwargs['seed'] = seed
    r = cls(name, **kwargs)
    first = r.render(0.)
    tmp = path.with_suffix('.partial.npy')
    frames = np.lib.format.open_memmap(tmp, mode='w+', dtype='uint8', shape=(count,) + first.shape)
    frames[0] = first
    for i in range(1, count):
        frames[i] = r.render(i / (count - 1))
    frames.flush(); del frames
    r.close()
    if own: ctx.release()
    tmp.replace(path)
    return np.load(path, mmap_mode='r')


def crop_box(m, pad_ratio=.30):
    x, y, x1, y1 = m['rect']; fw, fh = m['frame']
    pad = round(min(x1 - x, y1 - y) * pad_ratio)
    return max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad)


def sheet(name, columns, phases, out, scale=1., pad_ratio=.30, per_row=1, box=None):
    """columns: [(标题, 取帧函数 p->图像)]；每格一个进度，格内依次为各来源，per_row 格一行。"""
    m = meta(name); box = box or crop_box(m, pad_ratio)
    w = round((box[2] - box[0]) * scale); h = round((box[3] - box[1]) * scale)
    ncol = len(columns); head = 26; group = ncol * (w + 4) + 12
    rows = (len(phases) + per_row - 1) // per_row
    canvas = Image.new('RGB', (per_row * group, rows * (h + head + 6)), (14, 20, 30))
    d = ImageDraw.Draw(canvas)
    for i, p in enumerate(phases):
        x0 = (i % per_row) * group; y0 = (i // per_row) * (h + head + 6)
        for j, (title, get) in enumerate(columns):
            d.text((x0 + j * (w + 4) + 4, y0 + 2), f'{title} · {p:.3f}', font=FONT, fill=(235, 242, 250))
            im = Image.fromarray(np.ascontiguousarray(get(p))).crop(box)
            if scale != 1: im = im.resize((w, h), Image.Resampling.LANCZOS)
            canvas.paste(im, (x0 + j * (w + 4), y0 + head))
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, quality=92)
    return out


def reproduce():
    """已导出成片的原尺寸局部第三列 vs 当前代码渲染同一进度。"""
    name = 'ironman'; m = meta(name)
    frames = render_all(name, HERE, 'current')
    x, y, x1, y1 = m['rect']; fw, fh = m['frame']; pad = round(min(x1 - x, y1 - y) * .22)
    box = (max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad))
    w = box[2] - box[0]; h = box[3] - box[1]; w += w % 2; h += h % 2
    cap = cv2.VideoCapture(str(HERE / 'videos/shared-field-ironman-detail-1x.mp4'))
    rows = []
    for i in range(int(cap.get(cv2.CAP_PROP_FRAME_COUNT))):
        ok, f = cap.read()
        if not ok: break
        t = i / 60; p = float(np.clip(t - .35, 0, 1))
        if i % 6 or not (0 < p < 1): continue
        k = round(p * 120)
        decoded = cv2.cvtColor(f, cv2.COLOR_BGR2RGB)[40:40 + h, 2 * w:3 * w].astype('float32')
        ours = np.zeros((h, w, 3), 'float32')
        crop = np.asarray(frames[k])[box[1]:box[3], box[0]:box[2]].astype('float32')
        ours[:crop.shape[0], :crop.shape[1]] = crop
        diff = np.abs(decoded - ours).mean(2)
        rows.append((p, float(diff.mean()), float(np.quantile(diff, .99))))
    for p, mean, q in rows:
        print(f'进度 {p:.3f}  平均差 {mean:.2f}  99% 分位 {q:.1f}')
    print('全部帧平均差', round(float(np.mean([r[1] for r in rows])), 3), '（H.264 编码误差量级约 1～2）')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--reproduce', action='store_true')
    ap.add_argument('--sheet', action='store_true')
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--phases', type=float, nargs=3, default=[.30, .70, .02], metavar=('START', 'END', 'STEP'))
    ap.add_argument('--roots', nargs='*', default=[], help='标签=配置目录；默认只画当前配置')
    ap.add_argument('--scale', type=float, default=.75)
    ap.add_argument('--per-row', type=int, default=1)
    ap.add_argument('--no-current', action='store_true')
    ap.add_argument('--box', type=int, nargs=4, help='场景像素裁剪框 x0 y0 x1 y1；缺省为控件外扩 30%%')
    ap.add_argument('--out')
    a = ap.parse_args()
    if a.reproduce: reproduce()
    if a.sheet:
        truth = Truth(a.scene)
        columns = [('华为原片', truth.at)]
        # 标签=配置目录[::消融变体]；--no-current 时只画列出的配置。
        roots = ([] if a.no_current else [('当前', HERE, '')])
        for s in a.roots:
            # 标签=配置目录[::变体][@种子]
            label, rest = s.split('=', 1); rest, _, seed = rest.partition('@'); directory, _, spec = rest.partition('::')
            roots.append((label, Path(directory), spec, int(seed) if seed else None))
        roots = [r if len(r) == 4 else r + (None,) for r in roots]
        for label, root, spec, seed in roots:
            root = root if root.is_absolute() else HERE / root
            frames = render_all(a.scene, root, label, spec=spec, seed=seed)
            columns.append((label, lambda p, f=frames: np.asarray(f[round(np.clip(p, 0, 1) * (len(f) - 1))])))
        start, end, step = a.phases
        phases = [round(v, 4) for v in np.arange(start, end + 1e-9, step)]
        out = Path(a.out) if a.out else OUT / f'{a.scene}-{start:.2f}-{end:.2f}.jpg'
        print(sheet(a.scene, columns, phases, out, a.scale, per_row=a.per_row, box=tuple(a.box) if a.box else None))


if __name__ == '__main__':
    main()
