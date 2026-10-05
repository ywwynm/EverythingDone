"""检查共同扩张候选；不修改素材输入、既有模型或正式视频。"""
from pathlib import Path
import argparse,json,sys
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import front_transport
from front_transport import FrontRenderer
from study_front_stages_0926 import Activation,Stages,Elliptic,EllipticPulse,field
from review_reference_suite_0926 import main as review


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--law',default='activation');p.add_argument('--tag');p.add_argument('--rules-json');p.add_argument('--scenes',nargs='+');a=p.parse_args()
    data=json.loads((HERE/f'analysis/new-references-0926/front-{a.law}-study.json').read_text('utf-8'))
    rules={'activation':Activation,'elliptic':Elliptic,'pulse':Stages,'elliptic-pulse':EllipticPulse}[a.law](**data['rules'])
    front_transport.front_field=lambda nx,ny,w,h,d,s,sites,unused:field(nx,ny,w,h,d,s,sites,rules)
    init=FrontRenderer.__init__
    def create(self,name,*args,**kwargs):
        if name in data['seeds']:kwargs['seed']=data['seeds'][name]
        init(self,name,*args,**kwargs)
    FrontRenderer.__init__=create
    sys.argv=[sys.argv[0],'--candidate','--tag',a.tag or 'front-'+a.law]
    if a.rules_json:sys.argv+=['--rules-json',a.rules_json]
    if a.scenes:sys.argv+=['--scenes']+a.scenes
    review()
