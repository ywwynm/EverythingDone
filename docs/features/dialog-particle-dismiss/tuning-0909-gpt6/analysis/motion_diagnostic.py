"""调优探针：固定材料位移与速度，仅输出诊断数据。"""
import sys,json
from pathlib import Path
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from renderer import Renderer
from fit_flow import evaluate
for name in ['ironman','thanos','kobe']:
    r=Renderer(name)
    p=json.loads((r.directory/'flow-profile.json').read_text(encoding='utf-8'))
    out=[]
    for t in [.32,.48,.64,.80]:
        r.render(t);s=np.frombuffer(r.state.read(),dtype='float32').reshape(r.n,8)
        sel=(t-r.base[:,2]>.05)&(t-r.base[:,2]<.25)
        guide=evaluate(p,s[sel,0]/r.cw,s[sel,1]/r.ch,t)*r.span
        out.append({'t':t,'age_mean':float((t-r.base[sel,2]).mean()),'displacement_mean':(s[sel,:2]-r.base[sel,:2]).mean(0).tolist(),'velocity_mean':s[sel,4:6].mean(0).tolist(),'guide_mean':guide.mean(0).tolist(),'normal_mean':r.base[sel,4:6].mean(0).tolist()})
    print(name,json.dumps(out));r.close()
