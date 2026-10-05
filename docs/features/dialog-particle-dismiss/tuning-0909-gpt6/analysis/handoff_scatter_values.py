"""细粒材质共同函数（覆盖 / 中性反射 / 色度）在各布局、各进度的取值分布；只读权重，不改模型。"""
from pathlib import Path
import sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from scatter_material import load_scatter, scatter_field
from particle_cohorts import load_cohorts, cohort_field
from shared_transport import load_transport, correction
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
import json

SCENES = ['ironman', 'ironman-up-reference', 'reference-0926-2', 'kobe', 'reference-0926-7']


def main():
    scatter = load_scatter(HERE / 'particle-scatter-weights.json')
    cohorts = load_cohorts(HERE / 'particle-cohort-weights.json')
    transport = load_transport(HERE / 'conditional-transport-weights.json')
    print('细粒材质输出上限', scatter['scales'].tolist(), '年龄覆盖上限', cohorts['scale'])
    n = 48; y, x = np.mgrid[:n, :n].astype(np.float32); x = (x + .5) / n; y = (y + .5) / n   # 只看控件内
    for name in SCENES:
        m = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
        x0, y0, x1, y1 = m['rect']; w, h = x1 - x0, y1 - y0
        sites = [Site(*s) for s in OBSERVED_SITES[name]]
        print(f'== {name}')
        for t in [.2, .4, .6]:
            s = scatter_field(x, y, t, w, h, m['direction'], sites, scatter)
            c = cohort_field(x, y, t, w, h, m['direction'], sites, cohorts)
            d = correction(x, y, t, w, h, m['direction'], sites, transport)[..., 2]
            q = lambda a: ' '.join(f'{v:+.2f}' for v in np.quantile(a, [.1, .5, .9]))
            print(f'  t={t:.1f} 覆盖[{q(s[..., 0])}] 中性[{q(s[..., 1])}] 色度[{q(s[..., 2])}] | 年龄覆盖 新[{q(c[..., 0])}] 熟[{q(c[..., 1])}] 老[{q(c[..., 2])}] | 输运密度[{q(d)}]')


if __name__ == '__main__':
    main()
