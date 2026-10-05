"""共同输运的可逆耦合实验；各层只由另一坐标及起点几何决定。

每层为 x'=exp(s(y))*x+b(y)，交替处理顺流／横流坐标。连续映射
本身不翻转；最终离散场仍须另外检查。尚未作为默认参数采用。
"""
import math
import numpy as np


def features(u, v, t, extent, sites):
    one = np.ones_like(u)
    values = [u, v, u*u, v*v, u*v, one*t, one*t*t,
              one*extent[0], one*extent[1], one*len(sites)/4]
    a = u[..., None]-sites[:, 0]
    b = v[..., None]-sites[:, 1]
    st = sites[:, 2]+np.zeros_like(a)
    distance = np.sqrt(a*a+b*b+.0001)
    for rate in [2., 6., 16.]:
        cost = distance+st
        weight = np.exp(-rate*(cost-cost.min(-1, keepdims=True)))
        weight /= weight.sum(-1, keepdims=True)
        for f in [a, b, distance, st, a*a, b*b, a*b]:
            values.append((weight*f).sum(-1))
    coordinates = [u, v, one*t, values[24], values[25], values[26], one*t-values[27]]
    for frequency in [2*np.pi, 4*np.pi]:
        for coordinate in coordinates:
            values.extend([np.sin(frequency*coordinate), np.cos(frequency*coordinate)])
    return np.stack(values, -1).astype(np.float32)


def evaluate(f, layers):
    z = f.reshape(-1, f.shape[-1])
    for i, layer in enumerate(layers):
        z = z @ np.asarray(layer['weight'], np.float32).T + np.asarray(layer['bias'], np.float32)
        if i < len(layers)-1:
            z = np.tanh(z)
    return np.tanh(z).reshape(*f.shape[:-1], -1)


def geometry(width, height, direction, sites):
    span = min(width, height)
    theta = math.radians(direction)
    c, s = math.cos(theta), -math.sin(theta)
    rotation = np.array([[c, s], [-s, c]], np.float32)
    source = np.asarray([[(q.x-.5)*width/span, (q.y-.5)*height/span] for q in sites], np.float32)
    origins = np.concatenate([source @ rotation.T, np.asarray([q.start for q in sites], np.float32)[:, None]], -1)
    extent = np.asarray([abs(c)*width/span+abs(s)*height/span,
                         abs(s)*width/span+abs(c)*height/span], np.float32)
    return rotation, origins, extent


def compose(x, y, t, width, height, direction, sites, base, specification):
    rotation, origins, extent = geometry(width, height, direction, sites)
    span = min(width, height)
    q = np.stack([(x-.5)*width/span, (y-.5)*height/span], -1) + base[..., :2]
    uv = q @ rotation.T
    start = uv.copy()
    logdet = np.zeros_like(x)
    for i, layers in enumerate(specification['couplings']):
        axis = i % 2
        fixed = uv[..., 1-axis]
        zero = np.zeros_like(fixed)
        f = features(zero if axis == 0 else fixed, fixed if axis == 0 else zero, t, extent, origins)
        value = evaluate(f, layers)
        scale = value[..., 0]*specification['log_scale']*t
        shift = value[..., 1]*specification['shift_scale']*t
        uv[..., axis] = uv[..., axis]*np.exp(scale)+shift
        logdet += scale
    density = evaluate(features(start[..., 0], start[..., 1], t, extent, origins), specification['density'])
    delta = (uv-start) @ rotation
    # 密度不自动按雅可比反比放大：实际片元面积与重叠已经改变投影覆盖。
    result = base.copy()
    result[..., :2] += delta
    result[..., 2:] += density*specification['density_scale']
    return result
