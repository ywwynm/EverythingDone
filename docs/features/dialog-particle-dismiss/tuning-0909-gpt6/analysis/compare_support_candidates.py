"""完整钢铁侠保护检查：结构差与参考差分开记录，不能用局部细流改进掩盖宏观退化。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl,cv2
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_particle_support import configure,SupportRenderer
from probe_edge_support import OUT
from export_videos import Reference,load_meta

def main():
    ctx=moderngl.create_standalone_context(require=430)
    ref=Reference(load_meta('ironman'));refs=np.stack([ref.at(i/60) for i in range(61)])
    versions={'baseline':np.load(OUT/'ironman-baseline.npy',mmap_mode='r')}
    for gain in [2.,6.]:
        configure(gain,'residual');SupportRenderer.support_mode='residual';r=SupportRenderer('ironman',ctx=ctx)
        key=f'residual-{gain:g}';versions[key]=np.stack([r.render(i/60) for i in range(61)]);r.close()
        np.save(OUT/f'ironman-{key}.npy',versions[key]);print(key,'61 帧完成',flush=True)
    mask=np.s_[150:930,35:685];stats={}
    for name,frames in versions.items():
        rows=[]
        for i in range(61):
            row={'phase':i/60}
            for sigma in [2,6,12]:
                a=cv2.GaussianBlur(frames[i].astype('float32'),(0,0),sigma)[mask]
                b=cv2.GaussianBlur(versions['baseline'][i].astype('float32'),(0,0),sigma)[mask]
                c=cv2.GaussianBlur(refs[i].astype('float32'),(0,0),sigma)[mask]
                row[f'change_{sigma}']=float(np.abs(a-b).mean());row[f'reference_{sigma}']=float(np.abs(a-c).mean())
            rows.append(row)
        stats[name]={'mean':{k:float(np.mean([r[k] for r in rows[8:53]])) for k in rows[0] if k!='phase'},'frames':rows}
    (OUT/'support-full-metrics.json').write_text(json.dumps(stats,indent=2),'utf-8')
    print(json.dumps({k:v['mean'] for k,v in stats.items()}),flush=True)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for i in [20,26,30,34,40,47]:
        out=Image.new('RGB',(1440,670),'#101620');d=ImageDraw.Draw(out)
        for j,(name,frames) in enumerate([('华为参考',refs),*versions.items()]):
            out.paste(Image.fromarray(frames[i]).resize((360,640)),(j*360,30));d.text((j*360+3,2),f'{name} · {i/60:.3f}',font=font,fill='white')
        out.save(OUT/f'support-whole-{i:02d}.png')
    ctx.release()
if __name__=='__main__':main()
