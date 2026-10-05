"""检验出生位置连续的共同坐标形变，避免新颗粒突然承担累计位置偏移。"""
import numpy as np
from scatter_material import ScatterRenderer


class RelativeRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace(
        'move+=transport.xy*span*smoothstep(0.,.03,age);',r'''
    vec2 birth_query=(m.src.xy/card+vec2(.38))/1.76;
    float birth_phase=(clamp(m.src.z,0.,1.)*40.+.5)/41.;
    vec2 birth_transport=texture(transport_field,vec3(birth_query,birth_phase)).xy;
    move+=(transport.xy-birth_transport)*span;
''',1)


class SteadyRelativeRenderer(RelativeRenderer):
    def __init__(self,*a,**kw):
        super().__init__(*a,**kw)
        f=np.frombuffer(self.transport_tex.read(),np.float32).reshape(41,56,56,3).copy()
        # 同一几何输入产生同一个宽时间段平均形变方向；密度输出不动。
        times=np.linspace(0,1,41,dtype=np.float32)
        mean=(f[8:33,:,:,:2]/times[8:33,None,None,None]).mean(0)
        f[:,:,:,:2]=mean[None]*times[:,None,None,None]
        self.transport_tex.write(f.tobytes())
