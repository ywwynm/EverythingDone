"""将附加剥离转为当前空间中单值的速度场，并分离压缩与旋转分量。仅诊断。"""
from pathlib import Path
import sys,json,argparse,math
import numpy as np,moderngl
from PIL import Image
from scipy.ndimage import gaussian_filter
from scipy.fft import fft2,ifft2,fftfreq
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from ablate_edge_support import SHADERS,replace
from probe_edge_support import OUT
from probe_particle_support import smooth

def hash32(x):
    x=x.copy();x^=x>>16;x*=np.uint32(0x7feb352d);x^=x>>15;x*=np.uint32(0x846ca68b);return x^(x>>16)

def configure(gain=1.,coherent_floor=None,hybrid=False,restore=False,flip=False):
    c=replace(SHADERS['COMPUTE'],'uniform sampler3D guide_field;','uniform sampler3D guide_field;\nuniform sampler2D peel_field;\nuniform vec4 peel_bounds;')
    raw='peel*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age)'
    if flip:
        c=replace(c,'target+=peel*span*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age);',f'target+=({raw}+texture(peel_field,(p-peel_bounds.xy)/peel_bounds.zw).xy)*span*{gain:.8f};')
    elif hybrid:
        c=replace(c,'target+=peel*span*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age);',f'vec4 spatial_peel=texture(peel_field,(p-peel_bounds.xy)/peel_bounds.zw);\n    target+=mix({raw},spatial_peel.xy,spatial_peel.z)*span*{gain:.8f};')
    else:
        c=replace(c,'target+=peel*span*1.2141309*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age);',f'target+=texture(peel_field,(p-peel_bounds.xy)/peel_bounds.zw).xy*span*{gain:.8f};\n    target+=peel*span*.000001*(.10+1.80*peel_response)*exp(-age/.16)*smoothstep(.003,.028,age);')
    if restore:c=replace(c,'peel/=1.+peel_pressure*peel_pressure/(1.+peel_pressure);','peel/=1.+peel_pressure*.000001;')
    if coherent_floor is not None:
        c=replace(c,'float deficit=.16-axial;',f'float deficit=mix({coherent_floor:.8f},.16,missing)-axial;')
        c=replace(c,'sqrt(deficit*deficit+.0064)','sqrt(deficit*deficit+.0001)')
    renderer.COMPUTE=c;renderer.VERTEX=SHADERS['VERTEX'];renderer.FRAGMENT=SHADERS['FRAGMENT']

class ProjectedRenderer(renderer.Renderer):
    projection=1.
    restore_strength=False
    unilateral=False
    free_surface=False
    surface_threshold=.025
    hybrid_scale=.015
    flip=False
    correction_scale=0.
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs)
        fg=np.asarray(Image.open(self.directory/'foreground.png').convert('RGBA'))
        fx=np.clip((self.base[:,0]/self.cw*fg.shape[1]).astype(int),0,fg.shape[1]-1);fy=np.clip((self.base[:,1]/self.ch*fg.shape[0]).astype(int),0,fg.shape[0]-1)
        self.coverage=fg[fy,fx,3].astype('float32')/255
        self.gcell=self.span/96.;self.gshape=(math.ceil(self.ch/self.gcell)+192,math.ceil(self.cw/self.gcell)+192)
        self.pb=np.array([-self.span,-self.span,self.gshape[1]*self.gcell,self.gshape[0]*self.gcell],'float32')
        self.pt=self.ctx.texture((self.gshape[1],self.gshape[0]),4,dtype='f4');self.pt.filter=(moderngl.LINEAR,moderngl.LINEAR);self.pt.repeat_x=False;self.pt.repeat_y=False
        self.compute['peel_field']=5;self.compute['peel_bounds']=tuple(self.pb)
        kx,ky=np.meshgrid(fftfreq(self.gshape[1]),fftfreq(self.gshape[0]));self.kx=kx;self.ky=ky;self.denom=kx*kx+ky*ky;self.denom[0,0]=1
        peel=-self.base[:,4:6].copy();wind=np.array(self.wind);axial=peel@wind;peel-=np.minimum(axial,0)[:,None]*wind;peel-=np.maximum(axial,0)[:,None]*wind*.82
        edge=np.minimum(self.base[:,:2],np.array([self.cw,self.ch])-self.base[:,:2]);out=np.where(self.base[:,:2]<np.array([self.cw,self.ch])*.5,-1,1)*np.exp(-edge/(self.span*.13))
        unit=lambda v:(v+1e-6)/np.maximum(np.linalg.norm(v+1e-6,axis=1)[:,None],1e-8)
        eject=np.maximum(np.sum(unit(out)*unit(peel),axis=1),0);peel*=1.-.95*eject[:,None]*np.exp(-edge.min(axis=1)[:,None]/(self.span*.18))
        seed=self.base[:,8].copy().view('uint32')^self.base[:,9].copy().view('uint32')^np.uint32(0xa54ff53a)
        response=(hash32(seed)>>8).astype('float32')/16777216.;strength=1.2141309*(.1+1.8*response)
        compression=np.frombuffer(self.peel_compression.read(),'float32');pressure=2*compression*.16*strength
        self.peel=peel*strength[:,None];self.pressure=np.zeros(self.gshape,'float32')
        if not self.restore_strength:self.peel/=1+pressure[:,None]**2/(1+pressure[:,None])
        self.update_field(0)
    def update_field(self,time):
        s=np.frombuffer(self.state.read(),'float32').reshape(-1,8);age=time-self.base[:,2]
        pos=self.base[:,:2]+(s[:,:2]-self.base[:,:2])/(.55+.9*self.touch_strength)
        q=(pos-self.pb[:2])/self.gcell-.5;lo=np.floor(q).astype(int);frac=q-lo;h,w=self.gshape
        keep=(self.base[:,3]<self.nx*self.ny)&(age>0)&(age<self.base[:,6]);weight0=(1-smooth((age-(self.base[:,6]-.075))/.075))*self.coverage
        velocity=self.peel*(np.exp(-np.maximum(age,0)/.16)*smooth((age-.003)/.025))[:,None]
        weights=np.zeros((h,w),'float32');mom=np.zeros((h,w,2),'float32')
        for dx,dy in [(0,0),(1,0),(0,1),(1,1)]:
            x=lo[:,0]+dx;y=lo[:,1]+dy;ok=keep&(x>=0)&(x<w)&(y>=0)&(y<h);index=y[ok]*w+x[ok]
            mass=(weight0*(frac[:,0] if dx else 1-frac[:,0])*(frac[:,1] if dy else 1-frac[:,1]))[ok]
            weights+=np.bincount(index,weights=mass,minlength=h*w).reshape(h,w).astype('float32')
            for j in range(2):mom[:,:,j]+=np.bincount(index,weights=mass*velocity[ok,j],minlength=h*w).reshape(h,w).astype('float32')
        weights=gaussian_filter(weights,1.3);mom=gaussian_filter(mom,(1.3,1.3,0));u=mom/np.maximum(weights[:,:,None],.5)
        hx=fft2(u[:,:,0]);hy=fft2(u[:,:,1]);potential=(self.kx*hx+self.ky*hy)/self.denom
        correction=np.stack([ifft2(self.kx*potential).real,ifft2(self.ky*potential).real],axis=-1)
        if self.unilateral:
            # 正压力只抵消压缩；零压力区的展开不被整体投影删除。
            divergence=(np.roll(u[:,:,0],-1,axis=1)-np.roll(u[:,:,0],1,axis=1)+np.roll(u[:,:,1],-1,axis=0)-np.roll(u[:,:,1],1,axis=0))*.5
            pressure=self.pressure*.9
            fluid=weights*self.cell[0]*self.cell[1]/self.gcell**2>self.surface_threshold
            for _ in range(100):
                pressure=np.maximum((np.roll(pressure,1,0)+np.roll(pressure,-1,0)+np.roll(pressure,1,1)+np.roll(pressure,-1,1)-divergence)*.25,0.)
                if self.free_surface:pressure*=fluid
            correction=np.stack([(np.roll(pressure,-1,1)-np.roll(pressure,1,1))*.5,(np.roll(pressure,-1,0)-np.roll(pressure,1,0))*.5],axis=-1)
            self.pressure=pressure
        if self.correction_scale>0:
            correction-=gaussian_filter(correction,(self.correction_scale,self.correction_scale,0))
        self.field=np.zeros((*self.gshape,4),'float32');self.field[:,:,:2]=u-self.projection*correction
        if self.flip:self.field[:,:,:2]=-self.projection*correction
        self.field[:,:,2]=1.-np.exp(-self.pressure/max(self.hybrid_scale,.000001))
        self.pt.write(self.field.tobytes());self.pt.use(5)
    def seek(self,time):
        if not hasattr(self,'pt'):return super().seek(time)
        target=int(max(time,0)/renderer.STEP+1e-6)
        if target<self.step:self.reset();self.pressure.fill(0);self.update_field(0)
        self.pt.use(5)
        # 零进度也须绑定当前材料；不能继承上一场景的存储缓冲。
        super().seek(self.step*renderer.STEP)
        while self.step<target:
            end=min(target,(self.step//4+1)*4);super().seek(end*renderer.STEP)
            if self.step%4==0:self.update_field(self.step*renderer.STEP)
    def close(self):self.pt.release();super().close()

def main():
    p=argparse.ArgumentParser();p.add_argument('--projection',type=float,default=1);p.add_argument('--gain',type=float,default=1);p.add_argument('--restore-strength',action='store_true');p.add_argument('--unilateral',action='store_true');p.add_argument('--free-surface',action='store_true');a=p.parse_args();configure(a.gain);ProjectedRenderer.projection=a.projection;ProjectedRenderer.restore_strength=a.restore_strength;ProjectedRenderer.unilateral=a.unilateral;ProjectedRenderer.free_surface=a.free_surface
    ctx=moderngl.create_standalone_context(require=430);inputs=json.loads((OUT/'matched-inputs.json').read_text());inputs.append(dict(name='ironman',angle=122,seed=909602,phase=.56))
    for q in inputs:
        r=ProjectedRenderer('ironman' if q['name']=='ironman' else 'attachment',direction=q['angle'],seed=q['seed'],ctx=ctx)
        frames=[r.render(float(q['phase']+d)) for d in [-.06,0,.06]];np.save(OUT/q['name']/f'ablate-projection-{a.projection:g}{"-gain"+str(a.gain) if a.gain!=1 else ""}{"-restore" if a.restore_strength else ""}{"-unilateral" if a.unilateral else ""}{"-free" if a.free_surface else ""}.npy',np.array(frames));r.close();print(q['name'],a.projection,a.gain,a.restore_strength,a.unilateral,a.free_surface,flush=True)
    ctx.release()
if __name__=='__main__':main()
