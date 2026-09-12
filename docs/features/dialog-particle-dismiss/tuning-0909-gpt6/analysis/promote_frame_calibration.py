"""将已审阅的共同形状与光学参数写入共享模型，保留校准来源。"""
from pathlib import Path
import sys,ast,json,hashlib
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from probe_frame_difference import configure
from frame_difference import OUT,BASE

def main():
    config=json.loads((OUT/'selected-config.json').read_text('utf-8'));configure(config)
    selected={key:getattr(renderer,key) for key in ['COMPUTE','VERTEX','FRAGMENT']}
    selected['COMPUTE']=selected['COMPUTE'].replace('*(1.08+.12*reach)*0.8400000;','*(1.08+.12*reach);').replace('*wind_gain*1.0000000;','*wind_gain;')
    selected['VERTEX']=selected['VERTEX'].replace('    scale*=1.+(0.9700000-1.)', '    // 新生微片略减覆盖，中后段保持细粒层次；共同材质规则不按源图区域变化。\n    scale*=1.+(0.9700000-1.)')
    path=HERE/'renderer.py';source=path.read_text('utf-8');lines=source.splitlines(keepends=True)
    spans=[]
    for n in ast.parse(source).body:
        if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in selected:spans.append((n.lineno-1,n.end_lineno,n.targets[0].id))
    for lo,hi,key in reversed(spans):lines[lo:hi]=[key+"=r'''"+selected[key]+"'''\n"]
    path.write_text(''.join(lines),'utf-8',newline='\n')
    model.RELEASE.astype('<f4').tofile(model.SHARED/'common-release.f32')
    renderer.guidance().astype('<f2').tofile(model.SHARED/'common-flow.f16')
    p=model.SHARED/'rules.properties';s=p.read_text('utf-8')
    changes={'cell':1.65,'life_gain':.94,'guide_gain':.756}
    s='\n'.join(f'{line.split("=",1)[0]}={changes[line.split("=",1)[0]]:g}' if '=' in line and line.split('=',1)[0] in changes else line for line in s.splitlines())+'\n'
    p.write_text(s,'utf-8',newline='\n')
    p=HERE/'export_videos.py';s=p.read_text('utf-8').replace("VERSION='修正前沿衔接与局部流动'","VERSION='逐帧校准释放与粒子流动'").replace("BASELINE_VERSION='before-front-coherence'","BASELINE_VERSION='before-frame-difference'");p.write_text(s,'utf-8',newline='\n')
    data=dict(description='所有素材共用一份归一化释放场与速度场；运行端不读取本文件或参考图像。',
        method='25 个宽尺度释放系数；25 组二维速度与平滑时间斜率；有限范围共同寿命、细化、反光校准。',
        baseline_model=json.loads((BASE/'identity.json').read_text('utf-8'))['model_hash'],
        config=config,resources={name:hashlib.sha256((model.SHARED/name).read_bytes()).hexdigest() for name in ['common-release.f32','common-flow.f16','flow-confidence.u8','rules.properties']})
    (HERE/'analysis/common-shape-calibration.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    print('已写入共同场与共同着色规则；待导出、跨端和发布验证。',flush=True)

if __name__=='__main__':main()
