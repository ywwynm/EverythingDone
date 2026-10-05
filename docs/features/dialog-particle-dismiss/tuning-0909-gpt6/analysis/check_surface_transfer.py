"""交接场的空间顺序不变性、零配置回退和随机访问稳定性检查。"""
from pathlib import Path
import argparse,json,sys
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_transfer import spatial_clocks,TransferRules,TransferRenderer,load_transfer

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--gpu',action='store_true');a=ap.parse_args()
    nx,ny=31,47;n=nx*ny;yy,xx=np.mgrid[:ny,:nx];expected=.1+.4*xx/nx+.2*yy/ny
    rng=np.random.default_rng(27);base=np.zeros((n,12),np.float32);base[:,3]=np.arange(n);base[:,11]=rng.random(n)
    base[:,2]=expected.ravel()+.08*(base[:,11]-.5)
    extra=base[::7].copy();extra[:,3]+=n;extra[:,2]+=.21;extra[:,11]=rng.random(len(extra))
    material=np.concatenate([base,extra]);maximum=0.
    for _ in range(12):
        rng.shuffle(material,axis=0);_,got=spatial_clocks(material,nx,ny,.08)
        maximum=max(maximum,float(abs(got-expected).max()))
        gy,gx=np.gradient(got);assert np.max(abs(gx-.4/nx))<1e-6;assert np.max(abs(gy-.2/ny))<1e-6
    result={'空间乱序与副片检查':{'samples':12,'max_clock_error':maximum,'passed':maximum<1e-6}}
    if a.gpu:
        import moderngl
        from shared_transport import SharedTransportRenderer,load_transport
        from conditional_material import ConditionalRules
        from learned_release import load_weights
        from dissolve_v2 import Site
        from v2_inputs import OBSERVED_SITES
        ctx=moderngl.create_standalone_context(require=430)
        cfg=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
        options=dict(ctx=ctx,quality=2,weights=load_weights(HERE/'conditional-release-weights.json'),transport_weights=load_transport(HERE/'conditional-transport-weights.json'),rules=cfg)
        rows=[]
        for name in ['ironman','reference-0926-4']:
            kw=dict(options,sites=[Site(*s) for s in OBSERVED_SITES[name]])
            original=SharedTransportRenderer(name,**kw);control=TransferRenderer(name,transfer=TransferRules(stochastic=0.,pairing=0.,broaden=0.),**kw)
            errors=[]
            for t in [.0,.12,.24,.40,.58,.76,1.]:
                before=original.render(t);after=control.render(t);errors.append(float(abs(before.astype('float32')-after).max()))
            first=control.render(.4);control.render(.8);second=control.render(.4)
            rows.append(dict(scene=name,max_pixel_difference=max(errors),seek_equal=bool(np.array_equal(first,second))))
            assert max(errors)<=1 and np.array_equal(first,second)
            original.close();control.close()
        result['零配置与回放检查']=rows
        # 已采用规则需单独检查，零配置回退不代表实际候选通过。
        actual=[]
        for name in OBSERVED_SITES:
            kw=dict(options,sites=[Site(*s) for s in OBSERVED_SITES[name]])
            r=TransferRenderer(name,transfer=load_transfer(HERE/'surface-transfer-rules.json'),**kw)
            finite=True
            for t in np.linspace(0,1,21):
                frame=r.render(t)
                finite &= bool(np.isfinite(np.frombuffer(r.state.read(),np.float32)).all())
            from PIL import Image
            bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'))
            delta=abs(frame.astype('int16')-bg.astype('int16'))
            end_mae=float(delta.mean());end_active=float((delta.max(2)>3).mean())
            first=r.render(.4);r.render(.8);second=r.render(.4)
            seek_equal=bool(np.array_equal(first,second))
            actual.append(dict(scene=name,finite=finite,seek_equal=seek_equal,end_mae=end_mae,end_active_fraction=end_active))
            assert finite and seek_equal,(name,actual[-1])
            assert end_active<.001,(name,'动画结束后仍有明显残留',actual[-1])
            r.close()
        result['采用规则完整状态检查']=actual;ctx.release()
    out=HERE/'analysis/match-cache/material-transfer/checks.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
