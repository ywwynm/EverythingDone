"""颗粒覆盖的共同诊断：扩大微片足迹并降低单片不透明度，不模糊整幅图像。"""
from dataclasses import dataclass
from particle_coverage import CoverageRenderer


@dataclass(frozen=True)
class FootprintRules:
    spread: float = 1.
    tint: float = 1.


class FootprintRenderer(CoverageRenderer):
    vertex_source=CoverageRenderer.vertex_source.replace('void main(){','uniform float footprint_spread;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+', 'scale*=mix(1.,footprint_spread,smoothstep(.005,.07,age));\n    vec2 vertex=move+',1)
    fragment_source=CoverageRenderer.fragment_source.replace('void main(){','uniform float footprint_spread,footprint_tint;\nvoid main(){',1)
    fragment_source=fragment_source.replace('if(alpha<.001)discard;', 'alpha/=pow(mix(1.,footprint_spread,smoothstep(.005,.07,age)),2.);\n    if(alpha<.001)discard;',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','color=mix(vec3(dot(color,vec3(.2126,.7152,.0722))),color,mix(1.,footprint_tint,smoothstep(.01,.10,age)));\n    frag=vec4(color*alpha,alpha);',1)

    def __init__(self,*args,footprint=FootprintRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.apply_footprint(footprint)

    def apply_footprint(self,rules):
        self.program['footprint_spread']=rules.spread
        self.program['footprint_tint']=rules.tint
