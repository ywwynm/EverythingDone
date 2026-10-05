"""共同颗粒光学诊断：按材料亮度和颗粒年龄校准，不接受素材身份。"""
from dataclasses import dataclass
import numpy as np
from particle_cohorts import CohortRenderer


@dataclass(frozen=True)
class OpticsRules:
    # 三个年龄段 × 三个源亮度段的中性漫反射校准，随后三个值调整色度。
    coefficients: tuple = (0.,) * 12
    size: float = 1.
    count: float = 1.
    opacity: float = 1.


class OpticsRenderer(CohortRenderer):
    vertex_source = CohortRenderer.vertex_source.replace('void main(){',
        'uniform float optics_size;\nvoid main(){', 1)
    vertex_source = vertex_source.replace('vec2 vertex=move+',
        'scale*=mix(1.,optics_size,smoothstep(.008,.055,age));\n    vec2 vertex=move+', 1)
    fragment_source = CohortRenderer.fragment_source.replace('void main(){',
        'uniform float optics_coeff[12];\nuniform float optics_opacity;\nvoid main(){', 1)
    fragment_source = fragment_source.replace('if(alpha<.001)discard;',
        'if(optics_opacity!=1.)alpha=1.-pow(max(1.-alpha,.0001),optics_opacity);\n    if(alpha<.001)discard;', 1)
    fragment_source = fragment_source.replace('frag=vec4(color*alpha,alpha);', '''
    float source_luma=dot(linear(src.rgb),vec3(.2126,.7152,.0722));
    float dark=1.-smoothstep(.015,.10,source_luma);
    float bright=smoothstep(.25,.80,source_luma);
    vec3 tones=vec3(dark,1.-dark-bright,bright);
    float mature=smoothstep(.06,.22,age);
    float elder=smoothstep(.25,.50,age);
    vec3 ages=vec3(1.-mature,mature*(1.-elder),mature*elder)*smoothstep(.01,.07,age);
    float neutral=0.;float saturation=0.;
    for(int k=0;k<3;k++){
        for(int j=0;j<3;j++)neutral+=ages[k]*tones[j]*optics_coeff[k*3+j];
        saturation+=ages[k]*optics_coeff[9+k];
    }
    float luma=dot(color,vec3(.2126,.7152,.0722));
    color=max(vec3(0.),color+neutral+saturation*(color-luma));
    frag=vec4(color*alpha,alpha);''', 1)

    def __init__(self,*args,optics=OpticsRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.apply_optics(optics)

    def apply_optics(self,rules):
        self.optics_rules=rules
        self.program['optics_coeff'].write(np.asarray(rules.coefficients,np.float32).tobytes())
        self.program['optics_size']=rules.size
        self.program['optics_opacity']=rules.opacity
        self.apply_coverage(self.coverage_rules)
        grain=1+self.layout_change*(self.conditional_rules.grain*self.coverage_rules.grain-1)
        self.program['conditional_keep']=min(1.,rules.count/grain**2)
