"""当前粒子分布上的单侧剥离压力。GPU 原型，和 CPU 诊断独立核对。"""
from pathlib import Path
import sys,math
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_projected_peel import configure

SPLAT=r'''#version 430
layout(local_size_x=256) in;
struct Material{vec4 src;vec4 physical;vec4 random;};
struct State{vec4 pos;vec4 vel;};
layout(std430,binding=0) readonly buffer A{Material particles[];};
layout(std430,binding=1) readonly buffer B{State states[];};
layout(std430,binding=3) readonly buffer C{float compression[];};
layout(std430,binding=4) buffer D{ivec4 accumulation[];};
uniform int pass,count,grid_count;
uniform ivec2 grid_shape;
uniform vec2 card,wind;
uniform vec4 grid_bounds;
uniform float span,time,touch_strength;
uniform sampler2D foreground;
uint hash32(uint x){x^=x>>16u;x*=0x7feb352du;x^=x>>15u;x*=0x846ca68bu;return x^(x>>16u);}
float unit32(uint x){return float(hash32(x)>>8u)*(1./16777216.);}
void main(){
 uint i=gl_GlobalInvocationID.x;
 if(pass==0){if(i<uint(grid_shape.x*grid_shape.y))accumulation[i]=ivec4(0);return;}
 if(i>=uint(count))return;Material m=particles[i];float age=time-m.src.z;
 if(m.src.w>=float(grid_count)||age<=0.||age>=m.physical.z)return;
 ivec2 source=clamp(ivec2(m.src.xy/card*vec2(textureSize(foreground,0))),ivec2(0),textureSize(foreground,0)-1);
 float opacity=texelFetch(foreground,source,0).a;
 if(opacity<=0.)return;
 vec2 p=m.src.xy+(states[i].pos.xy-m.src.xy)/(.55+.90*touch_strength);
 vec2 peel=-m.physical.xy;
 peel-=wind*min(dot(peel,wind),0.);peel-=wind*max(dot(peel,wind),0.)*.82;
 vec2 edge=min(m.src.xy,card-m.src.xy);
 vec2 outside=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.)*exp(-edge/(span*.13));
 float eject=max(dot(normalize(outside+vec2(.000001)),normalize(peel+vec2(.000001))),0.);
 peel*=1.-.95*eject*exp(-min(edge.x,edge.y)/(span*.18));
 float response=unit32(floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au);
 float strength=1.2141309*(.10+1.80*response);
 float pressure=2.*compression[i]*.16*strength;
 peel*=strength/(1.+pressure*pressure/(1.+pressure));
 peel*=exp(-age/.16)*smoothstep(.003,.028,age);
 float mass=opacity*(1.-smoothstep(m.physical.z-.075,m.physical.z,age));
 vec2 q=(p-grid_bounds.xy)/grid_bounds.zw*vec2(grid_shape)-.5;
 ivec2 lo=ivec2(floor(q));vec2 f=fract(q);
 for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++){
  ivec2 cell=lo+ivec2(dx,dy);if(any(lessThan(cell,ivec2(0)))||any(greaterThanEqual(cell,grid_shape)))continue;
  float w=mass*(dx==0?1.-f.x:f.x)*(dy==0?1.-f.y:f.y);
  int k=cell.y*grid_shape.x+cell.x;
  atomicAdd(accumulation[k].x,int(round(peel.x*w*4096.)));
  atomicAdd(accumulation[k].y,int(round(peel.y*w*4096.)));
  atomicAdd(accumulation[k].z,int(round(w*4096.)));
 }
}
'''

_kernel=np.exp(-np.arange(-5,6,dtype=float)**2/(2*1.3**2));_kernel/=_kernel.sum()
BLUR=r'''#version 430
layout(local_size_x=16,local_size_y=16) in;
layout(std430,binding=4) readonly buffer A{ivec4 accumulation[];};
layout(std430,binding=5) buffer B{vec4 intermediate[];};
layout(std430,binding=6) buffer C{vec4 velocity[];};
uniform ivec2 grid_shape;uniform int pass;uniform float occupancy_scale;
const float weights[11]=float[11](KERNEL);
int at(ivec2 q){q=clamp(q,ivec2(0),grid_shape-1);return q.y*grid_shape.x+q.x;}
void main(){
 ivec2 q=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(q,grid_shape)))return;
 vec4 sum=vec4(0);
 for(int k=-5;k<=5;k++)sum+=(pass==0?vec4(accumulation[at(q+ivec2(k,0))])/4096.:intermediate[at(q+ivec2(0,k))])*weights[k+5];
 int i=at(q);
 if(pass==0)intermediate[i]=sum;
 else velocity[i]=vec4(sum.xy/max(sum.z,.5),sum.z*occupancy_scale,0.);
}
'''.replace('KERNEL',','.join(f'{x:.10f}' for x in _kernel))

PROJECT=r'''#version 430
layout(local_size_x=16,local_size_y=16) in;
layout(std430,binding=0) readonly buffer A{vec4 velocity[];};
layout(std430,binding=1) readonly buffer B{float old_pressure[];};
layout(std430,binding=2) writeonly buffer C{float new_pressure[];};
layout(rgba32f,binding=0) uniform writeonly highp image2D pressure_field;
uniform ivec2 grid_shape;uniform int iteration;
int at(ivec2 q){q=clamp(q,ivec2(0),grid_shape-1);return q.y*grid_shape.x+q.x;}
float pressure(ivec2 q){return old_pressure[at(q)];}
void main(){
 ivec2 q=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(q,grid_shape)))return;
 int i=at(q);ivec2 X=ivec2(1,0),Y=ivec2(0,1);
 if(iteration==100){
  vec2 correction=vec2(pressure(q+X)-pressure(q-X),pressure(q+Y)-pressure(q-Y))*.5;
  imageStore(pressure_field,q,vec4(-correction,0.,0.));return;
 }
 float div=(velocity[at(q+X)].x-velocity[at(q-X)].x+velocity[at(q+Y)].y-velocity[at(q-Y)].y)*.5;
 float neighbours=pressure(q+X)+pressure(q-X)+pressure(q+Y)+pressure(q-Y);
 if(iteration==0)neighbours*=.9;
 new_pressure[i]=velocity[i].z>.025?max((neighbours-div)*.25,0.):0.;
}
'''

class PressureGrid:
    def __init__(self,r):
        self.r=r;self.ctx=r.ctx;self.cell=r.span/96
        self.shape=(math.ceil(r.cw/self.cell)+192,math.ceil(r.ch/self.cell)+192)
        self.bounds=(-r.span,-r.span,self.shape[0]*self.cell,self.shape[1]*self.cell)
        count=math.prod(self.shape)
        self.accum=self.ctx.buffer(reserve=count*16);self.temp=self.ctx.buffer(reserve=count*16);self.velocity=self.ctx.buffer(reserve=count*16)
        self.pressures=[self.ctx.buffer(reserve=count*4),self.ctx.buffer(reserve=count*4)]
        self.texture=self.ctx.texture(self.shape,4,dtype='f4');self.texture.filter=(moderngl.LINEAR,moderngl.LINEAR);self.texture.repeat_x=False;self.texture.repeat_y=False
        self.splat=self.ctx.compute_shader(SPLAT);self.blur=self.ctx.compute_shader(BLUR);self.project=self.ctx.compute_shader(PROJECT)
        for p in [self.splat,self.blur,self.project]:p['grid_shape']=self.shape
        for key,value in dict(count=r.n,grid_count=r.nx*r.ny,card=(r.cw,r.ch),wind=r.wind,grid_bounds=self.bounds,span=r.span,touch_strength=r.touch_strength,foreground=0).items():self.splat[key]=value
        self.blur['occupancy_scale']=r.cell[0]*r.cell[1]/self.cell**2
        self.current=0;self.reset();r.compute['peel_field']=5;r.compute['peel_bounds']=self.bounds
    def reset(self):
        for p in self.pressures:p.clear()
        self.current=0
    def barrier(self):self.ctx.memory_barrier()
    def update(self,time):
        r=self.r;r.material.bind_to_storage_buffer(0);r.state.bind_to_storage_buffer(1);r.peel_compression.bind_to_storage_buffer(3);self.accum.bind_to_storage_buffer(4);r.fg_tex.use(0)
        self.splat['pass']=0;self.splat['time']=time;self.splat.run(group_x=(math.prod(self.shape)+255)//256);self.barrier()
        self.splat['pass']=1;self.splat.run(group_x=(r.n+255)//256);self.barrier()
        self.temp.bind_to_storage_buffer(5);self.velocity.bind_to_storage_buffer(6)
        gx,gy=(self.shape[0]+15)//16,(self.shape[1]+15)//16
        for p in [0,1]:self.blur['pass']=p;self.blur.run(group_x=gx,group_y=gy);self.barrier()
        self.velocity.bind_to_storage_buffer(0)
        for i in range(100):
            self.pressures[self.current].bind_to_storage_buffer(1);self.pressures[1-self.current].bind_to_storage_buffer(2)
            self.project['iteration']=i;self.project.run(group_x=gx,group_y=gy);self.barrier();self.current=1-self.current
        self.pressures[self.current].bind_to_storage_buffer(1);self.pressures[1-self.current].bind_to_storage_buffer(2)
        self.texture.bind_to_image(0,read=False,write=True)
        self.project['iteration']=100;self.project.run(group_x=gx,group_y=gy);self.barrier();self.texture.use(5)
    def close(self):
        for p in [self.accum,self.temp,self.velocity,*self.pressures,self.texture,self.splat,self.blur,self.project]:p.release()

class GpuPressureRenderer(renderer.Renderer):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs);self.pressure_grid=PressureGrid(self);self.pressure_grid.update(0)
    def seek(self,time):
        if not hasattr(self,'pressure_grid'):return super().seek(time)
        target=int(max(time,0)/renderer.STEP+1e-6)
        if target<self.step:self.reset();self.pressure_grid.reset();self.pressure_grid.update(0)
        self.pressure_grid.texture.use(5)
        while self.step<target:
            end=min(target,(self.step//4+1)*4);super().seek(end*renderer.STEP)
            if self.step%4==0:self.pressure_grid.update(self.step*renderer.STEP)
        super().seek(self.step*renderer.STEP)
    def close(self):self.pressure_grid.close();super().close()

if __name__=='__main__':
    from probe_projected_peel import ProjectedRenderer
    from probe_edge_support import OUT
    import time
    configure(flip=True);ProjectedRenderer.flip=True;ProjectedRenderer.restore_strength=False;ProjectedRenderer.unilateral=True;ProjectedRenderer.free_surface=True;ProjectedRenderer.projection=1.;ProjectedRenderer.surface_threshold=.025
    ctx=moderngl.create_standalone_context(require=430)
    for name,scene,angle,seed in [('down','attachment',270,909602),('up','attachment',90,909602),('right','attachment',0,42),('ironman','ironman',122,909602)]:
        cpu=ProjectedRenderer(scene,ctx=ctx,direction=angle,seed=seed);gpu=GpuPressureRenderer(scene,ctx=ctx,direction=angle,seed=seed)
        rows=[];frames=[];started=time.monotonic()
        for t in [.0,.2,.4,.6,.8]:
            a=cpu.render(t);b=gpu.render(t);frames.append(b)
            sa=np.frombuffer(cpu.state.read(),'float32').reshape(-1,8);sb=np.frombuffer(gpu.state.read(),'float32').reshape(-1,8)
            active=(t>cpu.base[:,2])&(t<cpu.base[:,2]+cpu.base[:,6]);err=np.linalg.norm(sa[active,:2]-sb[active,:2],axis=1)
            rows.append(dict(t=t,p99=float(np.quantile(err,.99)) if len(err) else 0,mae=float(np.abs(a.astype(float)-b).mean())))
        np.save(OUT/f'gpu-pressure-{name}.npy',frames);cpu.close();gpu.close();print(name,rows,round(time.monotonic()-started,2),flush=True)
    ctx.release()
