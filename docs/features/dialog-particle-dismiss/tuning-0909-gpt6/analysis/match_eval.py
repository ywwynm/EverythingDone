"""并行评估若干组 V2Rules 的训练损失（与 match_fit 同一损失），用于结构性改动的对照。

用法：match_eval.py --base <规则 JSON> --variants '{"名称":{参数:值,...},...}'
"""
from pathlib import Path
import argparse,dataclasses,json,sys,time
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
import match_fit as F
from dissolve_v2 import RULES

_T=None


def _init(scenes,dialogs):
    global _T
    import os,moderngl
    for k in ['OMP_NUM_THREADS','MKL_NUM_THREADS','OPENBLAS_NUM_THREADS']:os.environ[k]='1'
    ctx=moderngl.create_standalone_context(require=430)
    _T=([F.Target(n,ctx,18) for n in scenes],[F.DialogTarget(n,ctx) for n in dialogs])


def _run(args):
    label,rules=args;rules=dataclasses.replace(RULES,**rules);targets,dialogs=_T
    per={t.name:t.loss(rules) for t in targets}
    total=F.weighted(targets,rules)
    d=float(np.mean([x.loss(rules) for x in dialogs])) if dialogs else 0.
    return label,total+F.DIALOG_WEIGHT*d,per,d


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--base',required=True);ap.add_argument('--variants',required=True);ap.add_argument('--workers',type=int,default=8)
    a=ap.parse_args()
    base=json.loads(Path(a.base).read_text('utf-8'))['best']['rules'];base={k:v for k,v in base.items() if k in dataclasses.asdict(RULES)}
    variants=json.loads(a.variants);jobs=[('base',base)]+[(k,base|v) for k,v in variants.items()]
    import multiprocessing as mp
    with mp.get_context('spawn').Pool(min(a.workers,len(jobs)),initializer=_init,initargs=(F.TRAIN,('attachment',))) as pool:
        res=pool.map(_run,jobs)
    for label,total,per,d in res:
        print(f'{label:24s} 总 {total:7.3f}  弹窗 {d:.3f}  '+' '.join(f'{k[-6:]}:{v:.1f}' for k,v in per.items()),flush=True)


if __name__=='__main__':main()
