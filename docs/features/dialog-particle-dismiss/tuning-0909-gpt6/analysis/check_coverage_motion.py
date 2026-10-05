"""历史 PIV 诊断，不作为运动验收：颗粒缩小与淡出使互相关的位移标定失败。

请使用 check_cloud_evolution.py 和完整画面复核；本实验仅保留以追溯测量取舍。
"""
from pathlib import Path
import json,sys
import moderngl,numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,load_coverage
from surface_transfer import TransferRenderer,load_transfer
from conditional_material import ConditionalRules
from shared_transport import load_transport
from learned_release import load_weights
from fit_particle_optics import common,NAMES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def main():
    root=HERE/'analysis/match-cache/optics-before';out=HERE/'analysis/match-cache/particle-coverage/continuous-motion.json';report={}
    ctx=moderngl.create_standalone_context(require=430);before=dict(weights=load_weights(root/'conditional-release-weights.json'),transport_weights=load_transport(root/'conditional-transport-weights.json'),rules=ConditionalRules(**json.loads((root/'conditional-material-rules.json').read_text('utf-8'))['rules']),transfer=load_transfer(root/'surface-transfer-rules.json'))
    for name in NAMES:
        c=Comparison(name,piv_stride=4);options=dict(ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]])
        rows={}
        for key,cls,kw in [('before',TransferRenderer,before),('candidate',CoverageRenderer,dict(common(),coverage=load_coverage(HERE/'particle-coverage-rules.json')))]:
            r=cls(name,**options,**kw);pairs=c.motion(r.render);r.close()
            errors=[v['field']['median_error'] for v in pairs if v['field'] and v['field']['n']>=5]
            rows[key]=dict(pairs=pairs,matched_pairs=len(errors),median_field_error=float(np.median(errors)) if errors else None)
        report[name]=rows;out.write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print(name,{k:{q:rows[k][q] for q in ['matched_pairs','median_field_error']} for k in rows},flush=True)
    ctx.release()

if __name__=='__main__':main()
