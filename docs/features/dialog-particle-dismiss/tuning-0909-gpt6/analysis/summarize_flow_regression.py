"""汇总全量状态对照，并用额外的未调参输入检查真实 GPU 运动性质。"""
from pathlib import Path
import json,sys,hashlib
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import SHARED,model_fingerprint
from measure_marked_motion import metrics
OUT=HERE/'analysis/motion-field-extension'

def main():
    original=json.loads((OUT/'measure-original/summary.json').read_text('utf-8'))
    fixed=json.loads((OUT/'measure-extended/summary.json').read_text('utf-8'))
    field_info=json.loads((OUT/'field.json').read_text('utf-8'))
    field_hash=hashlib.sha256((SHARED/'common-flow.f16').read_bytes()).hexdigest()
    assert field_hash==field_info['candidate_sha256']
    assert len(original['cases'])==len(fixed['cases'])==233
    assert all(a['id']==b['id'] for a,b in zip(original['cases'],fixed['cases']))
    # 固定身份且整个 133 ms 窗口可见；不能用消失、出生或换一批材料伪装运动。
    assert sum(r['low_motion_133ms_windows'] for r in original['cases'])>10000
    assert sum(r['low_motion_133ms_windows'] for r in fixed['cases'])==0
    fresh=[('holdout-colored-panel',17,9017),('holdout-alpha',232,45283),
           ('holdout-compact-dialog',301,7719),('holdout-photo',359,92765)]
    ctx=moderngl.create_standalone_context(require=430);extra=[]
    for name,angle,seed in fresh:
        c=dict(id=name,scene=name,angle=angle,seed=seed)
        r=Renderer(name,direction=angle,seed=seed,quality=1,ctx=ctx);row=metrics(r,c)
        assert row['low_motion_133ms_windows']==0,row
        row['model_hash']=model_fingerprint();extra.append(row);r.close()
    ctx.release()
    report=dict(model_hash=model_fingerprint(),field_sha256=field_hash,annotated_cases=226,gallery_inputs=7,
        original_long_marks=sum(len(r['annotations']) for r in original['cases']),
        paired_visible_133ms_windows=sum(r['visible_133ms_windows'] for r in fixed['cases']),
        old_short_windows=sum(r['low_motion_133ms_windows'] for r in original['cases']),new_short_windows=0,
        old_short_lifetime_median=float(np.median([r['fraction_lifetime_path_below_4percent'] for r in original['cases']])),
        new_short_lifetime_median=float(np.median([r['fraction_lifetime_path_below_4percent'] for r in fixed['cases']])),
        extra_inputs=extra,scope='全部既有标注与画廊输入只测 GPU 状态；新增四个未参与本轮调参的输入。仍需检查慢放中移动边界与轮廓形变，不能以此宣称视觉完美。')
    (OUT/'regression.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='extra_inputs'},ensure_ascii=False),flush=True)
    print('额外四个输入通过',flush=True)

if __name__=='__main__':main()
