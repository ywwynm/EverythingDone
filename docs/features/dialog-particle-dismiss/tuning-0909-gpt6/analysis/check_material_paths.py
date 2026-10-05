"""在已记录的真实材料轨迹上复核完整三维材质纹理的重复变化。"""
from pathlib import Path
import argparse,json,sys
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--candidate',type=Path,required=True);ap.add_argument('--baseline',type=Path,default=HERE/'particle-scatter-weights.json')
    ap.add_argument('--traces',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return');ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--baseline-traces',type=Path,help='分别沿修改前后的路径比较同一批材料；省略则两份材质在同一路径比较')
    a=ap.parse_args()
    import torch
    from shared_transport import transport_features
    from dissolve_v2 import Site
    from v2_inputs import OBSERVED_SITES
    torch.set_num_threads(4)
    to=lambda v:torch.as_tensor(np.ascontiguousarray(v,np.float32),device='cuda')
    def load(path):
        d=json.loads(path.read_text('utf-8'));return [(to(l['weight']),to(l['bias'])) for l in d['layers']],to(d['scales'])
    models={'before':load(a.baseline),'candidate':load(a.candidate)}
    report={};rng=np.random.default_rng(1938)
    yy,xx=np.mgrid[:64,:64].astype(np.float32);xx=-.38+(xx+.5)/64*1.76;yy=-.38+(yy+.5)/64*1.76
    for path in sorted(a.traces.glob('*.npz')):
        s=np.load(path);alltr=s['trace'];card=s['rect'][2:]-s['rect'][:2]
        meta=json.loads((HERE/'assets'/path.stem/'scene.json').read_text('utf-8'));sites=[Site(*v) for v in OBSERVED_SITES[path.stem]]
        live=(alltr[:,:,4]>.04)&(alltr[:,:,4]<alltr[:,:,5]-.025)&(alltr[:,:,6]<.6)&s['mask'][None]
        first=live.argmax(0);last=len(live)-1-live[::-1].argmax(0);valid=np.flatnonzero((last-first>=16)&live.any(0))
        ids=rng.choice(valid,min(2048,len(valid)),replace=False);tr=alltr[:,ids];mask=live[:,ids];clock=alltr[:,0,4]-alltr[0,0,4]
        xy=(tr[:,:,2:4]/card+.38)/1.76*2-1;z=((clock*40+.5)/41*2-1)[:,None,None]*np.ones((1,len(ids),1))
        grid=to(np.concatenate([xy,z],-1)[None,None]);features=to(np.stack([transport_features(xx,yy,t,*card,meta['direction'],sites,True) for t in np.linspace(0,1,41)]))
        old_grid=grid
        if a.baseline_traces:
            old=np.load(a.baseline_traces/path.name)
            assert old['trace'].shape==alltr.shape and np.array_equal(old['source'],s['source'])
            old_live=(old['trace'][:,:,4]>.04)&(old['trace'][:,:,4]<old['trace'][:,:,5]-.025)&(old['trace'][:,:,6]<.6)&old['mask'][None]
            assert np.array_equal(live,old_live),'两份路径的可见材料区间不同，需要单独处理交集'
            old_xy=(old['trace'][:,ids,2:4]/card+.38)/1.76*2-1
            old_grid=to(np.concatenate([old_xy,z],-1)[None,None])
        row={'materials':len(ids),'method':'按实际三维纹理采样；仅检查覆盖、反射、色度控制量，不等同最终合成像素亮度','separate_paths':bool(a.baseline_traces)}
        with torch.no_grad():
            for name,(layers,scales) in models.items():
                v=features
                for i,(w,b) in enumerate(layers):
                    v=v@w.T+b
                    if i<len(layers)-1:v=v.tanh()
                volume=(v.tanh()*scales).permute(3,0,1,2)[None]
                values=torch.nn.functional.grid_sample(volume,old_grid if name=='before' else grid,mode='bilinear',padding_mode='border',align_corners=False)[0,:,0].permute(1,2,0).cpu().numpy()/scales.cpu().numpy()
                excess=[];ranges=[]
                for j,idx in enumerate(ids):
                    q=values[first[idx]:last[idx]+1,j]
                    travel=abs(np.diff(q,axis=0)).sum(0);span=np.ptp(q,axis=0)
                    excess.append(np.maximum(travel-2*span,0));ranges.append(span)
                excess=np.array(excess);ranges=np.array(ranges)
                row[name]=dict(repeated_change_q90=np.quantile(excess,.9,axis=0).tolist(),repeated_change_mean=excess.mean(0).tolist(),range_q90=np.quantile(ranges,.9,axis=0).tolist(),finite=bool(np.isfinite(values).all()))
        report[path.stem]=row;print(path.stem,{key:row[key]['repeated_change_q90'] for key in models},flush=True)
        a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(dict(baseline=str(a.baseline),candidate=str(a.candidate),baseline_traces=str(a.baseline_traces) if a.baseline_traces else None,scenes=report),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
