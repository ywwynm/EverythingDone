"""同时检查完整钢铁侠、白底反例和密度峰，避免只改善局部画面。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_device_filaments import OUT,configure,contact
from export_videos import Reference,load_meta
from frame_difference import blur

def main():
    ctx=moderngl.create_standalone_context(require=430);times=[.33,.40,.56,2/3];ref=Reference(load_meta('ironman'))
    refs=[ref.at(t) for t in times];rows=[('华为参考',refs)];metrics={}
    for kind in ['current','no-peel','peel-half','peel-dispersion','peel-drag']:
        configure(kind);r=renderer.Renderer('ironman',ctx=ctx);ims=[r.render(t) for t in times];r.close()
        rows.append((kind,ims));np.save(OUT/f'ironman-{kind}.npy',ims)
        metrics[kind]={str(s):float(np.mean([np.abs(blur(im,s)-blur(refim,s))[150:930,35:685].mean() for im,refim in zip(ims,refs)])) for s in [0,2,6,12]}
    for k in range(2,len(rows)):contact([rows[0],rows[1],rows[k]],f'ironman-peel-{rows[k][0]}',times)
    (OUT/'peel-appearance.json').write_text(json.dumps(metrics,indent=2),'utf-8');print(json.dumps(metrics),flush=True);ctx.release()
if __name__=='__main__':main()
