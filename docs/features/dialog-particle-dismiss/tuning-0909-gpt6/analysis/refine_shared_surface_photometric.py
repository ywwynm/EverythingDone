"""由真实渲染的表面敏感度校准共同释放函数，避免仅拟合单个完整度阈值。

各参考的中间修正图只用于离线优化；运行时仍是一份起点条件权重。
导数暂时固定颗粒层，因此候选必须经过再次完整运动渲染验证。
"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np,cv2,moderngl
from scipy.ndimage import gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_transfer import TransferRules,TransferRenderer
from conditional_material import ConditionalRules
from learned_release import load_weights,predict,features,canonical_sites
from shared_transport import load_transport
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
NAMES=['ironman','kobe','thanos','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in range(1,8)]

def linear(c):return np.where(c<=.04045,c/12.92,((c+.055)/1.055)**2.4)

def layer(r,which):
    r.fbo.use();r.fbo.clear(0,0,0,0);r.ctx.enable(moderngl.BLEND);r.ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
    r.program['material_pass']=which;r.program['diagnostic']=0;r.fg_tex.use(0)
    r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
    arr=np.frombuffer(r.tex.read(),np.float16).reshape(r.h*2,r.w*2,4)[::-1].astype('float32')
    return cv2.resize(arr,(r.w,r.h),interpolation=cv2.INTER_AREA)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--initial',required=True);ap.add_argument('--transfer',required=True);ap.add_argument('--rounds',type=int,default=2);ap.add_argument('--steps',type=int,default=3500);ap.add_argument('--label',default='photometric');ap.add_argument('--transport');ap.add_argument('--clock-calibration',action='store_true');a=ap.parse_args()
    out=HERE/'analysis/match-cache/material-transfer'/a.label;out.mkdir(parents=True,exist_ok=True)
    config=json.loads(Path(a.transfer).read_text('utf-8'));cfg=ConditionalRules(**config['material']);transfer=TransferRules(**config['transfer'])
    weights=load_weights(Path(a.initial));flow=load_transport(Path(a.transport) if a.transport else HERE/'conditional-transport-weights.json')
    import torch
    torch.set_num_threads(4);torch.manual_seed(27)
    def tensor(a):
        v=np.ascontiguousarray(a,np.float32);return torch.frombuffer(bytearray(v.tobytes()),dtype=torch.float32).reshape(v.shape)
    model=torch.nn.Sequential(torch.nn.Linear(33,48),torch.nn.Tanh(),torch.nn.Linear(48,48),torch.nn.Tanh(),torch.nn.Linear(48,1))
    with torch.no_grad():
        for q,(w,b) in zip([q for q in model if isinstance(q,torch.nn.Linear)],weights):q.weight.copy_(tensor(w));q.bias.copy_(tensor(b))
    initial=json.loads(Path(a.initial).read_text('utf-8'));ctx=moderngl.create_standalone_context(require=430);history=[]
    knots=tensor(initial.get('clock_knots',[0.,.12,.24,.36,.50,.66,.84]))
    clock=torch.nn.Parameter(tensor(initial.get('clock_offsets',[0.]*len(knots))))
    def shared_clock(b):
        i=torch.searchsorted(knots,b).clamp(1,len(knots)-1)
        fraction=((b-knots[i-1])/(knots[i]-knots[i-1])).clamp(0,1)
        values=clock if a.clock_calibration else clock.detach()
        return values[i-1]*(1-fraction)+values[i]*fraction
    for iteration in range(a.rounds):
        dataset={};measures={};start=time.monotonic()
        for name in NAMES:
            c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
            f,native=features(64,64,w,h,m['direction'],m['seed'],sites,rotation_aligned=weights.rotation_aligned)
            canon,_=features(64,64,w,h,m['direction'],m['seed'],canonical_sites(w,h,m['direction']),rotation_aligned=weights.rotation_aligned)
            current=predict(64,64,w,h,m['direction'],m['seed'],sites,weights);target=current.copy();importance=np.full((64,64),.10,np.float32)
            if (name!='ironman' or a.clock_calibration) and c.scene.roi.any():
                r=TransferRenderer(name,ctx=ctx,quality=2,sites=sites,weights=weights,transport_weights=flow,rules=cfg,transfer=transfer)
                original_base=r.base.copy();data=np.frombuffer(r.transfer_buffer.read(),np.float32).reshape(-1,4).copy()
                numerator=np.zeros((h,w),np.float64);denominator=np.zeros((h,w),np.float64);bg=linear(c.scene.bg.astype('float32')/255.)
                errors=[]
                for i,ref in [c.ref.nearest(t) for t in [.10,.18,.26,.34,.42,.50,.58,.66,.74,.82]]:
                    p=float(c.ref.progress[i]);frame=r.render(p);cloud=layer(r,1)
                    surfaces=[];epsilon=.014
                    for sign in [-1,1]:
                        b=original_base.copy();b[:,2]+=sign*epsilon;r.material.write(b.tobytes())
                        d=data.copy();d[:,0]+=sign*epsilon;r.transfer_buffer.write(d.tobytes())
                        surfaces.append(layer(r,0))
                    r.material.write(original_base.tobytes());r.transfer_buffer.write(data.tobytes())
                    derivative=(surfaces[1]-surfaces[0])/(2*epsilon)
                    derivative=(derivative[:,:,:3]-bg*derivative[:,:,3:])*(1-cloud[:,:,3:])
                    residual=linear(frame.astype('float32')/255.)-linear(np.asarray(ref).astype('float32')/255.)
                    derivative=cv2.GaussianBlur(derivative,(0,0),2.)
                    residual=cv2.GaussianBlur(residual,(0,0),2.)
                    valid=c.scene.roi[y:y1,x:x1]
                    numerator+=(derivative*residual).sum(2)[y:y1,x:x1]*valid
                    denominator+=(derivative*derivative).sum(2)[y:y1,x:x1]*valid
                    errors.append(float(np.abs(residual).mean(2)[c.scene.roi].mean()))
                num=cv2.resize(gaussian_filter(numerator,6.),(64,64),interpolation=cv2.INTER_AREA)
                den=cv2.resize(gaussian_filter(denominator,6.),(64,64),interpolation=cv2.INTER_AREA)
                correction=-num/(den+.03)
                correction=np.clip(correction,-.075,.075)
                confidence=den/(den+.12)
                target=np.clip(current+.70*correction*confidence,.002,.84)
                importance+=confidence
                np.savez_compressed(out/f'{iteration+1}-{name}.npz',current=current,target=target,confidence=confidence,correction=correction)
                measures[name]=dict(error=float(np.mean(errors)),mean_correction=float((correction*confidence).mean()),max_correction=float(abs(correction*confidence).max()))
                r.close();print('光度测量',iteration+1,name,measures[name],flush=True)
            dataset[name]=[tensor(v) for v in [f.reshape(-1,33),canon.reshape(-1,33),native.ravel(),target.ravel(),importance.ravel()]]
        opt=torch.optim.AdamW(list(model.parameters())+([clock] if a.clock_calibration else []),lr=.00055,weight_decay=.003)
        for step in range(a.steps):
            opt.zero_grad();loss=0.
            for name,(f,c,b,t,wt) in dataset.items():
                ids=torch.randint(len(f),(512,));v=b[ids]+shared_clock(b[ids])+.65*(torch.tanh(model(f[ids])[:,0])-torch.tanh(model(c[ids])[:,0]));e=v-t[ids]
                loss+=((torch.sqrt(e*e+.003**2)-.003)*wt[ids]).sum()/wt[ids].sum()
            if a.clock_calibration:
                loss+=.03*clock.square().mean()+.06*(clock[2:]-2*clock[1:-1]+clock[:-2]).square().mean()+3.*torch.relu(.025-torch.diff(knots+clock)).square().mean()
            loss.backward();opt.step()
            if a.clock_calibration:
                with torch.no_grad():clock.clamp_(-.08,.08)
            if (step+1)%1000==0:print('共同更新',iteration+1,step+1,round(float(loss.detach()),5),round(time.monotonic()-start,1),flush=True)
        result={k:v for k,v in initial.items() if k not in ['layers','history']}
        result.update(label='surface-photometric-shared-release',round=iteration+1,train=NAMES,holdout=[],minimum_sites=1,transfer=config,
            layers=[dict(weight=q.weight.detach().tolist(),bias=q.bias.detach().tolist()) for q in model if isinstance(q,torch.nn.Linear)])
        if a.clock_calibration:result.update(clock_knots=knots.detach().tolist(),clock_offsets=clock.detach().tolist())
        path=out/f'round-{iteration+1}.json';path.write_text(json.dumps(result,ensure_ascii=False),'utf-8');weights=load_weights(path)
        history.append(dict(round=iteration+1,measurements=measures));(out/'history.json').write_text(json.dumps(history,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
