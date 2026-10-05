"""将联合拟合的位置增量和另一次真实 GPU 路径逐材料核对。

只检查预测方程与时间插值，不把模型已有路径当成华为轨迹真值。
"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from scipy.ndimage import map_coordinates

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--candidate',type=Path,required=True)
    ap.add_argument('--traces',type=Path,required=True)
    ap.add_argument('--baseline-traces',type=Path,default=HERE/'analysis/match-cache/motion-continuity/all-material-return')
    ap.add_argument('--scenes',nargs='+',default=['reference-0926-2','reference-0926-4','ironman-up-reference','ironman'])
    a=ap.parse_args();ctx=moderngl.create_standalone_context(require=430);rows={}
    configs=[configuration(root) for root in [HERE,a.candidate]]
    for name in a.scenes:
        before=np.load(a.baseline_traces/f'{name}.npz');after=np.load(a.traces/f'{name}.npz')
        assert np.array_equal(before['source'],after['source'])
        tr=before['trace'];actual=after['trace'];span=float(before['span']);card=before['rect'][2:]-before['rect'][:2]
        alive=(tr[:,:,4]>.04)&(tr[:,:,4]<tr[:,:,5]-.055)&(tr[:,:,6]<.6)&before['mask']
        # 固定间隔抽样只为限制内存；检查不同于拟合时的出生分层和固定组抽样。
        ids=np.arange(7,tr.shape[1],max(1,tr.shape[1]//1536));tr=tr[:,ids];actual=actual[:,ids];alive=alive[:,ids]
        clock=tr[:,0,4]-tr[0,0,4]
        xy=(tr[:,:,:2]/card+.38)/1.76*56-.5
        coordinates=np.stack([np.broadcast_to(clock[:,None]*40,xy.shape[:2]),xy[:,:,1],xy[:,:,0]])
        changes=[]
        for cls,kw in configs:
            r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
            field=np.frombuffer(r.transport_tex.read(),np.float32).reshape(41,56,56,3)
            changes.append(np.stack([map_coordinates(field[...,k],coordinates,order=1,mode='nearest') for k in [0,1]],-1))
            r.close()
        age=np.clip(tr[:,:,4]/.03,0,1);warm=age*age*(3-2*age)
        predicted=(changes[1]-changes[0])*warm[...,None]
        measured=(actual[:,:,2:4]-tr[:,:,2:4])/span
        error=np.linalg.norm(predicted-measured,axis=-1)[alive]
        p=predicted[alive].ravel();q=measured[alive].ravel()
        row=dict(samples=int(alive.sum()),error_quantiles=np.quantile(error,[.5,.9,.99,1]).tolist(),
                 change_cosine=float(p@q/max(np.linalg.norm(p)*np.linalg.norm(q),1e-12)))
        rows[name]=row;print(name,row,flush=True)
    ctx.release()
    (a.candidate/'path-proxy-check.json').write_text(json.dumps(dict(scope='模型位置增量，不是华为路径误差',scenes=rows),ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
