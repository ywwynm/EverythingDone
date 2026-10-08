"""直接运行正式绘制着色器，检查两种颜色工作面；不连接设备。"""
import json
import sys
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT/'docs/features/harmony-particle-android-port/checks'))
from check_drift_clarity import Renderer, uniforms

OUT = ROOT/'tmp/particle-color-drawing-20261008/desktop'


def diff(a, b):
    d = np.abs(a.astype(np.float32)-b.astype(np.float32))
    active = np.any(a != b, axis=-1)
    return dict(mean=float(d.mean()), maximum=float(d.max()), over2=float((d>2).mean()),
                changedPixels=float(active.mean()))


def drift(ctx):
    rows = []
    root = ROOT/'tmp/harmony-particle-android-port/parity/clarity-20261008-after'
    for folder in sorted(root.iterdir()):
        if not (folder/'device/result.json').exists():
            continue
        high = Renderer(ctx, folder); low = Renderer(ctx, folder)
        low.work = ctx.texture((low.ww, low.wh), 4, dtype='f1')
        low.workfbo = ctx.framebuffer([low.work])
        low.resources += [low.workfbo, low.work]
        for r in (high, low):
            uniforms(r.particle, {'u_tightQuad': 1})
        for size in (1, 3):
            for fraction in (.15, .45, .75, .9):
                t = high.result['totalSeconds']*fraction
                for bg in ((20, 20, 20), (245, 245, 245)):
                    high.spec['background'] = low.spec['background'] = bg
                    a = high.render(t, size=size); b = low.render(t, size=size)
                    rows.append(dict(scene=folder.name, additive=high.spec['additive'], size=size,
                                     fraction=fraction, background=bg[0], **diff(a, b)))
                    if size == 3 and fraction == .45 and bg[0] == 20:
                        Image.fromarray(np.concatenate((a, b), axis=1)).save(OUT/f'{folder.name}-precision.png')
        high.close(); low.close()
    return rows


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    result = {'drift': drift(ctx)}
    (OUT/'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
    for name, rows in result.items():
        print(name, len(rows), 'cases', 'max', max(r.get('maximum', 0) for r in rows), flush=True)


if __name__ == '__main__':
    main()
