"""将固定基准路径上的速度积分写成稀疏线性算子；不推断原片颗粒身份。"""
import numpy as np
from scipy import sparse
from carried_velocity import FIELD,LO,SIZE,RAMP,CADENCE


def sample_matrix(carrier,age,card,age_slices=1):
    n=len(carrier);q=(carrier/card-LO)/SIZE*FIELD-.5
    q=np.clip(q,0,FIELD-1);ij=np.floor(q).astype(np.int32);f=q-ij
    u=np.clip(age/RAMP,0,1);warm=u*u*(3-2*u)
    rows=[];cols=[];data=[]
    ids=np.arange(n,dtype=np.int32)
    z=np.clip(age,0,1)*(age_slices-1);iz=np.floor(z).astype(np.int32);fz=z-iz
    for dz in ([0,1] if age_slices>1 else [0]):
        age_weight=(fz if dz else 1-fz) if age_slices>1 else 1.
        for dx,dy in [(0,0),(0,1),(1,0),(1,1)]:
            weight=(f[:,0] if dx else 1-f[:,0])*(f[:,1] if dy else 1-f[:,1])*warm*age_weight
            keep=weight>1e-10
            rows.append(ids[keep]);cols.append((np.minimum(iz+dz,age_slices-1)*FIELD*FIELD+np.minimum(ij[:,1]+dy,FIELD-1)*FIELD+np.minimum(ij[:,0]+dx,FIELD-1))[keep])
            data.append(weight[keep].astype(np.float32))
    return sparse.csr_matrix((np.concatenate(data),(np.concatenate(rows),np.concatenate(cols))),shape=(n,FIELD*FIELD*age_slices))


def operators(saved,phases,indices=None,age_slices=1):
    source=saved['source'];indices=np.arange(len(source)) if indices is None else indices
    times=saved['times'];carrier=saved['carrier'][:,indices];birth=saved['birth'][indices]
    card=saved['card'];n=len(indices)
    cumulative=sparse.csr_matrix((n,FIELD*FIELD*age_slices),dtype=np.float32)
    previous=sample_matrix(carrier[0],times[0]-birth,card,age_slices);step=0;result=[]
    for phase in phases:
        end=int(max(float(phase),0)/CADENCE+1e-6)
        while step<end:
            step+=1
            value=sample_matrix(carrier[step],times[step]-birth,card,age_slices)
            cumulative=cumulative+(previous+value)*np.float32(CADENCE*.5)
            previous=value
        result.append(cumulative+previous*np.float32(max(0.,float(phase)-step*CADENCE)))
    return result


def to_torch(matrix,device='cuda'):
    import torch
    # CSR 梯度仅对右侧共同速度场求导；路径及稀疏结构均为只读观测。
    return torch.sparse_csr_tensor(torch.as_tensor(matrix.indptr.astype(np.int32),device=device),
        torch.as_tensor(matrix.indices.astype(np.int32),device=device),
        torch.as_tensor(matrix.data,device=device),size=matrix.shape,device=device,check_invariants=True)
