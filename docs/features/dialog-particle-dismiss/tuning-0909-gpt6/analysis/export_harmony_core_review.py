"""逐一验证十二段参考，输出流式编码视频、关键帧与轻量状态报告。"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import time
import html

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT),str(ROOT/'analysis')]
from desktop_inputs import REFS, META, make_renderer, animation_inputs
from harmony_core import HarmonyCoreRenderer, DEFAULT
from match_refs import RefFrames

OUT = ROOT/'analysis/match-cache/takeover-20261004/harmony-core'
MODEL = 'harmony-core'
FPS = 120
STEPS = round(DEFAULT.stop_time*FPS)
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)


def identity():
    paths=['harmony_core.py','desktop_inputs.py','shaders/webgl-noise/noise3D.glsl',
           'analysis/export_harmony_core_review.py']
    if MODEL=='harmony-origins':paths+=['harmony_origins.py','v2_inputs.py','reference_initial_conditions.py']
    return {p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in paths}


def export(name,ctx,with_baseline=True):
    m=META[name]
    inputs=animation_inputs(name,'ref',m['seed'])
    core=HarmonyCoreRenderer(name,ctx=ctx) if MODEL=='harmony-core' else make_renderer(inputs,MODEL,ctx)
    baseline=make_renderer(animation_inputs(name,'ref',m['seed']),'baseline',ctx,quality=1) if with_baseline else None
    ref=RefFrames(name)
    w,h=m['frame'];x,y,x1,y1=m['rect']
    pad=round(min(x1-x,y1-y)*.23)
    box=[max(0,x-pad),max(0,y-pad),min(w,x1+pad),min(h,y1+pad)]
    cw,ch=box[2]-box[0],box[3]-box[1];cw-=cw%2;ch-=ch%2
    box[2],box[3]=box[0]+cw,box[1]+ch
    columns=3 if baseline else 2
    head=Image.new('RGB',(cw*columns,36),'#10151d')
    title='华为核心 · 显式起点与参考方向' if MODEL=='harmony-origins' else 'HarmonyOS 7 核心公式 · 绘制待核对'
    titles=['华为原片']+(['Android 同规则基准'] if baseline else [])+[title]
    for i,title in enumerate(titles):ImageDraw.Draw(head).text((i*cw+8,3),title,font=FONT,fill='white')
    ffmpeg=subprocess.Popen(['C:/ffmpeg/bin/ffmpeg.exe','-v','error','-y','-f','rawvideo','-pix_fmt','rgb24',
                             '-s',f'{cw*columns}x{ch+36}','-r',str(FPS),'-i','-','-an','-c:v','libx264',
                             '-preset','veryfast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',
                             str(OUT/f'{name}.mp4')],stdin=subprocess.PIPE)
    keys={round(p*STEPS) for p in [.18,.30,.42,.54,.66,.78,.90]}
    adjacent={round(p*STEPS) for p in np.linspace(.24,.76,17)}
    sheets=[];continuity=[];samples=[];peak=0;finite=True;valid=m['reference'].get('valid_progress',1.)
    try:
        for i in range(STEPS+1):
            p=i/STEPS
            render=core.render(p)
            diag=core.diagnostics();peak=max(peak,diag['active_count']);finite &= diag['finite']
            frames=[np.asarray(ref.nearest(p)[1])]
            if baseline:frames.append(baseline.render(p))
            frames.append(render)
            crops=[a[box[1]:box[3],box[0]:box[2],:3] for a in frames]
            joined=Image.fromarray(np.concatenate([np.asarray(head),np.concatenate(crops,axis=1)],axis=0))
            draw=ImageDraw.Draw(joined)
            draw.text((12,40),f'p={p:.2f}',font=FONT,fill='white',stroke_width=1,stroke_fill='black')
            if p>valid:
                draw.rectangle((0,36,cw,75),fill='#4f2923')
                draw.text((12,42),'原片在此之前已截断，后续不参与比较',font=FONT,fill='white')
            ffmpeg.stdin.write(np.asarray(joined).tobytes())
            if i in keys:
                small=joined.copy();small.thumbnail((1350,650));sheets.append(small)
                samples.append(dict(progress=p,**diag))
            if i in adjacent:
                # 连续性图只比较参考与核心，保留更多相邻时刻。
                pair=Image.fromarray(np.concatenate([crops[0],crops[-1]],axis=1))
                pair.thumbnail((800,400));continuity.append((p,pair))
            if i==round(.42*STEPS):joined.save(OUT/f'{name}-poster.jpg',quality=94)
            if i==STEPS//2:
                np.save(OUT/f'{name}-core-mid.npy',render)
                parity_progress=p
        assert finite,name+' 出现非有限粒子状态'
    finally:
        ffmpeg.stdin.close();code=ffmpeg.wait()
        core.close()
        if baseline:baseline.close()
    if code:raise RuntimeError(f'编码失败：{name}')
    canvas=Image.new('RGB',(max(s.width for s in sheets),sum(s.height for s in sheets)),'#10151d')
    y=0
    for s in sheets:canvas.paste(s,(0,y));y+=s.height
    canvas.save(OUT/f'{name}-sheet.jpg',quality=94)
    canvas=Image.new('RGB',(max(s.width for _,s in continuity),sum(s.height+30 for _,s in continuity)),'#10151d')
    y=0
    for p,s in continuity:
        ImageDraw.Draw(canvas).text((12,y),f'p={p:.3f}　华为原片 / 核心公式',font=FONT,fill='white')
        canvas.paste(s,(0,y+30));y+=s.height+30
    canvas.save(OUT/f'{name}-continuity.jpg',quality=93)
    return dict(name=name,title=m.get('title',name),frames=STEPS+1,fps=FPS,duration=STEPS/FPS,
                model=core.describe(),inputs=inputs,peak_active=peak,finite=finite,samples=samples,crop=box,
                valid_progress=valid,baseline=with_baseline,parity_progress=parity_progress,
                inspection='待逐段目视检查')


def page(report):
    options=''.join(f'<option value="{r["name"]}">{html.escape(r["title"])}</option>' for r in report['scenes'])
    return '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HarmonyOS 7 核心公式 · 十二段验证</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#10151d;color:#e6edf5;font:14px/1.75 "Microsoft YaHei",sans-serif}main{max-width:1500px;margin:auto;padding:24px}h1{font-size:22px;margin:0}p{color:#a8b8cb}nav{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin:14px 0}select,button{font:inherit;background:#243349;color:#e6edf5;border:1px solid #40546c;border-radius:6px;padding:6px 12px}a{color:#8ac4ff}video{display:block;width:100%;max-height:68vh;background:#080b10}input{flex:1;min-width:140px}details img{max-width:100%;display:block;margin:16px auto}.note{padding:12px 16px;background:#1c2634;border-radius:8px}.inspection{color:#f2c98e}summary{cursor:pointer}output{min-width:42px}</style>
<main><h1>HarmonyOS 7 核心公式 · 十二段验证</h1>
<p>左：华为参考　中：Android 同规则基准　右：从镜像取得的核心公式复刻</p>
<p class="note">已复刻消蚀、索引随机、风、三维噪声、寿命和尺寸变化。缺失的原生粒子绘制后端以透明圆片替代；录像物理像素及原始随机输入未知。当前是核心验证版，尚未达到视觉一致。</p>
<nav><label>序列 <select id="scene">'''+options+'''</select></label><label>速度 <select id="speed"><option value="1">1×</option><option value="0.5" selected>0.5×</option><option value="0.25">0.25×</option></select></label><button id="play">播放 / 暂停</button><a href="/latest.html">返回实时预览 ↗</a><a href="report.json">验证数据</a></nav>
<video id="video" controls muted playsinline preload="auto"></video><nav><button id="prev">前一帧</button><button id="next">后一帧</button><input id="progress" type="range" min="0" max="140" step="1" value="0" aria-label="动画进度"><output id="time">0.00</output></nav>
<p id="info"></p><p id="inspection" class="inspection"></p><p id="limit"></p>
<details><summary>关键帧：七个阶段，三列对照</summary><img id="sheet" alt="关键帧对照"></details>
<details><summary>连续性：十七个相邻时刻，参考与核心公式对照</summary><img id="continuity" alt="连续性对照"></details></main>
<script>const report='''+json.dumps(report,ensure_ascii=False)+''';const $=id=>document.getElementById(id),v=$('video');let row;
function update(){const p=Math.min(1,v.currentTime/row.duration);$('progress').value=Math.round(p*(row.frames-1));$('time').textContent=p.toFixed(2);$('limit').textContent=p>row.valid_progress?'原片已截断：左栏后续冻结画面不能用于判断相似度。':(row.valid_progress<1?'原片仅录到 '+row.valid_progress.toFixed(2)+' 进度。':'');}
function select(){row=report.scenes.find(r=>r.name==$('scene').value);v.pause();v.src=row.name+'.mp4';v.poster=row.name+'-poster.jpg';v.load();v.playbackRate=Number($('speed').value);$('progress').max=row.frames-1;$('progress').value=0;$('time').textContent='0.00';$('sheet').src=row.name+'-sheet.jpg';$('continuity').src=row.name+'-continuity.jpg';$('info').textContent='候选粒子 '+row.model.config.count.toLocaleString()+' · 峰值活动粒子 '+row.peak_active.toLocaleString()+' · 共同默认参数 · 种子 '+row.model.seed;$('inspection').textContent=row.inspection;update();}
function seek(t){v.pause();v.currentTime=Math.min(row.duration,Math.max(0,t));}
$('scene').onchange=select;$('speed').onchange=()=>v.playbackRate=Number($('speed').value);$('play').onclick=()=>v.paused?v.play():v.pause();$('progress').oninput=()=>seek(Number($('progress').value)/(row.frames-1)*row.duration);$('prev').onclick=()=>seek(v.currentTime-1/row.fps);$('next').onclick=()=>seek(v.currentTime+1/row.fps);v.ontimeupdate=update;select();</script></html>'''


def main():
    global MODEL,OUT
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenes',nargs='+',default=REFS)
    parser.add_argument('--no-baseline',action='store_true')
    parser.add_argument('--page-only',action='store_true')
    parser.add_argument('--model',choices=['harmony-core','harmony-origins'],default='harmony-core')
    args=parser.parse_args()
    MODEL=args.model
    OUT=ROOT/'analysis/match-cache/takeover-20261004'/MODEL
    OUT.mkdir(parents=True,exist_ok=True)
    if args.page_only:
        report=json.loads((OUT/'report.json').read_text('utf-8'))
    else:
        report=dict(identity=identity(),scenes=[],model=MODEL,status='核心公式验证；栅格化及录像输入仍待核对')
        ctx=moderngl.create_standalone_context(require=430)
        report['renderer']=ctx.info['GL_RENDERER']
        try:
            for name in args.scenes:
                begin=time.perf_counter();row=export(name,ctx,not args.no_baseline)
                report['scenes'].append(row)
                (OUT/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
                print(name,'活动峰值',row['peak_active'],'耗时',round(time.perf_counter()-begin,2),flush=True)
        finally:ctx.release()
    content=page(report)
    if MODEL=='harmony-origins':
        content=content.replace('HarmonyOS 7 核心公式 · 十二段验证','华为核心 · 起点与方向核对')
        content=content.replace('左：华为参考　中：Android 同规则基准　右：从镜像取得的核心公式复刻',
                                '左：华为参考　右：显式观测起点与参考方向，保留华为粒子运动核心')
        content=content.replace('已复刻消蚀、索引随机、风、三维噪声、寿命和尺寸变化。缺失的原生粒子绘制后端以透明圆片替代；录像物理像素及原始随机输入未知。当前是核心验证版，尚未达到视觉一致。',
                                '已将各段的起点数量、位置、启动时序和方向接入同一模型。起点传播为共同扩展规则，粒子运动与寿命保留华为公式。当前先核对初始条件；启动时序、后续卷曲和材质仍未通过视觉验收。')
        content=content.replace('关键帧：七个阶段，三列对照','关键帧：七个阶段，两列对照')
        content=content.replace(' · 共同默认参数 · 种子 ',' · 观测初始条件 · 种子 ')
    (OUT/'index.html').write_text(content,'utf-8')


if __name__=='__main__':main()
