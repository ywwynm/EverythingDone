"""同素材同方向检查多种子的形态变化；保存确定性、变换和释放区域证据。"""
from pathlib import Path
import json
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
import moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from unified_model import variation,release_field,clock_rate,clock_time,inverse_clock,model_fingerprint

out=HERE/'analysis/random-variation';out.mkdir(exist_ok=True)
seeds=[0,1,2,3]
ctx=moderngl.create_standalone_context(require=430)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
rows=[]
for name in ['ironman','thanos','kobe','language','color','attachment','attachment-image']:
    sheet=Image.new('RGB',(400*4,620*2),(14,20,30));d=ImageDraw.Draw(sheet)
    birth=[]
    for column,seed in enumerate(seeds):
        renderer=Renderer(name,seed=seed,ctx=ctx)
        primary=renderer.base[:,3]<renderer.nx*renderer.ny
        order=np.argsort(renderer.base[primary,3]);birth.append(renderer.base[primary,2][order])
        for row,t in enumerate([.43,.58]):
            a=renderer.render(t);im=Image.fromarray(a)
            x,y,x1,y1=renderer.meta['rect'];margin=round(min(x1-x,y1-y)*.30)
            im=im.crop((0,max(0,y-margin),im.width,min(im.height,y1+margin)))
            im.thumbnail((396,575));sheet.paste(im,(column*400+(400-im.width)//2,row*620+38))
            d.text((column*400+10,row*620+5),f'{name} · 种子 {seed} · {t:.2f}',font=font,fill='white')
        last=renderer.render(.58);renderer.render(.20)
        assert np.array_equal(last,renderer.render(.58))
        fresh=Renderer(name,seed=seed,ctx=ctx)
        assert np.array_equal(renderer.render(1.),fresh.render(1.));fresh.close()
        renderer.close()
    sheet.save(out/f'{name}-seeds.jpg',quality=94)
    b=np.stack(birth);differences=[float(np.mean(np.abs(b[i]-b[j]))) for i in range(4) for j in range(i)]
    rows.append(dict(scene=name,seeds=seeds,mean_birth_differences=differences))
    print(name,'种子间平均释放时差',round(min(differences)*1000,1),'～',round(max(differences)*1000,1),'ms',flush=True)
ctx.release()
stats=[];phase=np.linspace(0,1,101)
yy,xx=np.mgrid[:48,:48];xx=(xx+.5)/48;yy=(yy+.5)/48
for seed in range(256):
    v=variation(seed).astype(float)
    det=v[0]*v[1]-(v[2]+v[6]*v[9]*np.cos(v[9]*(yy-.5)+v[10]))*(v[3]+v[7]*v[8]*np.cos(v[8]*(xx-.5)+v[11]))
    assert np.abs(det).min()>.6 and det.min()*det.max()>0 and clock_rate(phase,v).min()>.6
    assert np.max(np.abs(clock_time(inverse_clock(phase,v),v)-phase))<1e-10
    meta=json.loads((HERE/'assets/ironman/scene.json').read_text('utf-8'))
    x0,y0,x1,y1=meta['rect']
    f=release_field(96,96,meta['direction'],x1-x0,y1-y0,seed)
    y,x=np.mgrid[:96,:96];x=(x+.5)/96;y=(y+.5)/96
    stats.append(dict(seed=seed,minimum_abs_determinant=float(np.abs(det).min()),minimum_clock_rate=float(clock_rate(phase,v).min()),
        first_region_centroid=[float(x.ravel()[np.argsort(f.ravel())[:460]].mean()),float(y.ravel()[np.argsort(f.ravel())[:460]].mean())],
        release_at_002=float((f<.02).mean()),
        left_unreleased=float((f[(x>.02)&(x<.10)&(y>.30)&(y<.85)]>.43).mean()),
        top_unreleased=float((f[(y>.02)&(y<.10)&(x>.30)&(x<.80)]>.43).mean())))
report=dict(model_hash=model_fingerprint(),visual_cases=rows,transforms=stats)
(out/'variation-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
print('256 种子坐标不折叠、时间单调且可逆；起始区域中心范围',np.ptp([s['first_region_centroid'] for s in stats],axis=0),'最早大面积释放',max(s['release_at_002'] for s in stats))
