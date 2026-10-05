"""以实际粒子分布驱动二维连续流场的独立试验，不覆盖已验收求解器。"""
from pathlib import Path
import argparse,sys
import moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import STEP
from front_transport import FrontRenderer

FLOW=r'''#version 430
layout(local_size_x=16,local_size_y=16) in;
layout(std430,binding=4) readonly buffer D{vec4 density[];};
layout(std430,binding=5) readonly buffer A{vec4 previous[];};
layout(std430,binding=6) buffer B{vec4 next_field[];};
layout(std430,binding=7) readonly buffer P{float pressure[];};
layout(std430,binding=8) writeonly buffer Q{float next_pressure[];};
layout(rgba32f,binding=1) uniform writeonly image2D output_field;
uniform ivec2 size;uniform int pass;uniform vec2 wind;
uniform float dt,cell,force,confinement;
int at(ivec2 p){p=clamp(p,ivec2(0),size-1);return p.y*size.x+p.x;}
vec2 vel(ivec2 p){return previous[at(p)].xy;}
vec2 sample_velocity(vec2 p){
 if(any(lessThan(p,vec2(0)))||any(greaterThan(p,vec2(size-1))))return vec2(0);
 ivec2 q=ivec2(floor(p));vec2 f=fract(p);
 return mix(mix(vel(q),vel(q+ivec2(1,0)),f.x),mix(vel(q+ivec2(0,1)),vel(q+ivec2(1,1)),f.x),f.y);
}
float curl(ivec2 q){return .5*(vel(q+ivec2(1,0)).y-vel(q-ivec2(1,0)).y-vel(q+ivec2(0,1)).x+vel(q-ivec2(0,1)).x);}
void main(){
 ivec2 q=ivec2(gl_GlobalInvocationID.xy);if(any(greaterThanEqual(q,size)))return;
 int k=at(q);ivec2 X=ivec2(1,0),Y=ivec2(0,1);
 if(pass==0){
  vec2 v=sample_velocity(vec2(q)-vel(q)*dt)*exp(-dt*.6);
  float mass=max(density[k].z,0.);
  v+=wind*(96.*force*mass/(1.+mass))*dt;
  vec2 grad=vec2(abs(curl(q+X))-abs(curl(q-X)),abs(curl(q+Y))-abs(curl(q-Y)));
  vec2 n=grad/(length(grad)+.0001);
  v+=vec2(n.y,-n.x)*curl(q)*confinement*dt;
  next_field[k]=vec4(v,0,0);return;
 }
 if(pass==1){
  float div=.5*(vel(q+X).x-vel(q-X).x+vel(q+Y).y-vel(q-Y).y);
  next_field[k]=vec4(vel(q),div,curl(q));next_pressure[k]=0.;return;
 }
 if(pass==2){
  float sum=pressure[at(q-X)]+pressure[at(q+X)]+pressure[at(q-Y)]+pressure[at(q+Y)];
  next_pressure[k]=(sum-previous[k].z)*.25;return;
 }
 vec2 v=vel(q)-.5*vec2(pressure[at(q+X)]-pressure[at(q-X)],pressure[at(q+Y)]-pressure[at(q-Y)]);
 next_field[k]=vec4(v,0,0);imageStore(output_field,q,vec4(v*cell,0,0));
}
'''


class DensityFlow:
    def __init__(self,r,force,confinement):
        self.r=r;self.ctx=r.ctx;self.shape=r.pressure_grid.shape;self.n=self.shape[0]*self.shape[1]
        self.vel=[self.ctx.buffer(reserve=self.n*16) for _ in range(2)]
        self.pressures=[self.ctx.buffer(reserve=self.n*4) for _ in range(2)]
        self.texture=self.ctx.texture(self.shape,4,dtype='f4');self.texture.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.texture.repeat_x=False;self.texture.repeat_y=False
        self.shader=self.ctx.compute_shader(FLOW)
        for k,v in dict(size=self.shape,wind=r.wind,dt=4*STEP,cell=r.pressure_grid.cell,force=force,confinement=confinement).items():self.shader[k]=v
        r.compute['density_flow']=6;r.compute['density_bounds']=r.pressure_grid.bounds
        self.reset()
    def reset(self):
        self.current=0;self.p=0
        for a in self.vel+self.pressures:a.clear()
        self.texture.write(bytes(self.n*16))
    def run(self,p):
        self.vel[self.current].bind_to_storage_buffer(5);self.vel[1-self.current].bind_to_storage_buffer(6)
        self.pressures[self.p].bind_to_storage_buffer(7);self.pressures[1-self.p].bind_to_storage_buffer(8)
        self.shader['pass']=p;self.shader.run(group_x=(self.shape[0]+15)//16,group_y=(self.shape[1]+15)//16);self.ctx.memory_barrier()
        if p!=2:self.current=1-self.current
        if p in [1,2]:self.p=1-self.p
    def update(self):
        self.r.pressure_grid.velocity.bind_to_storage_buffer(4);self.texture.bind_to_image(1,read=False,write=True)
        self.run(0);self.run(1)
        for i in range(90):self.run(2)
        self.run(3)
    def close(self):
        for a in self.vel+self.pressures+[self.texture,self.shader]:a.release()


class DensityRenderer(FrontRenderer):
    compute_source=FrontRenderer.compute_source.replace('uniform int release_site_count;',
        'uniform sampler2D density_flow;uniform vec4 density_bounds;\nuniform int release_site_count;')
    compute_source=compute_source.replace('vec2 velocity=wind*span*front_carry;',
        'return texture(density_flow,(p-density_bounds.xy)/density_bounds.zw).xy;\nvec2 velocity=wind*span*front_carry;')
    # 返回值不再依赖解析涡系数，这些 uniform 仍通过一个严格为零的外部系数保留。
    compute_source=compute_source.replace('return texture(density_flow,(p-density_bounds.xy)/density_bounds.zw).xy;',
        'return texture(density_flow,(p-density_bounds.xy)/density_bounds.zw).xy + '
        'wind*front_carry*span*0.000001 + release_sites[0]*front_curl*release_starts[0]*0.000001;')
    def __init__(self,*args,force=5.,confinement=1.5,**kwargs):
        super().__init__(*args,**kwargs);self.density=DensityFlow(self,force,confinement)
    def reset(self):
        super().reset()
        if hasattr(self,'density'):self.density.reset()
    def seek(self,time):
        target=int(max(time,0)/STEP+1e-6)
        if target<self.step:self.reset()
        self.pressure_grid.texture.use(5);self.density.texture.use(6)
        self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1)
        self.pigment.bind_to_storage_buffer(2);self.peel_compression.bind_to_storage_buffer(3)
        self.flow_tex.use(3);self.confidence_tex.use(4)
        while self.step<target:
            self.step+=1;self.compute['time']=self.step*STEP;self.compute.run(group_x=(self.n+255)//256);self.ctx.memory_barrier()
            if self.step%4==0:
                self.pressure_grid.update(self.step*STEP);self.density.update()
                self.material.bind_to_storage_buffer(0);self.state.bind_to_storage_buffer(1)
                self.pigment.bind_to_storage_buffer(2);self.peel_compression.bind_to_storage_buffer(3)
    def close(self):
        self.density.close();super().close()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--force',type=float,default=5.)
    parser.add_argument('--confinement',type=float,default=1.5);args=parser.parse_args()
    class Configured(DensityRenderer):
        def __init__(self,*a,**kw):super().__init__(*a,force=args.force,confinement=args.confinement,**kw)
    import front_transport
    front_transport.FrontRenderer=Configured
    from review_reference_suite_0926 import main as review
    sys.argv=[sys.argv[0],'--candidate','--tag',f'density-flow-{args.force:g}']
    review()
