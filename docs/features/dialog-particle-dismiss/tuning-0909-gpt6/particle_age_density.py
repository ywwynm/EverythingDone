"""按出生年龄与源材料亮度分解覆盖响应的离线诊断，不读取参考或素材身份。"""
from particle_coverage import CoverageRenderer
import numpy as np


class AgeDensityRenderer(CoverageRenderer):
    vertex_source=CoverageRenderer.vertex_source.replace('void main(){','uniform sampler2D foreground;\nuniform float age_density[9];\nvoid main(){',1)
    vertex_source=vertex_source.replace('density_out=transport.z*smoothstep(0.,.03,age);', '''
    density_out=transport.z*smoothstep(0.,.03,age);
    vec3 source_tone=texture(foreground,m.src.xy/card).rgb;
    source_tone=mix(source_tone/12.92,pow((source_tone+.055)/1.055,vec3(2.4)),step(vec3(.04045),source_tone));
    float luma=dot(source_tone,vec3(.2126,.7152,.0722));
    float dark=1.-smoothstep(.015,.10,luma),bright=smoothstep(.25,.80,luma);
    vec3 tones=vec3(dark,1.-dark-bright,bright);
    float mature=smoothstep(.06,.16,age),elder=smoothstep(.22,.38,age);
    vec3 ages=vec3(1.-mature,mature*(1.-elder),mature*elder)*smoothstep(.005,.035,age);
    for(int k=0;k<3;k++)for(int j=0;j<3;j++)density_out+=ages[k]*tones[j]*age_density[k*3+j];
''',1)

    def __init__(self,*args,age_density=None,**kwargs):
        super().__init__(*args,**kwargs)
        self.apply_age_density(np.zeros(9) if age_density is None else age_density)

    def apply_age_density(self,coefficients):
        self.program['age_density'].write(np.asarray(coefficients,np.float32).tobytes())
