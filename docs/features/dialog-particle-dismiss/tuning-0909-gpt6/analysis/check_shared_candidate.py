"""检查共同输入连续性、起点顺序不变性和流场先验；实际交接另由 check_surface_transfer 检查。"""
from pathlib import Path
import argparse,json,sys,dataclasses,copy
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from learned_release import load_weights,predict,canonical_sites
from conditional_material import ConditionalRenderer,ConditionalRules
from unified_model import release_components,refine_release
from renderer import Renderer
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--huawei-only',action='store_true')
    ap.add_argument('--root',type=Path,default=HERE);ap.add_argument('--out',type=Path,default=HERE/'analysis/match-cache/shared-checks');options=ap.parse_args()
    out=options.out;out.mkdir(parents=True,exist_ok=True)
    path=options.root/'conditional-release-weights.json'
    weights=load_weights(path if path.exists() else HERE/'analysis/match-cache/shared-release/conditional-full.json')
    report={}
    if weights.clock_knots is not None:
        knots=np.asarray(weights.clock_knots);offsets=np.asarray(weights.clock_offsets)
        report['shared-clock']=dict(maximum_offset=float(abs(offsets).max()),minimum_interval=float(np.diff(knots+offsets).min()))
        assert abs(offsets).max()<=.080001
        assert np.all(np.diff(knots+offsets)>0)
    for name in ['ironman','reference-0926-5','reference-0926-7']:
        m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;sites=[Site(*s) for s in OBSERVED_SITES[name]]
        run=lambda ss:predict(128,128,w,h,m['direction'],m['seed'],ss,weights)
        field=run(sites);permuted=run(list(reversed(sites)));samples=[]
        for delta in np.linspace(-.004,.004,33):samples.append(run([dataclasses.replace(s,x=s.x+delta,y=s.y+delta) for s in sites]))
        changes=[float(np.max(np.abs(a-b))) for a,b in zip(samples,samples[1:])]
        report[name]=dict(permutation_max_error=float(np.max(np.abs(field-permuted))),perturbation_max_step=max(changes),nonzero_steps=sum(v>1e-7 for v in changes))
        assert report[name]['permutation_max_error']<1e-4
        assert max(changes)<.01
    ctx=moderngl.create_standalone_context(require=430)
    rules=ConditionalRules()
    cfg=options.root/'conditional-material-rules.json'
    if cfg.exists():rules=ConditionalRules(**json.loads(cfg.read_text('utf-8'))['rules'])
    from shared_transport import SharedTransportRenderer,load_transport,correction
    transport=load_transport(options.root/'conditional-transport-weights.json')
    # 零修正控制组仍复现原先验；默认共同时间与输运现已允许校准标准布局，
    # 不能再以“最终画面等于旧实现”为正确性判据，最终效果必须对比华为原片。
    control_weights=copy.copy(weights);control_weights.clock_knots=None;control_weights.clock_offsets=None
    control_transport=copy.copy(transport);control_transport.canonical_gain=0.
    for name in (['ironman'] if options.huawei_only else ['ironman','attachment']):
        old=Renderer(name,ctx=ctx,quality=2);current=SharedTransportRenderer(name,weights=control_weights,transport_weights=control_transport,rules=rules,ctx=ctx,quality=2)
        differences=[]
        for p in np.linspace(0,1,31):
            a,b=old.render(p),current.render(p);differences.append((float(np.abs(a.astype('float32')-b).mean()),int(np.max(np.abs(a.astype('int16')-b)))))
        report[name+'-canonical-prior-control']=dict(max_frame_mae=max(q[0] for q in differences),max_channel_delta=max(q[1] for q in differences),layout_change=current.layout_change,note='显式关闭新增共同时间与输运修正的控制组；不是默认候选的最终画面')
        assert max(q[1] for q in differences)<=1
        old.close();current.close()
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in ([] if options.huawei_only else ['attachment','color','holdout-compact-dialog']):
        cases=[('标准布局',122,None),('内部与边缘',47,[Site(*s) for s in OBSERVED_SITES['reference-0926-5']]),('上方单起点',90,[Site(.5,0,0)]),('两侧起点',135,[Site(0,.35,0),Site(1,.7,.1)])]
        sheet=Image.new('RGB',(300*len(cases),580*3),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,(label,angle,sites) in enumerate(cases):
            r=ConditionalRenderer(name,weights=weights,rules=rules,sites=sites,direction=angle,ctx=ctx,quality=2)
            for k,p in enumerate([.3,.5,.7]):
                im=Image.fromarray(r.render(p));im.thumbnail((294,550));sheet.paste(im,(j*300+(300-im.width)//2,k*580+28));draw.text((j*300+5,k*580+4),f'{label} · {p:.1f}',font=font,fill='white')
            state=np.frombuffer(r.state.read(),np.float32);report[name+'-'+label]=dict(finite_state=bool(np.isfinite(state).all()),layout_change=r.layout_change)
            r.close()
        sheet.save(out/f'{name}.jpg',quality=94)
    for name,origins in OBSERVED_SITES.items():
        m=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'));x,y,x1,y1=m['rect'];w,h=x1-x,y1-y;span=min(w,h)
        yy,xx=np.mgrid[:56,:56].astype('float32');xx=-.38+(xx+.5)/56*1.76;yy=-.38+(yy+.5)/56*1.76
        sites=[Site(*q) for q in origins];min_det=100.;permutation=0.;maximum=0.;all_finite=True
        for p in np.linspace(0,1,21):
            f=correction(xx,yy,p,w,h,m['direction'],sites,transport)
            opposite=correction(xx,yy,p,w,h,m['direction'],list(reversed(sites)),transport)
            permutation=max(permutation,float(np.max(np.abs(f-opposite))))
            dx=(f[:-1,1:,:2]-f[:-1,:-1,:2])*56/1.76*span/w
            dy=(f[1:,:-1,:2]-f[:-1,:-1,:2])*56/1.76*span/h
            det=(1+dx[...,0])*(1+dy[...,1])-dx[...,1]*dy[...,0]
            min_det=min(min_det,float(det.min()));maximum=max(maximum,float(np.linalg.norm(f[...,:2],axis=-1).max()));all_finite &= bool(np.isfinite(f).all())
        report[name+'-transport']=dict(finite=all_finite,minimum_jacobian=min_det,permutation_max_error=permutation,max_displacement_short_side=maximum)
        assert all_finite and min_det>0 and permutation<1e-4, (name,report[name+'-transport'])
    ctx.release();(out/'checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)


if __name__=='__main__':main()
