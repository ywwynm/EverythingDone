"""未知种子只按启动段约束选取；后续帧不参与选择，所有场景共享随机分布。"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import FrontRules,ReleaseSite,front_field
from reference_observations_0926 import OBSERVED_SITES
from export_videos import load_meta
OUT=HERE/'analysis/new-references-0926'


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--rules-json');args=parser.parse_args()
    rules=FrontRules(**json.loads(Path(args.rules_json).read_text('utf-8'))) if args.rules_json else FrontRules()
    records=[]
    for name,positions in OBSERVED_SITES.items():
        m=load_meta(name);x,y,x1,y1=m['rect'];d=np.load(OUT/f'{name}-surface-measurements.npz')
        measured=d['arrival'];valid=d['initial_valid'];ny,nx=measured.shape;sites=[ReleaseSite(*q) for q in positions]
        early=valid&(measured>.025)&(measured<=.20);later=valid&(measured>.20)
        best=None
        for seed in range(2048):
            field=front_field(nx,ny,x1-x,y1-y,m['direction'],seed,sites,rules)
            loss=float(np.minimum(np.abs(field-measured)[early],.20).mean())
            loss+=float(np.maximum(.20-field[later],0).mean())
            if best is None or loss<best[0]:best=(loss,seed)
        m['seed']=best[1];m['seed_note']='未知原始种子；仅用前 0.20 进度的解除观测选择共同随机模型的初始条件，后段不参与选取。'
        (HERE/'assets'/name/'scene.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),'utf-8')
        records.append(dict(scene=name,seed=best[1],prefix_loss=best[0],prefix_limit=.20,early_samples=int(early.sum()),tested=2048,rules=dataclasses.asdict(rules)))
        print(name,best,flush=True)
    (OUT/'initial-seeds.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
