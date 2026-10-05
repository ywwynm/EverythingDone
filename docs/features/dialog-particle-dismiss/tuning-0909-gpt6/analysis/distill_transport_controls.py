"""把离线控制格作为监督信号，提炼共同、连续的输运函数。

控制格和参考帧只用于训练；导出文件只含所有布局共用的网络权重。
输入为方向坐标、尺寸、起点统计和进度，不含素材名称或逐素材隐变量。
"""
from pathlib import Path
import argparse,json,pickle,sys,time,math
import cv2,numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_particle_proxy import LO,SIZE


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',required=True);ap.add_argument('--controls',required=True)
    ap.add_argument('--steps',type=int,default=8000);ap.add_argument('--width',type=int,default=192)
    ap.add_argument('--label',default='distilled');ap.add_argument('--resume');ap.add_argument('--learning-rate',type=float,default=.001)
    a=ap.parse_args();out=HERE/'analysis/match-cache/particle-transport'/a.label;out.mkdir(parents=True,exist_ok=True)
    data=pickle.loads(Path(a.cache).read_bytes());controls=np.load(a.controls)
    for i,d in enumerate(data):
        assert d['name']==controls['names'][i] and abs(d['progress']-controls['phases'][i])<1e-6
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(928)
    def t(v):return torch.as_tensor(np.asarray(v,np.float32),device='cuda')
    n=56;config=json.loads((HERE/'conditional-transport-weights.json').read_text('utf-8'))
    assert config.get('residual') is None
    net=torch.nn.Sequential(torch.nn.Linear(59,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,3)).cuda()
    with torch.no_grad():net[-1].weight.zero_();net[-1].bias.zero_()
    if a.resume:
        prior=json.loads(Path(a.resume).read_text('utf-8'))['residual']
        with torch.no_grad():
            for q,saved in zip([q for q in net if isinstance(q,torch.nn.Linear)],prior['layers']):q.weight.copy_(t(saved['weight']));q.bias.copy_(t(saved['bias']))
    features=t(np.stack([d['features'] for d in data]));phase=t([d['phase'] for d in data]);rot=t(np.stack([d['rotation'] for d in data]));scale=t(np.stack([d['scale'] for d in data]))
    target=t(np.stack([cv2.resize(f,(n,n),interpolation=cv2.INTER_LINEAR) for f in controls['fields']]))
    importance=[]
    for d in data:
        xy=d['query']*n;weight=(d['mass']+.12*d['unit_mass'])*d['warm']
        ix=np.floor(xy).astype(int);valid=(ix[:,0]>=0)&(ix[:,0]<n)&(ix[:,1]>=0)&(ix[:,1]<n)
        mass=np.bincount(ix[valid,1]*n+ix[valid,0],weights=weight[valid],minlength=n*n).reshape(n,n).astype(np.float32)
        mass=cv2.GaussianBlur(mass,(0,0),1.0);mass/=max(np.quantile(mass,.90),1e-5)
        importance.append(.025+np.minimum(mass,5))
    importance=t(np.stack(importance))[...,None]
    yy,xx=torch.meshgrid(torch.arange(n,device='cuda'),torch.arange(n,device='cuda'),indexing='ij');grid=(torch.stack([xx,yy],-1).float()+.5)/n*2-1
    constants=[(t(q['weight']),t(q['bias'])) for q in config['layers']]
    def integrate(raw,p,r,s,magnitude,density,steps):
        raw=raw.tanh();v=magnitude*p[:,None,None,None]*raw[...,:2]
        v=torch.einsum('bxyc,bcd->bxyd',v,r)*s[:,None,None,:]/2**steps
        for _ in range(steps):v=v+F.grid_sample(v.permute(0,3,1,2),grid[None]+v*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return torch.cat([v/s[:,None,None,:],density*raw[...,2:]],-1)
    def base(f,p,r,s):
        z=f
        for i,(mat,bias) in enumerate(constants):
            z=z@mat.T+bias
            if i<len(constants)-1:z=z.tanh()
        return integrate(z,p,r,s,config['displacement_scale'],config['density_scale'],config['flow_steps'])
    def prediction(f,p,r,s):
        with torch.no_grad():b=base(f,p,r,s)
        extra=integrate(net(f),p,r,s,1.1,1.4,6)
        shifted=grid[None]+b[...,:2]*s[:,None,None,:]*2/SIZE
        carried=F.grid_sample(extra.permute(0,3,1,2),shifted,padding_mode='border',align_corners=False).permute(0,2,3,1)
        return carried,b+carried
    def interpolate(ids):
        # 相邻观测之间随机采样时间，避免只在八个关键帧上收敛。
        nxt=ids+1;frac=torch.rand((len(ids),1,1,1),device='cuda');p=phase[ids]+frac[:,0,0,0]*(phase[nxt]-phase[ids])
        f=features[ids].clone();f[...,5]=p[:,None,None];f[...,6]=p[:,None,None]**2
        for start,frequency in [(31,2*np.pi),(45,4*np.pi)]:
            for index,value in [(start+4,p[:,None,None]),(start+12,p[:,None,None]-f[...,27])]:
                f[...,index]=torch.sin(frequency*value);f[...,index+1]=torch.cos(frequency*value)
        goal=target[ids]*(1-frac)+target[nxt]*frac;w=importance[ids]*(1-frac)+importance[nxt]*frac
        return f,p,goal,w
    def error(delta,goal,w):
        normalized=(delta-goal)/t([.08,.08,.6])
        return (normalized.square()*w).sum()/w.sum().clamp(min=.01)/3
    allowed=torch.tensor([i for i in range(len(data)-1) if data[i]['name']==data[i+1]['name']],device='cuda')
    opt=torch.optim.AdamW(net.parameters(),lr=a.learning_rate,weight_decay=.0001);start=time.monotonic();best=float('inf');history=[]
    for step in range(a.steps):
        for group in opt.param_groups:group['lr']=a.learning_rate*(.08+.92*.5*(1+math.cos(np.pi*step/a.steps)))
        ids=allowed[torch.randint(len(allowed),(10,),device='cuda')];f,p,goal,w=interpolate(ids)
        opt.zero_grad();delta,total=prediction(f,p,rot[ids],scale[ids]);loss=error(delta,goal,w)
        move=total[...,:2]*scale[ids,None,None,:]
        jx=(move[:,:-1,1:]-move[:,:-1,:-1])*n/SIZE;jy=(move[:,1:,:-1]-move[:,:-1,:-1])*n/SIZE
        det=(1+jx[...,0])*(1+jy[...,1])-jx[...,1]*jy[...,0]
        ddx=(delta[:,:-1,1:]-delta[:,:-1,:-1])*n/SIZE;ddy=(delta[:,1:,:-1]-delta[:,:-1,:-1])*n/SIZE
        loss+=1.5*F.relu(.25-det).square().mean()+.001*(ddx.square().mean()+ddy.square().mean())
        loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),3.);opt.step()
        if (step+1)%250==0:
            with torch.no_grad():
                score=0.;baseline=0.
                for lo in range(0,len(data),10):
                    ix=slice(lo,lo+10);d,_=prediction(features[ix],phase[ix],rot[ix],scale[ix]);score+=float(error(d,target[ix],importance[ix]));baseline+=float(error(torch.zeros_like(d),target[ix],importance[ix]))
                ratio=score/baseline
            row=dict(step=step+1,control_error_ratio=ratio);history.append(row);print('共同连续函数蒸馏',step+1,round(time.monotonic()-start,1),round(ratio,4),flush=True)
            if score<best:
                best=score;result=dict(config);result['residual']=dict(displacement_scale=1.1,density_scale=1.4,flow_steps=6,multiscale_features=True,
                    layers=[dict(weight=q.weight.detach().cpu().tolist(),bias=q.bias.detach().cpu().tolist()) for q in net if isinstance(q,torch.nn.Linear)])
                result.update(method='共同几何函数；离线控制格监督，时间连续采样；运行时无逐参考资源',selection=row)
                (out/'candidate.json').write_text(json.dumps(result,ensure_ascii=False),'utf-8')
            (out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
