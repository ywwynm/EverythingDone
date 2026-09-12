"""完整画面的颗粒对比度消融；候选只保存在忽略区，不改正式模型。"""
from pathlib import Path
import sys, json, copy
import numpy as np, moderngl
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_frame_difference import configure
from frame_difference import OUT, blur
from optimize_optical_statistics import contrast

def main():
    base=json.loads((OUT/'selected-config.json').read_text('utf-8'))
    phases=[.25,.40,.56,.72]
    refs=np.load(OUT/'reference.npy',mmap_mode='r')
    ctx=moderngl.create_standalone_context(require=430)
    variants=[('当前',{},{}),('较大微片',{'cell':2.10},{}),
        ('较大微片与反光',{'cell':2.10},{'glint':.48,'base_light':.20}),
        ('较大微片与明暗变化',{'cell':2.10},{'glint':.32,'base_light':.20}),
        ('细片与明暗变化',{},{}),
        ('较弱明暗变化',{'cell':1.85},{'glint':.24,'base_light':.25})]
    rows=[('华为参考',[refs[round(t*60)] for t in phases])];scores=[]
    for i,(name,rules,optical) in enumerate(variants):
        config=copy.deepcopy(base);config.update(rules);config['optical'].update(optical)
        configure(config)
        if i in (3,4,5):
            # 粒子各自转动的朝向决定反射，均值接近原来，不在轮廓处单独增亮。
            amplitude=.65 if i==5 else 1.
            renderer.FRAGMENT=renderer.FRAGMENT.replace('float low=min',
                f'color*=1.+{amplitude:.4f}*(smoothstep(.25,.75,facing)-.5)*optical_loosen;\n    float low=min')
        r=renderer.Renderer('ironman',ctx=ctx);images=[r.render(t) for t in phases];r.close()
        rows.append((name,images));values=[]
        for image,ref in zip(images,rows[0][1]):
            region=np.s_[150:930,35:685]
            values.append(dict(structure=float(abs(blur(image,6)-blur(ref,6))[region].mean()),
                contrast=float(abs(contrast(image)-contrast(ref))[region].mean())))
        scores.append(dict(name=name,values=values,config=config));print(name,values,flush=True)
    width,height=288,512;header=28
    out=Image.new('RGB',(width*4,(height+header)*len(rows)),(14,20,30));d=ImageDraw.Draw(out)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for row,(name,images) in enumerate(rows):
        for col,(t,image) in enumerate(zip(phases,images)):
            x,y=col*width,row*(height+header)
            out.paste(Image.fromarray(image).resize((width,height)),(x,y+header));d.text((x+3,y+3),f'{name} · {t:.2f}',font=font,fill='white')
    out.save(OUT/'granular-contrast.jpg',quality=95)
    (OUT/'granular-contrast.json').write_text(json.dumps(scores,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
