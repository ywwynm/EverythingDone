"""以云团时序观测量复核原片，避免把不可靠的 PIV 当作颗粒轨迹真值。"""
from pathlib import Path
import sys,json,math,hashlib
import numpy as np,cv2,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,CoverageRules,load_coverage
from particle_cohorts import CohortRenderer,load_cohorts
from shared_transport import load_transport
from fit_particle_optics import common,NAMES
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison


def configuration(root, *, scatter=True):
    """读取完整候选；旧材料消融显式关闭细粒层以保持实验可重放。"""
    from conditional_material import ConditionalRules
    from learned_release import load_weights
    from surface_transfer import TransferRenderer,load_transfer
    from surface_coherence import CoherenceRenderer,load_coherence
    from scatter_material import ScatterRenderer,load_scatter
    options=dict(weights=load_weights(root/'conditional-release-weights.json'),
        transport_weights=load_transport(root/'conditional-transport-weights.json'),
        rules=ConditionalRules(**json.loads((root/'conditional-material-rules.json').read_text('utf-8'))['rules']),
        transfer=load_transfer(root/'surface-transfer-rules.json'))
    cls=TransferRenderer
    for file,key,loader,renderer in [('particle-coverage-rules.json','coverage',load_coverage,CoverageRenderer),
        ('particle-cohort-weights.json','cohorts',load_cohorts,CohortRenderer),
        ('surface-coherence-weights.json','coherence',load_coherence,CoherenceRenderer),
        ('particle-scatter-weights.json','scatter',load_scatter,ScatterRenderer)]:
        if key=='scatter' and not scatter:continue
        if (root/file).is_file():options[key]=loader(root/file);cls=renderer
    return cls,options


def main():
    from export_shared_candidate import signature
    manifest=json.loads((HERE/'videos/shared-field-manifest.json').read_text('utf-8'))
    previous=Path(manifest['previous']);previous=previous if previous.is_absolute() else HERE/previous
    h=hashlib.sha256(signature().encode())
    for name in ['conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json','particle-scatter-weights.json']:
        if (previous/name).is_file():h.update((previous/name).read_bytes())
    identity=h.hexdigest();assert identity==manifest['hash']
    ctx=moderngl.create_standalone_context(require=430);out={}
    variants={'before':configuration(previous),'candidate':configuration(HERE)}
    for name in NAMES:
        c=Comparison(name,stride=3);scene=c.scene
        rs={key:cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**options) for key,(cls,options) in variants.items()}
        a=math.radians(scene.meta['direction']);wind=np.array([math.cos(a),-math.sin(a)]);cross=np.array([-wind[1],wind[0]])
        def measure(f):
            observation=scene.observe(f);par=observation['particle']&scene.roi;y,x=np.nonzero(par)
            occ,weight=scene.blocks(par);pos=np.stack([x,y],-1).astype('float32')/scene.span
            geometry=None
            if len(x)>=200:
                along=pos@wind;across=pos@cross
                geometry=dict(centroid=pos.mean(0),front=float(np.quantile(along,.95)),back=float(np.quantile(along,.05)),width=float(np.quantile(across,.95)-np.quantile(across,.05)))
            return occ,weight>.5,geometry
        rows=[]
        for i in c.index:
            p=float(c.ref.progress[i])
            ref,w,geo=measure(np.asarray(c.ref.frames[i]));row=dict(phase=p,errors={})
            for key,r in rs.items():
                occ,_,g=measure(r.render(p));e=dict(cloud_map=float(np.abs(occ-ref)[w].mean()))
                if geo is not None and g is not None:
                    e.update(centroid=float(np.linalg.norm(g['centroid']-geo['centroid'])),front=abs(g['front']-geo['front']),back=abs(g['back']-geo['back']),width=abs(g['width']-geo['width']))
                row['errors'][key]=e
            rows.append(row)
        for r in rs.values():r.close()
        result={key:{metric:float(np.mean([v['errors'][key][metric] for v in rows if metric in v['errors'][key]])) for metric in ['cloud_map','centroid','front','back','width']} for key in variants}
        out[name]=dict(summary=result,rows=rows);print(name,result,flush=True)
        target=HERE/'analysis/match-cache/shared-candidate/cloud-evolution.json';target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(dict(hash=identity,previous=str(previous),scenes=out),ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
