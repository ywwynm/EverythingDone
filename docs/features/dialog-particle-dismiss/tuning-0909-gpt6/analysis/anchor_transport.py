"""用当前完整颗粒画面校准输运梯度的展开点，仅用于离线优化。"""
from pathlib import Path
import hashlib,json
import cv2,numpy as np,moderngl
from fit_shared_flow_basis import HERE,N,LO,SIZE
from particle_coverage import CoverageRenderer,CoverageRules
from conditional_material import ConditionalRules
from shared_transport import load_transport
from learned_release import load_weights
from surface_transfer import load_transfer,TransferRules
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def actual_frames(release,flow,coverage,transfer,names,scene_ids,phases):
    sources=[release,flow,coverage,HERE/'particle_coverage.py',HERE/'shared_transport.py',HERE/'renderer.py',HERE/'surface_transfer.py']
    if transfer:sources.append(Path(transfer))
    identity=hashlib.sha256(b'actual-anchor-1'+b''.join(p.read_bytes() for p in sources)+json.dumps(names).encode()+np.asarray(phases).tobytes()).hexdigest()[:16]
    path=HERE/'analysis/match-cache/shared-transport'/f'anchor-{identity}.npz'
    if path.is_file():return np.load(path)['frames']
    cfg=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules']);tr=load_transfer(HERE/'surface-transfer-rules.json')
    if transfer:
        d=json.loads(Path(transfer).read_text('utf-8'));cfg=ConditionalRules(**d['material']);tr=TransferRules(**d['transfer'])
    kw=dict(weights=load_weights(release),transport_weights=load_transport(flow),coverage=CoverageRules(**json.loads(coverage.read_text('utf-8'))),rules=cfg,transfer=tr)
    ctx=moderngl.create_standalone_context(require=430);result=[]
    for ni,name in enumerate(names):
        r=CoverageRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        x,y,x1,y1=r.meta['rect'];yy,xx=np.mgrid[:N,:N].astype('float32');xx=x+(LO+(xx+.5)/N*SIZE)*(x1-x);yy=y+(LO+(yy+.5)/N*SIZE)*(y1-y)
        for p in phases[scene_ids==ni]:
            f=r.render(float(p)).astype('float32')/255
            f=cv2.GaussianBlur(f,(0,0),min(x1-x,y1-y)/N*.50)
            result.append(cv2.remap(f,xx,yy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT))
        r.close();print('真实输运展开点',name,flush=True)
    ctx.release();result=np.asarray(result,np.float32);np.savez_compressed(path,frames=result);return result
