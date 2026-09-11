"""离线延续缺少有效观测的速度区；只生成候选资源，不覆盖正式模型。"""
from pathlib import Path
import json,sys,hashlib,argparse
import numpy as np
from scipy.ndimage import distance_transform_edt,gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import guidance,smooth

def main():
    out=HERE/'analysis/motion-field-extension';out.mkdir(parents=True,exist_ok=True)
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=out/'observed-flow.npy');a=p.parse_args()
    if not a.source.exists():
        raise FileNotFoundError('需要原始观测场；可从修复前的 Git 版本提取 shared/particle-dismiss/common-flow.f16，并以 --source 指定。')
    raw=np.load(a.source) if a.source.suffix=='.npy' else np.fromfile(a.source,'<f2').reshape(48,64,64,2)
    original=raw.astype('float64')
    assert hashlib.sha256(original.astype('<f2').tobytes()).hexdigest()=='361be90b8cfe7fa64e6bc59ecd96159211bdae6b51a99c0296114adeed4499d2','源观测场不匹配，不能重复延拓已修复的速度表'
    np.save(out/'observed-flow.npy',original.astype('float32'))
    # 先在时间/空间邻域延续可信速度，避免把无粒子的静态摄影背景当成阻挡物。
    # 这是观测有效性的模型假设，不把非零光流自动等同于真实物理场。
    speed=np.linalg.norm(original,axis=-1)
    valid=speed>=.32
    distance,nearest=distance_transform_edt(~valid,sampling=(1.8,1,1),return_indices=True)
    extrapolated=original[tuple(nearest)]
    # 平滑无效区，可信区作为固定边值；避免最近邻分界成为新速度接缝。
    for _ in range(70):
        average=gaussian_filter(extrapolated,(.7,1.0,1.0,0),mode='nearest')
        extrapolated=np.where(valid[...,None],original,average)
    # 确保延续区域没有因相反侧流平均而重新归零，保留当地横向流动比例。
    transverse=extrapolated[...,0]
    forward=np.maximum(-extrapolated[...,1],.32)
    ambient=np.stack([transverse,-forward],axis=-1)
    weight=smooth((speed-.12)/.28)[...,None]
    extended=original*weight+ambient*(1-weight)
    extended[speed>=.4]=original[speed>=.4]
    extended.astype('<f2').tofile(out/'extended-flow.f16')
    # 可信度与速度分开保存，运行时只在延续区域加强相干形变。
    confidence=gaussian_filter(smooth((speed-.08)/.32),(.4,.8,.8),mode='nearest')
    np.rint(confidence*255).astype('uint8').tofile(out/'flow-confidence.u8')
    info=dict(original_sha256=hashlib.sha256(original.astype('<f2').tobytes()).hexdigest(),
        candidate_sha256=hashlib.sha256(extended.astype('<f2').tobytes()).hexdigest(),
        fixed_observed_fraction=float(np.mean(speed>=.4)),
        max_error_in_fixed_samples=float(np.max(abs(original[speed>=.4]-extended[speed>=.4]))),
        description='在无效观测区延续邻域速度；可信采样保持原值，不更改释放场与寿命。',
        sources=['https://www.cs.ubc.ca/~rbridson/fluidsimulation/fluids_notes.pdf',
                 'https://www.sidefx.com/docs/houdini/nodes/dop/gasextrapolate.html'])
    (out/'field.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(info,ensure_ascii=False),flush=True)

if __name__=='__main__':main()
