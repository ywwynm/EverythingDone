"""离线运动观测控制组，用于测量渲染／释放／运动分别限制了多少相似度。

此模块逐素材读取参考片，只用于诊断，禁止作为共同模型运行依赖。
"""
from pathlib import Path
import sys
import cv2,numpy as np
from scipy.ndimage import gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from coupled_transport import CoupledRenderer


def observed_motion(probe):
    path=HERE/'analysis/match-cache/shared-motion'/f'{probe.name}.npy';path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():return np.load(path)
    ref=probe.comp.ref;meta=probe.scene.meta;x,y,x1,y1=meta['rect'];w,h=x1-x,y1-y;span=min(w,h)
    n=80;nt=48;yy,xx=np.mgrid[:n,:n].astype('float32');qx=x+(-.35+(xx+.5)/n*1.7)*w;qy=y+(-.35+(yy+.5)/n*1.7)*h
    ph,pw=probe.scene.source.shape[:2];fy,fx=np.mgrid[:ph,:pw].astype('float32')
    out=[]
    for t in np.linspace(0,1,nt):
        ia,_=ref.nearest(max(t-.013,0));ib,_=ref.nearest(min(t+.013,1))
        if ia==ib:ib=min(ib+1,len(ref.progress)-1)
        dt=max(ref.progress[ib]-ref.progress[ia],.001)
        a,b=[cv2.cvtColor(np.asarray(ref.frames[i]),cv2.COLOR_RGB2GRAY) for i in [ia,ib]]
        flow=cv2.calcOpticalFlowFarneback(a,b,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        reverse=cv2.calcOpticalFlowFarneback(b,a,None,.5,4,21,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
        back=cv2.remap(reverse,fx+flow[:,:,0],fy+flow[:,:,1],cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
        error=np.linalg.norm(flow+back,axis=-1)
        reliable=np.exp(-np.minimum(error,10)**2/1.5)
        # 只平滑可靠运动；零速度仍可代表尚未释放的材料，不当成缺失值。
        denominator=gaussian_filter(reliable,3)+1e-5
        flow=np.stack([gaussian_filter(flow[:,:,k]*reliable,3)/denominator for k in range(2)],-1)/(dt*span)
        sampled=cv2.remap(flow.astype('float32'),qx,qy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_REPLICATE)
        out.append(np.clip(sampled,-2.5,2.5))
    out=gaussian_filter(np.asarray(out,np.float32),(.45,.3,.3,0));np.save(path,out);return out


class OracleRenderer(CoupledRenderer):
    compute_source=CoupledRenderer.compute_source.replace('void main(){','uniform sampler3D observation_field;\nuniform float observation_weight;\nvoid main(){',1)
    compute_source=compute_source.replace('float response=1.-exp(',
        'target=mix(target,texture(observation_field,vec3((p/card+vec2(.35))/1.7,time)).xy*span/distance_gain,observation_weight);\n    float response=1.-exp(',1)

    def __init__(self,*args,observed=None,**kwargs):
        super().__init__(*args,**kwargs)
        import moderngl
        self.observation_tex=self.ctx.texture3d((observed.shape[2],observed.shape[1],observed.shape[0]),2,observed.astype('float32').tobytes(),dtype='f4')
        self.observation_tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.observation_tex.repeat_x=self.observation_tex.repeat_y=self.observation_tex.repeat_z=False
        self.compute['observation_field']=8;self.compute['observation_weight']=1.

    def seek(self,time):
        self.observation_tex.use(8);super().seek(time)

    def close(self):
        super().close();self.observation_tex.release()
