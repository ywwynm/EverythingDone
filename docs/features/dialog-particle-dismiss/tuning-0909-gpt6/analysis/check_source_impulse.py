"""材料年龄校准的零配置、实际渲染和几何输入检查；不替代原片验收。"""
from pathlib import Path
import argparse, dataclasses, json, sys
import moderngl, numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE/'analysis')]
from check_cloud_evolution import configuration
from source_impulse import SourceImpulseRenderer, load_impulse, impulse_field, FIELD
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--candidate', type=Path, required=True)
    ap.add_argument('--out', type=Path, required=True)
    a = ap.parse_args()
    cls, options = configuration(HERE)
    weights = load_impulse(a.candidate)
    ctx = moderngl.create_standalone_context(require=430)
    y, x = np.mgrid[:FIELD, :FIELD].astype(np.float32)
    x = -.38+(x+.5)/FIELD*1.76
    y = -.38+(y+.5)/FIELD*1.76
    report = {}
    for name, origins in OBSERVED_SITES.items():
        sites = [Site(*v) for v in origins]
        kw = dict(ctx=ctx, quality=2, sites=sites, **options)
        old = cls(name, **kw)
        zero = SourceImpulseRenderer(name, **kw)
        candidate = SourceImpulseRenderer(name, impulse=weights, **kw)
        zero_delta = 0
        state_equal = finite = True
        endpoints = []
        for phase in [0., .18, .37, .55, .79, 1.]:
            before = old.render(phase)
            empty = zero.render(phase)
            after = candidate.render(phase)
            zero_delta = max(zero_delta, int(abs(before.astype(np.int16)-empty).max()))
            state_equal &= old.state.read() == candidate.state.read() == zero.state.read()
            finite &= bool(np.isfinite(np.frombuffer(candidate.state.read(), np.float32)).all())
            if phase in [0., 1.]: endpoints.append(int(abs(before.astype(np.int16)-after).max()))
        frame = candidate.render(.43)
        candidate.render(.75)
        seek_equal = bool(np.array_equal(frame, candidate.render(.43)))
        def field(ss):
            return impulse_field(x, y, old.cw, old.ch, old.direction, ss, weights)
        original = field(sites)
        permutation = float(abs(original-field(sites[::-1])).max())
        samples = [field([dataclasses.replace(s, x=s.x+d, y=s.y+d) for s in sites])
                   for d in np.linspace(-.003, .003, 13)]
        perturbation = max(float(abs(u-v).max()) for u, v in zip(samples, samples[1:]))
        row = dict(zero_max_pixel_delta=zero_delta, state_equal=state_equal, finite=finite,
                   endpoint_max_delta=endpoints, seek_equal=seek_equal,
                   site_order_max_difference=permutation, perturbation_step=perturbation,
                   gl_error=ctx.error)
        assert zero_delta == 0 and state_equal and finite and max(endpoints) <= 1 and seek_equal, (name, row)
        assert np.isfinite(original).all() and permutation < 1e-4 and perturbation < .015, (name, row)
        assert row['gl_error'] == 'GL_NO_ERROR', (name, row)
        old.close(); zero.close(); candidate.close()
        report[name] = row
        print(name, row, flush=True)
    ctx.release()
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(dict(candidate=str(a.candidate),
        scope='运行正确性和几何输入连续性，不代表完整轨迹或华为视觉验收',
        scenes=report), ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__': main()
