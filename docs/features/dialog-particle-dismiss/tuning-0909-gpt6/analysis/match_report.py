"""同一损失下比较第二版与此前模型（旧参考用已认可的旧共同模型，新参考用上一会话的任意起点候选）。

输出 analysis/match-cache/reports/<label>-table.json 与 Markdown 表；数值只作方向判断，不代替目视。
"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
import match_fit as F
from match_compare import current_candidate
from dissolve_v2 import RULES

SCENES=['ironman','reference-0926-5','spiderman-original','reference-0926-6','reference-0926-7','reference-0926-1',
        'thanos','reference-0926-4','kobe','ironman-up-reference']
HOLDOUT={'ironman-up-reference'}


class Wrap:
    def __init__(self,r):self.r=r
    def set_rules(self,rules):pass
    def render(self,p):return self.r.render(p)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules',required=True);ap.add_argument('--label',required=True);a=ap.parse_args()
    import moderngl
    ctx=moderngl.create_standalone_context(require=430)
    d=json.loads(Path(a.rules).read_text('utf-8'));rules=dataclasses.replace(RULES,**{k:v for k,v in d.get('best',d).get('rules',d).items() if k in dataclasses.asdict(RULES)})
    rows=[]
    for name in SCENES:
        t=F.Target(name,ctx,18)
        v2,parts=t.loss(rules,True)
        t.renderer.close();t.renderer=Wrap(current_candidate(name,ctx))
        old,old_parts=t.loss(None,True)
        rows.append(dict(scene=name,holdout=name in HOLDOUT,v2=v2,previous=old,v2_parts=parts,previous_parts=old_parts))
        print(f'{name:22s} 第二版 {v2:6.2f}  此前 {old:6.2f}',flush=True)
    dialog=F.DialogTarget('attachment',ctx);dv=dialog.loss(rules)
    out=HERE/'analysis/match-cache/reports';out.mkdir(parents=True,exist_ok=True)
    (out/f'{a.label}-table.json').write_text(json.dumps(dict(rules=dataclasses.asdict(rules),rows=rows,dialog_proxy=dv),ensure_ascii=False,indent=1),'utf-8')
    lines=['| 参考 | 第二版 | 此前模型 |','|---|---:|---:|']
    for q in rows:lines.append(f"| {q['scene']}{'（留出）' if q['holdout'] else ''} | {q['v2']:.1f} | {q['previous']:.1f} |")
    (out/f'{a.label}-table.md').write_text('\n'.join(lines)+f'\n\n白底弹窗整体曲线误差（第二版对此前模型）：{dv:.3f}\n','utf-8')
    print('\n'.join(lines))


if __name__=='__main__':main()
