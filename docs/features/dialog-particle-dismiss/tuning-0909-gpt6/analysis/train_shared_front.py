"""训练有传播约束的共同起点核；起点数量、位置、时间固定，不拟合逐片速度。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from train_shared_release import TRAIN,HOLDOUT,corpus
from shared_front import site_features,canonical_sites
from v2_inputs import OBSERVED_SITES
from dissolve_v2 import Site


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--steps',type=int,default=5000);ap.add_argument('--label',default='kernel');a=ap.parse_args()
    import torch
    torch.set_num_threads(4);torch.manual_seed(29)
    data={}
    for name in TRAIN+HOLDOUT:
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=meta['rect'];w,h=x1-x,y1-y
        _,_,native,t,mask=corpus(name)
        pairs=[site_features(64,64,w,h,meta['direction'],ss) for ss in [[Site(*s) for s in OBSERVED_SITES[name]],canonical_sites(w,h,meta['direction'])]]
        tensor=lambda v:torch.tensor(v.tolist(),dtype=torch.float32)
        items=[]
        for f,r,st in pairs:items.extend([tensor(f.reshape(-1,f.shape[-2],f.shape[-1])),tensor(r.reshape(-1,r.shape[-1])),tensor(st.reshape(-1,st.shape[-1]))])
        data[name]=items+[tensor(v) for v in [native,t,mask]]
    model=torch.nn.Sequential(torch.nn.Linear(16,24),torch.nn.Tanh(),torch.nn.Linear(24,24),torch.nn.Tanh(),torch.nn.Linear(24,1))
    opt=torch.optim.AdamW(model.parameters(),lr=.0015,weight_decay=.015)
    def field(f,r,st):
        delay=.16+.95*torch.sigmoid(model(f)[:,:,0])
        return -.018*torch.logsumexp(-(st+r*delay)/.018,-1)
    def calc(row,ids):
        f,r,st,cf,cr,cst,b,t,mask=row
        v=b[ids]+field(f[ids],r[ids],st[ids])-field(cf[ids],cr[ids],cst[ids])
        return v-t[ids],mask[ids]
    history=[];started=time.monotonic()
    for step in range(a.steps):
        opt.zero_grad();loss=0.
        for name in TRAIN:
            e,mask=calc(data[name],torch.randint(4096,(256,)))
            loss+=((torch.sqrt(e*e+.005**2)-.005)*mask).sum()/mask.sum().clamp(min=1)
        loss.backward();opt.step()
        if (step+1)%500==0:
            with torch.no_grad():
                scores={}
                for name,row in data.items():
                    e,mask=calc(row,slice(None));scores[name]=float((e.abs()*mask).sum()/mask.sum().clamp(min=1))
            history.append(dict(step=step+1,scores=scores));print(step+1,round(time.monotonic()-started,1),{k:round(v,4) for k,v in scores.items()},flush=True)
    layers=[dict(weight=m.weight.detach().tolist(),bias=m.bias.detach().tolist()) for m in model if isinstance(m,torch.nn.Linear)]
    out=HERE/'analysis/match-cache/shared-release'/f'{a.label}.json';out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(dict(train=TRAIN,holdout=HOLDOUT,layers=layers,history=history),ensure_ascii=False),'utf-8');print(out,flush=True)


if __name__=='__main__':main()
