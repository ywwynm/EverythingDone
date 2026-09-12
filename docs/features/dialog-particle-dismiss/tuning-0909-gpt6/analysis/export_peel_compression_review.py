"""以完整画面对照固定基线和共同修正；诊断标线只放在单独的原录像定位图。"""
from pathlib import Path
import sys, json, shutil, hashlib
import numpy as np
import moderngl
from PIL import Image, ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import encode,OUT,PRE,POST,BG,MUTED,label,footer,Reference,load_meta,code_hash
from probe_filament_layers import CASES,BASE,OUT as ANALYSIS
import verify_frame_calibration as frozen

def main():
    frozen.BASE=BASE;Before=frozen.frozen_renderer()
    ctx=moderngl.create_standalone_context(require=430);items=[]
    # 首先用诊断时保存的整帧确认基线确实还原。
    check=Before('attachment',ctx=ctx,direction=270,seed=909602)
    assert np.array_equal(check.render(.3),np.load(ANALYSIS/'case-00-attachment-270-909602/layer-0.npy',mmap_mode='r')[1])
    check.close()
    for case,(name,angle,seed) in enumerate(CASES):
        old=Before(name,ctx=ctx,direction=angle,seed=seed);new=Renderer(name,ctx=ctx,direction=angle,seed=seed)
        meta=load_meta(name);reference=Reference(meta) if case==8 else None
        width=480;height=round(new.h/new.w*width);height+=height%2
        columns=3 if reference else 2
        for rate in [1.,.5]:
            def frame(time,rate=rate):
                phase=float(np.clip(time-PRE,0,1))
                panels=[('修复前',old.render(phase)),('共同修正',new.render(phase))]
                if reference:panels.insert(0,('华为参考',reference.at(phase)))
                im=Image.new('RGB',(width*columns,height+132),BG);d=ImageDraw.Draw(im)
                for col,(title,pixels) in enumerate(panels):
                    x=col*width;label(d,(x+12,8),title,25)
                    label(d,(x+12,43),f'{meta["title"]} · {angle}° · 种子 {seed}',18,MUTED)
                    im.paste(Image.fromarray(pixels).resize((width,height),Image.Resampling.LANCZOS),(x,80))
                footer(im,f'进度 {phase:.3f} · {rate:g} 倍速 · 相同材料来源、寿命与主流场',phase)
                return np.asarray(im)
            filename=f'peel-compression-{case+1:02d}-{name}-{rate:g}x.mp4'
            item=encode(OUT/filename,PRE+1+POST,rate,frame)
            item.update(case=case+1,scene=name,title=meta['title'],direction=angle,seed=seed,
                        sha256=hashlib.sha256((OUT/filename).read_bytes()).hexdigest());items.append(item)
        old.close();new.close();print('完整画面对照完成',case+1,name,angle,seed,flush=True)
    ctx.release()
    shutil.copy2(ANALYSIS/'target-location.png',OUT/'peel-recording-location.png')
    document=dict(model_hash=model_fingerprint(),code_hash=code_hash(),videos=items)
    (OUT/'peel-compression-videos.json').write_text(json.dumps(document,ensure_ascii=False,indent=2),'utf-8')
    options=''.join(f'<option value="{j+1}">{load_meta(name)["title"]} · {angle}° · 种子 {seed}</option>' for j,(name,angle,seed) in enumerate(CASES))
    page='''<!doctype html><meta charset="utf-8"><title>内部细缕：定位与剥离修正</title>
<style>body{margin:24px;background:#0e141e;color:#e8eff8;font:17px/1.65 system-ui}h1{font-size:28px}p{max-width:1150px}button,select{padding:10px;background:#223348;color:inherit;border:1px solid #52667f;border-radius:6px;margin:5px}video{display:block;max-width:100%;max-height:82vh;margin:16px auto;background:black}img{display:block;max-height:85vh;max-width:100%;margin:20px auto}a{color:#79ddd6}.controls{display:flex;flex-wrap:wrap;align-items:center}</style>
<h1>内部细缕：定位与剥离修正</h1>
<p>旧版的剥离运动会把宽区域的粒子压向内部窄曲线。共同修正按局部压缩程度减轻这部分运动，保留材料数量、寿命、颜色与主流场。下面按同一时刻比较完整画面；模型画面没有诊断标线。</p>
<div class="controls"><select id="cases">OPTIONS</select><button id="normal">1 倍速</button><button id="slow">0.5 倍速</button><button id="play">播放 / 暂停</button><button id="full">全屏</button><button id="prev">上一帧</button><button id="next">下一帧</button><a id="download" download>保存当前视频</a>　<a href="index.html">全部标准视频</a></div><div id="phases"></div>
<video id="player" controls playsinline loop preload="auto"></video>
<details><summary>原始真机录像：已定位的两条内部细缕</summary><p>原录像第 38 帧：左侧保留原图，右侧橙色与青色细线对应用户圈出的两条内部细缕。原录像随机种子未知；上方采用可重复输入复现同类缺陷。</p><img src="peel-recording-location.png"></details>
<script>const data=DATA;let speed=.5;const v=document.querySelector('#player'),sel=document.querySelector('#cases');function show(){let item=data.find(x=>x.case==+sel.value&&x.file.endsWith(`-${speed}x.mp4`));v.src=item.file;document.querySelector('#download').href=item.file;location.hash=item.file;}sel.onchange=show;document.querySelector('#normal').onclick=()=>{speed=1;show()};document.querySelector('#slow').onclick=()=>{speed=.5;show()};document.querySelector('#prev').onclick=()=>{v.pause();v.currentTime=Math.max(0,v.currentTime-1/60)};document.querySelector('#next').onclick=()=>{v.pause();v.currentTime=Math.min(v.duration,v.currentTime+1/60)};let initial=data.find(x=>x.file==decodeURIComponent(location.hash.slice(1)));if(initial){sel.value=initial.case;speed=initial.file.endsWith('-0.5x.mp4')?.5:1}show();</script>'''
    page=page.replace('OPTIONS',options).replace('DATA',json.dumps(items,ensure_ascii=False))
    page=page.replace('v.src=item.file;',"v.src=item.file+'?v="+code_hash()+"';")
    page=page.replace('let initial=data.find',"document.querySelector('#play').onclick=()=>v.paused?v.play():v.pause();document.querySelector('#full').onclick=()=>v.requestFullscreen();for(const p of [.2,.3,.4,.5,.6,.7]){let b=document.createElement('button');b.textContent='进度 '+p.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+p)/speed};document.querySelector('#phases').append(b)};let initial=data.find")
    (OUT/'peel-compression.html').write_text(page,'utf-8')

if __name__=='__main__':main()
