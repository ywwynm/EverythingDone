"""独立核对主动画完整相位和近远输入，避免为去掉内部细线破坏已认可运动。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import Reference,load_meta
from touch_geometry import distance_cases
from evaluate_targeted_release import destination_metrics
from frame_difference import metrics
from probe_filament_layers import OUT,BASE

def main():
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    r=Renderer('ironman',ctx=ctx);old=np.load(BASE/'ironman.npy',mmap_mode='r')[::2]
    new=np.stack([r.render(i/60) for i in range(61)]);r.close()
    reference=Reference(load_meta('ironman'));refs=np.stack([reference.at(i/60) for i in range(61)])
    mask=np.zeros((1280,720),bool);mask[150:930,35:685]=True
    a=metrics(new,refs,mask);b=metrics(old,refs,mask)
    summary={key:{'before':float(np.mean([b[i][key] for i in range(8,53)])),
                  'after':float(np.mean([a[i][key] for i in range(8,53)]))} for key in ['mae_0','mae_2','mae_6','mae_12']}
    print('完整动画区',json.dumps(summary),flush=True)
    np.save(OUT/'ironman-production.npy',new)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for i in [20,24,29,33,40,47]:
        im=Image.new('RGB',(1080,668),'#0e141e');d=ImageDraw.Draw(im)
        for j,(title,frames) in enumerate([('华为参考',refs),('修复前',old),('共同修正',new)]):
            d.text((j*360+8,2),f'{title} · {i/60:.3f}',font=font,fill='white')
            im.paste(Image.fromarray(frames[i]).resize((360,640)),(j*360,28))
        im.save(OUT/f'ironman-whole-{i:02d}.jpg',quality=97)
    assert summary['mae_6']['after']<summary['mae_6']['before']*1.02,'主动画结构明显退化'
    for name in ['ironman','attachment','color']:
        for angle in [135,90]:
            group=[];births=[]
            for case in distance_cases(load_meta(name),angle):
                r=Renderer(name,ctx=ctx,direction=case['angle'],touch_gap=case['gap'])
                births.append(r.base[:,[2,6]].copy())
                group.append(dict(scene=name,requested_direction=angle,**case,**destination_metrics(r)))
                r.close()
            assert all(np.array_equal(births[0],v) for v in births[1:])
            for key in ['speed_median','displacement_median']:
                v=[item[key] for item in group]
                assert v[0]<v[1]<v[2] and 1.6<v[2]/v[0]<2.7,(name,angle,key,v)
            rows.extend(group)
    (OUT/'motion-acceptance.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),appearance=summary,
        distances=rows,note='完整区平均误差只用于保护既有运动；固定来源细缕和连续画面独立验收。'),ensure_ascii=False,indent=2),'utf-8')
    ctx.release();print('完整相位及 18 个近远输入通过',flush=True)

if __name__=='__main__':main()
