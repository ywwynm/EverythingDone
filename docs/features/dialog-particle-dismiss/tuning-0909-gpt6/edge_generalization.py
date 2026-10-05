"""按起点布局差异放开边缘展开与存续的共同规则（桌面研究候选）。

原模型的几处边缘处理是围绕标准布局（原版钢铁侠）建立的：尾缘颗粒丢弃、自由边界朝外剥离收减、
沿 -n 的出生剥离。起点在内部或其它边缘时，这些处理会把最后释放、应当越出原边界的材料削掉。
本模块只用已有的布局差异系数 g（标准布局为 0）连续调节它们，因此原版钢铁侠逐像素不变；
所有布局共用同一组规则，不读取素材身份。
"""
from dataclasses import dataclass, asdict
import json
import numpy as np
from scatter_material import ScatterRenderer
from pressure_grid import SPLAT


@dataclass(frozen=True)
class EdgeRules:
    trailing: float = 0.      # g=1 时尾缘丢弃与交接提前被取消的比例
    edge: float = 0.          # g=1 时自由边界朝外剥离收减被取消的比例
    peel: float = 1.          # g=1 时出生剥离的幅度倍率（≥0）
    flip: float = 0.          # g=1 时剥离方向由 −n 转向 +n 的比例；先转方向，再做同样的逆风剔除
    peel_tau: float = .16     # g=1 时出生剥离的衰减时间
    life: float = 1.          # g=1 时寿命倍率（不越过动画终点）
    early: float = -1.        # g=1 时早释放材料的寿命系数；负值表示沿用共享规则 life_early_gain


def load_edge(path):
    d = json.loads(path.read_text('utf-8'))
    return EdgeRules(**d.get('rules', d))


def _replace(source, old, new):
    assert old in source, old
    return source.replace(old, new, 1)


class EdgeMixin:
    def __init__(self, *args, edge=EdgeRules(), **kwargs):
        super().__init__(*args, **kwargs)
        self.edge_original_life = self.base[:, 6].copy()
        self.apply_edge(edge)

    def apply_edge(self, rules):
        values = np.array([rules.trailing, rules.edge, rules.peel, rules.flip, rules.peel_tau, rules.life], np.float64)
        if not np.isfinite(values).all() or not 0 <= rules.trailing <= 1 or not 0 <= rules.edge <= 1 \
                or not 0 <= rules.flip <= 1 or rules.peel < 0 or rules.peel_tau <= 0 or rules.life <= 0:
            raise ValueError('边缘规则必须有限；比例位于 0～1，剥离幅度非负，衰减时间与寿命倍率为正')
        self.edge_rules = rules; g = float(self.layout_change)
        suppress = .95 * (1. - g * rules.edge)
        # 数值上等于不收减或不转向时仍保留极小正值，避免编译器删去 uniform 后设值失败。
        values = dict(edge_peel=max(1. + g * (rules.peel - 1.), 1e-9), edge_peel_tau=.16 + g * (rules.peel_tau - .16),
                      edge_suppress=max(suppress, 1e-9), edge_flip=max(g * rules.flip, 1e-9))
        for key, value in values.items():
            self.compute[key] = value
            self.pressure_grid.splat[key] = value
        self.program['edge_trailing'] = max(1. - g * rules.trailing, 1e-9)
        life = self.edge_original_life * (1. + g * (rules.life - 1.))
        if rules.early >= 0:
            # 早释放材料的寿命系数随出生时刻连续过渡到晚释放系数；只替换早期一端，按 g 加权。
            from unified_model import RULES, smooth
            born = self.base[:, 2]; early = RULES['life_early_gain']; late = RULES['life_gain']
            s = smooth((born - RULES['life_birth_start']) / RULES['life_birth_span'])
            target = early + g * (rules.early - early)
            life = life * (target + (late - target) * s) / (early + (late - early) * s)
        self.base[:, 6] = np.minimum(life, np.maximum(.995 - self.base[:, 2], self.edge_original_life))
        self.material.write(self.base.tobytes()); self.reset()

    def describe(self):
        return dict(asdict(self.edge_rules), layout_change=float(self.layout_change))


def with_edge(base):
    """在任一完整继承链渲染类上叠加边缘规则；着色器仍按字符串替换组合。"""
    compute = base.compute_source.replace('void main(){', 'uniform float edge_peel,edge_peel_tau,edge_suppress,edge_flip;\nvoid main(){', 1)
    compute = _replace(compute, 'vec2 peel=-m.physical.xy;', 'vec2 peel=-m.physical.xy*(1.-2.*edge_flip);')
    compute = _replace(compute,
        'peel*=1.-.95*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18));',
        'peel*=1.-edge_suppress*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18));')
    compute = _replace(compute,
        'target+=(peel*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age)+sample_peel(p))*span;',
        'target+=(peel*edge_peel*1.2141309*(.10+1.80*peel_response)*exp(-age/edge_peel_tau)*smoothstep(.003,.028,age)+sample_peel(p))*span;')
    splat = getattr(base, 'pressure_splat_source', SPLAT).replace('void main(){', 'uniform float edge_peel,edge_peel_tau,edge_suppress,edge_flip;\nvoid main(){', 1)
    splat = _replace(splat, 'vec2 peel=-m.physical.xy;', 'vec2 peel=-m.physical.xy*(1.-2.*edge_flip);')
    splat = _replace(splat, 'peel*=1.-.95*eject*exp(-min(edge.x,edge.y)/(span*.18));',
                     'peel*=1.-edge_suppress*eject*exp(-min(edge.x,edge.y)/(span*.18));')
    splat = _replace(splat, 'float strength=1.2141309*(.10+1.80*response);', 'float strength=edge_peel*1.2141309*(.10+1.80*response);')
    splat = _replace(splat, 'peel*=exp(-age/.16)*smoothstep(.003,.028,age);', 'peel*=exp(-age/edge_peel_tau)*smoothstep(.003,.028,age);')
    vertex = base.vertex_source.replace('void main(){', 'uniform float edge_trailing;\nvoid main(){', 1)
    vertex = _replace(vertex, 'trailing_out=trailing;source_clock-=.065*trailing;',
                      'trailing_out=trailing*edge_trailing;source_clock-=.065*trailing*edge_trailing;')
    return type('Edge' + base.__name__, (EdgeMixin, base),
                dict(compute_source=compute, pressure_splat_source=splat, vertex_source=vertex))


EdgeRenderer = with_edge(ScatterRenderer)
