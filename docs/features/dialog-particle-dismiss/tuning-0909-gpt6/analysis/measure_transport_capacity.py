"""不可发布的诊断上限：暂时解除共同函数约束，检查当前输运变量能解释多少误差。

这些逐帧控制格只用于定位模型／优化器的限制，不可被预览、导出或 Android 引用。
"""
from pathlib import Path
import argparse,json,pickle,sys,time
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_particle_proxy import Splat,LO,SIZE
from surface_coherence import CoherenceRenderer
from check_cloud_evolution import configuration
from match_compare import Comparison
from fit_particle_optics import measure
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


class CapacityProbe(CoherenceRenderer):
    vertex_source=CoherenceRenderer.vertex_source.replace('void main(){','uniform sampler2D capacity_field;\nvoid main(){',1)
    vertex_source=vertex_source.replace('vec3 transport=texture(transport_field,vec3(q,phase)).xyz;',
        'vec3 transport=texture(transport_field,vec3(q,phase)).xyz+texture(capacity_field,q).rgb;',1)
    def __init__(self,*a,capacity_size=24,**kw):
        super().__init__(*a,**kw);self.capacity=self.ctx.texture((capacity_size,capacity_size),3,np.zeros((capacity_size,capacity_size,3),np.float32).tobytes(),dtype='f4')
        self.capacity.filter=(moderngl.LINEAR,moderngl.LINEAR);self.capacity.repeat_x=self.capacity.repeat_y=False;self.program['capacity_field']=12
    def render(self,*a,**kw):self.capacity.use(12);return super().render(*a,**kw)
    def close(self):super().close();self.capacity.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--cache',required=True);ap.add_argument('--steps',type=int,default=1600)
    ap.add_argument('--all',action='store_true');ap.add_argument('--jacobian',type=float,default=0.);ap.add_argument('--label',default='')
    ap.add_argument('--integrated',action='store_true',help='先积分共同候选所用的连续速度表示，再测量表达能力')
    a=ap.parse_args()
    out=HERE/'analysis/match-cache/transport-capacity'/a.label;out.mkdir(parents=True,exist_ok=True)
    data=pickle.loads(Path(a.cache).read_bytes())
    names=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']
    chosen=[d for i,d in enumerate(data) if a.all or (d['name'] in names and i%8 in [2,4,6])]
    import torch
    import torch.nn.functional as F
    torch.set_num_threads(4);torch.manual_seed(28);s=Splat(n=160);t=s.tensor
    rows=[{k:t(v) if isinstance(v,(np.ndarray,float,np.floating)) else v for k,v in d.items()} for d in chosen]
    controls=torch.nn.Parameter(torch.zeros((len(rows),24,24,3),device='cuda'));scales=t([.24,.24,1.0])
    def lookup(f,d):return F.grid_sample(f.permute(2,0,1)[None],d['query'][None,None]*2-1,padding_mode='border',align_corners=False)[0,:,0].T
    def photo(image,d):
        z=image.permute(2,0,1)[None];values=[]
        for sigma,weight in [(4.,1.),(12.,.7),(24.,.4)]:
            q=max(.55,sigma*s.n/(SIZE*float(d['span'])));error=s.gaussian(z,q)[0].permute(1,2,0)-d['target_filters'][sigma]
            values.append(weight*((error.square()+.003**2).sqrt()*d['mask'][...,None]).sum()/d['mask'].sum().clamp(min=1)/3)
        return sum(values)/2.1
    with torch.no_grad():
        for d in rows:
            d['mass']=s.material_mass(d,torch.zeros_like(d['density']));d['anchor']=s.render(d['pos'],d['mass'],d['color'],d['base'])
            d['cohort_anchor']=s.cohort(d,d['pos'])
            d['target_filters']={q:s.gaussian(d['target'].permute(2,0,1)[None],max(.55,q*s.n/(SIZE*float(d['span']))))[0].permute(1,2,0) for q in [4.,12.,24.]}
            d['prior']=photo(d['actual'],d)
    field_size=56 if a.integrated else 24
    if a.jacobian or a.integrated:
        from shared_transport import correction,load_transport
        yy,xx=np.mgrid[:field_size,:field_size].astype(np.float32);xx=LO+(xx+.5)/field_size*SIZE;yy=LO+(yy+.5)/field_size*SIZE
        weights=load_transport(HERE/'conditional-transport-weights.json')
        for d,original in zip(rows,chosen):
            m=json.loads((HERE/'assets'/d['name']/'scene.json').read_text('utf-8'))
            d['baseline_field']=t(correction(xx,yy,float(original['phase']),*original['card'],m['direction'],[Site(*q) for q in OBSERVED_SITES[d['name']]],weights))
    if a.integrated:
        yy,xx=torch.meshgrid(torch.arange(field_size,device='cuda'),torch.arange(field_size,device='cuda'),indexing='ij')
        grid=(torch.stack([xx,yy],-1).float()+.5)/field_size*2-1
    def field_output(i):
        if not a.integrated:return controls[i].tanh()*scales
        d=rows[i];raw=F.interpolate(controls[i].permute(2,0,1)[None],size=(field_size,field_size),mode='bilinear',align_corners=False)[0].permute(1,2,0).tanh()
        v=raw[...,:2]*(1.1*d['phase'])*d['scale']/64
        for _ in range(6):v=v+F.grid_sample(v.permute(2,0,1)[None],(grid+v*2/SIZE)[None],padding_mode='border',align_corners=False)[0].permute(1,2,0)
        extra=torch.cat([v/d['scale'],raw[...,2:]*1.4],-1)
        shifted=grid+d['baseline_field'][...,:2]*d['scale']*2/SIZE
        return F.grid_sample(extra.permute(2,0,1)[None],shifted[None],padding_mode='border',align_corners=False)[0].permute(1,2,0)
    def output(i):
        d=rows[i];field=field_output(i);delta=lookup(field,d)*d['warm'][:,None]
        pos=d['pos']+delta[:,:2]*d['scale']/SIZE;density=delta[:,2]+s.cohort(d,pos)-d['cohort_anchor']
        mass=s.material_mass(d,density);image=d['actual']+s.render(pos,mass,d['color'],d['base'])-d['anchor']
        return image,field
    opt=torch.optim.Adam([controls],lr=.015);start=time.monotonic()
    for step in range(a.steps):
        for group in opt.param_groups:group['lr']=.015*(.06+.94*.5*(1+np.cos(np.pi*step/a.steps)))
        ids=torch.randint(len(rows),(4,)).tolist();opt.zero_grad();loss=0.
        for i in ids:
            d=rows[i];image,field=output(i);dx=(field[1:,:-1]-field[:-1,:-1])*field_size/SIZE;dy=(field[:-1,1:]-field[:-1,:-1])*field_size/SIZE
            loss+=photo(image,d)/d['prior'].clamp(min=.015)+.005*(dx.square().mean()+dy.square().mean())+.002*field.square().mean()
            if a.jacobian:
                total=(field+d['baseline_field'])[...,:2]*d['scale']
                jx=(total[:-1,1:]-total[:-1,:-1])*field_size/SIZE;jy=(total[1:,:-1]-total[:-1,:-1])*field_size/SIZE
                determinant=(1+jx[...,0])*(1+jy[...,1])-jx[...,1]*jy[...,0]
                loss+=a.jacobian*F.relu(.25-determinant).square().mean()
        (loss/len(ids)).backward();opt.step()
        if (step+1)%400==0:
            with torch.no_grad():ratios=[float(photo(output(i)[0],d)/d['prior']) for i,d in enumerate(rows)]
            print('诊断上限',step+1,round(time.monotonic()-start,1),[round(v,3) for v in ratios],flush=True)
    with torch.no_grad():fields=torch.stack([field_output(i) for i in range(len(rows))]).cpu().numpy()
    np.savez_compressed(out/'diagnostic-controls.npz',fields=fields,names=[d['name'] for d in chosen],phases=[d['progress'] for d in chosen])
    # 输出供离线共同函数蒸馏使用；严禁运行时读回这些控制格。
    ctx=moderngl.create_standalone_context(require=430);_,kw=configuration(HERE,scatter=False);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in sorted(set(d['name'] for d in chosen)):
        c=Comparison(name);r=CapacityProbe(name,ctx=ctx,quality=2,capacity_size=field_size,sites=[Site(*q) for q in OBSERVED_SITES[name]],**kw)
        indices=[i for i,d in enumerate(chosen) if d['name']==name];box=c.scene.rect;x,y,x1,y1=box;box=(max(0,x-35),max(0,y-35),min(c.scene.W,x1+35),min(c.scene.H,y1+35))
        tw=360;th=round((box[3]-box[1])*tw/(box[2]-box[0]));sheet=Image.new('RGB',(tw*len(indices),(th+25)*3),(14,20,30));draw=ImageDraw.Draw(sheet);measures=[]
        for j,i in enumerate(indices):
            d=chosen[i];p=d['progress'];_,ref=c.ref.nearest(p);r.capacity.write(np.zeros_like(fields[i]).tobytes());before=r.render(p)
            r.capacity.write(fields[i].tobytes());after=r.render(p);target=measure(ref,c.scene.roi)
            stat=dict(phase=p,proxy_ratio=float(photo(output(i)[0],rows[i]).detach()/rows[i]['prior']))
            for k,(label,frame) in enumerate([('华为原片',ref),('当前默认',before),('诊断上限：非共同模型',after)]):
                sheet.paste(Image.fromarray(frame).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25));draw.text((j*tw,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
                if k:stat['before' if k==1 else 'after']={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
            measures.append(stat)
        r.close();report[name]=measures;sheet.save(out/f'{name}.jpg',quality=95);print(name,measures,flush=True)
        (out/'diagnostic.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
