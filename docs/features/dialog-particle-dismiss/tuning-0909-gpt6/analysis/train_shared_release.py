"""固定初始起点，训练一个共同释放函数；保留整段留出，不优化逐素材速度。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
OUT=HERE/'analysis/match-cache/shared-release'
TRAIN=['ironman','kobe','thanos','spiderman-original','reference-0926-1','reference-0926-4','reference-0926-5','reference-0926-7']
HOLDOUT=['reference-0926-6','ironman-up-reference']


def corpus(name,aligned=False):
    from release_integrity import Probe
    from v2_inputs import OBSERVED_SITES
    from dissolve_v2 import Site
    from learned_release import features,canonical_sites
    from scipy.ndimage import map_coordinates
    p=Probe(name);m=p.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y
    sites=[Site(*s) for s in OBSERVED_SITES[name]]
    f,native=features(64,64,w,h,m['direction'],m['seed'],sites,rotation_aligned=aligned)
    canonical,_=features(64,64,w,h,m['direction'],m['seed'],canonical_sites(w,h,m['direction']),rotation_aligned=aligned)
    yy,xx=np.mgrid[:64,:64]
    T=p.huawei();valid=p.valid&np.isfinite(T)
    t=map_coordinates(np.nan_to_num(T,nan=0),[(yy+.5)/64*h/10-.5,(xx+.5)/64*w/10-.5],order=1,mode='nearest')
    mask=map_coordinates(valid.astype('float32'),[(yy+.5)/64*h/10-.5,(xx+.5)/64*w/10-.5],order=0,mode='nearest')>.5
    t=np.maximum(t-.025,.002)
    # 旧钢铁侠已经更接近原片：先以当前认可释放场作严格回归约束，后续再改进它。
    if name=='ironman':t=native.copy();mask[:]=True
    return f.reshape(-1,f.shape[-1]),canonical.reshape(-1,canonical.shape[-1]),native.ravel(),t.ravel(),mask.ravel()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=2400);ap.add_argument('--label',default='shared');ap.add_argument('--aligned',action='store_true');ap.add_argument('--all-references',action='store_true')
    ap.add_argument('--partial-references',action='store_true',help='纳入片尾不完整参考的可观测源纹理解除时刻，不使用重建背景')
    ap.add_argument('--initial');a=ap.parse_args()
    train=TRAIN+HOLDOUT if a.all_references else TRAIN
    holdout=[] if a.all_references else HOLDOUT
    partial=['reference-0926-2','reference-0926-3'] if a.partial_references else []
    train=train+partial
    import torch
    torch.set_num_threads(4);torch.manual_seed(19)
    OUT.mkdir(parents=True,exist_ok=True);data={n:corpus(n,a.aligned) for n in TRAIN+HOLDOUT+partial}
    def tensor(v):return torch.tensor(v.tolist(),dtype=torch.float32)
    d={n:tuple(tensor(v) for v in row) for n,row in data.items()}
    nf=data[TRAIN[0]][0].shape[1]
    model=torch.nn.Sequential(torch.nn.Linear(nf,48),torch.nn.Tanh(),torch.nn.Linear(48,48),torch.nn.Tanh(),torch.nn.Linear(48,1))
    torch.nn.init.zeros_(model[-1].weight);torch.nn.init.zeros_(model[-1].bias)
    if a.initial:
        previous=json.loads(Path(a.initial).read_text('utf-8'))
        with torch.no_grad():
            for layer,values in zip([layer for layer in model if isinstance(layer,torch.nn.Linear)],previous['layers']):
                layer.weight.copy_(torch.tensor(values['weight']));layer.bias.copy_(torch.tensor(values['bias']))
    opt=torch.optim.AdamW(model.parameters(),lr=.002,weight_decay=.003)
    started=time.monotonic();history=[]
    for step in range(a.steps):
        opt.zero_grad();loss=0.
        for n in train:
            f,canonical,b,t,mask=d[n];ids=torch.randint(len(f),(512,));v=b[ids]+.65*(torch.tanh(model(f[ids])[:,0])-torch.tanh(model(canonical[ids])[:,0]));e=v-t[ids]
            weight=1.
            robust=torch.sqrt(e*e+.005**2)-.005
            loss=loss+weight*(robust*mask[ids]).sum()/mask[ids].sum().clamp(min=1.)
        loss.backward();opt.step()
        if (step+1)%300==0:
            scores={}
            with torch.no_grad():
                for n,(f,canonical,b,t,mask) in d.items():
                    e=(b+.65*(torch.tanh(model(f)[:,0])-torch.tanh(model(canonical)[:,0]))-t).abs();scores[n]=float((e*mask).sum()/mask.sum().clamp(min=1.))
            history.append(dict(step=step+1,scores=scores));print(step+1,round(time.monotonic()-started,1),{k:round(v,4) for k,v in scores.items()},flush=True)
    layers=[dict(weight=layer.weight.detach().tolist(),bias=layer.bias.detach().tolist()) for layer in model if isinstance(layer,torch.nn.Linear)]
    result=dict(label=a.label,rotation_aligned=a.aligned,minimum_sites=1 if partial else 2,train=train,holdout=holdout,inputs='OBSERVED_SITES; scene direction/seed; no per-site rate or length',layers=layers,history=history)
    path=OUT/f'{a.label}.json';path.write_text(json.dumps(result,ensure_ascii=False),'utf-8');print(path,flush=True)


if __name__=='__main__':main()
