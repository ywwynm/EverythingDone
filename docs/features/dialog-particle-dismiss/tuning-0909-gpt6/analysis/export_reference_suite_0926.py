"""新旧参考共用一次渲染和规则快照；所有成片平铺，不覆盖此前已验收视频。"""
from pathlib import Path
import argparse,dataclasses,hashlib,json,sys,time
import cv2,moderngl,numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import export_videos as video
from export_videos import Reference,load_meta,PRE,POST,SAMPLE_FPS,DIRECTIONS
from front_transport import FrontRenderer,FrontRules,ReleaseSite,sample_sites
from export_reference_placeholder import ended_reference
from reference_metrics_0926 import difference,valid_pixels

OUT=HERE/'videos';DATA=HERE/'analysis/new-references-0926';CACHE=DATA/'render-cache'
NEW=[f'reference-0926-{i}' for i in range(1,8)]
OLD=['ironman','thanos','kobe','ironman-up-reference','spiderman-original']
DIALOGS=['attachment','color','language']
BG=(14,20,30);FG=(232,239,248);MUTED=(170,189,207)
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23)
SMALL=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)


def fingerprint():
    from unified_model import SHARED
    digest=hashlib.sha256()
    files=[HERE/n for n in ['renderer.py','unified_model.py','pressure_grid.py','front_transport.py']]
    files+=list(SHARED.glob('*'))
    for name in NEW+OLD+DIALOGS:
        files+=[HERE/'assets'/name/n for n in ['foreground.png','background.png','scene.json']]
    for path in sorted(set(files)):
        if path.is_file():digest.update(path.name.encode());digest.update(path.read_bytes())
    digest.update(json.dumps(dataclasses.asdict(FrontRules()),sort_keys=True).encode())
    return digest.hexdigest()


def cache(ctx,meta,identity,angle=None,seed=None,sites='recorded'):
    seed=meta['seed'] if seed is None else seed
    angle=meta['direction'] if angle is None else angle
    events=meta.get('release_sites') if sites=='recorded' else sites
    if events is not None:events=[ReleaseSite(**p) if isinstance(p,dict) else p for p in events]
    inputs=dict(scene=meta['name'],seed=seed,angle=angle,sites=None if events is None else [dataclasses.asdict(p) for p in events])
    key=hashlib.sha256(json.dumps(inputs,sort_keys=True).encode()).hexdigest()[:14]
    path=CACHE/f'{meta["name"]}-{key}.npy';stamp=path.with_suffix('.json')
    if path.exists() and stamp.exists() and json.loads(stamp.read_text('utf-8'))['hash']==identity:
        return np.load(path,mmap_mode='r'),inputs
    r=FrontRenderer(meta['name'],ctx=ctx,sites=events,seed=seed,direction=angle,quality=2)
    frames=np.lib.format.open_memmap(path,mode='w+',dtype='uint8',shape=(121,r.h,r.w,3))
    for i in range(121):frames[i]=r.render(i/120)
    frames.flush();r.close()
    stamp.write_text(json.dumps(dict(hash=identity,inputs=inputs),ensure_ascii=False,indent=2),'utf-8')
    print('渲染完成',meta['name'],angle,seed,flush=True)
    return np.load(path,mmap_mode='r'),inputs


def render_panel(frame,width):
    h=round(frame.shape[0]*width/frame.shape[1]);h+=h%2
    return Image.fromarray(frame).resize((width,h),Image.Resampling.LANCZOS)


def reference_frame(ref,meta,phase):
    if phase<=(meta.get('reference') or {}).get('valid_progress',1):return ref.at(phase)
    return ended_reference(meta['frame'])


def frame(meta,frames,ref,phase,kind,rate):
    model=video.sampled(frames,phase);truth=reference_frame(ref,meta,phase)
    parts=[(model,'共同模型 · 桌面候选')]
    if kind!='animation':parts.insert(0,(truth,'华为原片 · 进度对齐' if meta['reference'] else '原始弹窗 · 静态截图'))
    if kind=='difference':
        delta=np.abs(truth.astype('float32')-model.astype('float32')).mean(2)
        heat=cv2.applyColorMap(np.uint8(np.clip(delta*3,0,255)),cv2.COLORMAP_INFERNO)[:,:,::-1]
        heat[~valid_pixels(meta)]=[45,48,57]
        if phase>(meta.get('reference') or {}).get('valid_progress',1):heat[:]=[45,48,57]
        parts.append((heat,'RGB 像素差 ×3 · 灰色为无真值区域'))
    width=meta['frame'][0];height=meta['frame'][1]
    im=Image.new('RGB',(width*len(parts)+16*(len(parts)-1),height+174),BG);d=ImageDraw.Draw(im)
    for i,(arr,title) in enumerate(parts):
        x=i*(width+16);d.text((x+16,10),title,font=FONT,fill=FG)
        d.text((x+16,47),f'{meta["title"]} · {rate:g} 倍速 · 进度 {phase:.3f}',font=SMALL,fill=MUTED)
        im.paste(Image.fromarray(arr),(x,88))
    note=f'共同参数 · {meta["direction"]:g}° · 种子 {meta["seed"]} · 模型动画基准 1 秒'
    d.text((16,height+97),note,font=SMALL,fill=MUTED)
    valid=meta.get('reference',{}).get('valid_progress',1) if meta['reference'] else 1
    if valid<1:note=f'参考仅有效到进度 {valid:.3f}；背景有重建区域；不作完整像素真值'
    elif not meta['background_truth']:note='部分背景为重建值；请结合原片检查，灰色遮罩不参与误差统计'
    else:note='参考使用原片已有帧；按进度比较，不代表原录像的播放时长'
    d.text((16,height+132),note,font=SMALL,fill=MUTED)
    d.rectangle((0,height+88,round(im.width*phase),height+91),fill=(99,215,207))
    return np.asarray(im)


def matrix(meta,arrays,phase,rate,labels,title):
    columns=4;w=400;h=740;rows=(len(arrays)+columns-1)//columns
    im=Image.new('RGB',(columns*w,100+rows*h+50),BG);d=ImageDraw.Draw(im)
    d.text((16,12),f'{meta["title"]} · {title}',font=FONT,fill=FG)
    d.text((16,50),f'{rate:g} 倍速 · 同一共同模型 · 进度 {phase:.3f}',font=SMALL,fill=MUTED)
    for i,(arr,label) in enumerate(zip(arrays,labels)):
        x=i%columns*w;y=100+i//columns*h
        d.text((x+10,y+3),label,font=SMALL,fill=FG)
        img=Image.fromarray(video.sampled(arr,phase));img.thumbnail((w-8,h-38),Image.Resampling.LANCZOS)
        im.paste(img,(x+(w-img.width)//2,y+36+(h-38-img.height)//2))
    d.text((16,im.height-37),'完整画面同尺度呈现；边界、触点、素材名称不构成粒子停止条件',font=SMALL,fill=MUTED)
    return np.asarray(im)


def gallery(items,identity):
    manifest=dict(status='桌面候选；尚未达到逐像素一致',created=time.strftime('%Y-%m-%d %H:%M:%S'),
                  hash=identity,rules=dataclasses.asdict(FrontRules()),videos=items)
    (OUT/'new-references-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>新增参考 · 共同模型桌面对照</title><style>
body{background:#0e141e;color:#e8eff8;font:16px/1.65 system-ui,Microsoft YaHei;margin:20px auto;max-width:1500px;padding:0 18px}
h1{font-size:27px;margin:5px 0}p{color:#b0c4d8;margin:8px 0}select,button,a.control{background:#223248;color:#e8eff8;border:1px solid #426078;padding:9px 13px;border-radius:6px;font:inherit;cursor:pointer}
.bar{display:flex;gap:9px;align-items:center;flex-wrap:wrap;margin:14px 0}a{color:#7ee0df}video{display:block;width:100%;max-height:78vh;background:#000;border-radius:6px}#status{font-size:14px}#download{margin-left:12px}.active{outline:2px solid #73d9c6}#warning{color:#ecc794}
</style><h1>新增参考 · 共同模型桌面对照</h1>
<p>七段新参考与旧钢铁侠、科比、灭霸、蜘蛛侠放在一起检查。先看完整运动，再看逐帧像素差；旧视频仍以原始华为录像为参照。</p>
<p id="warning">当前是桌面候选。新参考的前沿扩张、颗粒覆盖及中后段轮廓仍有明显差距，尚未达到逐像素一致。</p>
<div class="bar"><select id="scene" aria-label="素材"></select><select id="kind" aria-label="对照类型"></select><button id="normal">1 倍速</button><button id="slow">0.5 倍速</button><a id="download" download>保存当前视频</a></div>
<video id="v" controls playsinline preload="metadata" loop></video><p id="status"></p>
<div class="bar"><button id="toggle">播放 / 暂停</button><button id="prev">上一帧</button><button id="next">下一帧</button><button id="restart">从头播放</button><button id="full">全屏</button></div>
<div class="bar" id="phases"></div><p>模型以 120 Hz 取样，视频均为 60 fps。动画进度映射到 1 秒；0.5 倍文件使用中间采样时刻。手持参考已配准，无法观测的背景和片尾不计像素准确率。</p>
<p><a href="index.html">此前已验收视频</a> · <a href="new-references-manifest.json">当前参数与文件清单</a></p>
<script>
const $=id=>document.getElementById(id), v=$('v');let data,rate=.5,selected;
const titles={animation:'完整动画',comparison:'华为 / 源素材对照',difference:'逐帧 RGB 差',directions:'八个方向',transfer:'四组起点迁移'};
function fill(el,values){el.replaceChildren(...values.map(([value,label])=>Object.assign(document.createElement('option'),{value,textContent:label})));}
function kinds(){const keep=$('kind').value;fill($('kind'),[...new Set(data.filter(q=>q.scene===$('scene').value).map(q=>q.kind))].map(k=>[k,titles[k]]));if([...$('kind').options].some(o=>o.value===keep))$('kind').value=keep;else if([...$('kind').options].some(o=>o.value==='comparison'))$('kind').value='comparison';load();}
function load(keep=false){const phase=keep&&selected?Math.max(0,Math.min(1,v.currentTime*selected.rate-.35)):0;selected=data.find(q=>q.scene===$('scene').value&&q.kind===$('kind').value&&q.rate===rate);if(!selected)return;v.src=selected.file;v.poster=selected.poster;$('download').href=selected.file;history.replaceState(null,'','#'+selected.file);$('status').textContent=selected.title+' · '+titles[selected.kind]+' · '+rate+' 倍速';$('normal').classList.toggle('active',rate===1);$('slow').classList.toggle('active',rate===.5);if(keep)v.addEventListener('loadedmetadata',()=>{v.currentTime=(.35+phase)/rate;},{once:true});}
$('scene').onchange=kinds;$('kind').onchange=()=>load();$('normal').onclick=()=>{rate=1;load(true)};$('slow').onclick=()=>{rate=.5;load(true)};
$('toggle').onclick=()=>v.paused?v.play():v.pause();$('prev').onclick=()=>{v.pause();v.currentTime=Math.max(0,v.currentTime-1/60)};$('next').onclick=()=>{v.pause();v.currentTime=Math.min(v.duration,v.currentTime+1/60)};
$('restart').onclick=()=>{v.currentTime=0;v.play()};$('full').onclick=()=>v.requestFullscreen();
for(const p of [.12,.25,.40,.56,.72,.88]){const b=document.createElement('button');b.textContent='进度 '+p.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+p)/rate};$('phases').append(b)}
fetch('new-references-manifest.json',{cache:'no-store'}).then(r=>r.json()).then(m=>{data=m.videos;fill($('scene'),[...new Map(data.map(q=>[q.scene,q.title])).entries()]);const q=data.find(q=>q.file===decodeURIComponent(location.hash.slice(1)));if(q){$('scene').value=q.scene;rate=q.rate;kinds();$('kind').value=q.kind;load()}else kinds()});
</script></html>'''
    (OUT/'new-references.html').write_text(html,'utf-8')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--scenes',nargs='+');parser.add_argument('--extra',action='store_true');a=parser.parse_args()
    OUT.mkdir(exist_ok=True);CACHE.mkdir(exist_ok=True)
    identity=fingerprint();video.VERSION='任意起点的共同规则 · 桌面候选';video.code_hash=lambda:identity
    ctx=moderngl.create_standalone_context(require=430);items=[];metrics=[]
    previous=OUT/'new-references-manifest.json'
    if previous.exists():
        old=json.loads(previous.read_text('utf-8'))
        if old['hash']==identity:items=old['videos']
    def publish(path,make,meta,kind,rate,inputs):
        item=video.encode(path,PRE+1+POST,rate,make)
        item.update(scene=meta['name'],title=meta['title'],kind=kind,inputs=inputs)
        items[:]=[q for q in items if q['file']!=item['file']];items.append(item);gallery(items,identity)
    for name in a.scenes or NEW+OLD+DIALOGS:
        m=load_meta(name);ref=Reference(m);arr,inputs=cache(ctx,m,identity)
        kinds=['comparison','animation']+(['difference'] if m['reference'] else [])
        for kind in kinds:
            for rate in [1.,.5]:
                publish(OUT/f'reference-suite-{name}-{kind}-{rate:g}x.mp4',
                        lambda t,k=kind,r=rate:frame(m,arr,ref,float(np.clip(t-PRE,0,1)),k,r),m,kind,rate,inputs)
        if m['reference']:
            metrics.append(dict(scene=name,frames=[difference(ref.at(p),video.sampled(arr,p),m,p) for p in [.12,.25,.40,.56,.72,.88]]))
    if a.extra:
        for name in ['ironman','attachment','color']:
            m=load_meta(name);x,y,x1,y1=m['rect'];sites=sample_sites(x1-x,y1-y,42)
            arrays=[];info=[]
            for angle,label in DIRECTIONS:
                arr,inputs=cache(ctx,m,identity,angle=angle,seed=42,sites=sites);arrays.append(arr);info.append(inputs)
            for rate in [1.,.5]:
                publish(OUT/f'reference-suite-{name}-directions-{rate:g}x.mp4',
                    lambda t,r=rate:matrix(m,arrays,float(np.clip(t-PRE,0,1)),r,[f'{title} · {angle}°' for angle,title in DIRECTIONS],'同一起点的八向检验'),m,'directions',rate,info)
            arrays=[];info=[];labels=[]
            for source in ['reference-0926-1','reference-0926-4','reference-0926-5','reference-0926-6']:
                src=load_meta(source);arr,inputs=cache(ctx,m,identity,angle=src['direction'],seed=src['seed'],sites=src['release_sites'])
                arrays.append(arr);info.append(inputs);labels.append('起点来自参考 '+source[-1]+' · '+str(round(src['direction']))+'°')
            for rate in [1.,.5]:
                publish(OUT/f'reference-suite-{name}-transfer-{rate:g}x.mp4',
                    lambda t,r=rate:matrix(m,arrays,float(np.clip(t-PRE,0,1)),r,labels,'同样输入迁移到其它素材'),m,'transfer',rate,info)
    (DATA/'export-metrics.json').write_text(json.dumps(dict(hash=identity,cases=metrics),ensure_ascii=False,indent=2),'utf-8')
    print('验收页就绪',OUT/'new-references.html',len(items),'个文件',flush=True)


if __name__=='__main__':main()
