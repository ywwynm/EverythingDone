"""任意释放起点的共同前沿与输运。输入不包含素材名或参考运动帧。"""
from dataclasses import dataclass
import math
import numpy as np
from scipy.ndimage import gaussian_filter
from renderer import Renderer,COMPUTE,VERTEX,FRAGMENT
from unified_model import materials,random_values,smooth,peel_compression,RULES
from pressure_grid import SPLAT


@dataclass(frozen=True)
class ReleaseSite:
    """控件归一化位置与启动事件时刻，不含局部速度、卷度或寿命。"""
    x: float
    y: float
    start: float=0.


@dataclass(frozen=True)
class FrontRules:
    duration: float=1.1742885165
    exponent: float=.8489377902
    interior_exponent: float=.8489377902
    rate_variation: float=.6636433969
    anisotropy: float=.0
    interior: float=.1308114012
    noise: float=.025
    curl: float=.08127524
    carry: float=-.20
    template: float=.03086946
    life: float=1.
    coverage: float=1.
    edge_retention: float=0.
    lighting: float=1.
    dissipation: float=0.
    dissipation_speed: float=.55
    deformation_clock: float=0.
    optical_thickness: float=1.
    grain: float=1.65
    edge_transport: float=0.


RULE=FrontRules()


def sample_sites(width,height,seed):
    """一般播放的起点采样；允许少量内部起点和多处边缘，不识别图片内容。"""
    a=random_values(5,int(seed)^0x1f83d9ab).ravel()
    count=1+int(a[0]*2.95)+int(max(width,height)/min(width,height)>1.8)
    result=[]
    for i in range(count):
        u,v=float(a[4*i+1]),float(a[4*i+2])
        if a[4*i+3]>.18 or i>0:
            edge=int(u*4);u=(u*4)%1
            x,y=[(u,0),(1,u),(1-u,1),(0,1-u)][edge]
        else:x,y=.18+.64*u,.18+.64*v
        result.append(ReleaseSite(x,y,0. if i==0 else .22*float(a[4*i])))
    return tuple(result)


def front_field(nx,ny,width,height,direction,seed,sites,rules=RULE):
    y,x=np.mgrid[:ny,:nx].astype('float64');span=min(width,height)
    x=(x+.5)/nx*width/span;y=(y+.5)/ny*height/span
    angle=math.radians(direction);wx,wy=math.cos(angle),-math.sin(angle)
    r=random_values(2,int(seed)^0x5be0cd19).ravel()
    local=random_values(len(sites),int(seed)^0x3c6ef372)[:,0]
    potential=[]
    for i,site in enumerate(sites):
        dx=x-site.x*width/span;dy=y-site.y*height/span
        inward=np.array([(.5-site.x)*width,(.5-site.y)*height]);length=np.linalg.norm(inward)
        inward=inward/max(length,1e-5) if length>1e-5 else np.array([-wx,-wy])
        a=dx*inward[0]+dy*inward[1];b=-dx*inward[1]+dy*inward[0]
        edge=min(site.x,site.y,1-site.x,1-site.y)
        anisotropy=rules.anisotropy*(1-float(smooth(max(edge,0)/.28)))
        # 边缘起点的解除区向材料内部推进，不能以固定圆心先吃掉整条边。
        # 这是带内向输运的共同到达距离；内部起点连续退化为圆形传播。
        one=1-anisotropy*anisotropy
        distance=(np.sqrt(a*a+one*b*b+1e-6)-anisotropy*a)/one
        # 边缘附近的侵蚀会沿切向展开；沿风向分量决定展开强度。
        # 使用真实矩形边的法向，而不是指向中心的对角线，内部起点连续退化为原传播。
        distances=np.array([site.x*width,(1-site.x)*width,site.y*height,(1-site.y)*height])/span
        weights=np.exp(-np.maximum(distances,0)/.025)
        normal=np.array([weights[0]-weights[1],weights[2]-weights[3]])
        normal/=max(np.linalg.norm(normal),1e-8)
        tangent=np.array([-normal[1],normal[0]])
        extent=1+rules.edge_transport*np.exp(-max(float(distances.min()),0)/.20)*abs(wx*tangent[0]+wy*tangent[1])
        across=dx*tangent[0]+dy*tangent[1]
        # 对位移作正定的切向缩放，再计算距离，避免与内向输运叠加后出现负平方。
        if rules.edge_transport:
            px=dx-tangent[0]*across*(1-1/extent)
            py=dy-tangent[1]*across*(1-1/extent)
            a=px*inward[0]+py*inward[1];b=-px*inward[1]+py*inward[0]
            distance=(np.sqrt(a*a+one*b*b+1e-6)-anisotropy*a)/one
        # 同样大小的内部开口须解除更多周围材料，不能当作已有自由边界。
        distance*=1+rules.interior*float(smooth(max(edge,0)/.42))
        rate=math.exp(2*rules.rate_variation*(float(local[i])-.5))
        exponent=rules.exponent+(rules.interior_exponent-rules.exponent)*float(smooth(max(edge,0)/.35))
        potential.append(.014+site.start+rules.duration*(distance/rate)**exponent)
    stack=np.maximum(np.stack(potential),.0001)
    # 相邻解除区连续相遇；不存在 min 运算在两支前沿间留下的折线。
    nearest=np.power(np.sum(stack**-6,axis=0),-1/6)
    # 固定空间噪声改变局部推进速度；它不复制任何参考片的轮廓。
    noise=(np.sin(x*8.1+y*3.2+float(r[0])*6.28)*np.sin(y*7.3-x*1.8+float(r[1])*6.28)
           +.3*np.sin(x*18.4-y*11.7+float(r[2])*6.28))
    nearest=np.maximum(nearest+rules.noise*noise,0)
    # 传播速度按短边确定，不能将每种拓扑各自拉伸到相同完成时刻。
    # 一个内部起点和多个边缘起点因此自然具有不同的清空节奏。
    boundary=np.exp(-np.minimum(x,width/span-x)/.10)+np.exp(-np.minimum(y,height/span-y)/.10)
    # 原表面靠近边缘时保留较晚的连续交接；只改变解除时刻，不限制粒子位置。
    retention=rules.edge_retention*boundary*(1-np.exp(-nearest/.16))
    arrival=nearest+retention
    # 全动画寿命的平滑上限，避免在远端剩下未释放的原表面。
    arrival=.86-.035*np.logaddexp(0,(.86-arrival)/.035)
    return arrival.astype('float32')


_FRONT_UNIFORMS='''
uniform int release_site_count;
uniform vec2 release_sites[8];
uniform float release_starts[8];
uniform float front_curl,front_carry,front_template,deformation_clock;
vec2 front_flow(vec2 p,float t){
    vec2 velocity=wind*span*front_carry;
    vec2 crosswind=vec2(-wind.y,wind.x);
    // 每处释放区生成一对有限尺度的涡；势函数导数给出连续、无散的卷动。
    // 涡心随共同主流移动，不跟随某个年龄队列，因此不把队列压成孤立细缕。
    for(int j=0;j<release_site_count;j++){
        float local_time=max(0.,t-release_starts[j]);
        float event_strength=smoothstep(0.,.07,local_time);
        float radius=span*(.11+.20*smoothstep(.04,.65,local_time));
        vec2 origin=release_sites[j]*card+wind*span*.23*local_time;
        for(int side=-1;side<=1;side+=2){
            vec2 center=origin+crosswind*radius*.95*float(side);
            vec2 q=(p-center)/radius;
            float falloff=exp(-dot(q,q)*.5);
            velocity+=vec2(q.y,-q.x)*falloff*span*front_curl*float(side)*event_strength;
        }
    }
    return velocity;
}
vec2 coherent_velocity(vec2 p,float t,float index,float weight){
    vec3 guide=guided_flow(p,local_anchor(index),t);
    if(field_geometry.z>.00001)guide=mix(guide,guided_flow(p,local_anchor(index+1.),t),weight);
    vec2 result=guide.xy*(guide_gain/.9)*1.144;
    if(release_site_count>0)result=mix(front_flow(p,time),result,front_template);
    return result;
}
'''


class FrontRenderer(Renderer):
    """相同的运动程序接受不同起点。None 保留已验收的共同初始场。"""
    # 插入位置必须在现有 uniform 声明之后，否则 GLSL 尚未声明 wind 等名字。
    compute_source=COMPUTE.replace('void main(){',_FRONT_UNIFORMS+'\nvoid main(){',1)
    compute_source=compute_source.replace(
        'vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach);',
        'vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach);\n'
        '    if(release_site_count>0){target=mix(front_flow(p,time),target,front_template);missing*=front_template;}')
    compute_source=compute_source.replace('state[i].vel.xy=v*distance_gain;',
        'state[i].vel.xy=v*distance_gain;\nstate[i].pos.w+=length(v*h*distance_gain)/span;\n'
        'float eps=span*.018;vec2 original=coherent_velocity(p,t,index,weight);\n'
        'vec2 gx=(coherent_velocity(p+vec2(eps,0),t,index,weight)-original)/eps;\n'
        'vec2 gy=(coherent_velocity(p+vec2(0,eps),t,index,weight)-original)/eps;\n'
        'float strain=sqrt(gx.x*gx.x+gy.y*gy.y+.5*(gx.y+gy.x)*(gx.y+gy.x));\n'
        'state[i].vel.w+=h*.75*(1.-smoothstep(0.,2.,strain))*deformation_clock;')
    vertex_source=VERTEX.replace('uniform float panel_weight,release_spread;',
        'uniform float panel_weight,release_spread;\nuniform float cloud_coverage,cloud_dissipation,cloud_speed;')
    vertex_source=vertex_source.replace('life_out=m.physical.z;random_out=m.random;',
        'float material_age=max(0.,age-s.vel.w*(1.-smoothstep(.58,.93,time)));\n'
        'float exposure=mix(material_age,s.pos.w/cloud_speed,cloud_dissipation);\n'
        'life_out=min(m.physical.z*age/max(exposure,.000001),.985-m.src.z);random_out=m.random;')
    vertex_source=vertex_source.replace('float loosen=smoothstep(.018,.18,age);',
        'float real_age=age;age=material_age;float loosen=smoothstep(.018,.18,age);')
    vertex_source=vertex_source.replace('vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);',
        'scale*=mix(1.,cloud_coverage,smoothstep(.006,.06,age));\n'
        '    vec2 vertex=move+(rot*d)*scale*(1.+z/1000.);')
    fragment_source=FRAGMENT.replace('uniform float light_gain;',
        'uniform float light_gain,cloud_thickness;')
    fragment_source=fragment_source.replace('if(alpha<.001)discard;',
        'alpha=1.-pow(1.-clamp(alpha,0.,1.),cloud_thickness);\nif(alpha<.001)discard;')

    pressure_splat_source=SPLAT.replace('uniform float span,time,touch_strength;',
        'uniform float span,time,touch_strength;\nuniform float cloud_dissipation,cloud_speed;')
    pressure_splat_source=pressure_splat_source.replace('if(m.src.w>=float(grid_count)||age<=0.||age>=m.physical.z)return;',
        'float material_age=max(0.,age-states[i].vel.w*(1.-smoothstep(.58,.93,time)));\n'
        'float exposure=mix(material_age,states[i].pos.w/cloud_speed,cloud_dissipation);\n'
        'if(m.src.w>=float(grid_count)||age<=0.||exposure>=m.physical.z||time>=.985)return;')
    pressure_splat_source=pressure_splat_source.replace('smoothstep(m.physical.z-.075,m.physical.z,age)',
        'smoothstep(m.physical.z-.075,m.physical.z,exposure)')

    def __init__(self,*args,sites=None,rules=RULE,**kwargs):
        self.sites=tuple(sites) if sites is not None else ()
        if len(self.sites)>8:raise ValueError('最多支持 8 个释放起点')
        if any(not (-.15<=q.x<=1.15 and -.15<=q.y<=1.15) for q in self.sites):
            raise ValueError('起点必须位于控件附近')
        if any(not math.isfinite(q.start) or not 0.<=q.start<1. for q in self.sites):
            raise ValueError('起点启动进度必须有限且位于 [0,1)')
        self.rules=rules
        super().__init__(*args,**kwargs)
        self.compute['release_site_count']=len(self.sites)
        values=np.zeros((8,2),dtype='float32')
        for i,p in enumerate(self.sites):values[i]=[p.x,p.y]
        self.compute['release_sites'].write(values.tobytes())
        starts=np.zeros(8,dtype='float32')
        for i,p in enumerate(self.sites):starts[i]=p.start
        self.compute['release_starts'].write(starts.tobytes())
        self.compute['front_curl']=rules.curl
        self.compute['front_carry']=rules.carry
        self.compute['front_template']=rules.template
        self.compute['deformation_clock']=rules.deformation_clock
        self.program['cloud_coverage']=rules.coverage
        self.program['cloud_thickness']=rules.optical_thickness
        self.program['light_gain']=self.settings['light_gain']*rules.lighting
        for program in [self.program,self.pressure_grid.splat]:
            program['cloud_dissipation']=rules.dissipation
            program['cloud_speed']=rules.dissipation_speed

    def material_builder(self,width,height,foreground,direction,seed,cell_px=None):
        # 新旧素材使用同一网格与粒径预算。
        built=materials(width,height,foreground,direction,seed,self.rules.grain if cell_px is None else cell_px)
        nx,ny=built['nx'],built['ny'];cx,cy=built['cell'];n=nx*ny
        b=built['base'];index=b[:,3].astype('int64')%n;random=b[:,8:];pigment=built['pigment']
        if self.sites:
            field=front_field(nx,ny,width,height,direction,seed,self.sites,self.rules)
            gy,gx=np.gradient(gaussian_filter(field,2),cy,cx)
            length=np.maximum(np.hypot(gx,gy),1e-6)
            normalx=gaussian_filter(gx/length,4);normaly=gaussian_filter(gy/length,4)
            compression=peel_compression(normalx,normaly,cx,cy,min(width,height),direction)
            born=field.ravel()[index]+built['release_spread']*(random[:,3]-.5)
            b[:,2]=np.maximum(born,.001);b[:,4]=normalx.ravel()[index];b[:,5]=normaly.ravel()[index]
            b[:,7]=0.
            built['peel_compression']=compression.ravel()[index]
        elif self.rules.life==1.:
            return built
        cap=.865+.115*random[:,2]-b[:,2]
        gain=RULES['life_early_gain']+(RULES['life_gain']-RULES['life_early_gain'])*smooth((b[:,2]-RULES['life_birth_start'])/RULES['life_birth_span'])
        life=(.10+.22*(-np.log(np.maximum(random[:,0],.004)))**.85+.20*b[:,2])*gain
        life=np.maximum(np.minimum(life*self.rules.life,cap),.11)
        b[:,6]=np.maximum(np.minimum(life*(1+.30*pigment),cap),.11)
        return built
