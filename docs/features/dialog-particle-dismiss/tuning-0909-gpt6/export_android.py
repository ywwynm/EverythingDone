"""把已冻结桌面着色器转换为 GLES 3.1 资源，导出固定验证输入。"""
from pathlib import Path
import json,hashlib
import numpy as np
from renderer import COMPUTE,VERTEX,FRAGMENT,FULLVERT,HERE,Renderer
from export_videos import VERSION,code_hash
from android_shaders import convert
REPO=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
DEST=REPO/'shared/particle-dismiss';DEST.mkdir(parents=True,exist_ok=True)
def es(source):
    return source.replace('#version 430','#version 310 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler3D;').replace('if(i>=count)', 'if(i>=uint(count))')
resolve=r'''#version 310 es
precision highp float;
precision highp sampler2D;
uniform sampler2D screen;
in vec2 uv;out vec4 frag;
vec3 srgb(vec3 c){return mix(c*12.92,1.055*pow(max(c,0.),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
void main(){
    vec4 p=texture(screen,vec2(uv.x,1.-uv.y));
    if(p.a<=.00001){frag=vec4(0.);return;}
    float a=clamp(p.a,0.,1.);
    frag=vec4(clamp(srgb(p.rgb/max(a,.00001)),0.,1.)*a,a);
}
'''
for name,source in [('step.comp',COMPUTE),('material.vert',VERTEX),('material.frag',FRAGMENT),('resolve.vert',FULLVERT),('resolve.frag',resolve)]:
    (DEST/name).write_text(convert(name,source),encoding='utf-8')
flow=np.load(HERE/'assets/common-flow.npy');(DEST/'common-flow.f16').write_bytes(flow.astype('<f2').tobytes())
metadata={'version':VERSION,'desktop_hash':code_hash(),'integration_hz':240,'flow_shape':[32,36,36,2],'flow_dtype':'little-endian float16','files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in DEST.iterdir() if p.suffix in ['.comp','.vert','.frag','.f16']}}
(DEST/'model.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
# 固定材料只供两端像素／轨迹验证，正式运行仍由实际弹窗快照实时建立材料。
fixtures=HERE/'android-fixtures';fixtures.mkdir(exist_ok=True)
for name in ['ironman','thanos','kobe','language','color','attachment','attachment-image']:
    r=Renderer(name,quality=1)
    (fixtures/f'{name}.materials.f32').write_bytes(r.base.astype('<f4').tobytes())
    (fixtures/f'{name}.pigment.f32').write_bytes(r.pigment.read())
    (fixtures/f'{name}.flow.f16').write_bytes(np.frombuffer(r.flow_tex.read(),dtype='float32').astype('<f2').tobytes())
    meta=dict(r.meta,nx=r.nx,ny=r.ny,cell=r.cell,body_weight=r.body_weight,guide_direction=r.meta['direction'] if name in ['ironman','thanos','kobe'] else 90)
    (fixtures/f'{name}.json').write_text(json.dumps(meta,ensure_ascii=False),encoding='utf-8')
    np.save(fixtures/f'{name}.release.npy',r.base[:,2])
    r.render(.56);(fixtures/f'{name}.state056.f32').write_bytes(r.state.read())
    r.close();print('Android 导出',name,flush=True)
print(DEST,metadata['desktop_hash'],flush=True)
