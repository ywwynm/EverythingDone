"""检查源位置运动函数是否遗漏现有粒子的固定初始条件；只做离线表达诊断。"""
from pathlib import Path
import argparse,json,math,sys
import numpy as np
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from material_motion_constraints import trace_path
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from causal_velocity import HORIZON,BIRTH_RAMP

OUT=HERE/'analysis/match-cache/causal-velocity/initial-condition-audit'


def conditions(base):
    """与基础着色器相同的随机量；不是材料编号或场景标签。"""
    def unit(value):
        value=np.asarray(value,np.uint32).copy()
        value^=value>>16;value*=np.uint32(0x7feb352d);value^=value>>15
        value*=np.uint32(0x846ca68b);value^=value>>16
        return (value>>8).astype(np.float32)/16777216.
    random=np.ascontiguousarray(base[:,8:12]);bits=random.view(np.uint32)
    seed=bits[:,1]^bits[:,3]
    peel=unit(bits[:,0]^bits[:,1]^np.uint32(0xa54ff53a))
    return np.column_stack([random*2-1,unit(seed)*2-1,unit(seed^np.uint32(0x9e3779b9))*2-1,peel*2-1,base[:,4:6],base[:,7]])


def prepare():
    import moderngl
    from check_cloud_evolution import configuration
    from export_shared_candidate import signature
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE);out={}
    for name in OBSERVED_SITES:
        r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        saved=np.load(HERE/f'analysis/match-cache/motion-continuity/all-material-return/{name}.npz')
        if not np.array_equal(saved['source'],r.base[:,:2]):raise ValueError('实际材料编号与轨迹不一致')
        out[name]=r.base.copy();r.close();print('初始材料',name,flush=True)
    ctx.release();np.savez_compressed(OUT/'materials.npz',signature=signature(),**out)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--prepare',action='store_true')
    ap.add_argument('--steps',type=int,default=4000);a=ap.parse_args();OUT.mkdir(parents=True,exist_ok=True)
    if a.prepare:return prepare()
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(929)
    to=lambda value:torch.as_tensor(np.asarray(value,np.float32),device='cuda')
    initial=np.load(OUT/'materials.npz');rows=[];checks=[];names=list(OBSERVED_SITES)
    def row(name,saved,p):
        card=saved['rect'][2:]-saved['rect'][:2]
        m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));q=p['origin']*1.76-.38
        geometry=transport_features(q[:,0],q[:,1],-p['age'][0],*card,m['direction'],[Site(*s) for s in OBSERVED_SITES[name]],True)
        extra=conditions(initial[name])[p['material_indices']]
        age=np.clip(p['age'],0,HORIZON);h=HORIZON;young=np.clip(p['age']/BIRTH_RAMP,0,1)
        basis=np.stack([young**2*(3-2*young),age-age**2/h+age**3/(3*h*h),age**2/h-2*age**3/(3*h*h),age**3/(3*h*h)+np.maximum(p['age']-h,0)],-1)
        visible=np.pad(p['pair'],((1,0),(0,0)))|np.pad(p['pair'],((0,1),(0,0)))
        target=p['base']-(p['query']*1.76-.38)/p['scale']
        rotation=np.column_stack([p['wind'],p['cross']]);target=target@rotation
        return dict(features=to(geometry),extra=to(extra),basis=to(basis),target=to(target),visible=to(visible))
    for name in names:
        saved=np.load(HERE/f'analysis/match-cache/motion-continuity/all-material-return/{name}.npz')
        training=trace_path(saved,seed=2810,maximum=2048);rows.append(row(name,saved,training))
        held=trace_path(saved,seed=2881,maximum=2048);test=row(name,saved,held)
        # 排除训练中出现过的材料，避免以另一随机抽样冒充未见材料。
        test['visible']*=to(~np.isin(held['material_indices'],training['material_indices']))[None]
        checks.append(test)
    output={}
    for augmented in [False,True]:
        torch.manual_seed(929)
        net=torch.nn.Sequential(torch.nn.Linear(69 if augmented else 59,192),torch.nn.Tanh(),torch.nn.Linear(192,192),torch.nn.Tanh(),torch.nn.Linear(192,8)).cuda()
        net[-1].weight.data.zero_();net[-1].bias.data.zero_();opt=torch.optim.AdamW(net.parameters(),lr=.001,weight_decay=.001)
        def error(d):
            inp=torch.cat([d['features'],d['extra']],-1) if augmented else d['features']
            raw=net(inp).reshape(-1,4,2);controls=torch.cat([.14*raw[:,:1].tanh(),4*(raw[:,1:]/4).tanh()],1)
            return (controls[None]*d['basis'][...,None]).sum(-2)-d['target']
        for step in range(a.steps):
            for group in opt.param_groups:group['lr']=.001*(.15+.85*.5*(1+math.cos(math.pi*step/a.steps)))
            opt.zero_grad();ids=torch.randperm(len(rows))[:4].tolist()
            loss=torch.stack([((error(rows[i])/.03).square()*rows[i]['visible'][...,None]).sum()/rows[i]['visible'].sum().clamp(min=1)/2 for i in ids]).mean()
            loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),3.);opt.step()
            if (step+1)%1000==0:print('表达诊断',augmented,step+1,float(loss.detach()),flush=True)
        with torch.no_grad():
            values={}
            for name,train,test in zip(names,rows,checks):
                values[name]={}
                for label,d in [('training_materials',train),('unseen_materials',test)]:
                    delta=torch.linalg.vector_norm(error(d),dim=-1)[d['visible']>.5]
                    values[name][label]=dict(samples=int(delta.numel()),median=float(delta.median()),q90=float(delta.quantile(.9)))
        output['with_initial_conditions' if augmented else 'geometry_only']=values
        (OUT/'report.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),'utf-8')
        print('已有路径表达误差；不是华为误差',augmented,values,flush=True)


if __name__=='__main__':main()
