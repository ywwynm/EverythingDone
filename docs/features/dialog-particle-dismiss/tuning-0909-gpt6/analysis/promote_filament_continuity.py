"""把经过整体画面检查的候选写入共同资源，保留可复现的参数来源。"""
from pathlib import Path
import sys,ast,json,hashlib
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from filament_lifetime import candidate
from filament_probe import OUT,FROZEN
from frame_difference import BASE

def main():
    config=json.loads((OUT/'selected-config.json').read_text('utf-8'));candidate(config,'combined')
    selected={key:getattr(renderer,key) for key in ['COMPUTE','VERTEX','FRAGMENT']}
    selected['COMPUTE']=selected['COMPUTE'].replace(f"*(1.08+.12*reach)*{config['motion']['guide']:.7f};",'*(1.08+.12*reach);').replace('*wind_gain*1.0000000;','*wind_gain;')
    selected['COMPUTE']=selected['COMPUTE'].replace('uint transport_hash(uint x)',
        '// 输运随机量与寿命随机量分离，避免长寿命片集中在较慢的一条轨迹上。\nuint transport_hash(uint x)')
    path=HERE/'renderer.py';source=path.read_text('utf-8');lines=source.splitlines(keepends=True)
    spans=[]
    for n in ast.parse(source).body:
        if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in selected:spans.append((n.lineno-1,n.end_lineno,n.targets[0].id))
    for lo,hi,key in reversed(spans):lines[lo:hi]=[key+"=r'''"+selected[key]+"'''\n"]
    path.write_text(''.join(lines),'utf-8',newline='\n')
    model.RELEASE.astype('<f4').tofile(model.SHARED/'common-release.f32')
    renderer.guidance().astype('<f2').tofile(model.SHARED/'common-flow.f16')
    cohort=config['cohort_model'];changes={'life_gain':cohort['late_gain'],
        'life_early_gain':cohort['early_gain'],'life_birth_start':cohort['birth_start'],
        'life_birth_span':cohort['birth_span'],'guide_gain':.9*config['motion']['guide']}
    p=model.SHARED/'rules.properties';lines=p.read_text('utf-8').splitlines();seen=set()
    for i,line in enumerate(lines):
        key=line.split('=',1)[0]
        if key in changes:lines[i]=f'{key}={changes[key]:.9f}';seen.add(key)
    lines+=['# 早期释放片较早退场；后续释放片保留卷边寿命，连续插值且与运动随机量独立。']
    lines+=[f'{key}={value:.9f}' for key,value in changes.items() if key not in seen]
    p.write_text('\n'.join(lines)+'\n','utf-8',newline='\n')
    p=HERE/'export_videos.py';s=p.read_text('utf-8').replace("VERSION='逐帧校准释放与粒子流动'","VERSION='去除孤立尘缕并细化共同流动'").replace("BASELINE_VERSION='before-frame-difference'","BASELINE_VERSION='before-filament-continuity'");p.write_text(s,'utf-8',newline='\n')
    data=dict(description='所有素材共用归一化释放场、速度场和材质规则，运行端不读取参考图片或诊断区域。',
        method='在原共同场上细化宽尺度释放和速度系数；按释放时刻连续调整寿命，输运随机量独立。',
        baseline_model=json.loads((BASE/'identity.json').read_text('utf-8'))['model_hash'],
        comparison_baseline_model=json.loads((FROZEN/'identity.json').read_text('utf-8'))['model_hash'],config=config,
        resources={name:hashlib.sha256((model.SHARED/name).read_bytes()).hexdigest() for name in ['common-release.f32','common-flow.f16','flow-confidence.u8','rules.properties']})
    (HERE/'analysis/common-shape-calibration.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    print('已写入共同场；Python 与 Android 寿命构造需同步后验证。',flush=True)

if __name__=='__main__':main()
