"""核验正式模型的孤立尘缕、完整相位误差以及可复现的近中远输入。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import Reference,load_meta
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics
from frame_difference import PHASES,metrics
from filament_probe import OUT,FROZEN,sheet
import verify_frame_calibration as previous_verifier

def main():
    ctx=moderngl.create_standalone_context(require=430)
    old=np.load(FROZEN/'ironman.npy',mmap_mode='r')[::2]
    previous_verifier.BASE=FROZEN
    before=previous_verifier.frozen_renderer()('ironman',ctx=ctx)
    assert np.array_equal(before.render(.4),old[24]),'基线必须使用其自身规则与资源'
    before.close();r=Renderer('ironman',ctx=ctx)
    images=np.stack([r.render(t) for t in PHASES]);r.close()
    ref=Reference(load_meta('ironman'));refs=np.stack([ref.at(t) for t in PHASES])
    np.save(OUT/'production-ironman.npy',images);np.save(OUT/'reference.npy',refs)
    phases=[.33,.40,.48,.55,2/3,.78];indices=[round(p*60) for p in phases]
    sheet([('华为参考',[refs[i] for i in indices]),('修改前',[old[i] for i in indices]),
           ('共同模型',[images[i] for i in indices])],'production',phases)
    yy,xx=np.mgrid[:1280,:720];mask=(xx>=35)&(xx<685)&(yy>=150)&(yy<930)
    errors=dict(before=metrics(old,refs,mask),after=metrics(images,refs,mask))
    train=set(range(8,53,4));train.add(40);test=[i for i in range(8,53) if i not in train]
    summary={}
    for name,indices in [('all_motion',list(range(8,53))),('held_phases',test),('mid_phase',list(range(12,49)))]:
        summary[name]={k:{side:float(np.mean([rows[i][k] for i in indices])) for side,rows in errors.items()} for k in ['mae_0','mae_2','mae_6','mae_12']}
    print(json.dumps(summary,ensure_ascii=False),flush=True)
    # 平均误差只作完整画面保护；显眼孤立细缕另有失败用例，不能互相抵消。
    assert summary['held_phases']['mae_6']['after']<summary['held_phases']['mae_6']['before']*1.02
    distances=[]
    for name in ['ironman','attachment','color']:
        for direction in [135,90]:
            group=[];births=[]
            for case in distance_cases(load_meta(name),direction):
                r=Renderer(name,ctx=ctx,direction=case['angle'],touch_gap=case['gap'],quality=1)
                births.append(r.base[:,[2,6]].copy());group.append(dict(scene=name,requested=direction,**case,touch_strength=r.touch_strength,**destination_metrics(r)));r.close()
            assert all(np.array_equal(births[0],b) for b in births[1:])
            for key in ['speed_median','displacement_median']:
                v=[c[key] for c in group];assert v[0]<v[1]<v[2] and 1.6<v[2]/v[0]<2.7,(name,direction,key,v)
            distances.extend(group)
    result=dict(model_hash=model_fingerprint(),appearance=dict(errors=errors,summary=summary),distance_cases=distances,
        scope='共同场；完整画面不作配准形变或时间重排。孤立细缕回归与全局像素误差分别验收，照片参与过共同场校准，留出相位不等于独立素材验证。')
    (OUT/'metrics.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');ctx.release()
    print('完整相位、基线身份、18 个真实距离输入通过',flush=True)

if __name__=='__main__':main()
