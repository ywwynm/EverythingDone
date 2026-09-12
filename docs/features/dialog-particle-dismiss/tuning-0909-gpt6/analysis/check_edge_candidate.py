"""先核对完整钢铁侠连续相位，候选不写正式模型。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from ablate_edge_support import configure
from probe_edge_support import OUT,BASE
from export_videos import Reference,load_meta
from frame_difference import metrics

def main():
    ctx=moderngl.create_standalone_context(require=430);renders={}
    for kind in ['baseline','no-peel']:
        configure(kind);r=renderer.Renderer('ironman',ctx=ctx)
        renders[kind]=np.stack([r.render(i/60) for i in range(61)]);r.close()
        np.save(OUT/f'ironman-{kind}.npy',renders[kind])
    ref=Reference(load_meta('ironman'));refs=np.stack([ref.at(i/60) for i in range(61)])
    mask=np.zeros((1280,720),bool);mask[150:930,35:685]=True
    results={}
    for kind,frames in renders.items():
        rows=metrics(frames,refs,mask)
        results[kind]={key:float(np.mean([rows[i][key] for i in range(8,53)])) for key in ['mae_0','mae_2','mae_6','mae_12']}
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
    for phase in [20,24,29,34,40,47]:
        im=Image.new('RGB',(1260,777),'#101620');d=ImageDraw.Draw(im)
        for j,(name,frames) in enumerate([('华为参考',refs),('本轮前',renders['baseline']),('仅移除独立剥离',renders['no-peel'])]):
            im.paste(Image.fromarray(frames[phase]).resize((420,747)),(j*420,30));d.text((j*420+4,2),f'{name} · {phase/60:.3f}',font=font,fill='white')
        im.save(OUT/f'ironman-candidate-{phase:02d}.png')
    (OUT/'ironman-candidate-metrics.json').write_text(json.dumps(results,indent=2),'utf-8');print(json.dumps(results),flush=True);ctx.release()
if __name__=='__main__':main()
