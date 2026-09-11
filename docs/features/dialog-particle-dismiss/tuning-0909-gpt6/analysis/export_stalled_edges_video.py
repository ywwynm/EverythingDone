"""在当前未修改的缓存帧上画候选边缘；先请用户确认位置，不把标线当成物理边界。"""
from pathlib import Path
import sys,json,math,hashlib,subprocess
import numpy as np
import cv2
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import code_hash,FFMPEG
from unified_model import model_fingerprint

OUT=HERE/'videos';REPORT=HERE/'analysis/stalled-edges';W,H,FPS=1920,1080,60
BG=(14,20,30);INK=(235,240,247);MUTED=(164,181,202);RED=(255,92,87);GOLD=(255,206,92)
FONTS={s:ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',s) for s in [20,24,28,32]}

def corner(x,y,r,which):
    angles=np.linspace(0,math.pi/2,25)
    if which=='right-bottom':return [[x-r+r*math.cos(a),y-r+r*math.sin(a)] for a in angles]
    return [[x+r-r*math.cos(a),y-r+r*math.sin(a)] for a in angles]

CASES=[dict(scene='ironman',seed=0,title='钢铁侠 · 原控件边缘',
    crop=[52,134,662,794],zoom=[450,565,636,772],focus_start=.36,
    red=[[[594,592],[594,706]]+corner(594,735,29,'right-bottom')+[[512,735]],
         [[126,594],[126,706]]+corner(126,735,29,'left-bottom')+[[193,735]]],gold=[],
    caption='① 红线：原控件下边缘与圆角附近，粒子形成直边／角部后逐渐消失。'),
    dict(scene='attachment',seed=1,title='添加附件 · 后段弯曲边缘',
    crop=[38,414,704,1108],zoom=[95,472,510,665],focus_start=.55,
    red=[[[98,878],[98,1022]]+corner(98,1051,29,'left-bottom')+[[203,1051]]],
    gold=[[[134,573],[169,566],[205,554],[235,543],[257,535],[271,521],[279,510],
           [286,521],[302,531],[331,527],[365,522],[405,512],[448,505],[482,500]]],
    caption='② 黄线：中后段上方的斜弯边及小尖角；请观察其形状是否停住后淡出。')]

def stroke(im,points,color,width=3):
    p=np.rint(points).astype('int32').reshape(-1,1,2)
    cv2.polylines(im,[p],False,(12,18,26),width+3,cv2.LINE_AA)
    cv2.polylines(im,[p],False,color,width,cv2.LINE_AA)

arrays={}
for case in CASES:
    key=f'{case["scene"]}-seed-{case["seed"]}'
    stamp=json.loads((HERE/'cache'/f'{key}.json').read_text('utf-8'))
    assert stamp['hash']==code_hash(),'缓存不属于当前模型'
    arrays[key]=np.load(HERE/'cache'/f'{key}.npy',mmap_mode='r')

sections=[];elapsed=0.
for index,case in enumerate(CASES):
    for rate,zoom,start,end in [(1.,False,0.,1.),(.5,False,0.,1.),(.25,True,case['focus_start'],.98)]:
        length=.4+(end-start)/rate+.6
        count=round(length*FPS);length=count/FPS
        sections.append(dict(case=index,rate=rate,zoom=zoom,start=start,end=end,at=elapsed,frames=count,length=length))
        elapsed+=length

def frame(section,t):
    c=CASES[section['case']];p=float(np.clip(section['start']+(t-.4)*section['rate'],section['start'],section['end']))
    a=np.asarray(arrays[f'{c["scene"]}-seed-{c["seed"]}'][round(p*120)]);marked=a.copy()
    for points in c['red']:stroke(marked,points,RED)
    if p>=.50:
        for points in c['gold']:stroke(marked,points,GOLD)
    crop=c['zoom'] if section['zoom'] else c['crop'];x,y,x1,y1=crop
    canvas=Image.new('RGB',(W,H),BG);d=ImageDraw.Draw(canvas)
    def label(x,y,text,size=24,color=INK):d.text((x,y),text,font=FONTS[size],fill=color)
    label(28,13,'粒子边缘停滞 · 位置确认',32)
    kind='尾段放大' if section['zoom'] else '完整过程'
    label(790,19,f'{c["title"]}  ·  {kind}  ·  {section["rate"]:g} 倍速',28)
    label(30,68,'左：当前原画',24);label(982,68,'右：候选边缘标线',24)
    for col,img in enumerate([a,marked]):
        tile=Image.fromarray(img[y:y1,x:x1]);scale=min(928/tile.width,850/tile.height)
        tile=tile.resize((round(tile.width*scale),round(tile.height*scale)),Image.Resampling.LANCZOS)
        canvas.paste(tile,(24+col*960+(928-tile.width)//2,106+(850-tile.height)//2))
    label(30,968,c['caption'],24,RED if section['case']==0 else GOLD)
    label(30,1014,'标线在画面坐标中固定；左右是同一批原始渲染帧。当前只确认位置，尚未修复。',20,MUTED)
    label(1570,1013,f'动画进度 t={p:.3f}',24)
    return np.asarray(canvas)

path=OUT/'stalled-edges-annotated.mp4';temp=path.with_suffix('.encoding.mp4')
command=[FFMPEG,'-hide_banner','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r',str(FPS),'-i','pipe:0',
 '-an','-vf','scale=out_color_matrix=bt709:in_range=full:out_range=limited','-c:v','libx264','-preset','fast','-crf','16','-pix_fmt','yuv420p',
 '-color_range','tv','-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart',str(temp)]
with (REPORT/'encode.log').open('wb') as log:
    process=subprocess.Popen(command,stdin=subprocess.PIPE,stderr=log)
    try:
        for section in sections:
            for i in range(section['frames']):process.stdin.write(frame(section,i/FPS).tobytes())
            print('标注片段',CASES[section['case']]['title'],section['rate'],section['zoom'],flush=True)
    finally:process.stdin.close()
    assert process.wait()==0
temp.replace(path)
Image.fromarray(frame(sections[1],1.35)).resize((960,540),Image.Resampling.LANCZOS).save(OUT/'stalled-edges-annotated.jpg',quality=94)
Image.fromarray(frame(sections[-1],1.32)).save(REPORT/'annotated-preview.png')
probe=json.loads(subprocess.check_output(['C:/ffmpeg/bin/ffprobe.exe','-v','error','-select_streams','v:0','-count_frames','-show_entries',
 'stream=width,height,nb_read_frames,avg_frame_rate,duration','-of','json',str(path)],text=True))['streams'][0]
assert int(probe['nb_read_frames'])==sum(s['frames'] for s in sections) and probe['avg_frame_rate']=='60/1'
cap=cv2.VideoCapture(str(path))
for section in sections:
    cap.set(cv2.CAP_PROP_POS_MSEC,(section['at']+min(section['length']-.1,1.35))*1000)
    ok,im=cap.read();assert ok and im.mean()>8
cap.release()
meta=dict(file=path.name,poster='stalled-edges-annotated.jpg',title='粒子边缘停滞：标线确认',purpose='待用户确认位置的诊断标注，非修复版本',
    model_hash=model_fingerprint(),code_hash=code_hash(),bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
    sections=sections,annotations=CASES,probe=probe,
    annotation_basis='原边缘取自源矩形圆角；后段弯边根据当前原画与低速材料位置手工描线。固定线仅为位置标记，不是求解器边界或根因结论。')
(REPORT/'video.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),'utf-8')
diag=OUT/'diagnostics.json';data=json.loads(diag.read_text('utf-8')) if diag.exists() else {'videos':[]}
data['videos']=[v for v in data['videos'] if v['file']!=meta['file']]+[meta]
diag.write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
links=''.join(f'<button data-time="{s["at"]}">{CASES[s["case"]]["title"]} · {"局部" if s["zoom"] else "全景"} {s["rate"]:g} 倍</button>' for s in sections)
page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>粒子边缘停滞 · 标线确认</title>
<style>body{margin:0;background:#0e141e;color:#ebf0f7;font:16px/1.7 "Microsoft YaHei",sans-serif}main{max-width:1700px;margin:auto;padding:22px}h1{font-size:25px;font-weight:600;margin:0}p{color:#a4b5ca;margin:8px 0 16px}video{width:100%;max-height:78vh;background:#080d14;border-radius:8px}nav{display:flex;gap:9px;flex-wrap:wrap;margin-top:15px}button,a{font:inherit;color:#ebf0f7;background:#213047;border:1px solid #3c4f6b;border-radius:6px;padding:6px 12px;text-decoration:none;cursor:pointer}.red{color:#ff5c57}.gold{color:#ffce5c}</style>
<main><h1>这些是否就是你指出的固定边缘？</h1><p>左侧保留当前原画，右侧画出候选边缘。<span class="red">① 红线：原控件边缘与角落</span>；<span class="gold">② 黄线：后段新的弯曲边缘</span>。每组都有原速、半速和尾段放大。标线只用于确认位置，尚未修改模型。</p>
<video id="v" src="stalled-edges-annotated.mp4" poster="stalled-edges-annotated.jpg" controls autoplay muted loop playsinline></video><nav>__LINKS__</nav><nav><button id="previous">上一帧</button><button id="next">下一帧</button><a href="stalled-edges-annotated.mp4" download>保存视频</a><a href="index.html">返回全部对比</a></nav></main>
<script>const v=document.getElementById('v');for(const b of document.querySelectorAll('[data-time]'))b.onclick=()=>{v.currentTime=Number(b.dataset.time);v.play();};const step=d=>{v.pause();v.currentTime=Math.max(0,Math.min(v.duration||0,v.currentTime+d/60));};document.getElementById('previous').onclick=()=>step(-1);document.getElementById('next').onclick=()=>step(1);</script></html>'''
page=page.replace('__LINKS__',links).replace('src="stalled-edges-annotated.mp4"',f'src="stalled-edges-annotated.mp4?v={meta["sha256"]}"')
(OUT/'stalled-edges.html').write_text(page,'utf-8')
print('标注视频完成',round(elapsed,2),'秒',round(path.stat().st_size/1024**2,2),'MiB，六段解码通过',flush=True)
