"""完整过程验收：照片直接比较华为原参考，自有弹窗比较已复现缺陷。"""
from pathlib import Path
import sys,json,time,hashlib
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import encode,OUT,PRE,POST,BG,MUTED,label,footer,Reference,load_meta,code_hash
from probe_edge_support import OUT as ANALYSIS,BASE
import verify_frame_calibration as frozen

CASES=[('ironman',122,909602),('attachment',270,909602),('attachment',90,909602),('attachment',0,42),
 ('attachment',135,20260912),('color',45,17),('language',315,42),('attachment-image',45,20260912),
 ('holdout-compact-dialog',225,99),('thanos',None,None),('kobe',None,None),('ironman-up-reference',None,None)]

def main():
    frozen.BASE=BASE;Before=frozen.frozen_renderer();ctx=moderngl.create_standalone_context(require=430);items=[]
    for number,(scene,angle,seed) in enumerate(CASES,1):
        meta=load_meta(scene);angle=meta['direction'] if angle is None else angle;seed=meta['seed'] if seed is None else seed
        r=Renderer(scene,ctx=ctx,direction=angle,seed=seed)
        frames=np.stack([r.render(i/120) for i in range(121)]);r.close()
        np.save(ANALYSIS/f'acceptance-{number:02d}-{scene}.npy',frames)
        reference=Reference(meta) if meta.get('reference') else None
        if reference:old=None
        else:
            r=Before(scene,ctx=ctx,direction=angle,seed=seed);old=np.stack([r.render(i/120) for i in range(121)]);r.close()
        width=480;height=round(meta['frame'][1]/meta['frame'][0]*width);height+=height%2
        for rate in [1.,.5]:
            def frame(t):
                phase=float(np.clip(t-PRE,0,1));index=round(phase*120)
                pairs=[('华为原始参考' if reference else '此前错误复现',reference.at(phase) if reference else old[index]),('共同模型 · 当前分布约束',frames[index])]
                im=Image.new('RGB',(width*2,height+132),BG);d=ImageDraw.Draw(im)
                for col,(title,pixels) in enumerate(pairs):
                    label(d,(col*width+12,8),title,25);label(d,(col*width+12,43),f'{meta["title"]} · {angle:g}° · 种子 {seed}',18,MUTED)
                    im.paste(Image.fromarray(pixels).resize((width,height),Image.Resampling.LANCZOS),(col*width,80))
                footer(im,f'进度 {phase:.3f} · {rate:g} 倍速',phase);return np.asarray(im)
            filename=f'pressure-flow-{number:02d}-{scene}-{rate:g}x.mp4';item=encode(OUT/filename,PRE+1+POST,rate,frame)
            item.update(case=number,scene=scene,title=meta['title'],direction=angle,seed=seed,rate=rate);items.append(item)
        print('完整视频',number,scene,flush=True)
    ctx.release()
    data=dict(model_hash=model_fingerprint(),code_hash=code_hash(),videos=items)
    (OUT/'pressure-flow-videos.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    options=''.join(f'<option value="{x["case"]}">{x["title"]} · {x["direction"]:g}° · 种子 {x["seed"]}</option>' for x in items if x['rate']==1)
    page='''<!doctype html><meta charset="utf-8"><title>孤立细缕与原参考验收</title>
<style>body{margin:24px;background:#0e141e;color:#e8eff8;font:17px/1.65 system-ui}h1{font-size:28px}button,select{padding:10px;background:#223348;color:inherit;border:1px solid #52667f;border-radius:6px;margin:5px}video{display:block;max-width:100%;max-height:82vh;margin:16px auto;background:black}a{color:#79ddd6}.controls{display:flex;flex-wrap:wrap;align-items:center}</style>
<h1>孤立细缕与原参考验收</h1><p>照片序列直接对照华为原片；自有弹窗左侧保留此前缺陷，右侧为同一输入的新计算。重点检查独立窄流是否出现，以及正常卷边、翻折和完整运动。</p>
<div class="controls"><select id="cases">OPTIONS</select><button id="normal">1 倍速</button><button id="slow">0.5 倍速</button><button id="play">播放 / 暂停</button><button id="full">全屏</button><button id="prev">上一帧</button><button id="next">下一帧</button><a id="download" download>保存当前视频</a></div><div id="phases"></div><video id="player" controls playsinline loop preload="auto"></video>
<script>const data=DATA;let speed=.5;const v=document.querySelector('#player'),sel=document.querySelector('#cases');function show(){let x=data.find(x=>x.case==+sel.value&&x.rate==speed);v.src=x.file+'?v=HASH';document.querySelector('#download').href=x.file;location.hash=x.file;}sel.onchange=show;document.querySelector('#normal').onclick=()=>{speed=1;show()};document.querySelector('#slow').onclick=()=>{speed=.5;show()};document.querySelector('#play').onclick=()=>v.paused?v.play():v.pause();document.querySelector('#full').onclick=()=>v.requestFullscreen();document.querySelector('#prev').onclick=()=>{v.pause();v.currentTime=Math.max(0,v.currentTime-1/60)};document.querySelector('#next').onclick=()=>{v.pause();v.currentTime=Math.min(v.duration,v.currentTime+1/60)};for(const p of [.2,.3,.4,.5,.6,.7]){let b=document.createElement('button');b.textContent='进度 '+p.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+p)/speed};document.querySelector('#phases').append(b)}let initial=data.find(x=>x.file==decodeURIComponent(location.hash.slice(1)));if(initial){sel.value=initial.case;speed=initial.rate}show();</script>'''
    page=page.replace('OPTIONS',options).replace('DATA',json.dumps(items,ensure_ascii=False)).replace('HASH',data['model_hash'])
    (OUT/'pressure-flow.html').write_text(page,'utf-8')

if __name__=='__main__':main()
