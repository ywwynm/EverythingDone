"""固定材料随机数的覆盖分配实验，减少半透明细粒的灰色平均覆盖。"""
from scatter_material import ScatterRenderer


class OpacityRenderer(ScatterRenderer):
    fragment_source=ScatterRenderer.fragment_source.replace('void main(){','uniform float opacity_distribution;\nvoid main(){',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);',r'''
    float probability=clamp(alpha/max(shape,.0001),0.,1.);
    float sample_id=fract(random_out.x*29.817+random_out.y*43.131+random_out.w*17.413);
    float realized=1.-smoothstep(probability-.075,probability+.075,sample_id);
    alpha=mix(alpha,shape*realized,opacity_distribution*smoothstep(.02,.09,age));
    frag=vec4(color*alpha,alpha);''',1)
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw);self.program['opacity_distribution']=0.
