"""真实完整画面复核细粒材质：原片全程误差、共同输入与运行状态检查。"""
from pathlib import Path
import sys,json,argparse,dataclasses
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer,load_scatter,scatter_field
from check_cloud_evolution import configuration
from fit_particle_optics import NAMES,measure
from fit_scatter_material import PHASES
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--weights',required=True);ap.add_argument('--baseline',type=Path,default=HERE);ap.add_argument('--label',default='full-review');ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES));ap.add_argument('--stride',type=int,default=1);a=ap.parse_args()
    out=HERE/'analysis/match-cache/scatter-material'/a.label;out.mkdir(parents=True,exist_ok=True)
    frozen=out/'weights.json';frozen.write_bytes(Path(a.weights).read_bytes());weights=load_scatter(frozen)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(a.baseline);report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    refinement='scatter' in kw
    y,x=np.mgrid[:40,:40].astype(np.float32);x=-.38+(x+.5)/40*1.76;y=-.38+(y+.5)/40*1.76
    for name in a.scenes:
        c=Comparison(name);sites=[Site(*s) for s in OBSERVED_SITES[name]];args=dict(ctx=ctx,quality=2,sites=sites,**kw)
        old=cls(name,**args)
        if refinement:
            zero=cls(name,**args)
            new=ScatterRenderer(name,**dict(args,scatter=weights))
        else:
            zero=ScatterRenderer(name,**args);zero.program['fission_strength']=0.
            new=ScatterRenderer(name,scatter=weights,**args)
        zeros=[];state_equal=True;position_equal=True
        if refinement:
            from diagnose_motion_continuity import trajectory_reader
            old_trace,old_cleanup=trajectory_reader(old);new_trace,new_cleanup=trajectory_reader(new)
        for p in [0.,.21,.47,.79,1.]:
            before=old.render(p);empty=zero.render(p);after=new.render(p);diff=abs(before.astype(np.int16)-empty)
            zeros.append((int(diff.max()),float(diff.mean())));state_equal &= old.state.read()==new.state.read()
            if refinement:
                old.render(p);trace_before=old_trace();new.render(p);trace_after=new_trace()
                position_equal &= bool(np.array_equal(trace_before,trace_after))
            if p==0:initial_equal=bool(np.array_equal(before,after))
        if refinement:old_cleanup();new_cleanup()
        zero.close();end_delta=int(abs(before.astype(np.int16)-after).max());first=new.render(.42);new.render(.80);seek_equal=bool(np.array_equal(first,new.render(.42)))
        permutation=0.;perturbation=0.;relative_perturbation=0.;linearity=0.;finite=True;maximum=np.zeros(3)
        for p in np.linspace(0,1,21):
            values=scatter_field(x,y,p,new.cw,new.ch,new.direction,sites,weights)
            permutation=max(permutation,float(abs(values-scatter_field(x,y,p,new.cw,new.ch,new.direction,sites[::-1],weights)).max()))
            changed=[dataclasses.replace(s,x=s.x+.0001,y=s.y+.0001) for s in sites]
            delta=scatter_field(x,y,p,new.cw,new.ch,new.direction,changed,weights)-values
            half=[dataclasses.replace(s,x=s.x+.00005,y=s.y+.00005) for s in sites]
            half_delta=scatter_field(x,y,p,new.cw,new.ch,new.direction,half,weights)-values
            perturbation=max(perturbation,float(abs(delta).max()))
            relative_perturbation=max(relative_perturbation,float(np.max(abs(delta)/weights['scales'])))
            linearity=max(linearity,float(abs(delta-2*half_delta).max()/(abs(delta).max()+1e-6)))
            finite &= bool(np.isfinite(values).all());maximum=np.maximum(maximum,np.max(abs(values),axis=(0,1)))
        checks=dict(zero_max=max(v[0] for v in zeros),zero_mean=max(v[1] for v in zeros),state_exact=state_equal,displayed_material_exact=position_equal if refinement else None,initial_exact=initial_equal,end_delta=end_delta,seek_exact=seek_equal,permutation=permutation,perturbation=perturbation,relative_perturbation=relative_perturbation,perturbation_nonlinearity=linearity,finite=finite,maximum=maximum.tolist(),gl_error=ctx.error)
        assert checks['zero_max']<=2 and checks['zero_mean']<.00002 and state_equal and position_equal and initial_equal and end_delta<=1 and seek_equal,(name,checks)
        # 三项分别是对数透过率、线性反射与色度，不能共用一个绝对单位阈值。
        # 0.01% 尺寸的输入扰动应小于各控制量范围的 2%，且对半扰动保持局部线性。
        assert finite and permutation<1e-4 and relative_perturbation<.02 and linearity<.1 and checks['gl_error']=='GL_NO_ERROR',(name,checks)
        rows=[];training_indices={c.ref.nearest(t)[0] for t in PHASES}
        if name in NAMES:
            for i in c.index[::a.stride]:
                p=float(c.ref.progress[i]);reference=np.asarray(c.ref.frames[i]);target=measure(reference,c.scene.roi);row=dict(index=int(i),phase=p,response_sample=i in training_indices)
                reference_cloud,_=c.scene.blocks(c.scene.observe(reference)['particle']&c.scene.roi)
                for key,r in [('before',old),('candidate',new)]:
                    f=r.render(p);metrics={metric:float(abs(u-v).mean()) for metric,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                    cloud,valid=c.scene.blocks(c.scene.observe(f)['particle']&c.scene.roi);metrics['cloud_map']=float(abs(cloud-reference_cloud)[valid>.5].mean());row[key]=metrics
                rows.append(row)
        x0,y0,x1,y1=c.scene.rect;box=(max(0,x0-40),max(0,y0-40),min(c.scene.W,x1+40),min(c.scene.H,y1+40));tw=330;th=round((box[3]-box[1])*tw/(box[2]-box[0]))
        sheet=Image.new('RGB',(tw*4,(th+25)*3),(14,20,30));draw=ImageDraw.Draw(sheet)
        for col,p in enumerate([.2,.4,.6,.8]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);frames=[ref,old.render(p),new.render(p)]
            for k,(label,f) in enumerate(zip(['华为原片','本轮调整前','共同细粒材质'],frames)):
                sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+25)+25));draw.text((col*tw,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
        sheet.save(out/f'{name}.jpg',quality=95);old.close();new.close()
        def summarize(values):return {stage:{metric:float(np.mean([r[stage][metric] for r in values])) for metric in ['rgb_1','rgb_4','rgb_12','grain','cloud_map']} for stage in ['before','candidate']} if values else None
        result=dict(checks=checks,frames=len(rows),summary=summarize(rows),other_frames_summary=summarize([r for r in rows if not r['response_sample']]),rows=rows)
        report[name]=result;print(name,checks,'全程',result['summary'],flush=True)
        (out/'report.json').write_text(json.dumps(dict(scatter=str(frozen),scope='本轮响应以外画面不是未见过的素材；新 2／3 仅检查完整视觉，不计算失真背景误差',scenes=report),ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
