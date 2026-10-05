"""第二版共同模型的目视审阅图：原片与模型同尺度并排，另有运动拖影叠加（看流动方向、速度与卷曲）。

拖影：把一段时间内的若干帧按“浅底取最暗、深底取最亮”叠加，颗粒留下运动轨迹；原片与模型用同样的
时间窗和帧数。输出 JPEG 到 analysis/match-cache/review/，只供审阅。
"""
from pathlib import Path
import argparse,dataclasses,json,sys
import numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
from dissolve_v2 import V2Renderer,RULES,reference_inputs
from match_refs import RefFrames
from match_streaks import MODE

OUT=HERE/'analysis/match-cache/review'
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)


def load_rules(path):
    if not path:return RULES
    d=json.loads(Path(path).read_text('utf-8'))
    return dataclasses.replace(RULES,**{k:v for k,v in d.get('best',d).get('rules',d).items() if k in dataclasses.asdict(RULES)})


def crop_box(meta,pad):
    x,y,x1,y1=meta['rect'];s=min(x1-x,y1-y);mg=round(s*pad);W,H=meta['frame']
    return max(0,x-mg),max(0,y-mg),min(W,x1+mg),min(H,y1+mg)


def review(name,rules,label,ctx,phases,windows,pad=.4,width=360):
    R=RefFrames(name);q=reference_inputs(name);r=V2Renderer(name,rules=rules,ctx=ctx,quality=2,**q)
    box=crop_box(R.meta,pad);valid=R.meta['reference'].get('valid_progress',1.)
    tw=width;th=round((box[3]-box[1])*tw/(box[2]-box[0]))
    def fit(a):return Image.fromarray(np.ascontiguousarray(a[box[1]:box[3],box[0]:box[2]])).resize((tw,th),Image.Resampling.LANCZOS)
    # 1) 同进度并排
    cols=len(phases);sheet=Image.new('RGB',(cols*(tw+6),2*(th+24)+8),(14,20,30));d=ImageDraw.Draw(sheet)
    for k,p in enumerate(phases):
        if p>valid:continue
        i,f=R.nearest(p);pp=float(R.progress[i])
        sheet.paste(fit(f),(k*(tw+6),24));sheet.paste(fit(r.render(pp)),(k*(tw+6),th+56))
        d.text((k*(tw+6)+4,2),f'华为 {pp:.3f}',font=FONT,fill=(255,230,120));d.text((k*(tw+6)+4,th+34),f'v2 {pp:.3f}',font=FONT,fill=(120,230,255))
    sheet.save(OUT/f'{label}-{name}-frames.jpg',quality=90)
    # 2) 拖影
    mode=MODE.get(name,'min');cols=len(windows)
    sheet=Image.new('RGB',(cols*(tw+6),2*(th+24)+8),(14,20,30));d=ImageDraw.Draw(sheet)
    for k,(a,b) in enumerate(windows):
        if a>valid:continue
        idx=[i for i,p in enumerate(R.progress) if a<=p<=min(b,valid)]
        if len(idx)<2:continue
        ref=np.stack([np.asarray(R.frames[i]).astype('int16') for i in idx])
        mod=np.stack([r.render(float(R.progress[i])).astype('int16') for i in idx])
        fr=(ref.min(0) if mode=='min' else ref.max(0)).astype('uint8');fm=(mod.min(0) if mode=='min' else mod.max(0)).astype('uint8')
        sheet.paste(fit(fr),(k*(tw+6),24));sheet.paste(fit(fm),(k*(tw+6),th+56))
        d.text((k*(tw+6)+4,2),f'华为拖影 {a:.2f}-{b:.2f}（{len(idx)}帧）',font=FONT,fill=(255,230,120));d.text((k*(tw+6)+4,th+34),'v2 同一时间窗',font=FONT,fill=(120,230,255))
    sheet.save(OUT/f'{label}-{name}-streaks.jpg',quality=90)
    r.close()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules');ap.add_argument('--label',required=True);ap.add_argument('--scenes',nargs='+',required=True)
    ap.add_argument('--phases',default='.1,.2,.3,.4,.5,.6,.7');ap.add_argument('--pad',type=float,default=.4);a=ap.parse_args()
    import moderngl
    OUT.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430);rules=load_rules(a.rules)
    phases=[float(v) for v in a.phases.split(',')]
    windows=[(.12,.22),(.25,.35),(.4,.5),(.55,.65)]
    for name in a.scenes:review(name,rules,a.label,ctx,phases,windows,a.pad);print(name,flush=True)


if __name__=='__main__':main()
