"""只在已产生压缩压力的区域共享剥离速度，检查自由边缘是否被无谓修改。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_projected_peel import configure,ProjectedRenderer
from probe_edge_support import OUT
from export_videos import Reference,load_meta

def main():
    ctx=moderngl.create_standalone_context(require=430)
    cases=[('down','attachment',270,909602,.425),('up','attachment',90,909602,.4),('right','attachment',0,42,.383333),('ironman','ironman',122,909602,.56)]
    for key,hybrid,threshold,scale,restore in [('free-t0.15',False,.15,.015,True),('free-t0.4',False,.4,.015,True),('hybrid-0.01',True,.025,.01,False),('hybrid-0.03',True,.025,.03,False),('hybrid-0.01-restore',True,.025,.01,True)]:
        configure(hybrid=hybrid,restore=restore);ProjectedRenderer.projection=1.;ProjectedRenderer.restore_strength=restore
        ProjectedRenderer.unilateral=True;ProjectedRenderer.free_surface=True;ProjectedRenderer.surface_threshold=threshold;ProjectedRenderer.hybrid_scale=scale
        for name,scene,angle,seed,phase in cases:
            r=ProjectedRenderer(scene,direction=angle,seed=seed,ctx=ctx)
            frames=np.stack([r.render(float(phase+d)) for d in [-.06,0,.06]])
            np.save(OUT/name/f'ablate-{key}.npy',frames);r.close()
        print(key,'完成',flush=True)
    ctx.release()
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for name,scene,angle,seed,phase in cases:
        keys=['free-t0.15','free-t0.4','hybrid-0.01','hybrid-0.03','hybrid-0.01-restore']
        rows=[('华为原参考',Reference(load_meta(scene)).at(phase))] if scene=='ironman' else []
        rows.extend((k,np.load(OUT/name/f'ablate-{k}.npy')[1]) for k in keys)
        size=(360,640) if scene=='ironman' else (420,420)
        im=Image.new('RGB',(len(rows)*size[0],size[1]+30),'#101620');d=ImageDraw.Draw(im)
        for j,(k,f) in enumerate(rows):
            p=Image.fromarray(f)
            if scene!='ironman':p=p.crop((40,460,680,1100))
            im.paste(p.resize(size),(j*size[0],30));d.text((j*size[0]+3,3),k,font=font,fill='white')
        im.save(OUT/name/'pressure-selectivity.png')

if __name__=='__main__':main()
