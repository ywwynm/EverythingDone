"""当前完整细粒模型的共同输运响应；局部展开与全程几何约束分开采样。

仅离线使用参考图。运行仍读取一份原有三输出共同函数，不加入逐素材控制图。
"""
from pathlib import Path
import argparse, hashlib, json, math, sys, time
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from shared_transport import load_transport, transport_features
from match_compare import Comparison
from fit_particle_optics import NAMES, measure
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

OUT = HERE / 'analysis/match-cache/fine-transport'
N, FIELD, LO, SIZE = 80, 56, -.38, 1.76
PHASES = [.12, .22, .32, .42, .52, .62, .72, .82]
EPS = np.array([.014, .014, .18], np.float32)


class ProbeRenderer(ScatterRenderer):
    vertex_source = ScatterRenderer.vertex_source.replace('void main(){', 'uniform vec3 transport_probe;\nflat out vec2 probe_input;\nvoid main(){', 1)
    vertex_source = vertex_source.replace('vec2 q=(move/card+vec2(.38))/1.76;', 'vec2 q=(move/card+vec2(.38))/1.76;\n    probe_input=q;', 1)
    # 在后续数量、三年龄覆盖和细粒材质之前施加，导数包含相应的重新采样。
    vertex_source = vertex_source.replace('move+=transport.xy*span', 'transport+=transport_probe;\n    move+=transport.xy*span', 1)
    fragment_source = ScatterRenderer.fragment_source.replace('void main(){', 'flat in vec2 probe_input;\nvoid main(){', 1)
    fragment_source = fragment_source.replace('frag=vec4(color*alpha,alpha);', 'if(diagnostic==4)color=vec3(probe_input,1.);\n    frag=vec4(color*alpha,alpha);', 1)

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.program['transport_probe'] = (0., 0., 0.)


def prepare(initial, grain=False):
    files = ['renderer.py', 'unified_model.py', 'coupled_transport.py', 'learned_release.py',
             'conditional_material.py', 'shared_transport.py', 'surface_transfer.py', 'particle_coverage.py',
             'particle_cohorts.py', 'surface_coherence.py', 'particle_fission.py', 'scatter_material.py',
             'conditional-release-weights.json', 'conditional-material-rules.json', 'surface-transfer-rules.json',
             'particle-coverage-rules.json', 'particle-cohort-weights.json', 'surface-coherence-weights.json',
             'particle-scatter-weights.json', 'analysis/fit_fine_transport_response.py']
    key = hashlib.sha256(initial.read_bytes() + str(grain).encode() + b''.join((HERE / p).read_bytes() for p in files)).hexdigest()[:16]
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f'response-{key}.npz'
    if path.exists():
        with np.load(path) as z:
            return {k: z[k] for k in z.files}
    ctx = moderngl.create_standalone_context(require=430)
    _, kw = configuration(HERE)
    kw['transport_weights'] = load_transport(initial)
    yy, xx = np.mgrid[:N, :N].astype(np.float32)
    xx, yy = LO + (xx + .5) / N * SIZE, LO + (yy + .5) / N * SIZE
    fy, fx = np.mgrid[:FIELD, :FIELD].astype(np.float32)
    fx, fy = LO + (fx + .5) / FIELD * SIZE, LO + (fy + .5) / FIELD * SIZE
    data = {k: [] for k in ['features', 'actual', 'target', 'slope', 'curve', 'lookup', 'mask', 'scene', 'phase', 'rotation', 'scale']}
    for ni, name in enumerate(NAMES):
        c = Comparison(name)
        x0, y0, x1, y1 = c.scene.rect
        w, h = x1 - x0, y1 - y0
        sites = [Site(*v) for v in OBSERVED_SITES[name]]
        r = ProbeRenderer(name, ctx=ctx, quality=2, sites=sites, **kw)
        qx, qy = x0 + xx * w, y0 + yy * h
        def sample(f):
            return cv2.remap(f, qx, qy, cv2.INTER_LINEAR)
        def compact(frame):
            f = frame.astype(np.float32) / 255.
            eb = c.scene.excess(frame, c.scene.bgmax, c.scene.bgmin)
            es = c.scene.excess(frame, c.scene.srcmax, c.scene.srcmin)
            soft = lambda v: 1. / (1. + np.exp(np.clip(-v, -25., 25.)))
            cloud = soft((eb - c.scene.threshold) / 8.) * soft((es - 28.) / 8.)
            values = [*[cv2.GaussianBlur(f, (0, 0), s) for s in [2., 5., 12.]],
                      *[cv2.GaussianBlur(cloud, (0, 0), s)[..., None] for s in [6., 16.]]]
            if grain:
                # 位移、数量改变同样会影响颗粒细节，直接测量完整 GPU 响应，
                # 不把细节误差留给后续独立材质优化补救。
                values += [cv2.GaussianBlur(abs(f - cv2.GaussianBlur(f, (0, 0), s)), (0, 0), 8.) for s in [.7, 1.5, 3.]]
            return sample(np.concatenate(values, -1))
        mask = sample(c.scene.roi.astype(np.float32))
        angle = math.radians(r.direction)
        cs, ss = math.cos(angle), -math.sin(angle)
        for requested in PHASES:
            i, ref = c.ref.nearest(requested)
            p = float(c.ref.progress[i])
            r.program['transport_probe'] = (0., 0., 0.)
            actual = compact(r.render(p))
            r.fbo.use(); r.fbo.clear(0, 0, 0, 0)
            r.ctx.enable(moderngl.BLEND); r.ctx.blend_func = (moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
            r.program['material_pass'] = 1; r.program['diagnostic'] = 4
            r.vao.render(mode=moderngl.TRIANGLES, vertices=6, instances=r.n)
            layer = np.frombuffer(r.tex.read(), np.float16).reshape(r.h * 2, r.w * 2, 4)[::-1].astype(np.float32)
            layer = sample(cv2.GaussianBlur(cv2.resize(layer, (r.w, r.h), interpolation=cv2.INTER_AREA), (0, 0), 5.))
            lookup = layer[..., :2] / np.maximum(layer[..., 3:], .0001)
            fallback = np.stack([(xx - LO) / SIZE, (yy - LO) / SIZE], -1)
            lookup = np.where(layer[..., 3:] > .003, lookup, fallback)
            slopes, curves = [], []
            for k, eps in enumerate(EPS):
                images = []
                for sign in [-1, 1]:
                    probe = [0., 0., 0.]; probe[k] = float(eps * sign)
                    r.program['transport_probe'] = probe
                    images.append(compact(r.render(p)))
                slopes.append((images[1] - images[0]) / (2 * eps))
                curves.append((images[1] + images[0] - 2 * actual) / (2 * eps ** 2))
            phase = p + r.layout_change * kw['rules'].clock * 4 * p * (1 - p)
            row = dict(features=transport_features(fx, fy, phase, w, h, r.direction, sites, True),
                       actual=actual, target=compact(ref), slope=np.stack(slopes, -1), curve=np.stack(curves, -1),
                       lookup=lookup, mask=mask, scene=ni, phase=phase, rotation=[[cs, ss], [-ss, cs]],
                       scale=[min(w, h) / w, min(w, h) / h])
            for k, v in row.items(): data[k].append(v)
        r.close()
        assert ctx.error == 'GL_NO_ERROR', name
        print('完整细粒输运响应', name, flush=True)
    ctx.release()
    data = {k: np.asarray(v, np.float32) for k, v in data.items()}
    np.savez_compressed(path, **data)
    print(path, flush=True)
    return data


def review(weights, out, scenes):
    out.mkdir(parents=True, exist_ok=True)
    frozen = out / 'review-weights.json'; frozen.write_bytes(weights.read_bytes())
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = configuration(HERE)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
    report = {}
    for name in scenes:
        c = Comparison(name)
        args = dict(ctx=ctx, quality=2, sites=[Site(*v) for v in OBSERVED_SITES[name]], **kw)
        before = cls(name, **args)
        args['transport_weights'] = load_transport(frozen)
        after = cls(name, **args)
        x, y, x1, y1 = c.scene.rect
        box = (max(0, x - 45), max(0, y - 45), min(c.scene.W, x1 + 45), min(c.scene.H, y1 + 45))
        tw = 320; th = round((box[3] - box[1]) * tw / (box[2] - box[0]))
        sheet = Image.new('RGB', (tw * 5, (th + 24) * 3), (14, 20, 30)); draw = ImageDraw.Draw(sheet)
        rows = []
        for col, requested in enumerate([.18, .29, .41, .58, .77]):
            i, ref = c.ref.nearest(requested); p = float(c.ref.progress[i])
            row = dict(phase=p)
            target = measure(ref, c.scene.roi)
            refcloud, _ = c.scene.blocks(c.scene.observe(ref)['particle'] & c.scene.roi)
            for k, (label, frame) in enumerate([('华为原片', ref), ('当前默认', before.render(p)), ('共同输运候选', after.render(p))]):
                sheet.paste(Image.fromarray(frame).crop(box).resize((tw, th), Image.Resampling.LANCZOS), (col * tw, k * (th + 24) + 24))
                draw.text((col * tw, k * (th + 24)), f'{label} {p:.3f}', font=font, fill='white')
                if k:
                    metrics = {m: float(abs(a - b).mean()) for m, a, b in zip(['rgb_1', 'rgb_4', 'rgb_12', 'grain'], measure(frame, c.scene.roi), target)}
                    cloud, weight = c.scene.blocks(c.scene.observe(frame)['particle'] & c.scene.roi)
                    metrics['cloud_map'] = float(abs(cloud - refcloud)[weight > .5].mean())
                    row['before' if k == 1 else 'after'] = metrics
            rows.append(row)
        before.close(); after.close()
        report[name] = rows; sheet.save(out / f'{name}.jpg', quality=95)
        (out / 'review.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
        print(name, {s: {m: round(float(np.mean([v[s][m] for v in rows])), 4) for m in ['rgb_4', 'grain', 'cloud_map']} for s in ['before', 'after']}, flush=True)
    ctx.release()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--initial', default=str(HERE / 'conditional-transport-weights.json'))
    ap.add_argument('--steps', type=int, default=6000)
    ap.add_argument('--label', default='continuous-topology')
    ap.add_argument('--review'); ap.add_argument('--scenes', nargs='+', default=NAMES)
    ap.add_argument('--cloud', type=float, default=.35)
    ap.add_argument('--grain', action='store_true', help='同时约束实际颗粒细节响应，避免输运改善颜色却破坏细粒')
    ap.add_argument('--prepare-only', action='store_true')
    ap.add_argument('--response', type=Path)
    ap.add_argument('--motion-only', action='store_true', help='固定密度，只校准位置')
    ap.add_argument('--temporal-weight', type=float, default=0.)
    ap.add_argument('--excursion-weight', type=float, default=0., help='直接限制固定材料多余的横向往返，不仅限制加速度')
    ap.add_argument('--excursion-retain',type=float,default=1.,help='保留后置修正新增往返的份额；基础积分自身的卷曲不受这一项惩罚')
    ap.add_argument('--trace-root', type=Path, default=HERE/'analysis/match-cache/motion-continuity/balanced-velocity')
    a = ap.parse_args()
    out = OUT / a.label; out.mkdir(parents=True, exist_ok=True)
    if a.review: return review(Path(a.review), out, a.scenes)
    if a.response:
        with np.load(a.response) as saved: data={k:saved[k] for k in saved.files}
    else: data = prepare(Path(a.initial), a.grain)
    if a.prepare_only: return
    config = json.loads(Path(a.initial).read_text('utf-8'))
    assert not config.get('residual'), '本轮仅细化已有稳定函数'
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4); torch.manual_seed(928)
    def tensor(v): return torch.from_numpy(np.ascontiguousarray(v, np.float32)).cuda()
    f, actual, target, slope, curve, lookup, mask, phase, rotation, scale = [tensor(data[k]) for k in ['features', 'actual', 'target', 'slope', 'curve', 'lookup', 'mask', 'phase', 'rotation', 'scale']]
    widths = [len(v['bias']) for v in config['layers']]
    model = torch.nn.Sequential(torch.nn.Linear(59, widths[0]), torch.nn.Tanh(), torch.nn.Linear(widths[0], widths[1]), torch.nn.Tanh(), torch.nn.Linear(widths[1], 3)).cuda()
    with torch.no_grad():
        for q, saved in zip([q for q in model if isinstance(q, torch.nn.Linear)], config['layers']):
            q.weight.copy_(tensor(saved['weight'])); q.bias.copy_(tensor(saved['bias']))
    import copy
    frozen_model=copy.deepcopy(model)
    for q in frozen_model.parameters():q.requires_grad_(False)
    yi, xi = np.mgrid[:FIELD, :FIELD].astype(np.float32)
    grid = tensor(np.stack([(xi + .5) / FIELD * 2 - 1, (yi + .5) / FIELD * 2 - 1], -1))[None]
    xx, yy = LO + (xi + .5) / FIELD * SIZE, LO + (yi + .5) / FIELD * SIZE
    checks = {k: [] for k in ['features', 'phase', 'rotation', 'scale']}
    for name, values in OBSERVED_SITES.items():
        m = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
        x0, y0, x1, y1 = m['rect']; w, h = x1 - x0, y1 - y0
        sites = [Site(*v) for v in values]; ang = math.radians(m['direction']); cs, ss = math.cos(ang), -math.sin(ang)
        for p in np.linspace(0, 1, 41):
            checks['features'].append(transport_features(xx, yy, p, w, h, m['direction'], sites, True))
            checks['phase'].append(p); checks['rotation'].append([[cs, ss], [-ss, cs]])
            checks['scale'].append([min(w, h) / w, min(w, h) / h])
    gf, gp, gr, gs = [tensor(checks[k]) for k in ['features', 'phase', 'rotation', 'scale']]
    def field(features, p, rot, sc):
        raw = model(features).tanh()
        if a.motion_only:
            raw=torch.cat([raw[...,:2],frozen_model(features).tanh()[...,2:]],-1)
        move = torch.einsum('bxyc,bcd->bxyd', raw[..., :2], rot) * (config['displacement_scale'] * p[:, None, None, None]) * sc[:, None, None, :] / 2 ** config['flow_steps']
        for _ in range(config['flow_steps']):
            move = move + F.grid_sample(move.permute(0, 3, 1, 2), grid + move * 2 / SIZE, padding_mode='border', align_corners=False).permute(0, 2, 3, 1)
        return torch.cat([move / sc[:, None, None, :], raw[..., 2:] * config['density_scale']], -1)
    def det(value, sc):
        v = value[..., :2] * sc[:, None, None, :]
        dx = (v[:, :-1, 1:] - v[:, :-1, :-1]) * FIELD / SIZE
        dy = (v[:, 1:, :-1] - v[:, :-1, :-1]) * FIELD / SIZE
        return (1 + dx[..., 0]) * (1 + dy[..., 1]) - dx[..., 1] * dy[..., 0]
    def sample(v, ids):
        return F.grid_sample(v.permute(0, 3, 1, 2), lookup[ids] * 2 - 1, padding_mode='border', align_corners=False).permute(0, 2, 3, 1)
    with torch.no_grad():
        anchor = torch.cat([field(f[i:i+8], phase[i:i+8], rotation[i:i+8], scale[i:i+8]) for i in range(0, len(f), 8)])
        anchor_sample = sample(anchor, list(range(len(f))))
        ganchor = torch.cat([field(gf[i:i+8], gp[i:i+8], gr[i:i+8], gs[i:i+8]) for i in range(0, len(gf), 8)])
        gdet = det(ganchor, gs)
    paths=[]
    if a.temporal_weight:
        geometry_lookup={(name,phase):i for i,(name,phase) in enumerate((name,k) for name in OBSERVED_SITES for k in range(41))}
        rng=np.random.default_rng(928)
        for path in sorted(a.trace_root.glob('*.npz')):
            saved=np.load(path);tr=saved['trace'];ts=tr[:,0,4]-tr[0,0,4]
            card=np.array(saved['rect'][2:])-saved['rect'][:2];span=float(saved['span'])
            for center in range(6,31,2):
                phases3=np.array([center-2,center,center+2])/40
                ids=[geometry_lookup[(path.stem,round(p*40))] for p in phases3]
                hi=np.clip(np.searchsorted(ts,phases3),1,len(ts)-1);lo=hi-1
                fraction=((phases3-ts[lo])/(ts[hi]-ts[lo]))[:,None,None]
                q=tr[lo]*(1-fraction)+tr[hi]*fraction
                live=((q[:,:,4]>.06)&(q[:,:,4]<q[:,:,5]-.06)&(q[:,:,6]<.6)).all(0)&saved['mask']
                take=np.flatnonzero(live)
                if not len(take):continue
                take=rng.choice(take,min(256,len(take)),replace=False);q=q[:,take]
                sample_path=dict(ids=ids,query=tensor((q[:,:,:2]/card+.38)/1.76),base=tensor(q[:,:,:2]/span),wind=tensor(saved['wind']))
                paths.append(sample_path)
        if not paths:raise ValueError('没有可用的连续材料路径')
    def path_values(sample_path,fields):
        return F.grid_sample(fields.permute(0,3,1,2),sample_path['query'][:,None]*2-1,padding_mode='border',align_corners=False)[:,:2,0].permute(0,2,1)
    with torch.no_grad():
        for q in paths:
            move=path_values(q,ganchor[q['ids']]);q['anchor_move']=move
            q['anchor_acceleration']=torch.linalg.vector_norm((move[2]-2*move[1]+move[0])/.05**2,dim=-1)
    excursions=[]
    if a.excursion_weight:
        rng=np.random.default_rng(929)
        for path in sorted(a.trace_root.glob('*.npz')):
            saved=np.load(path);tr=saved['trace'];ps=saved['phases'];card=np.array(saved['rect'][2:])-saved['rect'][:2]
            span=float(saved['span']);selected=np.flatnonzero((ps>=.30-1e-6)&(ps<=.70+1e-6))
            visible=((tr[selected,:,4]>.04)&(tr[selected,:,4]<tr[selected,:,5]-.055)&(tr[selected,:,6]<.6)).all(0)&saved['mask']
            take=np.flatnonzero(visible)
            if len(take)<16:continue
            take=rng.choice(take,min(len(take),256),replace=False);selected=selected[::3]
            q=tr[selected][:,take];ts=tr[selected,0,4]-tr[0,0,4]
            meta=json.loads((HERE/'assets'/path.stem/'scene.json').read_text('utf-8'));direction=meta['direction']
            sites=[Site(*v) for v in OBSERVED_SITES[path.stem]];cs,ss=math.cos(math.radians(direction)),-math.sin(math.radians(direction))
            sample_path=dict(query=tensor((q[:,:,:2]/card+.38)/1.76),base=tensor(q[:,:,:2]/span),
                wind=tensor(saved['wind']),cross=tensor([-saved['wind'][1],saved['wind'][0]]),
                features=tensor(np.stack([transport_features(xx,yy,float(p),*card,direction,sites,True) for p in ts])),
                phase=tensor(ts),rotation=tensor(np.tile([[cs,ss],[-ss,cs]],(len(ts),1,1))),
                scale=tensor(np.tile(span/card,(len(ts),1))))
            with torch.no_grad():
                move=path_values(sample_path,field(sample_path['features'],sample_path['phase'],sample_path['rotation'],sample_path['scale']))
                lateral=(sample_path['base']+move)@sample_path['cross']
                sample_path['anchor_excess']=abs(lateral[1:]-lateral[:-1]).sum(0)-abs(lateral[-1]-lateral[0])
                base_lateral=sample_path['base']@sample_path['cross']
                base_excess=abs(base_lateral[1:]-base_lateral[:-1]).sum(0)-abs(base_lateral[-1]-base_lateral[0])
                added=(sample_path['anchor_excess']-base_excess).clamp(min=0)
                sample_path['target_excess']=sample_path['anchor_excess']-(1.-a.excursion_retain)*added
            excursions.append(sample_path)
        if not excursions:raise ValueError('没有持续可见的材料，不能约束横向往返')
    def excursion_loss(sample_path):
        move=path_values(sample_path,field(sample_path['features'],sample_path['phase'],sample_path['rotation'],sample_path['scale']))
        lateral=(sample_path['base']+move)@sample_path['cross']
        excess=abs(lateral[1:]-lateral[:-1]).sum(0)-abs(lateral[-1]-lateral[0])
        added=F.relu(excess-sample_path['target_excess']*1.01-.0003)/.015
        return added.square().mean()+.5*added.topk(min(24,len(added))).values.square().mean()
    def continuity(sample_path):
        ids=sample_path['ids'];move=path_values(sample_path,field(gf[ids],gp[ids],gr[ids],gs[ids]))
        delta=move-sample_path['anchor_move'];acceleration=(move[2]-2*move[1]+move[0])/.05**2
        new_acceleration=F.relu(torch.linalg.vector_norm(acceleration,dim=-1)-sample_path['anchor_acceleration']*1.05-.15)
        change=(delta[2]-2*delta[1]+delta[0])/.05**2
        velocity=(sample_path['base'][2]+move[2]-sample_path['base'][0]-move[0])/.1
        return new_acceleration.square().mean()/16.+.1*change.square().sum(-1).mean()/16.+4*F.relu(.025-velocity@sample_path['wind']).square().mean()
    channels = tensor([.6] * 3 + [1.] * 3 + [.6] * 3 + [a.cloud, a.cloud] + ([.65] * 3 + [.9] * 3 + [.65] * 3 if a.grain else []))
    def error(frame, ids):
        return (torch.sqrt((frame - target[ids]).square() + .003 ** 2) * mask[ids, ..., None] * channels).sum((1, 2, 3)) / mask[ids].sum((1, 2)).clamp(min=1) / channels.sum()
    with torch.no_grad(): baseline = error(actual, list(range(len(f))))
    def loss(ids, regular=True):
        current = field(f[ids], phase[ids], rotation[ids], scale[ids])
        delta = sample(current, ids) - anchor_sample[ids]
        estimate = actual[ids] + (slope[ids] * delta[..., None, :] + curve[ids] * delta[..., None, :].square()).sum(-1)
        errors = error(estimate, ids)
        if not regular: return errors
        d = current - anchor[ids]
        spatial = ((d[:, 1:, :, :2] - d[:, :-1, :, :2]) * FIELD / SIZE).square().mean() + ((d[:, :, 1:, :2] - d[:, :, :-1, :2]) * FIELD / SIZE).square().mean()
        trust = F.relu(abs(d[..., :2]) - .025).square().mean() * 500 + F.relu(abs(d[..., 2]) - .25).square().mean() * 5
        # 在所有参考、所有进度另取几何样本；最差局部单独计入，不能被大量空白平均掉。
        gi = torch.randint(len(gf), (4,), device='cuda')
        gvalue = field(gf[gi], gp[gi], gr[gi], gs[gi]); jac = det(gvalue, gs[gi])
        floor = torch.minimum(gdet[gi] * .7, torch.full_like(jac, .18))
        penalty = F.relu(floor - jac).square().flatten(1)
        topology = 20 * penalty.mean() + 10 * penalty.topk(16, dim=1).values.mean()
        excess = (errors / baseline[ids].clamp(min=.008) - 1).relu().mean()
        temporal=a.temporal_weight*continuity(paths[int(torch.randint(len(paths),(1,)).item())]) if paths else 0.
        excursion=a.excursion_weight*excursion_loss(excursions[int(torch.randint(len(excursions),(1,)).item())]) if excursions else 0.
        return (errors / baseline[ids].clamp(min=.008)).mean() + 2 * excess + .1 * spatial + trust + topology + temporal + excursion
    opt = torch.optim.AdamW(model.parameters(), lr=.00008, weight_decay=.0001)
    best, history, start = 1.3, [], time.monotonic()
    for step in range(a.steps):
        for group in opt.param_groups: group['lr'] = .00008 * (.15 + .85 * .5 * (1 + np.cos(np.pi * step / a.steps)))
        ids = torch.randint(len(f), (8,), device='cuda')
        opt.zero_grad(); loss(ids).backward(); torch.nn.utils.clip_grad_norm_(model.parameters(), 2.); opt.step()
        if (step + 1) % 500 == 0:
            with torch.no_grad():
                errors = torch.cat([loss(list(range(i, min(i + 8, len(f)))), False) for i in range(0, len(f), 8)])
                minima = []
                for i in range(0, len(gf), 8): minima.append(float(det(field(gf[i:i+8], gp[i:i+8], gr[i:i+8], gs[i:i+8]), gs[i:i+8]).min()))
            ratios = {name: float(errors[k*8:(k+1)*8].mean() / baseline[k*8:(k+1)*8].mean()) for k, name in enumerate(NAMES)}
            score = float(np.mean(list(ratios.values())) + .3 * max(ratios.values()))
            with torch.no_grad():excursion_score=float(torch.stack([excursion_loss(q) for q in excursions]).mean()) if excursions else 0.
            score+=a.excursion_weight*excursion_score
            row = dict(step=step + 1, score=score, minimum_jacobian=min(minima), ratios=ratios,excursion_cost=excursion_score); history.append(row)
            print('完整细粒输运', step + 1, round(time.monotonic() - start, 1), round(score, 4), '最小雅可比', round(min(minima), 5), {k: round(v, 3) for k, v in ratios.items()}, flush=True)
            if score < best and min(minima) > .025:
                best = score
                result = {k: config[k] for k in ['displacement_scale', 'density_scale', 'flow_steps', 'canonical_gain', 'multiscale_features']}
                result.update(train=NAMES, holdout=[], selection=row, initial=str(a.initial), layers=[dict(weight=q.weight.detach().cpu().tolist(), bias=q.bias.detach().cpu().tolist()) for q in model if isinstance(q, torch.nn.Linear)])
                if a.motion_only:
                    from fit_particle_transport import preserve_density
                    result['layers']=preserve_density(result['layers'],config['layers'])
                result.update(temporal_weight=a.temporal_weight,cloud_weight=a.cloud,motion_only=a.motion_only,excursion_weight=a.excursion_weight,excursion_retain=a.excursion_retain)
                (out / 'candidate.json').write_text(json.dumps(result, ensure_ascii=False), 'utf-8')
            (out / 'history.json').write_text(json.dumps(history, ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
