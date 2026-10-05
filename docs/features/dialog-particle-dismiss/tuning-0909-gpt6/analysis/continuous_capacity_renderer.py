"""只供离线容量诊断的完整时间渲染器；禁止作为共同运行模型发布。"""
from pathlib import Path
import cv2, moderngl, numpy as np
from scipy.ndimage import map_coordinates
from scatter_material import ScatterRenderer
from fit_continuous_transport_capacity import time_basis, FIELD
from check_particle_proxy import SIZE


def renderer(path):
    saved=np.load(Path(path));names=list(saved['names']);controls=saved['controls']

    class CapacityRenderer(ScatterRenderer):
        vertex_source=ScatterRenderer.vertex_source.replace('void main(){','uniform sampler3D capacity_field;\nvoid main(){',1)
        vertex_source=vertex_source.replace('vec3 transport=texture(transport_field,vec3(q,phase)).xyz;',
            'vec3 transport=texture(transport_field,vec3(q,phase)).xyz;transport.xy+=texture(capacity_field,vec3(q,phase)).xy;',1)

        def __init__(self,name,*args,**kwargs):
            super().__init__(name,*args,**kwargs)
            if name not in names:raise ValueError('该布局没有离线容量结果，不能自动使用其它素材的控制')
            control=controls[names.index(name)];scale=np.array([self.span/self.cw,self.span/self.ch],np.float32)
            bases=np.frombuffer(self.transport_tex.read(),np.float32).reshape(41,FIELD,FIELD,3)
            yy,xx=np.mgrid[:FIELD,:FIELD].astype(np.float32);volume=[]
            for i,t in enumerate(np.linspace(0,1,41)):
                raw=np.einsum('k,kxyc->xyc',time_basis(t),control)
                raw=np.tanh(cv2.resize(raw,(FIELD,FIELD),interpolation=cv2.INTER_LINEAR))
                v=raw*float(saved['magnitude'])*t*scale/2**int(saved['flow_steps'])
                for _ in range(int(saved['flow_steps'])):
                    v=v+np.stack([map_coordinates(v[...,k],[yy+v[...,1]*FIELD/SIZE,xx+v[...,0]*FIELD/SIZE],order=1,mode='nearest') for k in range(2)],-1)
                coords=[yy+bases[i,...,1]*scale[1]*FIELD/SIZE,xx+bases[i,...,0]*scale[0]*FIELD/SIZE]
                volume.append(np.stack([map_coordinates(v[...,k]/scale[k],coords,order=1,mode='nearest') for k in range(2)],-1))
            self.capacity_tex=self.ctx.texture3d((FIELD,FIELD,41),2,np.asarray(volume,np.float32).tobytes(),dtype='f4')
            self.capacity_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
            self.capacity_tex.repeat_x=self.capacity_tex.repeat_y=self.capacity_tex.repeat_z=False
            self.program['capacity_field']=12

        def render(self,*args,**kwargs):
            self.capacity_tex.use(12)
            return super().render(*args,**kwargs)

        def close(self):
            super().close();self.capacity_tex.release()

    return CapacityRenderer
