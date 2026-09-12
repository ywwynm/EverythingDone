"""依据当前材料分布检测双侧稀疏的窄脊；实验中只反馈运动，不修改可见性。"""
from pathlib import Path
import sys,json,math,argparse
import numpy as np,moderngl,cv2
from scipy.ndimage import gaussian_filter,map_coordinates
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from ablate_edge_support import SHADERS,replace
from probe_edge_support import OUT

def smooth(x):
    a=np.clip(x,0,1);return a*a*(3.-2*a)

def configure(gain,mode='gradient'):
    c=SHADERS['COMPUTE'];c=replace(c,'uniform sampler3D guide_field;','uniform sampler3D guide_field;\nuniform sampler2D neighbourhood;\nuniform vec4 neighbourhood_bounds;')
    c=replace(c,'target+=peel*span*','target+=(1.-.000001+texture(neighbourhood,(p-neighbourhood_bounds.xy)/neighbourhood_bounds.zw).z*.000001)*peel*span*' if mode in ['potential','residual'] else 'target+=texture(neighbourhood,(p-neighbourhood_bounds.xy)/neighbourhood_bounds.zw).z*peel*span*')
    c=replace(c,'float depth_target=-state[i].pos.z*3.*roll_gain;',f'target+=texture(neighbourhood,(p-neighbourhood_bounds.xy)/neighbourhood_bounds.zw).xy*span*{gain:.8f};\n    float depth_target=-state[i].pos.z*3.*roll_gain;')
    renderer.COMPUTE=c;renderer.VERTEX=SHADERS['VERTEX'];renderer.FRAGMENT=SHADERS['FRAGMENT']

class SupportRenderer(renderer.Renderer):
    support_mode='gradient'
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        resolution=120 if self.support_mode=='residual' else 80
        self.grid_cell=self.span/resolution
        self.grid_shape=(math.ceil(self.ch/self.grid_cell)+resolution*2,math.ceil(self.cw/self.grid_cell)+resolution*2)
        self.bounds=np.array([-self.span,-self.span,self.grid_shape[1]*self.grid_cell,self.grid_shape[0]*self.grid_cell],dtype='float32')
        self.support_tex=self.ctx.texture((self.grid_shape[1],self.grid_shape[0]),4,dtype='f4')
        self.support_tex.filter=(moderngl.LINEAR,moderngl.LINEAR);self.support_tex.repeat_x=False;self.support_tex.repeat_y=False
        self.compute['neighbourhood']=5;self.compute['neighbourhood_bounds']=tuple(self.bounds)
        self.support_stats=[];self.field=None;self.update_support(0.)
    def update_support(self,time):
        state=np.frombuffer(self.state.read(),'float32').reshape(-1,8);age=np.maximum(time-self.base[:,2],0.)
        gain=.55+.9*self.touch_strength
        pos=self.base[:,:2]+(state[:,:2]-self.base[:,:2])/gain
        include=(self.base[:,3]<self.nx*self.ny)&(age<self.base[:,6])
        mass=(1.-smooth((age-(self.base[:,6]-.075))/.075))*(1.-.6*smooth((age-.08)/.30))
        q=(pos-self.bounds[:2])/self.grid_cell-.5;lower=np.floor(q).astype(int);f=q-lower
        density=np.zeros(self.grid_shape,'float32');height,width=self.grid_shape
        for dx,dy in [(0,0),(1,0),(0,1),(1,1)]:
            gx=lower[:,0]+dx;gy=lower[:,1]+dy;valid=include&(gx>=0)&(gx<width)&(gy>=0)&(gy<height)
            weight=mass*(f[:,0] if dx else 1-f[:,0])*(f[:,1] if dy else 1-f[:,1])
            density+=np.bincount((gy[valid]*width+gx[valid]),weights=weight[valid],minlength=width*height).reshape(height,width).astype('float32')
        density*=self.cell[0]*self.cell[1]/self.grid_cell**2
        fine=gaussian_filter(density,.8);broad=gaussian_filter(density,3.)
        dxx=gaussian_filter(density,1.,order=(0,2));dyy=gaussian_filter(density,1.,order=(2,0));dxy=gaussian_filter(density,1.,order=(1,1))
        low=(dxx+dyy)*.5-np.sqrt(((dxx-dyy)*.5)**2+dxy*dxy)
        nx=-dxy;ny=dxx-low;length=np.maximum(np.hypot(nx,ny),1e-8);nx/=length;ny/=length
        y,x=np.mgrid[:height,:width].astype('float32')
        side0=map_coordinates(fine,[y+ny*3.,x+nx*3.],order=1,mode='constant')
        side1=map_coordinates(fine,[y-ny*3.,x-nx*3.],order=1,mode='constant')
        ratio=fine/np.maximum(np.maximum(side0,side1),.025)
        isolated=smooth((ratio-1.20)/.85)*smooth((fine/(broad+.001)-1.18)/.8)*smooth((fine-.04)/.10)
        isolated=gaussian_filter(isolated,.8)
        gy,gx=np.gradient(fine);pressure=-np.stack([gx,gy],axis=-1)/(fine[:,:,None]+.05)
        pressure*=isolated[:,:,None]
        if self.support_mode=='potential':
            # 窄脊的压力势在两侧连续求导，峰值中心不依靠密度梯度乘遮罩。
            # 不改变原剥离；单侧连接高密度主群的自然边界没有正压力。
            excess=np.maximum(fine-np.maximum(side0,side1),0.)
            potential=gaussian_filter(excess,1.)
            gy,gx=np.gradient(potential)
            pressure=-np.stack([gx,gy],axis=-1)/(fine[:,:,None]+.10)
            isolated=potential/(fine+.10)
        if self.support_mode=='residual':
            fine=gaussian_filter(density,.6);broad=gaussian_filter(density,1.8)
            potential=gaussian_filter(np.maximum(fine-broad,0.),.6)
            gy,gx=np.gradient(potential)
            pressure=-np.stack([gx,gy],axis=-1)/(fine[:,:,None]+.10)
            isolated=potential/(fine+.10)
        self.field=np.concatenate([pressure,(1.-isolated)[:,:,None],fine[:,:,None]],axis=-1).astype('float32')
        self.support_tex.write(self.field.tobytes());self.support_tex.use(5)
        at=map_coordinates(isolated,[q[include,1],q[include,0]],order=1,mode='constant')
        self.support_stats.append(dict(time=time,active_fraction=float(np.mean(at>.1)) if at.size else 0.,mean=float(at.mean()) if at.size else 0.,maximum=float(at.max()) if at.size else 0.))
    def seek(self,time):
        if not hasattr(self,'support_tex'):return super().seek(time)
        target=int(max(time,0)/renderer.STEP+1e-6)
        if target<self.step:self.reset();self.update_support(0.)
        while self.step<target:
            end=min(target,(self.step//4+1)*4)
            super().seek(end*renderer.STEP)
            if self.step%4==0:self.update_support(self.step*renderer.STEP)
    def close(self):
        self.support_tex.release();super().close()

def main():
    p=argparse.ArgumentParser();p.add_argument('--gain',type=float,default=1.);p.add_argument('--mode',default='gradient');a=p.parse_args();configure(a.gain,a.mode);SupportRenderer.support_mode=a.mode
    ctx=moderngl.create_standalone_context(require=430);inputs=json.loads((OUT/'matched-inputs.json').read_text('utf-8'))
    inputs.append(dict(name='ironman',angle=122,seed=909602,phase=.56))
    for item in inputs:
        r=SupportRenderer('ironman' if item['name']=='ironman' else 'attachment',direction=item['angle'],seed=item['seed'],ctx=ctx)
        phases=np.array([-.06,0,.06])+item['phase'];frames=[r.render(float(t)) for t in phases]
        tag=f'support-{a.gain:g}' if a.mode=='gradient' else f'{a.mode}-{a.gain:g}'
        np.save(OUT/item['name']/f'ablate-{tag}.npy',np.array(frames))
        (OUT/item['name']/f'{tag}.json').write_text(json.dumps(r.support_stats,indent=2),'utf-8')
        np.save(OUT/item['name']/f'{tag}-field.npy',r.field)
        r.close();print(item['name'],'当前分布反馈',a.gain,flush=True)
    ctx.release()
if __name__=='__main__':main()
