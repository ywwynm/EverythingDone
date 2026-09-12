"""直接对齐原始华为视频检查空间速度约束，逐帧输出完整画面。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl,cv2
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from probe_projected_peel import configure,ProjectedRenderer
from probe_edge_support import OUT
from export_videos import Reference,load_meta

def main():
    ctx=moderngl.create_standalone_context(require=430);ref=Reference(load_meta('ironman'));refs=np.stack([ref.at(i/60) for i in range(61)])
    mask=np.s_[150:930,35:685];stats={};renders={}
    ref_blur={s:np.stack([cv2.GaussianBlur(f.astype('float32'),(0,0),s)[mask] for f in refs]) for s in [2,6,12]}
    for key,one_sided,gain in [('projected',False,1.),('unilateral',True,1.),('unilateral-1.7',True,1.7)]:
        configure(gain);ProjectedRenderer.projection=1.;ProjectedRenderer.restore_strength=True;ProjectedRenderer.unilateral=one_sided
        r=ProjectedRenderer('ironman',ctx=ctx);frames=np.stack([r.render(i/60) for i in range(61)]);r.close();renders[key]=frames
        np.save(OUT/f'ironman-{key}.npy',frames);rows=[]
        for i,frame in enumerate(frames):
            row={'phase':i/60}
            for s in [2,6,12]:row[f'reference_{s}']=float(np.abs(cv2.GaussianBlur(frame.astype('float32'),(0,0),s)[mask]-ref_blur[s][i]).mean())
            rows.append(row)
        stats[key]={'mean':{k:float(np.mean([r[k] for r in rows[8:53]])) for k in rows[0] if k!='phase'},'frames':rows}
        print(key,stats[key]['mean'],flush=True)
        (OUT/'projected-reference-metrics.json').write_text(json.dumps(stats,indent=2),'utf-8')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for i in [20,26,30,34,40,47]:
        im=Image.new('RGB',(1440,672),'#101620');d=ImageDraw.Draw(im)
        for j,(name,frames) in enumerate([('华为原始参考',refs),*renders.items()]):
            im.paste(Image.fromarray(frames[i]).resize((360,640)),(j*360,32));d.text((j*360+3,4),f'{name} · {i/60:.3f}',font=font,fill='white')
        im.save(OUT/f'projected-whole-{i:02d}.png')
    ctx.release()
if __name__=='__main__':main()
