"""桌面共同细粒材质；所有布局共用几何函数，不读取素材身份或参考帧。"""
import json
import numpy as np,moderngl
from particle_fission import FissionRenderer
from shared_transport import transport_features
from learned_release import canonical_sites

def load_scatter(path):
    d=json.loads(path.read_text('utf-8'))
    return dict(scales=np.asarray(d['scales'],np.float32),layers=[(np.asarray(v['weight'],np.float32),np.asarray(v['bias'],np.float32)) for v in d['layers']])

def scatter_field(x,y,t,w,h,direction,sites,weights):
    if weights is None:return np.zeros((*x.shape,3),np.float32)
    z=transport_features(x,y,t,w,h,direction,sites,True)
    for i,(a,b) in enumerate(weights['layers']):
        z=z@a.T+b
        if i<len(weights['layers'])-1:z=np.tanh(z)
    return (np.tanh(z)*weights['scales']).astype(np.float32)

class ScatterRenderer(FissionRenderer):
    vertex_source=FissionRenderer.vertex_source.replace('void main(){','''
uniform sampler3D scatter_field;
uniform vec3 scatter_delta;
flat out vec3 scatter_material;
void main(){''',1)
    vertex_source=vertex_source.replace('float projected=scale*','''
    scatter_material=texture(scatter_field,vec3((move/card+vec2(.38))/1.76,phase)).rgb+scatter_delta;
    float projected=scale*''',1)
    fragment_source=FissionRenderer.fragment_source.replace('void main(){','flat in vec3 scatter_material;\nvoid main(){',1)
    fragment_source=fragment_source.replace('fission_density*smoothstep','(fission_density+scatter_material.x)*smoothstep')
    fragment_source=fragment_source.replace('vec3(fission_neutral)+fission_chroma*(color-fission_luma)','vec3(fission_neutral+scatter_material.y)+(fission_chroma+scatter_material.z)*(color-fission_luma)')

    def __init__(self,*a,scatter=None,**kw):
        super().__init__(*a,**kw)
        n=64;y,x=np.mgrid[:n,:n].astype(np.float32);x=-.38+(x+.5)/n*1.76;y=-.38+(y+.5)/n*1.76
        sites=self.origin_sites or canonical_sites(self.cw,self.ch,self.direction)
        values=np.array([scatter_field(x,y,t,self.cw,self.ch,self.direction,sites,scatter) for t in np.linspace(0,1,41)],np.float32)
        self.scatter_tex=self.ctx.texture3d((n,n,41),3,values.tobytes(),dtype='f4');self.scatter_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.scatter_tex.repeat_x=self.scatter_tex.repeat_y=self.scatter_tex.repeat_z=False
        self.program['scatter_field']=11;self.program['scatter_delta']=(0.,0.,0.)
        self.program['fission_strength']=1.;self.program['fission_threshold']=.006;self.program['fission_spread']=.010

    def render(self,time,diagnostic=0):
        self.scatter_tex.use(11);return super().render(time,diagnostic)

    def close(self):
        super().close();self.scatter_tex.release()
