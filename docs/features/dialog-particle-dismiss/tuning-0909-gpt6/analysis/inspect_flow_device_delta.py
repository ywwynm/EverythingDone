"""固定材料输入，对单次设备差异区分建材、纹理采样和积分路径。"""
from pathlib import Path
import json, sys
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import guidance
import moderngl

r=Renderer('attachment',direction=130,seed=323)
if '--half-texture' in sys.argv:
    r.flow_tex.release()
    flow=guidance().astype('<f2')
    r.flow_tex=r.ctx.texture3d(tuple(flow.shape[2::-1]),2,flow.tobytes(),dtype='f2')
    r.flow_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
    r.flow_tex.repeat_x=r.flow_tex.repeat_y=r.flow_tex.repeat_z=False
folder=HERE/'device-flow-family-multiple/9018f404/generated'
got=np.fromfile(folder/'attachment-materials.f32',dtype='<f4').reshape(-1,12)
state=np.fromfile(folder/'attachment-state034.f32',dtype='<f4').reshape(-1,8)
indices=np.argsort(got[:,3]);order=np.argsort(r.base[:,3]);expected=r.base[order]
t=float(np.float32(34/60));r.seek(t)
ref=np.frombuffer(r.state.read(),dtype='<f4').reshape(-1,8)[order].copy()
delta=np.linalg.norm(state[indices,:2]-ref[:,:2],axis=1)
visible=(expected[:,2]<t)&(expected[:,2]+expected[:,6]>t)
ids=np.flatnonzero(visible);worst=ids[np.argsort(delta[visible])[-12:]]
rows=[dict(id=float(expected[i,3]),delta=float(delta[i]),source=expected[i,:].tolist(),
    material_delta=(got[indices[i]]-expected[i]).tolist(),desktop=ref[i].tolist(),device=state[indices[i]].tolist()) for i in worst]
print(json.dumps(rows,ensure_ascii=False,indent=2))
suffix='half' if '--half-texture' in sys.argv else 'float'
(HERE/f'analysis/flow-family/device-delta-{suffix}.json').write_text(json.dumps(rows,indent=2),'utf-8')
r.close()
