"""按颗粒年龄分解共同覆盖校准；三组响应共用几何输入，不改变运动或源颜色。"""
import json
import numpy as np
import moderngl
from particle_coverage import CoverageRenderer
from shared_transport import transport_features
from learned_release import canonical_sites


def load_cohorts(path):
    d=json.loads(path.read_text('utf-8'))
    return dict(scale=float(d.get('scale',1.2)),layers=[(np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in d['layers']])


def cohort_field(x,y,t,width,height,direction,sites,weights):
    if weights is None:return np.zeros((*x.shape,3),np.float32)
    f=transport_features(x,y,t,width,height,direction,sites,True);a=f.reshape(-1,f.shape[-1])
    for i,(w,b) in enumerate(weights['layers']):
        a=a@w.T+b
        if i<len(weights['layers'])-1:a=np.tanh(a)
    return (weights['scale']*np.tanh(a)).reshape(*x.shape,3).astype('float32')


class CohortRenderer(CoverageRenderer):
    vertex_source=CoverageRenderer.vertex_source.replace('void main(){','uniform sampler3D cohort_field;\nvoid main(){',1)
    vertex_source=vertex_source.replace('density_out=transport.z*smoothstep(0.,.03,age);', '''
    density_out=transport.z*smoothstep(0.,.03,age);
    vec2 cohort_q=(move/card+vec2(.38))/1.76;
    vec3 cohort=texture(cohort_field,vec3(cohort_q,phase)).rgb;
    float mature=smoothstep(.06,.16,age),elder=smoothstep(.22,.38,age);
    vec3 cohort_age=vec3(1.-mature,mature*(1.-elder),mature*elder);
    density_out+=dot(cohort,cohort_age)*smoothstep(.005,.035,age);
''',1)

    def __init__(self,*args,cohorts=None,**kwargs):
        super().__init__(*args,**kwargs)
        n=56;yy,xx=np.mgrid[:n,:n].astype('float32');xx=-.38+(xx+.5)/n*1.76;yy=-.38+(yy+.5)/n*1.76
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        field=np.array([cohort_field(xx,yy,t,self.cw,self.ch,self.direction,sites,cohorts) for t in np.linspace(0,1,41)],np.float32)
        self.cohort_tex=self.ctx.texture3d((n,n,41),3,field.tobytes(),dtype='f4');self.cohort_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.cohort_tex.repeat_x=self.cohort_tex.repeat_y=self.cohort_tex.repeat_z=False;self.program['cohort_field']=9

    def render(self,time,diagnostic=0):
        self.cohort_tex.use(9);return super().render(time,diagnostic)

    def close(self):
        super().close();self.cohort_tex.release()
