"""补充四参考形态与相同输入跨素材的演示；全部视频平铺到已有目录。"""
from pathlib import Path
import hashlib,json,sys
import numpy as np
import moderngl
from PIL import Image,ImageDraw

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import OUT,PRE,POST,BG,MUTED,Reference,load_meta,make_cache,sampled,encode,label,footer,focus_bounds,code_hash,BASELINE_VERSION
from rim_review_media import export_focus

EXAMPLES=[
    dict(label='连贯卷边',scene='ironman',direction=122,seed=909602),
    dict(label='分离起点',scene='ironman-up-reference',direction=90,seed=489),
    dict(label='多区域释放',scene='thanos',direction=130,seed=323),
    dict(label='侧部扩展',scene='kobe',direction=128,seed=494)]


def reference_frame(meta,example,arr,ref,p,rate):
    lo,hi=focus_bounds(meta);h=hi-lo;w=meta['frame'][0];gap=16
    im=Image.new('RGB',(2*w+gap,h+146),BG);d=ImageDraw.Draw(im)
    label(d,(16,10),meta['title']+' · 华为参考',25)
    label(d,(w+gap+16,10),f'共同模型 · {example["label"]}',25)
    label(d,(16,48),'参考与模型按进度比较；原片触点不可确认',20,MUTED)
    label(d,(w+gap+16,48),f'{example["direction"]}° · 种子 {example["seed"]} · {rate:g} 倍速',20,MUTED)
    im.paste(Image.fromarray(ref.at(p)[lo:hi]),(0,88));im.paste(Image.fromarray(sampled(arr,p)[lo:hi]),(w+gap,88))
    footer(im,f'进度 {p:.2f} · 各参考示例均使用同一默认距离；种子只控制共同规则的随机输入',p)
    return np.asarray(im)


def family_frame(meta,arrays,p,rate):
    lo,hi=focus_bounds(meta,True);w=500;h=round((hi-lo)*w/meta['frame'][0]);h+=h%2
    h=min(h,900);gap=12;rowh=h+74
    im=Image.new('RGB',(2*w+gap,98+2*rowh+gap+54),BG);d=ImageDraw.Draw(im)
    label(d,(16,10),meta['title']+' · 四组共同输入',29)
    label(d,(16,52),f'{rate:g} 倍速 · 同一素材、材质与时长 · 只改变方向和随机输入',20,MUTED)
    for i,(example,arr) in enumerate(zip(EXAMPLES,arrays)):
        x=(i%2)*(w+gap);y=98+(i//2)*(rowh+gap)
        label(d,(x+12,y+3),example['label'],25)
        label(d,(x+12,y+38),f'{example["direction"]}° · 种子 {example["seed"]}',20,MUTED)
        panel=Image.fromarray(sampled(arr,p)[lo:hi]);panel.thumbnail((w,h),Image.Resampling.LANCZOS)
        im.paste(panel,(x+(w-panel.width)//2,y+72+(h-panel.height)//2))
    footer(im,f'进度 {p:.2f} · 四组输入也用于其它素材；不是按图片名称选择运动',p)
    return np.asarray(im)


def main():
    ctx=moderngl.create_standalone_context(require=430);items=[]
    meta=load_meta('ironman');ref=Reference(meta);arr=make_cache(ctx,'ironman')
    old=np.load(HERE/'archive'/BASELINE_VERSION/'ironman.npy',mmap_mode='r')
    for rate in [1.,.5]:
        def frame(t,rate=rate):
            p=float(np.clip(t-PRE,0,1));w=420;gap=12;lo,hi=focus_bounds(meta)
            h=round((hi-lo)*w/meta['frame'][0]);h+=h%2;crop=(84,484,374,784);zoom_h=round((crop[3]-crop[1])*w/(crop[2]-crop[0]));zoom_h+=zoom_h%2
            im=Image.new('RGB',(3*w+2*gap,h+zoom_h+186),BG);d=ImageDraw.Draw(im)
            for col,(title,array) in enumerate([('华为参考',ref.at(p)),('本轮调整前',sampled(old,p)),('本轮共同模型',sampled(arr,p))]):
                x=col*(w+gap);label(d,(x+12,10),title,25);label(d,(x+12,49),f'进度 {p:.2f} · {rate:g} 倍速',20,MUTED)
                picture=Image.fromarray(array);im.paste(picture.crop((0,lo,meta['frame'][0],hi)).resize((w,h)),(x,82))
                label(d,(x+12,h+92),'左中部局部放大 · 同一画面',20,MUTED)
                im.paste(picture.crop(crop).resize((w,zoom_h)),(x,h+132))
            footer(im,'观察横向弧边与下方拖尾的连续分离；模型画面未添加轮廓标线',p)
            return np.asarray(im)
        item=encode(OUT/f'ironman-curve-split-{rate:g}x.mp4',PRE+1+POST,rate,frame)
        item.update(scene='ironman',title='钢铁侠',kind='curve-split');items.append(item)
    for example in EXAMPLES:
        meta=load_meta(example['scene']);ref=Reference(meta)
        arr=make_cache(ctx,example['scene'],example['direction'],seed=example['seed'])
        for rate in [1.,.5]:
            def frame(t,rate=rate):return reference_frame(meta,example,arr,ref,float(np.clip(t-PRE,0,1)),rate)
            item=encode(OUT/f'{meta["name"]}-family-reference-{rate:g}x.mp4',PRE+1+POST,rate,frame)
            item.update(scene=meta['name'],title=meta['title'],kind='family-reference',example=example)
            items.append(item)
    for name in ['ironman','attachment','color']:
        meta=load_meta(name);arrays=[make_cache(ctx,name,e['direction'],seed=e['seed']) for e in EXAMPLES]
        for rate in [1.,.5]:
            def frame(t,rate=rate):return family_frame(meta,arrays,float(np.clip(t-PRE,0,1)),rate)
            item=encode(OUT/f'{name}-flow-family-{rate:g}x.mp4',PRE+1+POST,rate,frame)
            item.update(scene=name,title=meta['title'],kind='flow-family',examples=EXAMPLES)
            items.append(item)
    items.extend(export_focus(ctx));ctx.release()
    for item in items:item['sha256']=hashlib.sha256((OUT/item['file']).read_bytes()).hexdigest()
    data=dict(code_hash=code_hash(),exporter_hash=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        focus_exporter_hash=hashlib.sha256((HERE/'analysis/rim_review_media.py').read_bytes()).hexdigest(),videos=items,
        scope='示例种子经过粗粒度释放位置观察筛选，用于呈现形态可能性，不是独立留出验证；原参考触点无法确认。')
    (OUT/'family-videos.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    print('共同输入与四参考补充视频完成',len(items),flush=True)


if __name__=='__main__':main()
