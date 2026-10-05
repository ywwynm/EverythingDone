"""共同表面交接实验：同时调整局部时刻和渐隐宽度，不改变颗粒状态。"""
import json
import numpy as np
import moderngl
from particle_cohorts import CohortRenderer
from shared_transport import transport_features
from learned_release import canonical_sites
from surface_coherence import monotone_clock


def load_handoff(path):
    data=json.loads(path.read_text('utf-8'))
    return dict(scales=np.asarray(data.get('scales',[.10,.85]),np.float32),
                layers=[(np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in data['layers']])


def handoff_field(x,y,t,width,height,direction,sites,weights):
    if weights is None:return np.zeros((*x.shape,2),np.float32)
    f=transport_features(x,y,t,width,height,direction,sites,True);v=f.reshape(-1,f.shape[-1])
    for i,(w,b) in enumerate(weights['layers']):
        v=v@w.T+b
        if i<len(weights['layers'])-1:v=np.tanh(v)
    return (weights['scales']*np.tanh(v)).reshape(*x.shape,2).astype('float32')


class HandoffRenderer(CohortRenderer):
    vertex_source=CohortRenderer.vertex_source.replace('void main(){', '''
uniform sampler3D handoff_field;
uniform vec2 handoff_delta;
void main(){''',1)
    vertex_source=vertex_source.replace('surface_out=1.-smoothstep(', '''
    float handoff_phase=(clamp(time,0.,1.)*40.+.5)/41.;
    vec2 handoff=texture(handoff_field,vec3(m.src.xy/card,handoff_phase)).rg+handoff_delta;
    float handoff_time=time-handoff.x*4.*time*(1.-time);
    float handoff_width=exp(handoff.y*4.*time*(1.-time));
    surface_out=1.-smoothstep(''',1)
    vertex_source=vertex_source.replace('max(.001,source_clock-.025),max(.018,source_clock+.060),time)',
        'max(.001,source_clock-.025-.0425*(handoff_width-1.)),max(.018,source_clock+.060+.0425*(handoff_width-1.)),handoff_time)')
    vertex_source=vertex_source.replace('time<=m.src.z?1.:0.','handoff_time<=m.src.z?1.:0.',1)
    vertex_source=vertex_source.replace('max(.001,source_clock-.025-transfer.y),max(.018,source_clock+.060+transfer.y),time)',
        'max(.001,source_clock-.025-transfer.y-(.0425+transfer.y)*(handoff_width-1.)),max(.018,source_clock+.060+transfer.y+(.0425+transfer.y)*(handoff_width-1.)),handoff_time)')
    vertex_source=vertex_source.replace('float paired=1.-smoothstep(0.,.016,time-m.src.z);',
        'float paired=1.-smoothstep(0.,.016*handoff_width,handoff_time-m.src.z);',1)

    def __init__(self,*args,handoff=None,coherence=None,**kw):
        super().__init__(*args,**kw)
        n=64;yy,xx=np.mgrid[:n,:n].astype('float32');xx=(xx+.5)/n;yy=(yy+.5)/n
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        if handoff is None and coherence is not None:
            from surface_coherence import coherence_components
            values=np.asarray([coherence_components(xx,yy,t,self.cw,self.ch,self.direction,sites,coherence) for t in np.linspace(0,1,41)],np.float32)
        else:values=np.asarray([handoff_field(xx,yy,t,self.cw,self.ch,self.direction,sites,handoff) for t in np.linspace(0,1,41)],np.float32)
        values[...,0],self.handoff_clock_gain,self.handoff_minimum_rate=monotone_clock(values[...,0])
        self.handoff_tex=self.ctx.texture3d((n,n,41),2,values.tobytes(),dtype='f4');self.handoff_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.handoff_tex.repeat_x=self.handoff_tex.repeat_y=self.handoff_tex.repeat_z=False
        self.program['handoff_field']=10;self.program['handoff_delta']=(0.,0.)

    def render(self,time,diagnostic=0):
        self.handoff_tex.use(10);return super().render(time,diagnostic)

    def close(self):
        super().close();self.handoff_tex.release()
