"""在全段同一时间轴下重训共享释放预测（只作研究候选，结果写到新的配置目录，不改默认配置）。

背景（2026-10-01）：用户裁定所有段共用一套参数。`conditional-release-weights.json` 是在「非标准布局按布局系数加时间轴扭曲
clock −0.062」的前提下训练的，全段去掉扭曲后（变体 `layout-g=1,no-clock`）八段的释放整体偏早 0.02～0.05。
这里保持释放预测的结构不变——标准布局原生场 + 共享时序校准 + 0.65×(网络(起点) − 网络(标准起点)) + 空间修正——
只从现有权重出发小步重训那个以起点为输入的网络：
- 目标：每段在块网格上，目标释放时刻 = 当前预测 + (原片可见释放时刻 − 模型可见释放时刻)，两者都来自 `release_integrity.Probe`
  （原片 `huawei()`，模型 `model_release` 作用于指定变体的渲染帧）；只用两者都有效的块；
- 钢铁侠的起点就是标准起点，残差项恒为零，结构上不受影响；
- 损失 = 各段块上均方误差之和 + λ·‖θ − θ₀‖²（留在原权重附近）。
输出：`analysis/match-cache/configs/<标签>/`，复制根目录全部配置文件，只替换 `conditional-release-weights.json`；
另存训练前后各段的块误差。渲染时用 `<配置目录>::<变体>` 引用。
"""
from pathlib import Path
import argparse, json, shutil, sys, time
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from learned_release import load_weights, features, canonical_sites, spatial_correction, predict
from unified_model import panel_material
from release_integrity import Probe
from handoff_review import render_all, CONFIG_FILES
from handoff_scorecard import model_release
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from PIL import Image

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
N = 64


def scene_data(name, weights, spec, root):
    import math
    pr = Probe(name); To = pr.huawei()
    frames = render_all(name, root, 'refit-src', spec=spec); Tm = model_release(pr, name, frames)
    m = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
    x, y, x1, y1 = m['rect']; w, h = x1 - x, y1 - y
    fg = np.array(Image.open(HERE / 'assets' / name / 'foreground.png').convert('RGBA')); _, panel, _ = panel_material(fg)
    sites = [Site(*s) for s in OBSERVED_SITES[name]]
    aligned = getattr(weights, 'rotation_aligned', False)
    f, native = features(N, N, w, h, m['direction'], m['seed'], sites, panel, aligned)
    fc, _ = features(N, N, w, h, m['direction'], m['seed'], canonical_sites(w, h, m['direction']), panel, aligned)
    clock = np.interp(native, weights.clock_knots, weights.clock_offsets).astype('float32') if getattr(weights, 'clock_knots', None) is not None else 0.
    fixed = native + clock + spatial_correction(N, N, w, h, m['direction'], sites, native, weights)
    current = predict(N, N, w, h, m['direction'], m['seed'], sites, weights, panel)
    # 块中心在 64×64 网格上的采样坐标（与 Probe.analytic 相同的换算）。
    V, U = np.meshgrid(pr.v, pr.u, indexing='ij')
    gx = U * N - .5; gy = V * N - .5
    ok = pr.valid & np.isfinite(To) & np.isfinite(Tm) & (To < .99) & (Tm < .99)
    from scipy.ndimage import map_coordinates
    cur_b = map_coordinates(current.astype('float64'), [gy, gx], order=1, mode='nearest')
    target = np.clip(cur_b + (To - Tm), .002, .84)
    return dict(name=name, f=f.reshape(-1, f.shape[-1]), fc=fc.reshape(-1, fc.shape[-1]), fixed=np.asarray(fixed, np.float32).reshape(N, N),
                gx=gx[ok], gy=gy[ok], target=target[ok], before=float(np.sqrt(np.mean((To - Tm)[ok] ** 2))), n=int(ok.sum()),
                mean_shift=float(np.mean((To - Tm)[ok])))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--spec', required=True, help='计算模型可见释放时刻用的变体（@文件）')
    ap.add_argument('--label', required=True)
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--steps', type=int, default=2500)
    ap.add_argument('--lam', type=float, default=3e-3)
    ap.add_argument('--lr', type=float, default=2e-3)
    ap.add_argument('--root', default='', help='起始配置目录（相对 tuning-0909-gpt6；缺省为根目录默认配置）：从它的释放权重出发、用它渲染')
    a = ap.parse_args()
    root = HERE / a.root if a.root else HERE
    spec = Path(a.spec[1:]).read_text('utf-8').strip() if a.spec.startswith('@') else a.spec
    weights = load_weights(root / 'conditional-release-weights.json')
    data = []
    for name in a.scenes:
        t = time.time(); d = scene_data(name, weights, spec, root); data.append(d)
        print(f'{name:22s} 有效块 {d["n"]:5d}  原片−模型 平均 {d["mean_shift"]:+.3f}  均方根 {d["before"]:.3f}  ({time.time() - t:.0f}s)', flush=True)
    import torch
    torch.manual_seed(7)

    def T(a):
        # 本机 PyTorch 2.3 与 NumPy 2 的二进制接口不兼容（torch.tensor(ndarray) 报错），经字节缓冲转换。
        v = np.ascontiguousarray(a, np.float32)
        return torch.frombuffer(bytearray(v.tobytes()), dtype=torch.float32).reshape(v.shape).clone()
    layers = [(T(wt), T(b)) for wt, b in weights]
    theta0 = [(wt.clone(), b.clone()) for wt, b in layers]
    params = []
    for wt, b in layers:
        wt.requires_grad_(True); b.requires_grad_(True); params += [wt, b]

    def mlp(x):
        for i, (wt, b) in enumerate(layers):
            x = x @ wt.T + b
            if i < len(layers) - 1: x = torch.tanh(x)
        return torch.tanh(x[:, 0])

    tens = []
    for d in data:
        f = T(d['f']); fc = T(d['fc']); fixed = T(d['fixed'])
        gx = T(d['gx']); gy = T(d['gy'])
        # grid_sample 的归一化坐标（align_corners=True：像素中心 0..N-1 ↔ −1..1）。
        grid = torch.stack([gx / (N - 1) * 2 - 1, gy / (N - 1) * 2 - 1], -1).view(1, 1, -1, 2)
        tens.append((f, fc, fixed, grid, T(d['target'])))
    opt = torch.optim.Adam(params, lr=a.lr)

    def evaluate():
        losses = []
        for f, fc, fixed, grid, target in tens:
            field = fixed + .65 * (mlp(f) - mlp(fc)).view(N, N)
            field = torch.clamp(field, .002, .84)
            val = torch.nn.functional.grid_sample(field.view(1, 1, N, N), grid, mode='bilinear', align_corners=True).view(-1)
            losses.append(torch.mean((val - target) ** 2))
        return losses

    for step in range(a.steps):
        opt.zero_grad()
        losses = evaluate()
        reg = sum(torch.sum((wt - w0) ** 2) + torch.sum((b - b0) ** 2) for (wt, b), (w0, b0) in zip(layers, theta0))
        loss = sum(losses) / len(losses) + a.lam * reg
        loss.backward(); opt.step()
        if step % 500 == 0 or step == a.steps - 1:
            print(f'step {step:5d} 块误差均方根 ' + ' '.join(f'{float(l) ** .5:.3f}' for l in losses) + f'  正则 {float(reg):.3f}', flush=True)
    out = HERE / 'analysis/match-cache/configs' / a.label
    out.mkdir(parents=True, exist_ok=True)
    for n in CONFIG_FILES:
        if (root / n).is_file(): shutil.copyfile(root / n, out / n)
    doc = json.loads((root / 'conditional-release-weights.json').read_text('utf-8'))
    doc['layers'] = [dict(weight=wt.detach().tolist(), bias=b.detach().tolist()) for wt, b in layers]
    doc['label'] = a.label
    doc['refit'] = dict(date='2026-10-01', root=a.root or '.', spec=spec, scenes=a.scenes, steps=a.steps, lam=a.lam,
                        note='全段同一时间轴（layout-g=1,no-clock）下，以原片与模型可见释放时刻之差为逐块目标重训起点网络；结构与其余项不变。',
                        before={d['name']: d['before'] for d in data},
                        after={d['name']: float(l) ** .5 for d, l in zip(data, evaluate())})
    (out / 'conditional-release-weights.json').write_text(json.dumps(doc, ensure_ascii=False), 'utf-8')
    print('写入', out)


if __name__ == '__main__':
    main()
