"""读取当前 GPU 的逐材料几何和九点着色，用于离线可微散布；不进入运行端。"""
from pathlib import Path
import sys,math
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from measure_particle_footprint import geometry_reader


class ParticleProxy:
    def __init__(self,r):
        self.r=r;self.read_geometry,self.close_geometry=geometry_reader(r)
        self.copies=10 if 'fission_strength' in r.program else 1
        self.count=r.n*self.copies
        self.width=2048;self.height=math.ceil(self.count*9/self.width)
        # 中间实例编号不与细分着色器自身的 parent_instance 冲突。
        vertex=r.vertex_source.replace('gl_InstanceID','proxy_instance')
        vertex=vertex.replace('void main(){','uniform vec2 proxy_frame;\nvoid main(){\nint proxy_instance=gl_InstanceID/9;int sample_index=gl_InstanceID%9;',1)
        vertex=vertex.replace('vec2 c=corners[gl_VertexID];','vec2 c=(vec2(sample_index%3,sample_index/3)+.5)/3.;',1)
        vertex=vertex.replace('gl_Position=vec4(world.x/frame.x*2.-1.,1.-world.y/frame.y*2.,0,1);',
             'vec2 proxy_pixel=vec2(gl_InstanceID%int(proxy_frame.x),gl_InstanceID/int(proxy_frame.x))+.5;\n    gl_Position=vec4(proxy_pixel/proxy_frame*2.-1.,0.,1.);\n    gl_PointSize=1.;',1)
        fragment=r.fragment_source.replace('void main(){','uniform float proxy_retention;\nvoid main(){',1)
        fragment=fragment.replace('alpha*=mix(1.,retain,smoothstep(.008,.060,age));','alpha*=mix(1.,mix(1.,retain,proxy_retention),smoothstep(.008,.060,age));',1)
        if self.copies>1:
            # 单位材料保留九个片内样本，不在这里固定母片／子片份额和非线性透过率。
            fragment=fragment.replace('alpha*=fission_weight;','alpha*=mix(1.,fission_weight,proxy_retention);',1)
            formula='1.-pow(max(1.-alpha,.00001),exp((fission_density+scatter_material.x)*smoothstep(.02,.10,age)))'
            fragment=fragment.replace('alpha='+formula+';','alpha=mix(alpha,'+formula+',proxy_retention);',1)
        self.program=r.ctx.program(vertex_shader=vertex,fragment_shader=fragment);self.vao=r.ctx.vertex_array(self.program,[])
        self.tex=r.ctx.texture((self.width,self.height),4,dtype='f4');self.fbo=r.ctx.framebuffer([self.tex])

    def read(self):
        r=self.r;g=self.read_geometry()
        for name in self.program:
            if isinstance(self.program[name],moderngl.Uniform) and name in r.program:self.program[name].value=r.program[name].value
        self.program['proxy_frame']=(self.width,self.height);self.program['material_pass']=1;self.program['diagnostic']=0
        self.fbo.use();self.fbo.clear(0,0,0,0);r.ctx.disable(moderngl.BLEND);self.program['proxy_retention']=1.
        self.vao.render(mode=moderngl.POINTS,vertices=1,instances=self.count*9)
        values=np.frombuffer(self.tex.read(),np.float32).reshape(-1,4)[:self.count*9].reshape(self.count,9,4).mean(1)
        color=values[:,:3]/np.maximum(values[:,3:],1e-6)
        mass=values[:,3]*g['area']
        self.program['proxy_retention']=0.;self.fbo.clear(0,0,0,0)
        self.vao.render(mode=moderngl.POINTS,vertices=1,instances=self.count*9)
        samples=np.frombuffer(self.tex.read(),np.float32).reshape(-1,4)[:self.count*9].reshape(self.count,9,4).copy()
        unit=samples.mean(1)
        color=unit[:,:3]/np.maximum(unit[:,3:],1e-6)
        result={**g,'color':color,'mass':mass,'unit_mass':unit[:,3]*g['area']}
        if self.copies>1:result['unit_alpha']=samples[:,:,3]
        return result

    def surface(self):
        r=self.r;r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
        r.program['material_pass']=0;r.fg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST)
        r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
        return np.frombuffer(r.tex.read(),np.float16).reshape(round(r.h*2),round(r.w*2),4)[::-1].astype(np.float32)

    def close(self):
        self.close_geometry()
        if self.r.ctx.fbo is self.fbo:self.r.ctx.detect_framebuffer(0).use()
        for obj in [self.vao,self.program,self.fbo,self.tex]:obj.release()
