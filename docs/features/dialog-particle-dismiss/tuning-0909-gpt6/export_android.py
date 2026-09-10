"""把已冻结桌面着色器转换为 GLES 3.1 资源，导出固定验证输入。"""
from pathlib import Path
import json,hashlib,shutil
import numpy as np
from renderer import COMPUTE,VERTEX,FRAGMENT,FULLVERT,HERE
from unified_model import model_fingerprint,RULES
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
def export_resources():
    for name,source in [('step.comp',COMPUTE),('material.vert',VERTEX),('material.frag',FRAGMENT),('resolve.vert',FULLVERT),('resolve.frag',resolve)]:
        (DEST/name).write_text(convert(name,source),encoding='utf-8',newline='\n')
    metadata={'model':'共同释放与输运的微片消散','model_hash':model_fingerprint(),'integration_hz':240,'flow_shape':[int(RULES['flow_time']),int(RULES['flow_height']),int(RULES['flow_width']),2],'release_shape':[int(RULES['release_height']),int(RULES['release_width'])],'flow_dtype':'little-endian float16','release_dtype':'little-endian float32','files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in DEST.iterdir() if p.suffix in ['.comp','.vert','.frag','.f16','.f32','.properties']},'scope':'共同表示从认可的观测结果提炼；所有素材共用，运行时不读取照片配置、参考视频或原始光流。'}
    (DEST/'model.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')


def export_fixtures():
    fixtures=HERE/'android-unified-fixtures';fixtures.mkdir(exist_ok=True)
    for path in sorted((HERE/'assets').glob('*/scene.json')):
        m=json.loads(path.read_text('utf-8'));name=m['name']
        shutil.copyfile(path.parent/'foreground.png',fixtures/f'{name}.png')
        # 只交给设备快照及实际输入，材料数组由设备独立建立。
        data={key:m[key] for key in ['name','frame','rect','direction','seed']}
        (fixtures/f'{name}.json').write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')
        print('Android 独立建材输入',name,flush=True)


if __name__=='__main__':
    export_resources();export_fixtures()
