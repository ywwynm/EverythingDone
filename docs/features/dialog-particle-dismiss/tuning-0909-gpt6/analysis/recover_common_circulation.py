"""检查合成场的旋转抵消，生成共用环流候选；不按运行素材选择参考。"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from scipy.fft import fft2,ifft2,fftfreq
from fit_flow import evaluate
from renderer import HERE

out=HERE/'analysis/edge-roll';n=36;dy=1.9/n
yy,xx=np.mgrid[:n,:n].astype(float);x=-.45+(xx+.5)*dy;y=-.45+(yy+.5)*dy
fields=[]
for name in ['ironman','thanos','kobe']:
    p=json.loads((HERE/'assets'/name/'flow-profile.json').read_text('utf-8'))
    angle=np.deg2rad(p['direction']-90);c,s=np.cos(angle),np.sin(angle)
    qx=.5+c*(x-.5)+s*(y-.5);qy=.5-s*(x-.5)+c*(y-.5)
    layers=[]
    for t in np.linspace(0,1,32):
        v=evaluate(p,qx,qy,t);layers.append(np.stack((c*v[...,0]-s*v[...,1],s*v[...,0]+c*v[...,1]),axis=-1))
    fields.append(np.array(layers))
fields=np.array(fields);mean=fields.mean(axis=0)
omega=np.gradient(fields[:,:,:,:,1],dy,axis=3)-np.gradient(fields[:,:,:,:,0],dy,axis=2)
avg=omega.mean(axis=0);rms=np.sqrt(np.mean(omega**2,axis=0))
# 保留合成场的旋向，只恢复被相反旋向抵消的部分幅值。
extra=np.tanh(avg/(.20+.15*rms))*np.maximum(rms-abs(avg),0)*.80
border=np.minimum.reduce([x+.45,1.45-x,y+.45,1.45-y])
u=np.clip(border/.24,0,1);extra*=u*u*(3-2*u)
ky,kx=np.meshgrid(fftfreq(n,dy)*2*np.pi,fftfreq(n,dy)*2*np.pi,indexing='ij')
den=kx*kx+ky*ky;den[0,0]=1.
hat=fft2(extra);hat[:,0,0]=0
psi=-hat/den
delta=np.stack([ifft2(-1j*ky*psi).real,ifft2(1j*kx*psi).real],axis=-1)
result=(mean+delta).astype('float32')
np.save(out/'recovered-common-flow.npy',result)
stats={'source_count':3,'mean_vorticity_rms':float(np.sqrt(np.mean(avg**2))),
       'source_vorticity_rms':float(np.sqrt(np.mean(rms**2))),
       'correction_speed_p95':float(np.quantile(np.linalg.norm(delta,axis=-1),.95)),
       'scope':'一份共用候选；保留合成旋向，恢复部分旋转幅值，运行时不选择人物配置。'}
(out/'circulation-recovery.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(stats,ensure_ascii=False))
