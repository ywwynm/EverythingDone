"""桌面材质实验：减少实例、保留源色，以几何缩小完成退场。

沿用多起点候选的释放与运动。仅作为可选研究模型，尚未通过全序列视觉验收。
"""
import numpy as np
from desktop_candidate import DesktopCandidate


def replace_once(source, before, after):
    if source.count(before) != 1:
        raise ValueError('基础着色器已经变化，须重新核对材质扩展')
    return source.replace(before, after, 1)


VERTEX = DesktopCandidate.vertex_source
for before, after in [
    ('flat out float surface_out;', 'flat out float surface_out;\nflat out vec2 particle_uv;'),
    ('uv=source/card;', 'uv=source/card;particle_uv=m.src.xy/card;'),
    ('exp(0.4000000*sqrt(-2.*log(max(m.random.y,.004)))*cos(m.random.z*6.28318)-0.1600000)',
     'exp(0.1800000*sqrt(-2.*log(max(m.random.y,.004)))*cos(m.random.z*6.28318)-0.0324000)'),
    ('scale*=1.-0.2500000*smoothstep(.12,.36,age);', 'scale*=1.-.10*smoothstep(.12,.36,age);'),
    ('vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);', '''
    // 覆盖在生命周期末段连续变小，避免大片低透明度残留。
    float death_scale=sqrt(1.-smoothstep(max(m.physical.z-.12,m.physical.z*.65),m.physical.z,age));
    scale*=death_scale;
    vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);'''),
]:
    VERTEX = replace_once(VERTEX, before, after)

FRAGMENT = r'''#version 430
uniform sampler2D foreground;
uniform float light_gain;
uniform int diagnostic,material_pass;
in vec2 uv,local_uv;
in float age_out,life_out,light_out,shape_out;
flat in float surface_out,trailing_out,replica_out;
flat in vec4 random_out;
flat in vec2 particle_uv;
out vec4 frag;
vec3 linear(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
void main(){
    vec4 src=texture(foreground,material_pass==0?uv:particle_uv);
    if(src.a<.001)discard;
    if(replica_out>.5 && (material_pass==0 || age_out<=.001))discard;
    if(material_pass==0){
        float alpha=src.a*surface_out;
        if(alpha<.001||diagnostic==2)discard;
        vec3 color=diagnostic==1?vec3(.05,.45,.95):linear(src.rgb);
        frag=vec4(color*alpha,alpha);return;
    }
    if(age_out<=0.||age_out>=life_out||diagnostic==3)discard;
    vec2 q=local_uv-.5;
    float radius=length(q*vec2(.85+random_out.z*.35,.87+random_out.w*.34));
    float cut=1.-smoothstep(.42,.53,radius);
    float alpha=src.a*mix(1.,cut,shape_out)*(1.-surface_out)*smoothstep(.001,.012,age_out);
    if(replica_out>.5)alpha*=smoothstep(.008,.055,age_out);
    if(fract(random_out.y*13.731+random_out.z*17.371)<.64*trailing_out)discard;
    if(alpha<.001)discard;
    // 每片取固定材料位置的颜色；出生后只有一次温和的明暗过渡。
    vec3 color=linear(src.rgb);
    float young=smoothstep(.004,.03,age_out)*(1.-smoothstep(.08,.20,age_out));
    color=color*(1.+.12*young*light_gain)+vec3(.012+.020*young)*light_gain;
    if(diagnostic==1)color=vec3(1.,.20,.07);
    frag=vec4(color*alpha,alpha);
}
'''


class MaterialCandidate(DesktopCandidate):
    vertex_source = VERTEX
    fragment_source = FRAGMENT

    def __init__(self, *args, **kwargs):
        kwargs.setdefault('cell_px', 3.3)
        super().__init__(*args, **kwargs)

    def material_builder(self, *args, **kwargs):
        built = super().material_builder(*args, **kwargs)
        base = built['base']
        random = base[:, 8:]
        # 寿命不再使用有大量短命实例的指数分布；全局结束仍连续错开。
        life = .50 + .26*(random[:, 0]-.5)
        base[:, 6] = np.maximum(np.minimum(life, .80+.16*random[:, 2]-base[:, 2]), .07)
        return built
