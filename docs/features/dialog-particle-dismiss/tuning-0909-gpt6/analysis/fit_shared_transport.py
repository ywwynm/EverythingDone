"""共同条件输运实验：在校准过的密度散布近似上求梯度，再用实际颗粒验证。"""
from pathlib import Path
import argparse,json,sys,time,math
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from fit_shared_flow_basis import prepare,N,LO,SIZE,NAMES,PHASES
from shared_transport import transport_features
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
OUT=HERE/'analysis/match-cache/shared-transport'


def main():
    global NAMES
    ap=argparse.ArgumentParser();ap.add_argument('--weights',required=True);ap.add_argument('--steps',type=int,default=5000);ap.add_argument('--label',default='first');ap.add_argument('--density',action='store_true');ap.add_argument('--initial');ap.add_argument('--origin-coverage',action='store_true');ap.add_argument('--transfer');ap.add_argument('--multiscale',action='store_true');ap.add_argument('--move-scale',type=float,default=.42);ap.add_argument('--canonical',action='store_true');ap.add_argument('--width',type=int,default=32);ap.add_argument('--teacher',action='store_true',help='仅作离线诊断的低分辨率控制场，不输出运行权重');ap.add_argument('--distill');ap.add_argument('--feature-scales',action='store_true');ap.add_argument('--flow-steps',type=int,default=0)
    ap.add_argument('--anchor-render',help='在当前真实 GPU 渲染处校准近似响应，指定共同覆盖规则 JSON')
    ap.add_argument('--refinement',action='store_true',help='约束相对于当前共同场的变化，避免每次重新压平已有卷曲')
    ap.add_argument('--coverage',help='粗粒化层采用与真实 GPU 相同的共同覆盖／存续规则')
    ap.add_argument('--density-scale',type=float,default=1.6)
    ap.add_argument('--temporal',type=float,default=0.,help='共同相邻时刻的低频画面变化约束，直接对照原片')
    a=ap.parse_args()
    if a.canonical:NAMES=['ironman']+NAMES
    OUT.mkdir(parents=True,exist_ok=True);data=prepare(Path(a.weights),a.transfer,NAMES,a.coverage)
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(27)
    def tensor(a):
        v=np.ascontiguousarray(a,np.float32)
        return torch.frombuffer(bytearray(v.tobytes()),dtype=torch.float32).reshape(v.shape)
    yy,xx=np.mgrid[:N,:N].astype('float32');xx=LO+(xx+.5)/N*SIZE;yy=LO+(yy+.5)/N*SIZE
    fs=[];rot=[];phases=[];scales=[];origin_bands=[]
    rules=json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules']
    if a.transfer:rules=json.loads(Path(a.transfer).read_text('utf-8'))['material']
    for j,(ni,p) in enumerate(zip(data['scene'],data['phase'])):
        name=NAMES[ni];m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
        x,y,x1,y1=m['rect'];angle=math.radians(m['direction']);c,s=math.cos(angle),-math.sin(angle)
        actual=float(p+data['gain'][j]*rules['clock']*4*p*(1-p));phases.append(actual)
        fs.append(transport_features(xx,yy,actual,x1-x,y1-y,m['direction'],[Site(*s) for s in OBSERVED_SITES[name]],a.feature_scales))
        rot.append([[c,s],[-s,c]])
        span=min(x1-x,y1-y);scales.append([span/(x1-x),span/(y1-y)])
        band=np.zeros_like(xx)
        for ox,oy,start in OBSERVED_SITES[name]:
            distance=((xx-ox)*(x1-x)/span)**2+((yy-oy)*(y1-y)/span)**2
            band=np.maximum(band,np.exp(-distance/(2*.28**2)))
        exterior=(xx<0)|(xx>1)|(yy<0)|(yy>1)
        origin_bands.append(band*exterior*(1.-np.clip((p-.36)/.25,0,1)))
    feature_count=fs[0].shape[-1]
    features=tensor(np.asarray(fs).reshape(-1,N*N,feature_count));rotation=tensor(rot);phase=tensor(phases)
    scale=tensor(scales);origin_band=tensor(origin_bands)
    gain=tensor(data['gain']);gain=gain+(1-gain)*float(a.canonical);cloud=tensor(data['cloud'].reshape(-1,N*N,4));surface=tensor(data['surface']);bg=tensor(data['background'])
    target=tensor(data['target']);mask=tensor(data['mask']);baseline=tensor(data['baseline']);warmup=tensor(data['warmup'].reshape(-1,N*N,1))
    opacity=cloud[...,3].clamp(0,.999);tau=-torch.log1p(-opacity);color=cloud[...,:3]/opacity[...,None].clamp(min=.001)
    yy,xx=torch.meshgrid(torch.arange(N),torch.arange(N),indexing='ij');coords=torch.stack([xx,yy],-1).reshape(-1,2).float()
    model=torch.nn.Sequential(torch.nn.Linear(feature_count,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,a.width),torch.nn.Tanh(),torch.nn.Linear(a.width,3 if a.density else 2))
    with torch.no_grad():model[-1].weight.zero_();model[-1].bias.zero_()
    opt=torch.optim.AdamW(model.parameters(),lr=.00018 if a.refinement else .0018,weight_decay=.001 if a.refinement else .004);offset=None;teacher_controls=None;anchor_field=None
    def render(ids):
        raw=model(features[ids]) if teacher_controls is None else F.interpolate(teacher_controls[ids],(N,N),mode='bicubic',align_corners=False).permute(0,2,3,1).reshape(len(ids),N*N,-1)
        output=torch.tanh(raw);log_density=a.density_scale*output[...,2:3]*gain[ids,None,None]*warmup[ids] if a.density else torch.zeros_like(output[...,:1])
        field=a.move_scale*output[...,:2]*phase[ids,None,None]*gain[ids,None,None]
        field=torch.bmm(field,rotation[ids])
        # 位移以短边归一化，采样网格分别以宽、高归一化，转换不能遗漏长宽比。
        field=field*scale[ids,None,:]
        if a.flow_steps:
            velocity=field.reshape(len(ids),N,N,2)/(2**a.flow_steps)
            grid=(coords.reshape(N,N,2)+.5)/N*2-1
            for _ in range(a.flow_steps):
                velocity=velocity+F.grid_sample(velocity.permute(0,3,1,2),grid[None]+velocity*2/SIZE,padding_mode='border',align_corners=False).permute(0,2,3,1)
            field=velocity.reshape(len(ids),N*N,2)
        regular_field=field
        field=field*warmup[ids]
        pos=coords+field*(N/SIZE);p0=pos.floor().detach();f=pos-p0;batch=len(ids)
        density=tau[ids,:,None]*torch.exp(log_density)
        accum=torch.zeros((batch,N*N,4));values=torch.cat([color[ids]*density,density],-1)
        for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
            p=p0+torch.tensor([dx,dy]);valid=(p[...,0]>=0)&(p[...,0]<N)&(p[...,1]>=0)&(p[...,1]<N)
            weight=(f[...,0] if dx else 1-f[...,0])*(f[...,1] if dy else 1-f[...,1])*valid
            index=(p[...,1].clamp(0,N-1)*N+p[...,0].clamp(0,N-1)).long()
            accum.scatter_add_(1,index[...,None].expand(-1,-1,4),values*weight[...,None])
        total=accum[...,3];alpha=1-torch.exp(-total);rgb=accum[...,:3]/total[...,None].clamp(min=.001)*alpha[...,None]
        base=surface[ids,:,:,:3]+bg[ids]*(1-surface[ids,:,:,3:])
        result=rgb.reshape(batch,N,N,3)+base*(1-alpha.reshape(batch,N,N,1))
        result=torch.where(result<=.0031308,12.92*result,1.055*result.clamp(min=.0031308).pow(1/2.4)-.055)
        if offset is not None:result=result+offset[ids]
        return result,(regular_field if a.flow_steps else field).reshape(batch,N,N,2),log_density.reshape(batch,N,N,1)
    def blurred(image,radius):
        if not radius:return image
        nchw=image.permute(0,3,1,2)
        # 分离箱式卷积近似高斯；保留像素间的空间导数，缓解远离目标时的局部极小值。
        for _ in range(2):
            nchw=F.avg_pool2d(F.pad(nchw,(radius,radius,0,0),mode='replicate'),(1,2*radius+1),stride=1)
            nchw=F.avg_pool2d(F.pad(nchw,(0,0,radius,radius),mode='replicate'),(2*radius+1,1),stride=1)
        return nchw.permute(0,2,3,1)
    target_scales={r:blurred(target,r) for r in ([0,2,5] if a.multiscale else [0])}
    phase_observed=tensor(data['phase'])
    pair_left=torch.tensor([i for i in range(len(cloud)-1) if data['scene'][i]==data['scene'][i+1]],dtype=torch.long)
    temporal_target=blurred(target,2)
    def temporal_loss(left,regular=True):
        image,field,_=render(torch.cat([left,left+1]));count=len(left)
        dt=(phase_observed[left+1]-phase_observed[left]).clamp(min=.02)
        low=blurred(image,2)
        observed=(temporal_target[left+1]-temporal_target[left])/dt[:,None,None,None]
        predicted=(low[count:]-low[:count])/dt[:,None,None,None]
        weight=torch.minimum(mask[left],mask[left+1])
        delta=predicted-observed
        value=(torch.sqrt(delta.square()+.003**2)*weight[...,None]).sum()/weight.sum().clamp(min=1)/3
        if regular and anchor_field is not None:
            # 只约束新增的时间导数，保留已有卷曲；不以更平滑替代原片的流动。
            change=field-torch.cat([anchor_field[left],anchor_field[left+1]])
            velocity_change=(change[count:]-change[:count])/dt[:,None,None,None]
            value=value+.025*velocity_change.square().mean()
        return value
    def temporal_scores():
        with torch.no_grad():
            return {n:float(temporal_loss(torch.arange(i*len(PHASES),(i+1)*len(PHASES)-1),False)) for i,n in enumerate(NAMES)}
    iteration=0
    def loss(ids,regular=True):
        image,field,density=render(ids);diff=image-target[ids]
        weight=mask[ids]*(1.+4.*origin_band[ids]) if a.origin_coverage else mask[ids]
        error=(torch.sqrt(diff*diff+.003**2)*weight[:,:,:,None]).sum()/weight.sum().clamp(min=1)/3
        if not regular:return error
        if a.multiscale:
            fine_weight=.15+.55*min(1.,iteration/max(1,a.steps*.65))
            medium=blurred(image,2)-target_scales[2][ids]
            coarse=blurred(image,5)-target_scales[5][ids]
            def weighted(d):return (torch.sqrt(d*d+.003**2)*weight[:,:,:,None]).sum()/weight.sum().clamp(min=1)/3
            error=fine_weight*error+.65*weighted(medium)+.65*weighted(coarse)
        dx=(field[:,:-1,1:]-field[:,:-1,:-1])*(N/SIZE);dy=(field[:,1:,:-1]-field[:,:-1,:-1])*(N/SIZE)
        det=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
        curvature=(field[:,2:]-2*field[:,1:-1]+field[:,:-2]).square().mean()+(field[:,:,2:]-2*field[:,:,1:-1]+field[:,:,:-2]).square().mean()
        if a.refinement and anchor_field is not None:
            change=field-anchor_field[ids]
            cx=(change[:,:-1,1:]-change[:,:-1,:-1])*(N/SIZE);cy=(change[:,1:,:-1]-change[:,:-1,:-1])*(N/SIZE)
            return error+.002*(cx.square().mean()+cy.square().mean())+.10*F.relu(.15-det).square().mean()+.30*curvature+.025*change.square().mean()+.0001*density.square().mean()
        # 共同映射只允许温和形变，不把宽粒子群挤成错误的孤立细缕。
        return error+.0015*(dx.square().mean()+dy.square().mean())+(.09 if a.multiscale else .045)*F.relu(.5-det).square().mean()+.30*curvature+.002*field.square().mean()+.0004*density.square().mean()
    def scores():
        with torch.no_grad():return {n:float(loss(list(range(i*len(PHASES),(i+1)*len(PHASES))),False)) for i,n in enumerate(NAMES)}
    with torch.no_grad():offset=baseline-torch.cat([render(list(range(i,min(i+10,len(cloud)))))[0] for i in range(0,len(cloud),10)])
    if a.initial:
        previous_data=json.loads(Path(a.initial).read_text('utf-8'));previous=previous_data['layers']
        with torch.no_grad():
            for index,(layer,saved) in enumerate(zip([q for q in model if isinstance(q,torch.nn.Linear)],previous)):
                w=tensor(saved['weight']);b=tensor(saved['bias']);layer.weight[:w.shape[0],:w.shape[1]].copy_(w);layer.bias[:b.shape[0]].copy_(b)
                if index==0 and w.shape[1]<feature_count:layer.weight[:,w.shape[1]:].zero_()
            if a.density:
                density_ratio=previous_data.get('density_scale',1.6)/a.density_scale
                model[-1].weight[2]*=density_ratio;model[-1].bias[2]*=density_ratio
    if a.anchor_render:
        if not a.initial:raise ValueError('真实渲染校准必须明确当前共同输运权重')
        from anchor_transport import actual_frames
        actual=tensor(actual_frames(Path(a.weights),Path(a.initial),Path(a.anchor_render),a.transfer,NAMES,data['scene'],data['phase']))
        with torch.no_grad():
            offset=None
            approx=torch.cat([render(list(range(i,min(i+10,len(cloud)))))[0] for i in range(0,len(cloud),10)])
            offset=actual-approx
        print('已在真实 GPU 候选处校准近似渲染',flush=True)
    if a.refinement:
        with torch.no_grad():anchor_field=torch.cat([render(list(range(i,min(i+10,len(cloud)))))[1] for i in range(0,len(cloud),10)])
    if a.distill:
        teacher=np.load(a.distill)
        if list(teacher['names'])!=NAMES or not np.allclose(teacher['phases'],data['phase']):raise ValueError('离线控制场的序列或时刻与本轮不一致')
        expected=F.interpolate(tensor(teacher['controls']),(N,N),mode='bicubic',align_corners=False).permute(0,2,3,1).reshape(len(cloud),N*N,-1).tanh()
        student_opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.003)
        for step in range(2500):
            ids=torch.randint(len(cloud),(6,));pixels=torch.randint(N*N,(384,));student_opt.zero_grad()
            delta=torch.tanh(model(features[ids][:,pixels]))-expected[ids][:,pixels]
            importance=.06+cloud[ids][:,pixels,3:4].clamp(0,1)
            value=(delta.square()*tensor([1.,1.,.25])*importance).sum()/importance.sum()/2.25;value.backward();student_opt.step()
            if (step+1)%500==0:print('投影为共同函数',step+1,round(float(value.detach()),6),flush=True)
    if a.teacher:
        with torch.no_grad():
            initial_grid=torch.cat([model(features[i:i+10]).reshape(-1,N,N,3).permute(0,3,1,2) for i in range(0,len(cloud),10)])
            teacher_controls=torch.nn.Parameter(F.interpolate(initial_grid,(16,16),mode='area'))
        opt=torch.optim.Adam([teacher_controls],lr=.018)
    before=scores();before_temporal=temporal_scores() if a.temporal else {};best=None;history=[];start=time.monotonic();print('初始',before,flush=True)
    if a.temporal:print('初始时间变化',before_temporal,flush=True)
    for step in range(a.steps):
        iteration=step
        opt.zero_grad();value=loss(torch.randint(len(cloud),(6,)))
        if a.temporal:value=value+a.temporal*temporal_loss(pair_left[torch.randint(len(pair_left),(3,))])
        if teacher_controls is not None:
            tc=teacher_controls.reshape(len(NAMES),len(PHASES),3,16,16)
            value=value+.0006*(tc[:,2:]-2*tc[:,1:-1]+tc[:,:-2]).square().mean()
        value.backward();opt.step()
        if (step+1)%(250 if a.teacher else 500)==0:
            s=scores();ratios=[s[n]/before[n] for n in NAMES];score=float(np.mean(ratios)+.30*max(ratios))
            ts=temporal_scores() if a.temporal else {}
            if a.temporal:score+=.30*np.mean([ts[n]/before_temporal[n] for n in NAMES])
            row=dict(step=step+1,score=score,scores=s,before=before,train=NAMES,holdout=[],origin_coverage=a.origin_coverage,multiscale=a.multiscale,multiscale_features=a.feature_scales,flow_steps=a.flow_steps,displacement_scale=a.move_scale,density_scale=a.density_scale,canonical_gain=float(a.canonical),coverage=a.coverage,refinement=a.refinement,coordinate_units='位移短边归一化后转换到宽高各自坐标',layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
            row.update(temporal_weight=a.temporal,temporal_scores=ts,temporal_before=before_temporal)
            if best is None or score<best['score']:
                best=row
                if teacher_controls is not None:
                    np.savez_compressed(OUT/f'{a.label}.npz',controls=np.asarray(teacher_controls.detach().tolist(),np.float32),names=NAMES,phases=data['phase'])
                    row={k:v for k,v in row.items() if k!='layers'};row['diagnostic_only']=True
                (OUT/f'{a.label}.json').write_text(json.dumps(row,ensure_ascii=False),'utf-8')
            history.append({k:v for k,v in row.items() if k!='layers'})
            print(step+1,round(time.monotonic()-start,1),round(score,4),{n:round(s[n]/before[n],3) for n in NAMES},flush=True)
    (OUT/f'{a.label}-history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
