"""颗粒绘制核消融：保持运动、释放与源颜色，检查微片内部的覆盖分布。"""
from dataclasses import dataclass
import math
from particle_cohorts import CohortRenderer


@dataclass(frozen=True)
class KernelRules:
    amount: float = 0.
    support: float = 1.5
    sigma: float = .20
    mass: float = .66


class KernelRenderer(CohortRenderer):
    vertex_source=CohortRenderer.vertex_source.replace('void main(){',
        'uniform float kernel_amount,kernel_support;\nflat out float kernel_scale;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+', '''
    kernel_scale=mix(1.,kernel_support,kernel_amount*smoothstep(.012,.085,age));
    scale*=kernel_scale;
    vec2 vertex=move+''',1)
    fragment_source=CohortRenderer.fragment_source.replace('void main(){',
        'uniform float kernel_amount,kernel_sigma,kernel_peak;\nflat in float kernel_scale;\nvoid main(){',1)
    fragment_source=fragment_source.replace('float shape=mix(1.,cut,shape_out*.92);', '''
    float original_radial=radial*kernel_scale;
    float original_cut=1.-smoothstep(.35,.58,original_radial);
    float original_box=step(max(abs(q.x),abs(q.y))*kernel_scale,.5);
    float original=mix(1.,original_cut,shape_out*.92)*original_box;
    float gaussian=kernel_peak*exp(-.5*radial*radial/(kernel_sigma*kernel_sigma));
    float shape=mix(original,gaussian,kernel_amount*smoothstep(.012,.085,age));
''',1)

    def __init__(self,*args,kernel=KernelRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.apply_kernel(kernel)

    def apply_kernel(self,rules):
        if not 0<=rules.amount<=1 or rules.support<1 or rules.sigma<=0 or rules.mass<=0:
            raise ValueError('绘制核参数不合法')
        self.kernel_rules=rules
        self.program['kernel_amount']=rules.amount
        self.program['kernel_support']=rules.support
        self.program['kernel_sigma']=rules.sigma
        self.program['kernel_peak']=rules.mass/(2*math.pi*(rules.sigma*rules.support)**2)
