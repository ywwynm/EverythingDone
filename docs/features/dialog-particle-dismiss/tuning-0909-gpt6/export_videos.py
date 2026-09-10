"""固定 120 Hz 采样，同一模型生成全部视频；视频统一平铺保存。"""
from pathlib import Path
import argparse, json, math, subprocess, time, hashlib
import numpy as np
import moderngl
from PIL import Image, ImageDraw, ImageFont
from renderer import Renderer, HERE

FPS=60
VERSION='共同释放与输运'
BASELINE_VERSION='streams-before-edge-roll'
SAMPLE_FPS=120
PRE=.35
POST=.50
FFMPEG='C:/ffmpeg/bin/ffmpeg.exe'
OUT=HERE/'videos'
CACHE=HERE/'cache'
FONT_PATH='C:/Windows/Fonts/msyh.ttc'
FONTS={s:ImageFont.truetype(FONT_PATH,s) for s in (18,20,22,25,29,34)}
INK=(232,239,248)
MUTED=(167,184,206)
BG=(14,20,30)
ACCENT=(99,215,207)
DIRECTIONS=[(0,'右'),(45,'右上'),(90,'上'),(135,'左上'),(180,'左'),(225,'左下'),(270,'下'),(315,'右下')]

def code_hash():
    from unified_model import SHARED
    paths=[HERE/n for n in ['renderer.py','fields.py','unified_model.py','export_videos.py']]
    paths+=sorted((HERE/'assets').glob('*/scene.json'))
    paths+=sorted((HERE/'assets').glob('*/*.png'))
    paths += [SHARED/'rules.properties',SHARED/'common-release.f32',SHARED/'common-flow.f16']
    h=hashlib.sha256()
    for p in paths:h.update(p.name.encode());h.update(p.read_bytes())
    return h.hexdigest()

def load_meta(name):
    return json.loads((HERE/'assets'/name/'scene.json').read_text(encoding='utf-8'))

def make_cache(ctx,name,angle=None):
    key=name if angle is None else f'{name}-direction-{angle:03d}'
    path=CACHE/f'{key}.npy';stamp=path.with_suffix('.json')
    fingerprint=code_hash()
    if path.exists() and stamp.exists() and json.loads(stamp.read_text())['hash']==fingerprint:
        return np.load(path,mmap_mode='r')
    r=Renderer(name,direction=angle,ctx=ctx)
    a=np.lib.format.open_memmap(path,mode='w+',dtype='uint8',shape=(SAMPLE_FPS+1,r.h,r.w,3))
    for i in range(SAMPLE_FPS+1):a[i]=r.render(i/SAMPLE_FPS)
    a.flush();r.close()
    stamp.write_text(json.dumps({'hash':fingerprint,'sample_fps':SAMPLE_FPS,'scene':name,'angle':angle,'shape':list(a.shape)}),encoding='utf-8')
    print('缓存完成',key,flush=True)
    return a

def sampled(a,p):return np.asarray(a[round(float(np.clip(p,0,1))*SAMPLE_FPS)])

class Reference:
    def __init__(self,m):
        self.meta=m;d=HERE/'assets'/m['name']
        self.original=np.array(Image.open(d/'source.png').convert('RGB'))
        if m['reference']:
            self.frames=np.load(d/'reference.npy',mmap_mode='r')
            self.times=np.load(d/'reference-times.npy')
        else:self.frames=None
    def at(self,p):
        if self.frames is None:return self.original
        r=self.meta['reference'];t=r['start']+float(np.clip(p,0,1))*(r['end']-r['start'])
        return np.asarray(self.frames[np.argmin(abs(self.times-t))])

def focus_bounds(m,matrix=False):
    if not m['reference'] and not matrix:return 0,m['frame'][1]
    x,y,x1,y1=m['rect'];span=min(x1-x,y1-y)
    margin=round(span*(.36 if not matrix else .43))
    lo=max(0,y-margin);hi=min(m['frame'][1],y1+margin)
    return lo,hi+(hi-lo)%2

def label(d,xy,text,size=22,color=INK):d.text(xy,text,font=FONTS[size],fill=color)

def footer(im,text,p):
    d=ImageDraw.Draw(im);y=im.height-48
    label(d,(16,y+14),text,18,MUTED)
    d.rectangle((0,y,round(im.width*float(np.clip(p,0,1))),y+3),fill=ACCENT)

def single_frame(m,a,p,rate):
    im=Image.new('RGB',(m['frame'][0],m['frame'][1]+140),BG);d=ImageDraw.Draw(im)
    label(d,(18,12),m['title'],29)
    label(d,(18,53),f'桌面 · {VERSION} · {rate:g} 倍速 · {m["direction"]}°',20,MUTED)
    im.paste(Image.fromarray(sampled(a,p)),(0,92))
    note='构造背景' if m.get('holdout') and m['name']!='holdout-notification' else '真实背景' if m['background_truth'] else '遮挡区域为背景重建'
    footer(im,f'进度 {p:.2f}  ·  动画基准 1.00 秒  ·  {note}',p)
    return np.asarray(im)

def compare_frame(m,a,ref,p,rp,rate,mode):
    lo,hi=focus_bounds(m);h=hi-lo;w=m['frame'][0];gap=16
    im=Image.new('RGB',(w*2+gap,h+140),BG);d=ImageDraw.Draw(im)
    left='华为参考 · 进度对齐' if mode=='compare-phase' else '华为参考 · 文件原速' if mode=='compare-file' else '源素材 · 静态' if m.get('holdout') else '原始真机截图 · 静态'
    label(d,(16,12),f'{m["title"]}｜{left}',25)
    label(d,(w+gap+16,12),f'桌面 · {VERSION} · {rate:g} 倍速',25)
    sub='参考区间映射为 1.00 秒；用于比较形状与材料变化' if mode=='compare-phase' else '保留参考文件时间；录屏原文件可能已经慢放' if mode=='compare-file' else '此截图没有同内容华为参考动画'
    label(d,(16,52),sub,20,MUTED)
    label(d,(w+gap+16,52),f'方向 {m["direction"]}° · 动画基准 1.00 秒',20,MUTED)
    im.paste(Image.fromarray(ref.at(rp)[lo:hi]),(0,92))
    im.paste(Image.fromarray(sampled(a,p)[lo:hi]),(w+gap,92))
    if m['reference']:
        r=m['reference'];source_t=r['start']+rp*(r['end']-r['start'])
        note=f'源文件 {source_t:.3f} 秒 · 参考进度 {rp:.2f} · 模型进度 {p:.2f}'
        if m['name']=='thanos' and rp>=.999:note+=' · 原片尾部在转黑前截止'
    else:note=f'模型进度 {p:.2f} · {m["background_note"]}'
    footer(im,note,p)
    return np.asarray(im)

def versions_frame(m,a,old,ref,p,rate,old_title='此前发布版'):
    lo,hi=focus_bounds(m);w=600 if m['reference'] else 720;gap=16;h=round((hi-lo)*w/m['frame'][0]);h+=h%2
    panels=[(sampled(old,p),old_title),(sampled(a,p),'共同释放与输运')]
    if m['reference']:panels.insert(0,(ref.at(p),'华为参考 · 进度对齐'))
    im=Image.new('RGB',(w*len(panels)+gap*(len(panels)-1),h+154),BG);d=ImageDraw.Draw(im)
    for i,(arr,title) in enumerate(panels):
        x=i*(w+gap);label(d,(x+16,12),title,29)
        label(d,(x+16,55),f'{m["title"]} · {rate:g} 倍速',22,MUTED)
        im.paste(Image.fromarray(arr[lo:hi]).resize((w,h),Image.Resampling.LANCZOS),(x,98))
    note='三栏按相同进度播放 · 参考区间映射为 1 秒' if m['reference'] else '两栏按相同进度播放 · 同一真机前景与背景'
    footer(im,f'进度 {p:.2f} · {note} · 上一版使用归档原始渲染帧',p)
    return np.asarray(im)

def matrix_frame(m,arrays,p,rate):
    cw=480;ch=600;gap=12;top=96;bottom=54;cell_h=ch+42
    im=Image.new('RGB',(cw*4+gap*3,top+cell_h*2+gap+bottom),BG);d=ImageDraw.Draw(im)
    label(d,(18,13),f'{m["title"]} · 八个消逝方向',34)
    label(d,(18,59),f'桌面 · {VERSION} · {rate:g} 倍速 · 同一时刻、同一随机种子',22,MUTED)
    lo,hi=focus_bounds(m,matrix=True)
    for i,(angle,title) in enumerate(DIRECTIONS):
        x=(i%4)*(cw+gap);y=top+(i//4)*(cell_h+gap)
        label(d,(x+13,y+5),f'{title}  {angle}°',25)
        frame=Image.fromarray(sampled(arrays[i],p)[lo:hi]);frame.thumbnail((cw,ch),Image.Resampling.LANCZOS)
        im.paste(frame,(x+(cw-frame.width)//2,y+42+(ch-frame.height)//2))
    footer(im,f'进度 {p:.2f} · 0° 向右，90° 向上 · 模型支持连续任意角度',p)
    return np.asarray(im)

def encode(path,seconds,rate,make_frame):
    count=round(seconds*FPS/rate);first=make_frame(0.);h,w=first.shape[:2]
    assert w%2==0 and h%2==0,(w,h)
    temporary=path.with_suffix('.encoding.mp4')
    args=[FFMPEG,'-hide_banner','-loglevel','error','-y','-f','rawvideo','-pixel_format','rgb24','-video_size',f'{w}x{h}','-framerate',str(FPS),'-i','pipe:0','-an','-vf','scale=out_color_matrix=bt709:in_range=full:out_range=limited','-c:v','libx264','-preset','fast','-crf','16','-pix_fmt','yuv420p','-color_range','tv','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart',str(temporary)]
    log=HERE/'analysis/export-ffmpeg.log'
    with log.open('ab') as err:
        proc=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=err)
        try:
            for i in range(count):
                im=first if i==0 else make_frame(i/FPS*rate)
                proc.stdin.write(np.ascontiguousarray(im).tobytes())
        finally:proc.stdin.close()
        if proc.wait()!=0:raise RuntimeError(f'编码失败：{path}；见 {log}')
    temporary.replace(path)
    poster=path.with_suffix('.jpg')
    Image.fromarray(make_frame(PRE+.48)).resize((round(w*.5),round(h*.5)),Image.Resampling.LANCZOS).save(poster,quality=91)
    print('视频完成',path.name,f'{count/FPS:.2f}s',f'{path.stat().st_size/1024**2:.1f}MB',flush=True)
    return {'file':path.name,'poster':poster.name,'width':w,'height':h,'fps':FPS,'frames':count,'duration':count/FPS,'rate':rate,'bytes':path.stat().st_size,'version':VERSION,'code_hash':code_hash()}

def save_manifest(items):
    path=OUT/'manifest.json';old=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'videos':[]}
    by={v['file']:v for v in old['videos']};by.update({v['file']:v for v in items})
    data={'version':VERSION,'created_local':time.strftime('%Y-%m-%d %H:%M:%S'),'code_hash':code_hash(),'fps':FPS,'model_sample_fps':SAMPLE_FPS,'model_duration':1.,'pre_hold':PRE,'post_hold':POST,'videos':sorted(by.values(),key=lambda x:x['file'])}
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')

def main():
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+');p.add_argument('--kinds',nargs='+',default=['animation','comparison','directions','versions','control']);p.add_argument('--rates',nargs='+',type=float,default=[1.,.5]);a=p.parse_args()
    OUT.mkdir(exist_ok=True);CACHE.mkdir(exist_ok=True)
    metas=json.loads((HERE/'assets/scenes.json').read_text(encoding='utf-8'))
    ctx=moderngl.create_standalone_context(require=430)
    for m in metas:
        name=m['name']
        if a.scenes and name not in a.scenes:continue
        arr=make_cache(ctx,name);ref=Reference(m)
        if 'control' in a.kinds and name=='ironman':
            control=np.load(HERE/'archive/observed-approved/ironman.npy',mmap_mode='r')
            for rate in a.rates:
                def control_frame(t,rate=rate):return versions_frame(m,arr,control,ref,float(np.clip(t-PRE,0,1)),rate,'用户认可的观测控制组')
                item=encode(OUT/f'{name}-compare-control-{rate:g}x.mp4',PRE+1+POST,rate,control_frame)
                item.update(scene=name,title=m['title'],kind='compare-control',model_direction=m['direction']);save_manifest([item])
        if 'versions' in a.kinds and not m.get('holdout'):
            old=np.load(HERE/'archive'/BASELINE_VERSION/f'{name}.npy',mmap_mode='r')
            for rate in a.rates:
                def version_frame(t,rate=rate):return versions_frame(m,arr,old,ref,float(np.clip(t-PRE,0,1)),rate)
                item=encode(OUT/f'{name}-compare-versions-{rate:g}x.mp4',PRE+1+POST,rate,version_frame)
                item.update(scene=name,title=m['title'],kind='compare-versions',model_direction=m['direction']);save_manifest([item])
        kinds=[]
        if 'animation' in a.kinds:kinds.append('animation')
        if 'comparison' in a.kinds:kinds+=['compare-phase','compare-file'] if m['reference'] else ['compare-source']
        for kind in kinds:
            length=m['reference']['end']-m['reference']['start'] if kind=='compare-file' else 1.
            for rate in a.rates:
                def frame(t,kind=kind,length=length,rate=rate):
                    p=float(np.clip(t-PRE,0,1));rp=float(np.clip((t-PRE)/length,0,1))
                    return single_frame(m,arr,p,rate) if kind=='animation' else compare_frame(m,arr,ref,p,rp,rate,kind)
                item=encode(OUT/f'{name}-{kind}-{rate:g}x.mp4',PRE+max(1,length)+POST,rate,frame)
                item.update(scene=name,title=m['title'],kind=kind,model_direction=m['direction']);save_manifest([item])
        if 'directions' in a.kinds and name in ['ironman','attachment','attachment-image']:
            arrays=[make_cache(ctx,name,angle) for angle,_ in DIRECTIONS]
            for rate in a.rates:
                def frame(t,rate=rate):return matrix_frame(m,arrays,float(np.clip(t-PRE,0,1)),rate)
                item=encode(OUT/f'{name}-eight-directions-{rate:g}x.mp4',PRE+1+POST,rate,frame)
                item.update(scene=name,title=m['title'],kind='eight-directions',directions=[a for a,_ in DIRECTIONS]);save_manifest([item])
    ctx.release()
    print('导出完成。全部视频位于同一个 videos 目录。',flush=True)

if __name__=='__main__':main()
