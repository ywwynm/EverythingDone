"""把共同光学厚度校准分配给面积与透过率，检验不透明颗粒的覆盖饱和。"""
from dataclasses import dataclass
import json
import numpy as np
from surface_transfer import TransferRenderer


@dataclass(frozen=True)
class CoverageRules:
    area: float = 0.
    grain: float = 1.
    count_share: float = 0.
    survival: float = 0.
    footprint: float = 1.


def load_coverage(path):
    data=json.loads(path.read_text('utf-8'))
    return CoverageRules(**data.get('rules',data))


class CoverageRenderer(TransferRenderer):
    vertex_source=TransferRenderer.vertex_source.replace('void main(){','uniform float coverage_area,coverage_count,coverage_footprint,conditional_keep;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+',
        '''float count_shift=min(density_out*coverage_area,max(0.,-log(conditional_keep)))*coverage_count;
    scale*=exp(.5*(density_out*coverage_area-count_shift))*mix(1.,coverage_footprint,smoothstep(.005,.07,age));
    vec2 vertex=move+''',1)
    fragment_source=TransferRenderer.fragment_source.replace('void main(){','uniform float coverage_area,coverage_count,coverage_footprint;\nvoid main(){',1)
    fragment_source=fragment_source.replace('exp(density_out)','exp(density_out*(1.-coverage_area))')
    fragment_source=fragment_source.replace('float retain=step(fract(random_out.x*47.123+random_out.z*19.817),conditional_keep);', '''
    float count_shift=min(density_out*coverage_area,max(0.,-log(conditional_keep)))*coverage_count;
    float keep=clamp(conditional_keep*exp(count_shift),0.,1.);
    float random_keep=fract(random_out.x*47.123+random_out.z*19.817);
    float retain=mix(step(random_keep,conditional_keep),1.-smoothstep(max(0.,keep-.05),min(1.,keep+.05),random_keep),coverage_count);
''')
    fragment_source=fragment_source.replace('if(alpha<.001)discard;', 'alpha/=pow(mix(1.,coverage_footprint,smoothstep(.005,.07,age)),2.);\n    if(alpha<.001)discard;',1)

    def __init__(self,*args,coverage=CoverageRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.coverage_original_life=self.base[:,6].copy();self.coverage_survival=0.
        self.apply_coverage(coverage)

    def apply_coverage(self,rules):
        values=[rules.area,rules.grain,rules.count_share,rules.survival,rules.footprint]
        if not np.isfinite(values).all() or rules.grain<=0 or rules.footprint<=0 or not 0<=rules.count_share<=1 or not 0<=rules.survival<=1:
            raise ValueError('覆盖参数必须有限，颗粒尺度和足迹为正，数量份额与存续修正位于 0 到 1')
        self.coverage_rules=rules;self.program['coverage_area']=rules.area;self.program['coverage_count']=rules.count_share
        self.program['coverage_footprint']=rules.footprint
        g=self.layout_change;r=self.conditional_rules
        grain=1+g*(r.grain*rules.grain-1)
        self.program['conditional_size']=(1+g*(r.size-1))*grain
        self.program['conditional_keep']=min(1.,1./grain**2)
        if rules.survival!=self.coverage_survival:
            self.base[:,6]=self.coverage_original_life+rules.survival*(.985-self.base[:,2]-self.coverage_original_life)
            self.material.write(self.base.tobytes());self.coverage_survival=rules.survival;self.reset()
