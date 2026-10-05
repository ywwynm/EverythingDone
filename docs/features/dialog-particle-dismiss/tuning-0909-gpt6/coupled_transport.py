"""释放与输运共变换实验。运行接口不接收参考帧，旧材料绘制保持不变。"""
from dataclasses import dataclass
import numpy as np
from scipy.ndimage import gaussian_filter, map_coordinates
from renderer import Renderer, COMPUTE
from unified_model import materials, release_components, refine_release, peel_compression, RULES, smooth


@dataclass(frozen=True)
class CoupledRules:
    warp: bool = False
    local_clock: bool = True
    steps: int = 140
    smoothness: float = .002
    max_displacement: float = .45


def fit_coordinates(native, target, rules):
    """同一小网格配准求解器；由释放场生成平滑坐标映射，不拟合视频或粒子位置。"""
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(2)
    n=64
    # 本机 PyTorch 2.3 与 NumPy 2 的二进制桥不兼容；小网格用普通列表交换，不改共享环境。
    def tensor(a):return F.interpolate(torch.tensor(a.tolist(),dtype=torch.float32)[None,None],(n,n),mode='bilinear',align_corners=True)
    src=tensor(native);dst=tensor(target)
    x=torch.linspace(-1,1,n);yy,xx=torch.meshgrid(x,x,indexing='ij');grid=torch.stack([xx,yy],-1)[None]
    controls=torch.zeros((1,2,13,13),requires_grad=True)
    opt=torch.optim.Adam([controls],lr=.032)
    best=float('inf');chosen=None
    for _ in range(rules.steps):
        opt.zero_grad()
        v=F.interpolate(torch.tanh(controls)*rules.max_displacement,(n,n),mode='bicubic',align_corners=True)
        d=v.permute(0,2,3,1)/32
        # 五次复合；固定速度积分，减少位移图直接拟合时的局部折叠。
        for j in range(5):d=d+F.grid_sample(d.permute(0,3,1,2),grid+d,mode='bilinear',padding_mode='border',align_corners=True).permute(0,2,3,1)
        pred=F.grid_sample(src,grid+d,mode='bilinear',padding_mode='border',align_corners=True)
        dx=(d[:,1:,:-1]-d[:,:-1,:-1])*(n-1)/2
        dy=(d[:,:-1,1:]-d[:,:-1,:-1])*(n-1)/2
        det=(1+dy[...,0])*(1+dx[...,1])-dx[...,0]*dy[...,1]
        loss=(pred-dst).abs().mean()+rules.smoothness*(dx.square().mean()+dy.square().mean())+.15*F.relu(.30-det).square().mean()+.003*d.square().mean()
        loss.backward();opt.step()
        val=float(loss.detach())
        if val<best:best=val;chosen=np.asarray(d.detach()[0].tolist(),np.float32)/2
    return chosen.astype('float32'),dict(loss=best,mean_displacement=float(np.linalg.norm(chosen,axis=2).mean()),max_displacement=float(np.linalg.norm(chosen,axis=2).max()))


_WARP=r'''
uniform sampler2D coordinate_field;
uniform float coordinate_enabled;
vec2 registered_position(vec2 p){
    vec2 u=p/card;
    vec2 inset=clamp(u,vec2(0.),vec2(1.));
    float outside=length((u-inset)*card)/span;
    float taper=1.-smoothstep(0.,.45,outside);
    return p+texture(coordinate_field,inset).xy*card*taper;
}
vec3 guided_flow(vec2 p,vec2 anchor,float t){
    if(coordinate_enabled<.5)return native_guided_flow(p,anchor,t);
    vec2 q=registered_position(p);
    vec2 ex=vec2(card.x/64.,0),ey=vec2(0,card.y/64.);
    vec2 jx=(registered_position(p+ex)-registered_position(p-ex))/(2.*ex.x);
    vec2 jy=(registered_position(p+ey)-registered_position(p-ey))/(2.*ey.y);
    float det=jx.x*jy.y-jy.x*jx.y;
    vec3 v=native_guided_flow(q,anchor,t);
    if(det>.18)v.xy=vec2(jy.y*v.x-jy.x*v.y,-jx.y*v.x+jx.x*v.y)/det;
    return v;
}
'''


class CoupledRenderer(Renderer):
    compute_source=COMPUTE.replace('vec3 guided_flow(', 'vec3 native_guided_flow(',1).replace('void main(){',_WARP+'\nvoid main(){',1)

    def __init__(self,*args,release=None,rules=CoupledRules(),**kwargs):
        self.release_input=release;self.coupled_rules=rules
        self.coordinate_map=np.zeros((2,2,2),np.float32);self.warp_report={}
        super().__init__(*args,**kwargs)
        a=self.coordinate_map
        self.coordinate_tex=self.ctx.texture((a.shape[1],a.shape[0]),2,a.tobytes(),dtype='f4')
        import moderngl
        self.coordinate_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.coordinate_tex.repeat_x=self.coordinate_tex.repeat_y=False
        self.compute['coordinate_field']=7
        self.compute['coordinate_enabled']=float(self.release_input is not None and rules.warp)

    def material_builder(self,width,height,foreground,direction,seed,cell_px=None):
        built=materials(width,height,foreground,direction,seed,cell_px)
        if self.release_input is None:return built
        nx,ny=built['nx'],built['ny'];cx,cy=built['cell'];n=nx*ny
        native,offset=release_components(nx,ny,direction,width,height,seed)
        native=refine_release(native,width,height,direction,built['panel_weight'])
        if callable(self.release_input):field=self.release_input(nx,ny,width,height,direction,seed,foreground)
        else:
            y,x=np.mgrid[:ny,:nx]
            a=self.release_input
            field=map_coordinates(a,[(y+.5)/ny*a.shape[0]-.5,(x+.5)/nx*a.shape[1]-.5],order=1,mode='nearest')
        field=np.maximum(np.asarray(field,np.float32),.001)
        if not self.coupled_rules.warp and np.array_equal(field,native):return built
        if self.coupled_rules.warp:
            self.coordinate_map,self.warp_report=fit_coordinates(native,field,self.coupled_rules)
        yy,xx=np.mgrid[:ny,:nx];u=(xx+.5)/nx;v=(yy+.5)/ny
        if self.coupled_rules.warp:
            m=self.coordinate_map
            du=map_coordinates(m[:,:,0],[v*m.shape[0]-.5,u*m.shape[1]-.5],order=1,mode='nearest')
            dv=map_coordinates(m[:,:,1],[v*m.shape[0]-.5,u*m.shape[1]-.5],order=1,mode='nearest')
        else:du=dv=0.
        clock=map_coordinates(native-offset,[(v+dv)*ny-.5,(u+du)*nx-.5],order=1,mode='nearest')
        gy,gx=np.gradient(gaussian_filter(field,3),cy,cx);ln=np.maximum(np.hypot(gx,gy),1e-6)
        normalx=gaussian_filter(gx/ln,7);normaly=gaussian_filter(gy/ln,7)
        b=built['base'];index=b[:,3].astype('int64')%n;rand=b[:,8:]
        b[:,2]=np.maximum(field.ravel()[index]+np.float32(built['release_spread'])*(rand[:,3]-.5),.001)
        b[:,4]=normalx.ravel()[index];b[:,5]=normaly.ravel()[index]
        if self.coupled_rules.local_clock:b[:,7]=(field-clock).ravel()[index]
        cap=.865+.115*rand[:,2]-b[:,2]
        gain=RULES['life_early_gain']+(RULES['life_gain']-RULES['life_early_gain'])*smooth((b[:,2]-RULES['life_birth_start'])/RULES['life_birth_span'])
        life=np.maximum(np.minimum((.10+.22*(-np.log(np.maximum(rand[:,0],.004)))**.85+.20*b[:,2])*gain,cap),.11)
        b[:,6]=np.maximum(np.minimum(life*(1+.30*built['pigment']),cap),.11)
        built['peel_compression']=peel_compression(normalx,normaly,cx,cy,min(width,height),direction).ravel()[index]
        return built

    def seek(self,time):
        self.coordinate_tex.use(7)
        super().seek(time)

    def close(self):
        super().close();self.coordinate_tex.release()
