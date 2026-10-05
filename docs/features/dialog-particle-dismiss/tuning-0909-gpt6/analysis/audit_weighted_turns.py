"""以实际材料面积、透明度和背景对比复核共同换向；诊断量不等于华为运动真值。"""
from pathlib import Path
import argparse, hashlib, json, sys
import moderngl, numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter1d, map_coordinates

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from check_cloud_evolution import configuration
from particle_proxy import ParticleProxy
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def linear(value):
    return np.where(value <= .04045, value / 12.92, ((value + .055) / 1.055) ** 2.4)


def weighted_quantile(value, weight, quantiles):
    order = np.argsort(value)
    cumulative = np.cumsum(weight[order])
    if not len(cumulative) or cumulative[-1] <= 0:
        return [None] * len(quantiles)
    return np.interp(quantiles, cumulative / cumulative[-1], value[order]).tolist()


def measure(saved, sample_times, sample_weights):
    tr, times = saved['trace'], saved['phases']
    visible = ((tr[:, :, 4] > .04) & (tr[:, :, 4] < tr[:, :, 5] - .055)
               & (tr[:, :, 6] < .6) & saved['mask'])
    cross = np.array([-saved['wind'][1], saved['wind'][0]])
    pos = tr[:, :, 2:4] / float(saved['span'])
    rows = []
    for start, end in [(.20, .45), (.30, .70), (.50, .75)]:
        window = (times >= start - 1e-6) & (times <= end + 1e-6)
        keep = visible[window].all(0)
        row = dict(start=start, end=end, stable_materials=int(keep.sum()))
        if keep.sum() < 32:
            rows.append(row)
            continue
        dt = np.diff(times[window])
        velocity = gaussian_filter1d(np.diff(pos[window][:, keep] @ cross, axis=0) / dt[:, None], 2., axis=0)
        signs = np.sign(velocity)
        signs[abs(velocity) < .02] = 0
        for i in range(1, len(signs)):
            signs[i] = np.where(signs[i] == 0, signs[i-1], signs[i])
        switch = signs[1:] * signs[:-1] < 0
        length = max(1, round(.05 / float(dt.mean())))
        # 固定区间的平均贡献，避免正在渐隐的材料使分母逐帧跳变。
        samples = (sample_times >= start - 1e-6) & (sample_times <= end + 1e-6)
        weight = sample_weights[samples][:, keep].mean(0)
        count_curve, weighted_curve = [], []
        for i in range(len(switch)):
            turn = switch[i:i+length].any(0)
            count_curve.append(float(turn.mean()))
            weighted_curve.append(float((turn * weight).sum() / max(weight.sum(), 1e-12)))
        excess = np.maximum((abs(velocity) * dt[:, None]).sum(0) - abs((velocity * dt[:, None]).sum(0)), 0)
        row.update(count_peak=max(count_curve), contrast_peak=max(weighted_curve),
                   count_excess_q90=float(np.quantile(excess, .9)),
                   contrast_excess_q90=weighted_quantile(excess, weight, [.9])[0],
                   effective_materials=float(weight.sum() ** 2 / max((weight * weight).sum(), 1e-12)),
                   largest_weight_fraction=float(weight.max() / max(weight.sum(), 1e-12)),
                   count_curve=count_curve, contrast_curve=weighted_curve)
        rows.append(row)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['reference-0926-2', 'ironman-up-reference', 'ironman', 'kobe'])
    ap.add_argument('--root', type=Path, default=HERE)
    ap.add_argument('--traces', type=Path, default=HERE / 'analysis/match-cache/motion-continuity/native-cohort-continuity')
    ap.add_argument('--out', type=Path, default=HERE / 'analysis/match-cache/weighted-turns')
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    cls, kw = configuration(args.root)
    ctx = moderngl.create_standalone_context(require=430)
    sample_times = np.arange(.20, .751, .05)
    identity = hashlib.sha256((args.root / 'conditional-transport-weights.json').read_bytes()
                              + (args.root / 'particle-scatter-weights.json').read_bytes()).hexdigest()
    report = dict(identity=identity, scope='真实绘制材料的潜在对比权重，未计遮挡；只诊断模型自身，不是原片运动误差', scenes={})
    if (args.out / 'report.json').is_file():
        report = json.loads((args.out / 'report.json').read_text('utf-8'))
        assert report['identity'] == identity, '诊断目录属于另一份模型，不能混合结果'
    for name in args.scenes:
        saved = np.load(args.traces / f'{name}.npz')
        renderer = cls(name, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[name]], **kw)
        assert np.array_equal(saved['source'], renderer.base[:, :2])
        reader = ParticleProxy(renderer)
        background = np.asarray(Image.open(renderer.directory / 'background.png').convert('RGB'), np.float32) / 255.
        values = []
        for phase in sample_times:
            frame = renderer.render(float(phase))
            particles = reader.read()
            xy = particles['pos'].astype(np.float32)
            backdrop = linear(background * (1 - renderer.bg_program['dim'].value))
            back = np.stack([map_coordinates(backdrop[..., k], [xy[:, 1], xy[:, 0]],
                                              order=1, mode='constant', cval=0.) for k in range(3)], -1)
            difference = abs(particles['color'] - back).mean(-1)
            inside = ((xy[:, 0] >= 0) & (xy[:, 0] < renderer.w) & (xy[:, 1] >= 0) & (xy[:, 1] < renderer.h))
            weight = particles['mass'] * difference * inside
            values.append(np.bincount(particles['parent'], weights=weight, minlength=renderer.n))
            # 读取诊断不能改变下一次正常绘制。
            assert np.array_equal(frame, renderer.render(float(phase)))
        values = np.asarray(values, np.float32)
        np.savez_compressed(args.out / f'{name}.npz', phases=sample_times, weights=values, source=renderer.base[:, :2])
        report['scenes'][name] = measure(saved, sample_times, values)
        print(name, [{k: v for k, v in row.items() if not k.endswith('_curve')} for row in report['scenes'][name]], flush=True)
        reader.close(); renderer.close()
        (args.out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    ctx.release()


if __name__ == '__main__':
    main()
