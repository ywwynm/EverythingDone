"""核对共同速度积分的单位、实现一致性与采样范围内的位置映射。

正雅可比仅约束共同修正场；不能代替完整粒子动画的视觉验收。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from shared_transport import load_transport, transport_features, correction, TransportWeights
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--weights', default=str(HERE / 'conditional-transport-weights.json'))
    parser.add_argument('--out', default=str(HERE / 'analysis/match-cache/shared-checks/integrated-transport.json'))
    args = parser.parse_args()
    weights = load_transport(Path(args.weights))
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4)

    def tensor(value):
        v = np.ascontiguousarray(value, np.float32)
        return torch.frombuffer(bytearray(v.tobytes()), dtype=torch.float32).reshape(v.shape)

    n = 56
    yi, xi = np.mgrid[:n, :n].astype('float32')
    xx, yy = -.38 + (xi + .5) / n * 1.76, -.38 + (yi + .5) / n * 1.76
    grid = tensor(np.stack([(xi + .5) / n * 2 - 1, (yi + .5) / n * 2 - 1], -1))[None]
    def torch_field(wt,p,w,h,direction,sites,scale):
        raw=tensor(transport_features(xx,yy,p,w,h,direction,sites,wt.multiscale_features))
        for i,(mat,bias) in enumerate(wt):
            raw=raw@tensor(mat).T+tensor(bias)
            if i<len(wt)-1:raw=raw.tanh()
        if getattr(wt,'motion_time_basis',None)=='affine_velocity':
            ends=[]
            for endpoint in [0.,1.]:
                value=tensor(transport_features(xx,yy,endpoint,w,h,direction,sites,wt.multiscale_features))
                for i,(mat,bias) in enumerate(wt):
                    value=value@tensor(mat).T+tensor(bias)
                    if i<len(wt)-1:value=value.tanh()
                ends.append(value[...,:2])
            raw=torch.cat([ends[0]*(1.-p)+ends[1]*p,raw[...,2:]],-1)
        raw=raw.tanh();angle=math.radians(direction);c,s=math.cos(angle),-math.sin(angle)
        move=raw[...,:2]@tensor([[c,s],[-s,c]])*(wt.displacement_scale*p)*tensor(scale)
        move=move[None]/2**wt.flow_steps
        for _ in range(wt.flow_steps):
            move=move+F.grid_sample(move.permute(0,3,1,2),grid+move*2/1.76,padding_mode='border',align_corners=False).permute(0,2,3,1)
        value=torch.cat([move[0]/tensor(scale),raw[...,2:]*wt.density_scale],-1)
        if wt.residual is not None:
            r=wt.residual;rw=TransportWeights((np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in r['layers'])
            for key in ['displacement_scale','density_scale','flow_steps','multiscale_features']:setattr(rw,key,r[key])
            extra=torch_field(rw,p,w,h,direction,sites,scale)
            shift=grid+move*2/1.76
            value=value+F.grid_sample(extra.permute(2,0,1)[None],shift,padding_mode='border',align_corners=False)[0].permute(1,2,0)
        if getattr(wt,'coupling',None) is not None:
            from fit_coupling_transport import make_model
            from coupling_transport import geometry
            spec=wt.coupling;model=make_model(len(spec['couplings'][0][0]['bias']),len(spec['couplings']))
            model.restore(spec)
            rotation,origins,extent=geometry(w,h,direction,sites)
            uv=(torch.stack([(tensor(xx)-.5)*w/min(w,h),(tensor(yy)-.5)*h/min(w,h)],-1)+value[...,:2])@tensor(rotation).T
            with torch.no_grad():
                move,density,_=model(uv[None],tensor([p]),tensor(extent)[None],tensor(origins)[None],torch.ones((1,len(sites))))
            value=value+torch.cat([move[0]@tensor(rotation),density[0]],-1)
        return value
    results = {}
    for name, origins in OBSERVED_SITES.items():
        meta = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
        x, y, x1, y1 = meta['rect']
        w, h = x1 - x, y1 - y
        scale = np.array([min(w, h) / w, min(w, h) / h], np.float32)
        sites = [Site(*s) for s in origins]
        values, min_det, permutation, agreement = [], 1., 0., 0.
        for p in np.linspace(0, 1, 21):
            field = correction(xx, yy, p, w, h, meta['direction'], sites, weights)
            values.append(field)
            permutation = max(permutation, float(np.max(np.abs(field - correction(xx, yy, p, w, h, meta['direction'], sites[::-1], weights)))))
            displacement = field[..., :2] * scale
            dx = np.diff(displacement, axis=1)[:-1] * n / 1.76
            dy = np.diff(displacement, axis=0)[:, :-1] * n / 1.76
            det = (1 + dx[..., 0]) * (1 + dy[..., 1]) - dx[..., 1] * dy[..., 0]
            min_det = min(min_det, float(det.min()))
            if abs(p - .5) < .001:
                actual=np.array(torch_field(weights,p,w,h,meta['direction'],sites,scale).tolist(),np.float32)
                agreement = float(np.max(np.abs(actual - field)))
        values = np.asarray(values)
        results[name] = dict(minimum_jacobian=min_det, permutation_max_error=permutation,
                             torch_numpy_max_error=agreement, finite=bool(np.isfinite(values).all()),
                             maximum_displacement_short_side=float(np.linalg.norm(values[..., :2], axis=-1).max()))
        assert min_det > 0, (name, min_det)
        assert permutation < 1e-4, (name, permutation)
        assert agreement < 2e-5, (name, agreement)
        assert np.isfinite(values).all(), name
        print(name, results[name], flush=True)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(dict(scope='仅共同修正场的数值检查，不是全动画视觉合格证', scenes=results), ensure_ascii=False, indent=2), 'utf-8')


if __name__ == '__main__':
    main()
