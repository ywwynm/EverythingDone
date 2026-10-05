"""允许连续空间涡的局部回转；全局漂移与独立扰动仍有顺向约束。"""
from pathlib import Path
import argparse,dataclasses,json,sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import FrontRenderer,FrontRules
from review_reference_suite_0926 import main as review


def configure():
    # 不能把无散卷动的反向半圈逐点压平，否则完整涡会退化为固定的密度边界。
    # 只取解析卷动项；全局负 carry 不获得豁免，随机力也不能产生随意反弹。
    FrontRenderer.compute_source=FrontRenderer.compute_source.replace(
        'float axial=dot(target,wind)/span;',
        'vec2 resolved_roll=release_site_count>0 ? (front_flow(p,time)-wind*span*front_carry)*(1.-front_template) : vec2(0);\n'
        'float axial=dot(target-resolved_roll,wind)/span;')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--tag',default='boundary-turn');p.add_argument('--curl',type=float,default=.5);p.add_argument('--carry',type=float,default=.05);p.add_argument('--scenes',nargs='+');p.add_argument('--material',action='store_true');a=p.parse_args()
    configure()
    params={}
    if a.material:
        from study_material_transport_0926 import configure as material
        material();params=dict(dissipation=.8,dissipation_speed=.65,coverage=1.1,lighting=.55)
    path=HERE/'analysis/new-references-0926'/f'{a.tag}.json'
    path.write_text(json.dumps(dataclasses.asdict(FrontRules(curl=a.curl,carry=a.carry,template=.0,**params)),indent=2),'utf-8')
    sys.argv=[sys.argv[0],'--candidate','--tag',a.tag,'--rules-json',str(path)]
    if a.scenes:sys.argv+=['--scenes']+a.scenes
    review()
