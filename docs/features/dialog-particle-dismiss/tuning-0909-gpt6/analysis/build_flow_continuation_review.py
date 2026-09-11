"""重点片段的真实前后对照；全部视频仍放在同一个产物目录。"""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parents[1]
ITEMS=[('color','调整颜色 · 画廊实际输入'),('attachment','添加附件 · 画廊实际输入'),
       ('ironman','钢铁侠 · 画廊实际输入'),('thanos','灭霸 · 画廊实际输入'),
       ('C225','钢铁侠 · 原标注与屏幕边缘'),('C226','添加附件 · 原标注与弯曲轮廓'),
       ('C005','钢铁侠 · 向左消散'),('C079','科比 · 向下消散'),('C107','更改语言 · 向上消散')]
HTML='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>粒子流动 · 重点前后对照</title>
<style>:root{color-scheme:dark}body{margin:24px;background:#0e141e;color:#e8eff8;font:16px/1.6 "Microsoft YaHei",sans-serif}main{max-width:1500px;margin:auto}h1{font-size:25px}p{color:#b3c2d6}video{width:100%;max-height:72vh;background:#080b10}button,a{display:inline-block;color:#e8eff8;background:#213047;border:1px solid #3c4f6b;border-radius:5px;padding:7px 12px;margin:5px;text-decoration:none;font:inherit;cursor:pointer}button[aria-current=true]{border-color:#63d7cf}nav{margin-top:12px}</style>
<main><h1>粒子流动 · 重点前后对照</h1><p>重新分析了全部 226 组既有标注及 7 组画廊输入；这里展示重点片段，原标注视频保留。前四组依次为：已发布原版、被否决的微小速度补偿、本次运动场延续；后五组为原版与本次的两列对照。均已导出为 0.5 倍速。</p>
<h2 id="name"></h2><video id="v" controls loop muted playsinline></video><nav id="list"></nav>
<button id="back">上一帧</button><button id="next">下一帧</button><button onclick="v.pause();v.currentTime=1.4">进度 0.35</button><button onclick="v.pause();v.currentTime=1.8">进度 0.55</button><button onclick="v.pause();v.currentTime=2.1">进度 0.70</button><button onclick="v.pause();v.currentTime=2.4">进度 0.85</button><a id="save" download>保存视频</a><a href="index.html">全部正式对照</a><p>重点观察：原控件边角是否继续迁移，尾段曲线是否继续变化，以及内部材料是否仍在流动。不要把保留的未释放面板、短小局部线条或截图里的静态背景算成停滞粒子。</p></main>
<script>const items=__ITEMS__,v=document.getElementById('v');function select(k){const x=items.find(x=>x[0]===k)||items[0];const src='flow-extension-'+x[0]+'-0.5x.mp4';v.src=src;document.getElementById('name').textContent=x[1];document.getElementById('save').href=src;history.replaceState(null,'','#'+x[0]);for(const b of document.querySelectorAll('nav button'))b.setAttribute('aria-current',b.dataset.key===x[0]);}for(const x of items){const b=document.createElement('button');b.textContent=x[1];b.dataset.key=x[0];b.onclick=()=>{select(x[0]);v.play()};document.getElementById('list').append(b)}document.getElementById('back').onclick=()=>{v.pause();v.currentTime=Math.max(0,v.currentTime-1/60)};document.getElementById('next').onclick=()=>{v.pause();v.currentTime=Math.min(v.duration,v.currentTime+1/60)};select(location.hash.slice(1));</script></html>'''
for key,_ in ITEMS:assert (HERE/f'videos/flow-extension-{key}-0.5x.mp4').is_file()
(HERE/'videos/flow-continuation.html').write_text(HTML.replace('__ITEMS__',json.dumps(ITEMS,ensure_ascii=False)),'utf-8')
print('重点对照页：9 个视频')
