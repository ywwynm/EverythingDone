"""共享的表面—颗粒交接实验；过渡尺度来自释放场梯度，不读取素材标签。"""
from dataclasses import dataclass
import json
import numpy as np
from scipy.ndimage import gaussian_filter
from shared_transport import SharedTransportRenderer


@dataclass(frozen=True)
class TransferRules:
    spatial: float = .035
    temporal: float = .025
    stochastic: float = 1.
    broaden: float = 0.
    delay: float = 0.
    pairing: float = 1.
    coverage: float = 0.
    origin_lead: float = 0.


def spatial_clocks(base,nx,ny,release_spread):
    """按格点身份恢复时钟；绘制深度顺序和副片均不改变空间梯度。"""
    ids=base[:,3].astype('int64');primary=ids<nx*ny
    clock=base[:,2]-release_spread*(base[:,11]-.5)
    ordered=np.empty(nx*ny,np.float32);ordered[ids[primary]]=clock[primary]
    return clock,ordered.reshape(ny,nx)


def load_transfer(path):
    d=json.loads(path.read_text('utf-8'))
    return TransferRules(**d.get('rules',d.get('transfer',d)))


class TransferRenderer(SharedTransportRenderer):
    vertex_source = SharedTransportRenderer.vertex_source.replace('void main(){', '''
layout(std430,binding=10) readonly buffer Transfer {vec4 transfer_data[];};
uniform float transfer_pairing,transfer_broaden;
flat out float born_fraction;
void main(){''', 1)
    vertex_source = vertex_source.replace('float source_clock=m.src.z-release_spread*(m.random.w-.5);',
        'vec4 transfer=transfer_data[gl_InstanceID];\n    float source_clock=transfer.x;')
    vertex_source = vertex_source.replace('surface_out=mix(time<=m.src.z?1.:0.,surface_out,.85*(1.-.60*panel_weight));', '''
    surface_out=mix(time<=m.src.z?1.:0.,surface_out,.85*(1.-.60*panel_weight));
    float extended=1.-smoothstep(max(.001,source_clock-.025-transfer.y),max(.018,source_clock+.060+transfer.y),time);
    surface_out=mix(surface_out,extended,transfer_broaden);
    // 每片原表面与运动片共用自己的交接量。空间柔和由分散的出生时间形成，
    // 不再把平均释放比例同时乘到已释放粒子，避免出现覆盖量的二次衰减。
    float paired=1.-smoothstep(0.,.016,time-m.src.z);
    surface_out=mix(surface_out,paired,transfer_pairing);
    born_fraction=clamp((time-transfer.z)/max(transfer.w,.001)+.5,0.,1.);
''')
    fragment_source=SharedTransportRenderer.fragment_source.replace('void main(){','uniform float transfer_coverage;\nflat in float born_fraction;\nvoid main(){',1)
    fragment_source=fragment_source.replace('if(replica_out>.5)alpha*=smoothstep(.008,.055,age);', '''
    alpha*=mix(1.,1./max(.18,born_fraction),transfer_coverage);
    alpha=min(alpha,1.);
    if(replica_out>.5)alpha*=smoothstep(.008,.055,age);''',1)

    def __init__(self,*args,transfer=TransferRules(),**kwargs):
        super().__init__(*args,**kwargs)
        self.transfer_original=self.base.copy()
        self.transfer_original_life=self.original_life.copy()
        n=self.nx*self.ny;index=self.base[:,3].astype('int64')%n
        # 建材结果按绘制深度排序，不能把存储顺序当作二维网格顺序。
        clock,field=spatial_clocks(self.base,self.nx,self.ny,self.release_spread)
        gy,gx=np.gradient(gaussian_filter(field,2),self.cell[1],self.cell[0])
        self.transfer_gradient=np.hypot(gx,gy).ravel()[index]*self.span
        self.transfer_clock=clock
        from learned_release import canonical_sites
        sites=self.origin_sites if self.origin_sites is not None else canonical_sites(self.cw,self.ch,self.direction)
        core=np.zeros(self.n,np.float32)
        for site in sites:
            d=((self.base[:,0]-site.x*self.cw)/self.span)**2+((self.base[:,1]-site.y*self.ch)/self.span)**2
            core=np.maximum(core,np.exp(-d/(2*.20**2)))
        self.transfer_origin_core=core
        self.transfer_buffer=self.ctx.buffer(reserve=self.n*16)
        self.apply_transfer(transfer)

    def apply_transfer(self,rules):
        self.transfer_rules=rules
        self.base[:]=self.transfer_original
        self.original_life[:]=self.transfer_original_life
        half=np.clip(np.sqrt(rules.temporal**2+(rules.spatial*self.transfer_gradient)**2),.001,.14)
        clock=self.transfer_clock-rules.origin_lead*self.transfer_origin_core
        data=np.zeros((self.n,4),np.float32);data[:,0]=clock;data[:,1]=half
        random=self.base[:,11]
        # 有界、对称的连续分布；同一空间宽度在传播快慢不同的区域均有效。
        offset=(2*random-1)*half*1.6
        birth=np.clip(clock+offset+rules.delay,.001,.90)
        self.base[:,2]-=rules.origin_lead*self.transfer_origin_core
        self.base[:,2]+=(birth-self.base[:,2])*rules.stochastic
        self.base[:,2]=np.maximum(self.base[:,2],.001)
        data[:,2]=clock+rules.delay*rules.stochastic
        data[:,3]=self.release_spread*(1-rules.stochastic)+half*3.2*rules.stochastic
        g=self.layout_change
        self.base[:,6]=self.original_life+g*(np.minimum(self.original_life*self.conditional_rules.life,.985-self.base[:,2])-self.original_life)
        self.base[:,6]=np.minimum(self.base[:,6],.995-self.base[:,2])
        self.material.write(self.base.tobytes())
        self.transfer_buffer.write(data.tobytes())
        self.program['transfer_pairing']=rules.pairing
        self.program['transfer_broaden']=rules.broaden
        self.program['transfer_coverage']=rules.coverage
        self.reset()

    def render(self,time,diagnostic=0):
        self.transfer_buffer.bind_to_storage_buffer(10)
        return super().render(time,diagnostic)

    def close(self):
        super().close();self.transfer_buffer.release()
