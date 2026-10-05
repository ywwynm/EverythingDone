"""检验纹理材料的解体是否应跟随局部形变，而非单独累计出生年龄。"""
from pathlib import Path
import dataclasses,json,sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import front_transport
from front_transport import FrontRenderer,FrontRules
from review_reference_suite_0926 import main as review


if __name__=='__main__':
    FrontRenderer.compute_source=FrontRenderer.compute_source.replace(
        'h*.75*(1.-smoothstep(0.,2.,strain))*deformation_clock',
        'h*.95*(1.-smoothstep(.40,3.0,strain))*deformation_clock')
    for source in ['vertex_source','pressure_splat_source']:
        setattr(FrontRenderer,source,getattr(FrontRenderer,source).replace('smoothstep(.58,.93,time)','smoothstep(.76,.96,time)'))
    rules=dataclasses.asdict(FrontRules(deformation_clock=1.0))
    dest=HERE/'analysis/new-references-0926/cohesion-study-rules.json'
    dest.write_text(json.dumps(rules,indent=2),'utf-8')
    sys.argv=[sys.argv[0],'--tag','sheet-cohesion','--candidate','--rules-json',str(dest)]
    review()
