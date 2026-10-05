"""一次只消融一项运动或材质机制；保留完整画面与真实 GPU 轨迹。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from scipy.ndimage import gaussian_filter1d
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from diagnose_motion_continuity import trajectory_reader,metrics
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from fit_particle_optics import measure


def configure(r,variant):
    if variant in ['no-offset','time-smooth','time-linear','time-quadratic']:
        field=np.frombuffer(r.transport_tex.read(),np.float32).reshape(41,56,56,3).copy()
        if variant=='no-offset':field[:,:,:,:2]=0
        elif variant=='time-smooth':
            field[:,:,:,:2]=gaussian_filter1d(field[:,:,:,:2],3.,axis=0,mode='nearest')
            field[:,:,:,:2]-=field[:1,:,:,:2]
        else:
            times=np.linspace(0,1,41,dtype=np.float32)
            powers=[times] if variant=='time-linear' else [times,times*times]
            basis=np.stack(powers,-1)
            fitted=np.linalg.lstsq(basis[4:37],field[4:37,:,:,:2].reshape(33,-1),rcond=None)[0]
            field[:,:,:,:2]=(basis@fitted).reshape(41,56,56,2)
        r.transport_tex.write(field.tobytes())
    elif variant=='no-fission':r.program['fission_strength']=0.
    elif variant=='less-fission':r.program['fission_strength']=.6
    elif variant=='no-scatter':r.scatter_tex.write(bytes(len(r.scatter_tex.read())))
    elif variant=='coarser-fission':r.program['fission_threshold']=.010
    elif variant=='full-light':r.program['conditional_light']=1.
    elif variant=='opaque-footprint':r.program['coverage_footprint']=1.
    elif variant in ['neutral-floor','neutral-zero']:
        field=np.frombuffer(r.scatter_tex.read(),np.float32).reshape(41,64,64,3).copy()
        field[:,:,:,1]=np.maximum(field[:,:,:,1],0) if variant=='neutral-floor' else 0.
        r.scatter_tex.write(field.tobytes())
    elif variant.startswith('opacity-'):r.program['opacity_distribution']=float(variant.split('-')[1])
    elif variant!='current':raise ValueError(variant)


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','ironman','reference-0926-4']);ap.add_argument('--variants',nargs='+',default=['current','no-offset','time-smooth','no-fission','no-scatter']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/motion-continuity'/('opacity-probes' if any(v.startswith('opacity-') for v in a.variants) else 'probes');out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE);ctx=moderngl.create_standalone_context(require=430);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    if any(v.startswith('opacity-') for v in a.variants):
        from opacity_candidate import OpacityRenderer
        cls=OpacityRenderer
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        original=r.transport_tex.read();scatter=r.scatter_tex.read();light=r.program['conditional_light'].value
        tw=290;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*4,(th+26)*(len(a.variants)+1)),(14,20,30));draw=ImageDraw.Draw(sheet);report[name]={}
        samples=[]
        for j,t in enumerate([.26,.38,.50,.64]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);samples.append((p,ref,measure(ref,c.scene.roi)))
            sheet.paste(Image.fromarray(ref).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,26));draw.text((j*tw+2,2),f'华为原片 {p:.3f}',font=font,fill='white')
        for k,variant in enumerate(a.variants):
            r.transport_tex.write(original);r.scatter_tex.write(scatter);r.program['fission_strength']=1.;r.program['fission_threshold']=.006;r.program['conditional_light']=light;r.program['coverage_footprint']=kw['coverage'].footprint;configure(r,variant)
            if 'opacity_distribution' in r.program and not variant.startswith('opacity-'):r.program['opacity_distribution']=0.
            rows=[]
            for j,(p,ref,target) in enumerate(samples):
                f=r.render(p);rows.append({key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)})
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,(k+1)*(th+26)+26));draw.text((j*tw+2,(k+1)*(th+26)+2),f'{variant} {p:.3f}',font=font,fill='white')
                if j==1:Image.fromarray(f).save(out/f'{name}-{variant}.png')
            report[name][variant]={key:float(np.mean([row[key] for row in rows])) for key in rows[0]}
            print(name,variant,report[name][variant],flush=True)
        sheet.save(out/f'{name}.jpg',quality=94);r.close();(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
