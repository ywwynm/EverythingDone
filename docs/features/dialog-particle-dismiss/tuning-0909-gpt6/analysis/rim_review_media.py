"""局部弧边和单方向近中远视频；不改变原始渲染或播放时间。"""
import numpy as np
from PIL import Image,ImageDraw
from export_videos import HERE,OUT,PRE,POST,BG,MUTED,Reference,load_meta,make_cache,sampled,encode,label,footer,BASELINE_VERSION
from touch_geometry import distance_cases,distance_view_bounds

def export_focus(ctx):
    items=[];m=load_meta('ironman');ref=Reference(m);now=make_cache(ctx,'ironman')
    before=np.load(HERE/'archive'/BASELINE_VERSION/'ironman.npy',mmap_mode='r')
    for rate in [1.,.5]:
        def frame(t,rate=rate):
            p=float(np.clip(t-PRE,0,1));crop=(80,440,410,760);w=600;h=582
            im=Image.new('RGB',(1800,h+130),BG);d=ImageDraw.Draw(im)
            for col,(name,pixels) in enumerate([('华为参考',ref.at(p)),('本轮调整前',sampled(before,p)),('本轮共同模型',sampled(now,p))]):
                label(d,(col*w+14,9),name,29);label(d,(col*w+14,49),f'同一位置放大 · 进度 {p:.2f} · {rate:g} 倍速',20,MUTED)
                im.paste(Image.fromarray(pixels).crop(crop).resize((w,h),Image.Resampling.LANCZOS),(col*w,82))
            footer(im,'横向弧边的弯曲、向外展开与下方拖尾 · 没有额外绘制亮边或改变局部时间',p)
            return np.asarray(im)
        item=encode(OUT/f'ironman-rim-detail-{rate:g}x.mp4',PRE+1+POST,rate,frame)
        item.update(scene='ironman',title='钢铁侠',kind='rim-detail');items.append(item)
    for name in ['ironman','attachment','color']:
        m=load_meta(name)
        for direction,title,key in [(135,'左上','upper-left'),(90,'向上','up')]:
            bounds=distance_view_bounds(m,direction);cases=distance_cases(m,direction)
            arrays=[make_cache(ctx,name,c['angle'],touch_gap=c['gap'],view_bounds=bounds) for c in cases]
            w=480;h=round((bounds[3]-bounds[1])*w/(bounds[2]-bounds[0]));h+=h%2
            for rate in [1.,.5]:
                def frame(t,rate=rate):
                    p=float(np.clip(t-PRE,0,1));im=Image.new('RGB',(w*3,h+124),BG);d=ImageDraw.Draw(im)
                    for col,(c,arr) in enumerate(zip(cases,arrays)):
                        x=col*w
                        label(d,(x+12,8),f'{m["title"].split(" · ")[0]} · {title} · {c["label"]}',25)
                        label(d,(x+12,45),f'{rate:g} 倍速 · {c["angle"]:.1f}° · 边缘外 {c["distance_px"]:.0f} px',20,MUTED)
                        tile=Image.fromarray(sampled(arr,p)).resize((w,h),Image.Resampling.LANCZOS);im.paste(tile,(x,78))
                        px=x+(c['point'][0]-bounds[0])*w/(bounds[2]-bounds[0]);py=78+(c['point'][1]-bounds[1])*h/(bounds[3]-bounds[1])
                        d.ellipse((px-6,py-6,px+6,py+6),outline=(255,203,112),width=2)
                        # 每栏单独保留进度，方便网页在同一位置切换三栏。
                        label(d,(x+12,h+85),f'进度 {p:.2f} · 同种子、同尺度、同一秒时长',18,MUTED)
                    return np.asarray(im)
                item=encode(OUT/f'{name}-distance-{key}-{rate:g}x.mp4',PRE+1+POST,rate,frame)
                item.update(scene=name,title=m['title'],kind='distance-focus',requested_direction=direction,touch_cases=cases)
                items.append(item)
    return items
