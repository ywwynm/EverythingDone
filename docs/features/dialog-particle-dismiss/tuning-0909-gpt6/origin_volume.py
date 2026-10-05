"""起点附近的开放边界与浅层深度实验；所有素材使用同一组参数。

只改变已释放材料的运动。源控件仍按原位绘制；不额外生成粒子团，
不把屏幕或控件边缘当作碰撞边界。该模块目前仅供桌面验证。
"""
from dataclasses import dataclass
import numpy as np
from shared_transport import SharedTransportRenderer
from pressure_grid import SPLAT
from learned_release import canonical_sites


@dataclass(frozen=True)
class OriginVolumeRules:
    edge_attenuation: float = 1.
    depth: float = 0.
    radius: float = .24
    focal: float = 2.
    transverse: float = 0.


_UNIFORMS = '''
uniform float origin_edge_attenuation,origin_depth,origin_radius;
uniform int origin_count;
uniform vec4 origin_sites[8];
'''


class OriginVolumeRenderer(SharedTransportRenderer):
    compute_source = SharedTransportRenderer.compute_source.replace('void main(){', _UNIFORMS+'\nvoid main(){', 1)
    compute_source = compute_source.replace('1.-.95*eject*', '1.-.95*origin_edge_attenuation*eject*')
    compute_source = compute_source.replace('1.-.65*eject*', '1.-.65*origin_edge_attenuation*eject*')
    compute_source = compute_source.replace('float depth_target=-state[i].pos.z*3.*roll_gain;', '''
    float empty=1.;
    for(int k=0;k<8;k++){
        if(k>=origin_count)break;
        vec4 origin=origin_sites[k];
        vec2 delta=(m.src.xy-origin.xy)/span;
        float cap=exp(-dot(delta,delta)/(2.*origin_radius*origin_radius));
        cap*=smoothstep(0.,.055,time-origin.z);
        empty*=1.-cap;
    }
    // 同一起点附近是一片连续、具有厚度的隆起；不会额外复制一条流束。
    float thickness=.72+.56*transport_unit(peel_seed^0x68bc21ebu);
    float desired_depth=span*origin_depth*(1.-empty)*thickness*(1.-exp(-age/.07));
    float depth_target=(desired_depth-state[i].pos.z)*roll_gain/.065;
''')
    pressure_splat_source = SPLAT.replace('void main(){', 'uniform float origin_edge_attenuation;\nvoid main(){', 1)
    pressure_splat_source = pressure_splat_source.replace('1.-.95*eject*', '1.-.95*origin_edge_attenuation*eject*')
    vertex_source = SharedTransportRenderer.vertex_source.replace('void main(){', 'uniform float origin_focal,origin_transverse;\nvoid main(){', 1)
    vertex_source = vertex_source.replace('float z=s.pos.z+span*.035*', '''
    // 深度投影作用于粒子中心；此前的伪深度只改变每片自身的尺寸。
    float focal=origin_focal*span;
    float magnification=focal/(focal-s.pos.z);
    vec2 radial=move-card*.5;
    radial-=wind*dot(radial,wind)*origin_transverse;
    move+=radial*(magnification-1.);
    float z=span*.035*''', 1)
    vertex_source = vertex_source.replace('vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);',
        'vec2 vertex=move+(rot*d)*scale*(1.+z/1000.)*magnification;', 1)

    def __init__(self,*args,volume=OriginVolumeRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.volume_rules=volume
        if not 0. <= volume.depth < volume.focal*.45:
            raise ValueError('浅层深度必须小于投影焦距的 45%，避免近投影奇点')
        self.compute['origin_edge_attenuation']=volume.edge_attenuation
        self.pressure_grid.splat['origin_edge_attenuation']=volume.edge_attenuation
        self.compute['origin_depth']=volume.depth
        self.compute['origin_radius']=volume.radius
        sites=self.origin_sites or canonical_sites(self.cw,self.ch,self.direction)
        if len(sites)>8:raise ValueError('本轮实验最多使用八个起点')
        data=np.zeros((8,4),np.float32)
        for k,s in enumerate(sites):data[k]=[s.x*self.cw,s.y*self.ch,s.start,0.]
        self.compute['origin_count']=len(sites)
        self.compute['origin_sites'].write(data.tobytes())
        self.program['origin_focal']=volume.focal
        self.program['origin_transverse']=volume.transverse

