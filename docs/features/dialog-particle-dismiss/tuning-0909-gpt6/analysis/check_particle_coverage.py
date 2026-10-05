"""核对覆盖改动的零配置、数值范围、首尾帧及回放；不代替原片视觉验收。"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from particle_coverage import CoverageRenderer,CoverageRules,load_coverage
from surface_transfer import TransferRenderer
from fit_particle_optics import common
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--config',default=str(HERE/'particle-coverage-rules.json'));ap.add_argument('--flow');a=ap.parse_args()
    ctx=moderngl.create_standalone_context(require=430);kw=common();cfg=load_coverage(Path(a.config));report={}
    if a.flow:
        from shared_transport import load_transport
        kw['transport_weights']=load_transport(Path(a.flow))
    for name in OBSERVED_SITES:
        options=dict(kw,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]])
        r=CoverageRenderer(name,coverage=cfg,**options)
        finite=True
        for p in np.linspace(0,1,25):
            frame=r.render(float(p));finite &= bool(np.isfinite(np.frombuffer(r.state.read(),np.float32)).all())
        background=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'))
        end_error=float(np.abs(frame.astype('float32')-background).max());first=r.render(.4);r.render(.8);back=r.render(.4);seek=bool(np.array_equal(first,back))
        assert finite and end_error<=1 and seek,(name,finite,end_error,seek)
        # 覆盖不应修改开场尚完整的面板；零配置也不能悄悄改粒子。
        original=TransferRenderer(name,**options);r.apply_coverage(CoverageRules());zero=0
        for p in [0.,.23,.51,.79,1.]:zero=max(zero,float(np.abs(r.render(p).astype('int16')-original.render(p)).max()))
        assert zero==0,(name,zero)
        original.close();r.close();report[name]=dict(finite=finite,end_error=end_error,seek_equal=seek,zero_config_max_error=zero)
        print(name,report[name],flush=True)
    ctx.release();out=HERE/'analysis/match-cache/particle-coverage/actual-checks.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
