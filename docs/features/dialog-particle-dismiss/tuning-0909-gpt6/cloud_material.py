"""共同颗粒存续实验：分离数量、面积和寿命，避免用透明度代替缺失颗粒。"""
from dataclasses import dataclass
import numpy as np
from surface_transfer import TransferRenderer


@dataclass(frozen=True)
class CloudRules:
    life: float = 1.
    size: float = 1.
    count: float = 1.
    late_size: float = 1.
    tonal_power: float = 1.
    chroma: float = 1.
    survival: float = 0.


class CloudRenderer(TransferRenderer):
    vertex_source=TransferRenderer.vertex_source.replace('void main(){',
        'uniform float cloud_size,cloud_late_size;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+',
        'scale*=mix(1.,cloud_size,smoothstep(.008,.055,age))*mix(1.,cloud_late_size,smoothstep(.12,.36,age));\n    vec2 vertex=move+',1)
    fragment_source=TransferRenderer.fragment_source.replace('void main(){',
        'uniform float cloud_tonal_power,cloud_chroma;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);',
        '''float material_phase=smoothstep(.025,.12,age);
    vec3 illuminated=pow(max(color,vec3(.00001)),vec3(cloud_tonal_power));
    float luminance=dot(illuminated,vec3(.2126,.7152,.0722));
    illuminated=mix(vec3(luminance),illuminated,cloud_chroma);
    color=mix(color,illuminated,material_phase);
    frag=vec4(color*alpha,alpha);''',1)

    def __init__(self,*args,cloud=CloudRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.cloud_original=self.base.copy()
        self.apply_cloud(cloud)

    def apply_cloud(self,rules):
        self.cloud_rules=rules
        self.base[:]=self.cloud_original
        g=self.layout_change
        self.base[:,6]=np.minimum(self.base[:,6]*(1+g*(rules.life-1)),.995-self.base[:,2])
        self.base[:,6]+=g*rules.survival*(.985-self.base[:,2]-self.base[:,6])
        self.material.write(self.base.tobytes())
        self.program['cloud_size']=1+g*(rules.size-1)
        self.program['cloud_late_size']=1+g*(rules.late_size-1)
        self.program['cloud_tonal_power']=1+g*(rules.tonal_power-1)
        self.program['cloud_chroma']=1+g*(rules.chroma-1)
        self.program['conditional_keep']=min(1.,(1+g*(rules.count-1))/(1+g*(self.conditional_rules.grain-1))**2)
        self.reset()
