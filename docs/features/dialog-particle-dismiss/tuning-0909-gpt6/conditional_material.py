"""共同条件场候选：标准布局保留原场，其余布局使用同一个条件函数。

仍是桌面研究候选。权重与规则全素材共用，不读取逐素材输出或参考帧。
"""
from dataclasses import dataclass
from pathlib import Path
import numpy as np
from coupled_transport import CoupledRenderer,CoupledRules
from learned_release import predict,load_weights,canonical_sites
from unified_model import panel_material,release_components,refine_release
from renderer import VERTEX,FRAGMENT


@dataclass(frozen=True)
class ConditionalRules:
    size: float=1.6
    life: float=1.4
    light: float=1.
    motion: float=1.
    wind: float=1.
    clock: float=0.
    grain: float=1.
    lift: float=0.
    curl_balance: float=0.


class ConditionalRenderer(CoupledRenderer):
    compute_source=CoupledRenderer.compute_source.replace('void main(){','uniform float conditional_motion,conditional_wind,conditional_curl;\nvoid main(){',1)
    compute_source=compute_source.replace('vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach);','vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach)*conditional_motion;')
    compute_source=compute_source.replace('carried*wind_gain;','carried*wind_gain*conditional_wind;')
    compute_source=compute_source.replace('vec2 flow=curl(q,time)*span*curl_gain;',
        'vec2 flow=curl(q,time)*span*curl_gain*conditional_curl;',1)
    compute_source=compute_source.replace('target+=curl(p+transport_random*span*.4,time)*span*.075',
        'target+=curl(p+transport_random*span*.4,time)*span*.075*conditional_curl',1)
    vertex_source=VERTEX.replace('void main(){','uniform float conditional_size;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 vertex=move+', 'scale*=mix(1.,conditional_size,smoothstep(.008,.055,age));\n    vec2 vertex=move+',1)
    fragment_source=FRAGMENT.replace('void main(){','uniform float conditional_light,conditional_keep,conditional_lift;\nvoid main(){',1)
    fragment_source=fragment_source.replace('if(alpha<.001)discard;',r'''
    float retain=step(fract(random_out.x*47.123+random_out.z*19.817),conditional_keep);
    alpha*=mix(1.,retain,smoothstep(.008,.060,age));
    if(alpha<.001)discard;''',1)
    fragment_source=fragment_source.replace('frag=vec4(color*alpha,alpha);','color*=mix(1.,conditional_light,smoothstep(.01,.08,age));\n    color+=vec3(conditional_lift)*smoothstep(.01,.08,age);\n    frag=vec4(color*alpha,alpha);',1)

    def __init__(self,*args,sites=None,weights,rules=ConditionalRules(),**kwargs):
        self.origin_sites=tuple(sites) if sites is not None else None;self.weights=weights;self.conditional_rules=rules;self.layout_change=0.
        def release(nx,ny,w,h,direction,seed,fg):
            _,panel,_=panel_material(fg)
            sites=self.origin_sites if self.origin_sites is not None else canonical_sites(w,h,direction)
            result=predict(nx,ny,w,h,direction,seed,sites,self.weights,panel)
            native,_=release_components(nx,ny,direction,w,h,seed);native=refine_release(native,w,h,direction,panel)
            if getattr(self.weights,'clock_knots',None) is not None:
                # 共同的时序校准不应被误认为起点布局改变，否则会连带切换材料与速度。
                native=np.clip(native+np.interp(native,self.weights.clock_knots,self.weights.clock_offsets),.002,.84)
            # 以场的实际改变量度量条件距离；不根据素材标签选择外观。
            layout_result=result
            if getattr(self.weights,'spatial_coefficients',None) is not None:
                # 传播校准与原来的布局距离分开，避免改变释放时刻时顺带改动速度、大小和明暗。
                layout_result=predict(nx,ny,w,h,direction,seed,sites,self.weights,panel,spatial=False)
            error=float(np.sqrt(np.mean((layout_result-native)**2)))
            self.layout_change=1.-np.exp(-(error/.085)**2)
            return result
        super().__init__(*args,release=release,rules=CoupledRules(),**kwargs)
        self.apply_rules(rules)

    def apply_rules(self,rules):
        self.conditional_rules=rules
        g=self.layout_change;r=rules
        self.compute['conditional_motion']=1+g*(r.motion-1)
        self.compute['conditional_wind']=1+g*(r.wind-1)
        # 引导随布局减弱时，附加卷动也按同一几何系数缩放，避免其相对占比增大。
        # 0 保持既有模型，1 保持引导与卷动的原比例；不改变随机相位和释放时序。
        if not np.isfinite(r.curl_balance) or not 0.<=r.curl_balance<=1.:
            raise ValueError('curl_balance 必须为 0～1 的有限值')
        self.compute['conditional_curl']=1+g*r.curl_balance*(r.motion-1)
        self.program['conditional_size']=(1+g*(r.size-1))*(1+g*(r.grain-1))
        self.program['conditional_keep']=1./((1+g*(r.grain-1))**2)
        self.program['conditional_light']=1+g*(r.light-1)
        self.program['conditional_lift']=g*r.lift
        new_life=np.minimum(self.original_life*r.life,.985-self.base[:,2])
        self.base[:,6]=self.original_life+g*(new_life-self.original_life)
        self.material.write(self.base.tobytes());self.reset()

    def material_builder(self,*args,**kwargs):
        b=super().material_builder(*args,**kwargs)
        q=b['base'];self.original_life=q[:,6].copy()
        new_life=np.minimum(q[:,6]*self.conditional_rules.life,.985-q[:,2])
        q[:,6]+=self.layout_change*(new_life-q[:,6])
        return b

    def render(self,time,diagnostic=0):
        p=float(np.clip(time,0,1.05))
        if p<=1.:p+=self.layout_change*self.conditional_rules.clock*4*p*(1-p)
        return super().render(p,diagnostic)
