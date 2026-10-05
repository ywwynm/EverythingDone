"""行程驱动的解体时钟必须同步控制面积与寿命，不能只延长几乎不可见的细粒。"""
from pathlib import Path
import argparse,dataclasses,json,sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import FrontRenderer,FrontRules
from review_reference_suite_0926 import main as review


def configure():
    FrontRenderer.vertex_source=FrontRenderer.vertex_source.replace(
        'float material_age=max(0.,age-s.vel.w*(1.-smoothstep(.58,.93,time)));\nfloat exposure=mix(material_age,s.pos.w/cloud_speed,cloud_dissipation);',
        'float exposure=min(age,mix(age,s.pos.w/cloud_speed,cloud_dissipation));\nfloat material_age=exposure;')
    FrontRenderer.pressure_splat_source=FrontRenderer.pressure_splat_source.replace(
        'float exposure=mix(material_age,states[i].pos.w/cloud_speed,cloud_dissipation);',
        'float exposure=min(age,mix(age,states[i].pos.w/cloud_speed,cloud_dissipation));')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--tag',default='material-transport');p.add_argument('--clock',type=float,default=.8);p.add_argument('--speed',type=float,default=.65);p.add_argument('--coverage',type=float,default=1.1);p.add_argument('--lighting',type=float,default=.55);p.add_argument('--scenes',nargs='+');a=p.parse_args()
    configure()
    path=HERE/'analysis/new-references-0926'/f'{a.tag}.json'
    path.write_text(json.dumps(dataclasses.asdict(FrontRules(dissipation=a.clock,dissipation_speed=a.speed,coverage=a.coverage,lighting=a.lighting)),indent=2),'utf-8')
    sys.argv=[sys.argv[0],'--candidate','--tag',a.tag,'--rules-json',str(path)]
    if a.scenes:sys.argv+=['--scenes']+a.scenes
    review()
