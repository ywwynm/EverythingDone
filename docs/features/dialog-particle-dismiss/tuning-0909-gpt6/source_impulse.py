"""材料自身年龄驱动的连续运动实验，尚未接入默认模型。

只在出生位置计算一份速度、加速度场；不以全局进度周期性改变位置。
几何条件和规则共用，运行不读取素材名、原片或逐参考控制图。
"""
import json,math
import numpy as np,moderngl
from scatter_material import ScatterRenderer
from shared_transport import transport_features
from learned_release import canonical_sites

VELOCITY=.24
ACCELERATION=.45
RAMP=.045
FIELD=56


def load_impulse(path):
    value=json.loads(path.read_text('utf-8'))
    return [(np.asarray(v['weight'],np.float32),np.asarray(v['bias'],np.float32)) for v in value['layers']]


def impulse_field(x,y,w,h,direction,sites,weights):
    if weights is None:return np.zeros((*x.shape,4),np.float32)
    value=transport_features(x,y,0.,w,h,direction,sites,True)
    for i,(matrix,bias) in enumerate(weights):
        value=value@matrix.T+bias
        if i<len(weights)-1:value=np.tanh(value)
    value=np.tanh(value);angle=math.radians(direction)
    c,s=math.cos(angle),-math.sin(angle);rot=np.array([[c,s],[-s,c]],np.float32)
    return np.concatenate([value[...,:2]@rot*VELOCITY,value[...,2:]@rot*ACCELERATION],-1).astype(np.float32)


class SourceImpulseRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){','uniform sampler2D source_impulse;\nvoid main(){',1)
    vertex_source=vertex_source.replace('density_out=transport.z*smoothstep(0.,.03,age);','''
    density_out=transport.z*smoothstep(0.,.03,age);
    vec4 impulse=texture(source_impulse,(m.src.xy/card+vec2(.38))/1.76);
    float elapsed=max(age,0.);
    float travel=elapsed*elapsed/(elapsed+.045);
    move+=(impulse.xy*travel+impulse.zw*(.5*elapsed*elapsed))*span;
''',1)

    def __init__(self,*args,impulse=None,**kwargs):
        super().__init__(*args,**kwargs)
        y,x=np.mgrid[:FIELD,:FIELD].astype(np.float32)
        x=-.38+(x+.5)/FIELD*1.76;y=-.38+(y+.5)/FIELD*1.76
        sites=self.origin_sites or canonical_sites(self.cw,self.ch,self.direction)
        field=impulse_field(x,y,self.cw,self.ch,self.direction,sites,impulse)
        self.impulse_tex=self.ctx.texture((FIELD,FIELD),4,field.tobytes(),dtype='f4')
        self.impulse_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.impulse_tex.repeat_x=self.impulse_tex.repeat_y=False
        self.program['source_impulse']=12

    def render(self,*args,**kwargs):
        self.impulse_tex.use(12)
        return super().render(*args,**kwargs)

    def close(self):
        super().close();self.impulse_tex.release()
