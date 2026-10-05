"""重建对照用：从现行候选（GLHMRP）的渲染器里取出材料与释放场，供连续薄膜模型在「同一释放」下单独比较运动与外观。
输出 analysis/match-cache/rebuild/release/<变体标签>/<段>.npz：base（材料表，与渲染器同序：卡片内 x、y、出生、编号、前沿法向、寿命、
偏移时刻、四个随机数）、nx/ny/cw/ch、rect、direction、seed、release_spread，以及两倍材料网格分辨率的平滑释放场 T2（不含逐颗抖动）。
只读渲染器状态，不渲染、不改模型。用法：rebuild_extract_release.py 标签 配置目录 变体文件 [段 ...]"""
from pathlib import Path
import json, sys
import numpy as np
import moderngl
from PIL import Image

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from handoff_variants import build
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-5', 'reference-0926-6', 'reference-0926-7']

if __name__ == '__main__':
    label, root, spec_file = sys.argv[1], HERE / sys.argv[2], Path(sys.argv[3])
    spec = spec_file.read_text('utf-8').strip()
    out = HERE / 'analysis/match-cache/rebuild/release' / label
    out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    for name in (sys.argv[4:] or SCENES):
        cls, options = build(root, spec)
        r = cls(name, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[name]], **options)
        fg = np.array(Image.open(r.directory / 'foreground.png').convert('RGBA'))
        seed = int(r.meta['seed'])
        T2 = None
        if getattr(r, 'release_input', None) is not None and callable(r.release_input):
            T2 = np.asarray(r.release_input(2 * r.nx, 2 * r.ny, r.cw, r.ch, r.direction, seed, fg), np.float32)
        np.savez_compressed(out / f'{name}.npz', base=np.asarray(r.base, np.float32), nx=r.nx, ny=r.ny, cw=r.cw, ch=r.ch,
                            rect=np.asarray(r.meta['rect']), direction=float(r.direction), seed=seed,
                            release_spread=float(r.release_spread), T2=T2 if T2 is not None else np.zeros((0, 0), np.float32),
                            spec=spec)
        print(name, '材料', r.n, '网格', r.nx, r.ny, '方向', r.direction, '释放场', None if T2 is None else T2.shape, flush=True)
        r.close()
    ctx.release()
