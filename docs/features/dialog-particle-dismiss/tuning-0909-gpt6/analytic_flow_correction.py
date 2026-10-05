"""共同形变系数的完整颗粒渲染验证；保持表面和颗粒材质，移动实际颗粒。"""
import numpy as np
import moderngl
from conditional_material import ConditionalRenderer
from learned_release import canonical_sites
from flow_basis import displacement


class AnalyticFlowRenderer(ConditionalRenderer):
    vertex_source=ConditionalRenderer.vertex_source.replace('void main(){','uniform sampler3D correction_field;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec2 move=s.pos.xy+s.vel.xy*extrapolate;',r'''
    vec2 move=s.pos.xy+s.vel.xy*extrapolate;
    vec2 q=(move/card+vec2(.38))/1.76;
    float phase=(clamp(time,0.,1.)*40.+.5)/41.;
    move+=texture(correction_field,vec3(q,phase)).xy*span*smoothstep(0.,.03,age);
''',1)

    def __init__(self,*args,coefficients,**kwargs):
        super().__init__(*args,**kwargs)
        n=56;yy,xx=np.mgrid[:n,:n].astype('float32');xx=-.38+(xx+.5)/n*1.76;yy=-.38+(yy+.5)/n*1.76
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        field=np.array([displacement(xx,yy,t,self.cw,self.ch,self.direction,sites,coefficients)*self.layout_change for t in np.linspace(0,1,41)],np.float32)
        self.correction_tex=self.ctx.texture3d((n,n,41),2,field.tobytes(),dtype='f4')
        self.correction_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.correction_tex.repeat_x=self.correction_tex.repeat_y=self.correction_tex.repeat_z=False
        self.program['correction_field']=8

    def render(self,time,diagnostic=0):
        self.correction_tex.use(8)
        return super().render(time,diagnostic)

    def close(self):
        super().close();self.correction_tex.release()
