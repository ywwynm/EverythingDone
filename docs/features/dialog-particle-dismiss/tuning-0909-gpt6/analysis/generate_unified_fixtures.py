"""生成两端独立建材的数值契约输入；不使用照片拟合配置。"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from unified_model import ROOT,materials,release_field,refine_release

out=ROOT/'app/src/test/resources/particle-dismiss'
for direction in [0,45,90,125,180,225,270,315]:
    (out/f'release-{direction}.f32').write_bytes(release_field(37,29,direction).astype('<f4').tobytes())
colors=np.array([[0xffffffff,0xffe83030,0x00ffffff],[0xff2c387e,0xff00dedd,0xffd8d8d8]],dtype='uint32')
rgba=np.stack([(colors>>16)&255,(colors>>8)&255,colors&255,colors>>24],axis=-1).astype('uint8')
cases=[]
for i,(w,h,direction,seed) in enumerate([(120,160,137,909602),(47,211,0,99),(231,39,315,4294967305)]):
    b=materials(w,h,rgba,direction,seed)
    a=b['base'][np.argsort(b['base'][:,3])]
    pigment=b['pigment'][np.argsort(b['base'][:,3])]
    (out/f'materials-{i}.f32').write_bytes(a.astype('<f4').tobytes())
    (out/f'pigment-{i}.f32').write_bytes(pigment.astype('<f4').tobytes())
    compression=b['peel_compression'][np.argsort(b['base'][:,3])]
    (out/f'peel-compression-{i}.f32').write_bytes(compression.astype('<f4').tobytes())
    cases.append(dict(width=w,height=h,direction=direction,seed=seed,n=b['nx']*b['ny']))
print(json.dumps(cases))
# 在实际分辨率的边界、内部及角部冻结少量独立结果，覆盖梯度放大一 ULP 差异的问题。
field=release_field(253,257,122,468,474,909602)
refined=refine_release(field,468,474,122,0.)
ids=np.array([0,182,183,252,16117,32510,57178,60720,65020])
np.column_stack([ids,field.ravel()[ids],refined.ravel()[ids]]).astype('<f4').tofile(out/'refined-edge-cases.f32')
