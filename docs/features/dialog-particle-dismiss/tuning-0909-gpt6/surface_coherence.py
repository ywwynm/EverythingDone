"""共同材料交接场实验：几何输入决定局部交接，不改变已有运动状态。"""
import json
import numpy as np
import moderngl
from particle_cohorts import CohortRenderer
from shared_transport import transport_features
from learned_release import canonical_sites


def load_coherence(path):
    d=json.loads(path.read_text('utf-8'))
    scales=np.asarray(d.get('scales',[d.get('scale',.035)]),np.float32)
    return dict(scale=float(scales[0]),scales=scales,layers=[(np.asarray(q['weight'],np.float32),np.asarray(q['bias'],np.float32)) for q in d['layers']])


def coherence_field(x,y,t,width,height,direction,sites,weights):
    return coherence_components(x,y,t,width,height,direction,sites,weights)[...,0]


def coherence_components(x,y,t,width,height,direction,sites,weights):
    """首项为交接时刻，次项为对数宽度；兼容以前只有时刻的权重。"""
    if weights is None:return np.zeros((*x.shape,2),np.float32)
    f=transport_features(x,y,t,width,height,direction,sites,True);v=f.reshape(-1,f.shape[-1])
    for i,(w,b) in enumerate(weights['layers']):
        v=v@w.T+b
        if i<len(weights['layers'])-1:v=np.tanh(v)
    scales=weights.get('scales',np.asarray([weights['scale']],np.float32))
    value=(scales*np.tanh(v)).reshape(*x.shape,len(scales)).astype('float32')
    if value.shape[-1]==1:value=np.concatenate([value,np.zeros_like(value)],-1)
    return value


def monotone_clock(values,minimum_rate=.15):
    """限制分段线性场对应的时钟导数，避免未知输入让材料状态反向变化。"""
    count=len(values)-1;t0=np.arange(count,dtype=np.float64)[:,None,None]/count;t1=t0+1/count
    slope=np.diff(values.astype('float64'),axis=0)*count;intercept=values[:-1]-slope*t0
    # d/dt [4t(1-t)(a+bt)] 是二次式；端点和区间内驻点覆盖其最大值。
    critical=np.clip(np.divide(slope-intercept,3*slope,out=np.zeros_like(slope),where=abs(slope)>1e-12),t0,t1)
    def derivative(t):return 4*(intercept+2*(slope-intercept)*t-3*slope*t*t)
    maximum=float(np.maximum(np.maximum(derivative(t0),derivative(t1)),derivative(critical)).max())
    gain=min(1.,(1.-minimum_rate)/max(maximum,1e-12))
    return (values*gain).astype('float32'),gain,1.-maximum*gain


class CoherenceRenderer(CohortRenderer):
    vertex_source=CohortRenderer.vertex_source.replace('void main(){',
        'uniform sampler3D coherence_field;\nuniform float handoff_delay;\nvoid main(){',1)
    vertex_source=vertex_source.replace('surface_out=1.-smoothstep(', '''
    float coherence_phase=(clamp(time,0.,1.)*40.+.5)/41.;
    vec2 coherence=texture(coherence_field,vec3(m.src.xy/card,coherence_phase)).rg;
    float local_delay=handoff_delay+coherence.x;
    float handoff_time=time-local_delay*4.*time*(1.-time);
    float handoff_width=exp(coherence.y*4.*time*(1.-time));
    surface_out=1.-smoothstep(''',1)
    vertex_source=vertex_source.replace('source_clock+.060),time);','source_clock+.060),handoff_time);',1)
    vertex_source=vertex_source.replace('max(.001,source_clock-.025),max(.018,source_clock+.060),handoff_time)',
        'max(.001,source_clock-.025-.0425*(handoff_width-1.)),max(.018,source_clock+.060+.0425*(handoff_width-1.)),handoff_time)')
    vertex_source=vertex_source.replace('time<=m.src.z?1.:0.','handoff_time<=m.src.z?1.:0.',1)
    vertex_source=vertex_source.replace('source_clock+.060+transfer.y),time);','source_clock+.060+transfer.y),handoff_time);',1)
    vertex_source=vertex_source.replace('max(.001,source_clock-.025-transfer.y),max(.018,source_clock+.060+transfer.y),handoff_time)',
        'max(.001,source_clock-.025-transfer.y-(.0425+transfer.y)*(handoff_width-1.)),max(.018,source_clock+.060+transfer.y+(.0425+transfer.y)*(handoff_width-1.)),handoff_time)')
    vertex_source=vertex_source.replace('float paired=1.-smoothstep(0.,.016,time-m.src.z);',
        'float paired=1.-smoothstep(0.,.016*handoff_width,handoff_time-m.src.z);',1)

    def __init__(self,*args,coherence=None,**kwargs):
        super().__init__(*args,**kwargs)
        n=64;yy,xx=np.mgrid[:n,:n].astype('float32');xx=(xx+.5)/n;yy=(yy+.5)/n
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        values=np.array([coherence_components(xx,yy,t,self.cw,self.ch,self.direction,sites,coherence) for t in np.linspace(0,1,41)],np.float32)
        values[...,0],self.coherence_clock_gain,self.coherence_minimum_rate=monotone_clock(values[...,0])
        self.coherence_tex=self.ctx.texture3d((n,n,41),2,values.tobytes(),dtype='f4');self.coherence_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.coherence_tex.repeat_x=self.coherence_tex.repeat_y=self.coherence_tex.repeat_z=False
        self.program['coherence_field']=10;self.program['handoff_delay']=0.

    def render(self,time,diagnostic=0):
        self.coherence_tex.use(10)
        return super().render(time,diagnostic)

    def close(self):
        super().close();self.coherence_tex.release()
