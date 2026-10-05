"""固定材料从出现到渐隐的运动约束，不要求它在整个公共时间窗始终可见。"""
import numpy as np


def trace_path(saved,seed=1932,maximum=1536,cohort_samples=0,native_clock=False):
    tr=saved['trace'];clock=tr[:,0,4]-tr[0,0,4]
    if native_clock:
        times=clock.copy();q=tr
    else:
        times=np.arange(int(np.floor(clock[-1]*40))+1,dtype=np.float32)/40
        hi=np.clip(np.searchsorted(clock,times),1,len(clock)-1);lo=hi-1
        f=((times-clock[lo])/(clock[hi]-clock[lo]))[:,None,None]
        q=tr[lo]*(1-f)+tr[hi]*f
    visible=(q[:,:,4]>.04)&(q[:,:,4]<q[:,:,5]-.055)&(q[:,:,6]<.6)&saved['mask']
    valid=np.flatnonzero(visible.sum(0)>=5);rng=np.random.default_rng(seed)
    # 按首次可见进度分层，避免短寿命和晚释放的材料被长期可见材料淹没。
    first=visible.argmax(0);take=[]
    for band in np.array_split(np.arange(len(times)),8):
        ids=valid[np.isin(first[valid],band)]
        if len(ids):take.extend(rng.choice(ids,min(len(ids),maximum//8),replace=False))
    display_phase=np.interp(times,clock,saved['phases'])
    cohorts=[]
    if cohort_samples:
        # 出生分层仍可能只抽到十余颗长寿命材料，低于同步统计门槛。
        # 额外保留固定播放区间的材料；不用降低门槛掩盖样本不足。
        raw_visible=(tr[:,:,4]>.04)&(tr[:,:,4]<tr[:,:,5]-.055)&(tr[:,:,6]<.6)&saved['mask']
        for begin,end in [(.20,.45),(.30,.70),(.50,.75)]:
            interval=(saved['phases']>=begin-1e-6)&(saved['phases']<=end+1e-6)
            stable=raw_visible[interval].all(0);cohorts.append(stable)
            ids=np.flatnonzero(stable)
            if len(ids):take.extend(rng.choice(ids,min(len(ids),cohort_samples),replace=False))
    take=np.unique(np.asarray(take,int)) if cohort_samples else np.asarray(take,int)
    cohort_masks=np.asarray(cohorts,bool)[:,take] if cohorts else np.zeros((0,len(take)),bool)
    q=q[:,take];visible=visible[:,take]
    card=saved['rect'][2:]-saved['rect'][:2];span=float(saved['span']);pos=q[:,:,2:4]/span
    wind=saved['wind'];cross=np.array([-wind[1],wind[0]])
    pair=visible[1:]&visible[:-1];triple=pair[1:]&pair[:-1]
    lateral=np.diff(pos@cross,axis=0)*pair
    dt=np.diff(times)[:,None,None] if native_clock else .025
    velocity=np.diff(pos,axis=0)/dt
    result=dict(phase=times,display_phase=display_phase,cohort_masks=cohort_masks,indices=np.arange(len(times)),query=(q[:,:,:2]/card+.38)/1.76,
        origin=(saved['source'][take]/card+.38)/1.76,age=q[:,:,4],
        base=pos,wind=wind,cross=cross,pair=pair,triple=triple,
        excess=abs(lateral).sum(0)-abs(lateral.sum(0)),
        reverse=np.maximum(-velocity@wind,0),
        acceleration=np.linalg.norm(np.diff(velocity,axis=0)/(dt[:-1] if native_clock else dt),axis=-1),
        materials=len(take),material_indices=take,scale=span/card)
    if native_clock:result['delta_time']=np.diff(times);result['window_scale']=.025/float(np.median(np.diff(times)))
    return result


def motion_penalty(torch,pos,path,acceleration_weight=1.):
    f=torch.nn.functional
    pair=path['pair'];triple=path['triple']
    lateral=(pos[1:]-pos[:-1])@path['cross']
    lateral=lateral*pair
    excess=abs(lateral).sum(0)-abs(lateral.sum(0))
    added=f.relu(excess-path['excess']*1.025-.0003)/.012
    dt=path['delta_time'][:,None,None] if 'delta_time' in path else .025
    velocity=(pos[1:]-pos[:-1])/dt
    # 惩罚新增加的逆风速度，保留基准已具有的边缘卷转。
    reverse=f.relu(-(velocity@path['wind'])-path['reverse']*1.025-.01)*pair/.10
    acceleration=torch.linalg.vector_norm((velocity[1:]-velocity[:-1])/(dt[:-1] if 'delta_time' in path else dt),dim=-1)
    acceleration=f.relu(acceleration-path['acceleration']*1.05-.10)*triple
    # 全生命周期总量相同，仍可能把原有的一次转向搬到中段并形成同步回摆。
    # 同时约束重叠子区间，不允许用别的时段的少量往返抵消当前区间的增加。
    old_lateral=((path['base'][1:]-path['base'][:-1])@path['cross'])*pair
    def cumulative(value):return torch.cat([torch.zeros_like(value[:1]),value.cumsum(0)],0)
    travel,net,old_travel,old_net,count=map(cumulative,[abs(lateral),lateral,abs(old_lateral),old_lateral,pair])
    local=[];local_valid=[]
    scale=path.get('window_scale',1.)
    for old_length in [8,16]:
        length=round(old_length*scale)
        starts=torch.arange(0,len(pair)-length+1,max(1,round(4*scale)),device=pos.device);ends=starts+length
        value=(travel[ends]-travel[starts])-abs(net[ends]-net[starts])
        old=(old_travel[ends]-old_travel[starts])-abs(old_net[ends]-old_net[starts])
        valid=count[ends]-count[starts]>=4
        local.append(f.relu(value-old*1.025-.0003)/.012*valid);local_valid.append(valid)
    local=torch.cat(local).flatten();local_valid=torch.cat(local_valid).sum().clamp(min=1)
    local_loss=local.square().sum()/local_valid+.5*local.topk(min(64,local.numel())).values.square().mean()
    return (added.square().mean()+.5*added.topk(min(64,len(added))).values.square().mean()
        +local_loss
        +.2*reverse.square().sum()/pair.sum().clamp(min=1)
        +.10*reverse.flatten().topk(min(64,reverse.numel())).values.square().mean()
        +acceleration_weight*(.05*acceleration.square().sum()/triple.sum().clamp(min=1)
        +.01*acceleration.flatten().topk(min(64,acceleration.numel())).values.square().mean()))
