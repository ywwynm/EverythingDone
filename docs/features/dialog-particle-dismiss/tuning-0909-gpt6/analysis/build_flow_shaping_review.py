"""把本轮新增验收集中到一页，视频仍平铺在原目录。"""
from pathlib import Path
import json
HERE=Path(__file__).resolve().parents[1]
items=[(f'{name}-touch-distances-0.5x.mp4',title+' · 双方向三距离') for name,title in [('ironman','钢铁侠'),('attachment','添加附件'),('color','调整颜色')]]
items += [('color-eight-directions-0.5x.mp4','调整颜色 · 八方向')]
items += [(f'{name}-compare-versions-0.5x.mp4',title+' · 上一版与本轮') for name,title in [('ironman','钢铁侠'),('attachment','添加附件'),('color','调整颜色')]]
html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>粒子卷动与触点距离 · 验收</title>
<style>:root{color-scheme:dark}body{max-width:1500px;margin:auto;padding:24px;background:#0e141e;color:#e8eff8;font:16px/1.6 "Microsoft YaHei",sans-serif}h1{font-size:27px;margin:0}p{color:#afbed0}video{display:block;width:100%;max-height:72vh;background:#070b11}button,a{display:inline-block;background:#243348;color:#edf3fb;border:1px solid #465b73;border-radius:5px;padding:8px 12px;margin:5px 6px 5px 0;cursor:pointer;font:inherit;text-decoration:none}button[aria-pressed=true]{border-color:#6ee1d6}#status{color:#aabed2;margin-left:10px}h2{font-size:19px}</style>
<h1>卷动形变、长弹窗与触点距离</h1><p>近、中、远从控件边缘沿消逝方向向外量距，分别为短边的 0.15、0.65、1.5 倍。六格上排向左上，下排向上，同一素材固定种子。返回键对应左上、中距离。</p>
<h2 id="title"></h2><video id="video" controls muted loop playsinline preload="metadata"></video>
<div id="phases"></div><div id="choices"></div><a id="download" download>保存此视频</a><a href="index.html">全部原速、半速视频</a><span id="status"></span>
<p>“上一版与本轮”使用相同素材、种子和进度；照片对比另有华为参考列。新增局部卷动作用于补充流动区域，长轴采用受控尺度和重叠区域；视频中的触点是流向与延伸的输入，不是粒子的停止点。</p>
<script>const items=__ITEMS__;const v=document.querySelector('#video');let current=0;
function choose(i){current=i;v.src=items[i][0]+'?flow-shaping';document.querySelector('#title').textContent=items[i][1];document.querySelector('#download').href=items[i][0];history.replaceState(null,'','#'+items[i][0]);document.querySelectorAll('#choices button').forEach((b,j)=>b.setAttribute('aria-pressed',j===i));}
for(const [i,item] of items.entries()){const b=document.createElement('button');b.textContent=item[1];b.onclick=()=>{choose(i);v.play();};document.querySelector('#choices').append(b);}
for(const t of [.35,.48,.61,.74,.87]){const b=document.createElement('button');b.textContent='进度 '+t.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+t)/.5;};document.querySelector('#phases').append(b);}
for(const [label,delta] of [['上一帧',-1],['下一帧',1]]){const b=document.createElement('button');b.textContent=label;b.onclick=()=>{v.pause();v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+delta/60));};document.querySelector('#phases').append(b);}
v.ontimeupdate=()=>document.querySelector('#status').textContent='动画进度 '+Math.max(0,Math.min(1,v.currentTime*.5-.35)).toFixed(3);
choose(Math.max(0,items.findIndex(item=>item[0]===decodeURIComponent(location.hash.slice(1)))));</script></html>'''
(HERE/'videos/flow-shaping.html').write_text(html.replace('__ITEMS__',json.dumps(items,ensure_ascii=False)),encoding='utf-8')
print('本轮重点验收页已生成')
