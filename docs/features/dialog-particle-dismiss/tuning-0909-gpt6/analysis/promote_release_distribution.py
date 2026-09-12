"""将通过新旧细缕约束的共同候选写入桌面和 Android 共用资源。"""
from pathlib import Path
import sys,json,ast
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from calibrate_release_distribution import configure,OUT

def main():
    if (OUT/'promoted-config.json').exists():
        raise RuntimeError('本轮共同候选已写入，不能再次累加校准差量；后续迭代须先建立新的冻结基线。')
    config=json.loads((OUT/'candidate-config.json').read_text('utf-8'));configure(config)
    path=HERE/'renderer.py';source=path.read_text('utf-8');lines=source.splitlines(keepends=True)
    nodes=[n for n in ast.parse(source).body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']]
    for n in reversed(nodes):
        name=n.targets[0].id;lines[n.lineno-1:n.end_lineno]=[name+"=r'''"+getattr(renderer,name)+"'''\n"]
    path.write_text(''.join(lines),'utf-8',newline='\n')
    (model.SHARED/'common-release.f32').write_bytes(model.RELEASE.astype('<f4').tobytes())
    (model.SHARED/'common-flow.f16').write_bytes(renderer.guidance().astype('<f2').tobytes())
    path=model.SHARED/'rules.properties';lines=path.read_text('utf-8').splitlines()
    for i,line in enumerate(lines):
        if '=' in line and not line.startswith('#'):
            key=line.split('=',1)[0]
            if key in ['guide_gain','life_gain','life_early_gain']:lines[i]=f'{key}={model.RULES[key]:.9f}'
    path.write_text('\n'.join(lines)+'\n','utf-8',newline='\n')
    path=HERE/'analysis/common-shape-calibration.json';data=json.loads(path.read_text('utf-8'));old=data['config']
    old['release_grid']=(np.array(old['release_grid'])+config['release_delta']).tolist()
    old['flow_grid']=(np.array(old['flow_grid'])+config['flow_delta']).tolist()
    old['motion']['guide']*=config['guide'];old['motion']['peel']=config['peel']
    old['cohort_model']['early_gain']=config['life_early'];old['cohort_model']['late_gain']=config['life_late']
    old['optical']['size']=config['size'];old['optical']['base_light']=config['light']
    data['release_response']=dict(independent=True,min_gain=.15,max_gain=1.85,mean_gain=1.,note='修正同步剥离造成的窄密度峰；全部输入共享。')
    data['comparison_baseline_model']='archive/before-device-filament-origins'
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n','utf-8')
    path=HERE/'export_videos.py';text=path.read_text('utf-8').replace("VERSION='去除孤立尘缕并细化共同流动'","VERSION='修正释放初段的孤立细缕'").replace("BASELINE_VERSION='before-filament-continuity'","BASELINE_VERSION='before-device-filament-origins'");path.write_text(text,'utf-8',newline='\n')
    (OUT/'promoted-config.json').write_text(json.dumps(config,indent=2),'utf-8');print('共同资源已写入；需独立进程重新验证量化后的正式资源',flush=True)
if __name__=='__main__':main()
