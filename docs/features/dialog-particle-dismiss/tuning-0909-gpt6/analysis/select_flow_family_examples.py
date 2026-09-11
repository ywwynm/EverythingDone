"""以粗粒度观测选择可复现的演示种子；不把观测坐标传入正式运行模型。"""
import json
import numpy as np
import moderngl
from PIL import Image, ImageDraw
from flow_family_experiment import HERE, OUT, FONT, candidate, locality
from export_videos import Reference, focus_bounds

# 只记录早晚及局部位置，权重不用于运行时参数。
observations = {
    'kobe': [(0.04,.50,.07),(.08,.32,.20),(.10,.08,.39),(.83,.04,.32),(.94,.68,.48),(.20,.90,.40)],
    'thanos': [(.95,.34,.06),(.63,.57,.18),(.05,.04,.15),(.08,.88,.59),(.40,.07,.41),(.9,.95,.40)],
    'ironman-up-reference': [(.86,.06,.08),(.05,.65,.08),(.10,.1,.42),(.84,.90,.61),(.47,.5,.36)]}
cls, model = candidate(); ctx = moderngl.create_standalone_context(require=430)
report = []
for name, points in observations.items():
    meta = json.loads((HERE/f'assets/{name}/scene.json').read_text('utf-8'))
    x,y,x1,y1 = meta['rect']; width=x1-x; height=y1-y
    ranked=[]
    for seed in range(512):
        field=model.release_field(64,64,meta['direction'],width,height,seed)
        values=[float(field[min(63,int(py*64)),min(63,int(px*64))]) for px,py,_ in points]
        score=float(np.mean([(a-p[2])**2 for a,p in zip(values,points)]))
        ranked.append((score,seed,values))
    ranked.sort()
    report.append(dict(scene=name,candidates=ranked[:8],scope='观测只筛选随机输入，不进入正式建材或流场'))
    ref=Reference(meta);lo,hi=focus_bounds(meta,True);w=300;h=round((hi-lo)*w/meta['frame'][0])
    sheet=Image.new('RGB',(5*w,4*(h+32)),'#0e141e');draw=ImageDraw.Draw(sheet)
    for col,t in enumerate([.20,.35,.48,.63,.78]):
        sheet.paste(Image.fromarray(ref.at(t)[lo:hi]).resize((w,h)),(col*w,32))
        draw.text((col*w+5,4),f'参考 · {t}',font=FONT,fill='white')
    for row,(_,seed,_) in enumerate(ranked[:3],1):
        r=cls(name,ctx=ctx,seed=seed)
        for col,t in enumerate([.20,.35,.48,.63,.78]):
            sheet.paste(Image.fromarray(r.render(t)[lo:hi]).resize((w,h)),(col*w,row*(h+32)+32))
            draw.text((col*w+5,row*(h+32)+4),f'种子 {seed} · {t}',font=FONT,fill='white')
        r.close()
    sheet.save(OUT/f'input-examples-{name}.jpg',quality=95)
    print(name,[(round(x[0],4),x[1]) for x in ranked[:3]],flush=True)
(OUT/'input-example-selection.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
ctx.release()
