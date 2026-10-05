"""第二版共同模型的成片：原片对照、完整动画、像素差、八方向与随机种子矩阵，1 倍与 0.5 倍平铺在 videos/。

原片按独立画面序号换算进度（真实采集时间，见 match_refs.py），片尾缺失的时段显示缺帧提示，
不冻结最后一帧冒充后续。视频较大，不进 Git。
"""
from pathlib import Path
import argparse,dataclasses,hashlib,json,sys,time
import cv2,moderngl,numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE));sys.path.insert(0,str(HERE/'analysis'))
import export_videos as video
from export_videos import PRE,POST,DIRECTIONS
from export_reference_placeholder import ended_reference
from dissolve_v2 import V2Renderer,V2Rules,RULES,reference_inputs,sample_sites
from match_refs import RefFrames,ALL
from reference_metrics_0926 import valid_pixels

OUT=HERE/'videos';CACHE=HERE/'analysis/match-cache/v2-render'
DIALOGS=['attachment','color','language']
BG=(14,20,30);FG=(232,239,248);MUTED=(170,189,207)
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23);SMALL=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
PREFIX='v2'


def load_rules(path):
    if not path:return RULES
    data=json.loads(Path(path).read_text('utf-8'))
    return dataclasses.replace(RULES,**data.get('best',data).get('rules',data))


def fingerprint(rules):
    h=hashlib.sha256()
    for p in [HERE/'dissolve_v2.py',HERE/'v2_inputs.py',HERE/'v2_inputs.json',HERE/'unified_model.py']:
        if p.exists():h.update(p.read_bytes())
    h.update(json.dumps(dataclasses.asdict(rules),sort_keys=True).encode())
    return h.hexdigest()


def meta_of(name):return json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))


def render_cache(ctx,name,rules,identity,direction=None,seed=None,sites='recorded',touch_gap=None):
    q=reference_inputs(name) if sites=='recorded' else dict(direction=meta_of(name)['direction'],seed=meta_of(name)['seed'],sites=sites,touch_strength=None)
    direction=q['direction'] if direction is None else direction;seed=q['seed'] if seed is None else seed
    s=q['sites'];s=None if s is None else [dataclasses.asdict(p) if dataclasses.is_dataclass(p) else p for p in s]
    touch=q.get('touch_strength') if sites=='recorded' else None
    inputs=dict(scene=name,direction=direction,seed=seed,sites=s,touch_gap=touch_gap,touch_strength=touch)
    key=hashlib.sha256(json.dumps(inputs,sort_keys=True).encode()).hexdigest()[:14]
    CACHE.mkdir(parents=True,exist_ok=True);path=CACHE/f'{name}-{key}.npy';stamp=path.with_suffix('.json')
    if path.exists() and stamp.exists() and json.loads(stamp.read_text('utf-8'))['hash']==identity:
        return np.load(path,mmap_mode='r'),inputs
    r=V2Renderer(name,direction=direction,seed=seed,sites=s,touch_gap=touch_gap,touch_strength=touch,rules=rules,ctx=ctx,quality=2)
    frames=np.lib.format.open_memmap(path,mode='w+',dtype='uint8',shape=(121,r.h,r.w,3))
    for i in range(121):frames[i]=r.render(i/120)
    frames.flush();r.close()
    stamp.write_text(json.dumps(dict(hash=identity,inputs=inputs),ensure_ascii=False,indent=2),'utf-8')
    return np.load(path,mmap_mode='r'),inputs


def sampled(frames,p):return np.asarray(frames[round(float(np.clip(p,0,1))*120)])


def old_cache(ctx,name):
    """此前已认可的共同模型（renderer.Renderer，默认输入），与第二版同一时刻取样。"""
    from renderer import Renderer
    import hashlib
    h=hashlib.sha256()
    for f in ['renderer.py','unified_model.py','pressure_grid.py']:h.update((HERE/f).read_bytes())
    identity=h.hexdigest();CACHE.mkdir(parents=True,exist_ok=True)
    path=CACHE/f'old-{name}.npy';stamp=path.with_suffix('.json')
    if path.exists() and stamp.exists() and json.loads(stamp.read_text('utf-8'))['hash']==identity:return np.load(path,mmap_mode='r')
    r=Renderer(name,ctx=ctx,quality=2)
    frames=np.lib.format.open_memmap(path,mode='w+',dtype='uint8',shape=(121,r.h,r.w,3))
    for i in range(121):frames[i]=r.render(i/120)
    frames.flush();r.close();stamp.write_text(json.dumps(dict(hash=identity),ensure_ascii=False),'utf-8')
    return np.load(path,mmap_mode='r')


class Truth:
    """原片（独立画面序号时间）或静态源图。"""
    def __init__(self,name):
        self.meta=meta_of(name);self.frames=RefFrames(name) if self.meta.get('reference') and name in ALL else None
        self.source=np.array(Image.open(HERE/'assets'/name/'source.png').convert('RGB'))
        self.valid=(self.meta.get('reference') or {}).get('valid_progress',1.)
    def at(self,p):
        if self.frames is None:return self.source
        if p>self.valid:return ended_reference(self.meta['frame'])
        return self.frames.nearest(p)[1]


def panel_frame(meta,parts,phase,rate,note):
    width,height=meta['frame']
    im=Image.new('RGB',(width*len(parts)+16*(len(parts)-1),height+174),BG);d=ImageDraw.Draw(im)
    for i,(arr,title) in enumerate(parts):
        x=i*(width+16);d.text((x+16,10),title,font=FONT,fill=FG)
        d.text((x+16,47),f'{meta["title"]} · {rate:g} 倍速 · 进度 {phase:.3f}',font=SMALL,fill=MUTED)
        im.paste(Image.fromarray(np.ascontiguousarray(arr)),(x,88))
    d.text((16,height+97),note,font=SMALL,fill=MUTED)
    d.text((16,height+132),'原片按独立画面序号对齐进度（真实采集时间）；模型动画基准 1 秒',font=SMALL,fill=MUTED)
    d.rectangle((0,height+88,round(im.width*phase),height+91),fill=(99,215,207))
    return np.asarray(im)


def difference_panel(meta,truth,model,phase,valid):
    delta=np.abs(truth.astype('float32')-model.astype('float32')).mean(2)
    heat=cv2.applyColorMap(np.uint8(np.clip(delta*3,0,255)),cv2.COLORMAP_INFERNO)[:,:,::-1]
    heat[~valid_pixels(meta)]=[45,48,57]
    if phase>(meta.get('reference') or {}).get('valid_progress',1):heat[:]=[45,48,57]
    return heat


def matrix_frame(meta,arrays,labels,phase,rate,title):
    columns=4;w=400;h=740;rows=(len(arrays)+columns-1)//columns
    im=Image.new('RGB',(columns*w,100+rows*h+50),BG);d=ImageDraw.Draw(im)
    d.text((16,12),f'{meta["title"]} · {title}',font=FONT,fill=FG)
    d.text((16,50),f'{rate:g} 倍速 · 同一套 V2Rules · 进度 {phase:.3f}',font=SMALL,fill=MUTED)
    for i,(arr,label) in enumerate(zip(arrays,labels)):
        x=i%columns*w;y=100+i//columns*h
        d.text((x+10,y+3),label,font=SMALL,fill=FG)
        img=Image.fromarray(sampled(arr,phase));img.thumbnail((w-8,h-38),Image.Resampling.LANCZOS)
        im.paste(img,(x+(w-img.width)//2,y+36+(h-38-img.height)//2))
    d.text((16,im.height-37),'完整画面同尺度呈现；边界、触点、素材名称不构成粒子停止条件',font=SMALL,fill=MUTED)
    return np.asarray(im)


def page(items,identity,rules):
    manifest=dict(status='第二版共同模型桌面候选',created=time.strftime('%Y-%m-%d %H:%M:%S'),hash=identity,rules=dataclasses.asdict(rules),videos=items)
    (OUT/f'{PREFIX}-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    template=(OUT/'new-references.html').read_text('utf-8')
    html=template.replace('new-references-manifest.json',f'{PREFIX}-manifest.json')
    html=html.replace('新增参考 · 共同模型桌面对照','第二版共同模型 · 华为原片逐帧对照')
    html=html.replace('当前是桌面候选。新参考的前沿扩张、颗粒覆盖及中后段轮廓仍有明显差距，尚未达到逐像素一致。',
                      '第二版共同模型：所有素材共用一套 V2Rules，输入只有尺寸、方向、触点远近、源图、种子和起点。桌面候选，仍需逐段目视验收。')
    html=html.replace("transfer:'四组起点迁移'","transfer:'四组起点迁移',seeds:'六个随机种子',versions:'原片／此前认可版／第二版'")
    (OUT/f'{PREFIX}-review.html').write_text(html,'utf-8')


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--rules');ap.add_argument('--scenes',nargs='+');ap.add_argument('--extra',action='store_true')
    ap.add_argument('--rates',nargs='+',type=float,default=[1.,.5]);a=ap.parse_args()
    rules=load_rules(a.rules);identity=fingerprint(rules)
    video.VERSION='第二版共同模型';video.code_hash=lambda:identity
    ctx=moderngl.create_standalone_context(require=430);OUT.mkdir(exist_ok=True)
    old=OUT/f'{PREFIX}-manifest.json';items=[]
    if old.exists():
        prev=json.loads(old.read_text('utf-8'))
        if prev['hash']==identity:items=prev['videos']
    def publish(path,make,meta,kind,rate,inputs):
        item=video.encode(path,PRE+1+POST,rate,make)
        item.update(scene=meta['name'],title=meta['title'],kind=kind,inputs=inputs)
        items[:]=[q for q in items if q['file']!=item['file']];items.append(item);page(items,identity,rules)
    for name in a.scenes or ALL+DIALOGS:
        m=meta_of(name);truth=Truth(name);arr,inputs=render_cache(ctx,name,rules,identity)
        kinds=['comparison','animation']+(['difference'] if truth.frames is not None and valid_pixels(m).any() else [])
        for kind in kinds:
            for rate in a.rates:
                def make(t,k=kind,r=rate):
                    p=float(np.clip(t-PRE,0,1));model=sampled(arr,p);parts=[(model,'第二版共同模型')]
                    if k!='animation':
                        tr=truth.at(p);parts.insert(0,(tr,'华为原片 · 进度对齐' if truth.frames is not None else '原始弹窗 · 静态截图'))
                    if k=='difference':parts.append((difference_panel(m,truth.at(p),model,p,truth.valid),'RGB 像素差 ×3 · 灰色为无真值区域'))
                    note=f'共同参数 · {inputs["direction"]:g}° · 种子 {inputs["seed"]} · 起点 {len(inputs["sites"] or [])} 个'
                    return panel_frame(m,parts,p,r,note)
                publish(OUT/f'{PREFIX}-{name}-{kind}-{rate:g}x.mp4',make,m,kind,rate,inputs)
    if a.extra:
        # 旧参考与自有弹窗：华为原片（或静态源图）／此前认可版／第二版三栏，检查没有退化。
        for name in ['ironman','thanos','kobe','spiderman-original','attachment','color']:
            m=meta_of(name);truth=Truth(name);arr,inputs=render_cache(ctx,name,rules,identity);old=old_cache(ctx,name)
            for rate in a.rates:
                def make(t,r=rate,m=m,truth=truth,arr=arr,old=old,inputs=inputs):
                    p=float(np.clip(t-PRE,0,1))
                    parts=[(truth.at(p),'华为原片 · 进度对齐' if truth.frames is not None else '原始弹窗 · 静态截图'),(sampled(old,p),'此前认可的共同模型'),(sampled(arr,p),'第二版共同模型')]
                    return panel_frame(m,parts,p,r,f'三栏同一时刻 · 第二版输入 {inputs["direction"]:g}° · 种子 {inputs["seed"]}')
                publish(OUT/f'{PREFIX}-{name}-versions-{rate:g}x.mp4',make,m,'versions',rate,inputs)
        for name in ['ironman','attachment','color']:
            m=meta_of(name);x,y,x1,y1=m['rect'];sites=[dataclasses.asdict(s) for s in sample_sites(x1-x,y1-y,42)]
            arrays=[];info=[]
            for angle,label in DIRECTIONS:
                arr,inputs=render_cache(ctx,name,rules,identity,direction=angle,seed=42,sites=sites);arrays.append(arr);info.append(inputs)
            for rate in a.rates:
                publish(OUT/f'{PREFIX}-{name}-directions-{rate:g}x.mp4',lambda t,r=rate:matrix_frame(m,arrays,[f'{l} · {g}°' for g,l in DIRECTIONS],float(np.clip(t-PRE,0,1)),r,'同一起点的八向检验'),m,'directions',rate,info)
        for name in ['attachment','ironman']:
            m=meta_of(name);x,y,x1,y1=m['rect'];arrays=[];info=[];labels=[]
            for seed in range(6):
                sites=[dataclasses.asdict(s) for s in sample_sites(x1-x,y1-y,seed)]
                arr,inputs=render_cache(ctx,name,rules,identity,seed=seed,sites=sites);arrays.append(arr);info.append(inputs)
                labels.append(f'种子 {seed} · 起点 {len(sites)} 个')
            for rate in a.rates:
                publish(OUT/f'{PREFIX}-{name}-seeds-{rate:g}x.mp4',lambda t,r=rate:matrix_frame(m,arrays,labels,float(np.clip(t-PRE,0,1)),r,'同一方向的六个随机种子'),m,'seeds',rate,info)
    page(items,identity,rules);print('完成',len(items),'个视频',flush=True)


if __name__=='__main__':main()
