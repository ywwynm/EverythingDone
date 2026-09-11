"""本轮参考、触点距离和局部释放的集中验收页。"""
from pathlib import Path
import json,shutil
HERE=Path(__file__).resolve().parents[1]
items=[('ironman-up-reference-compare-phase','新增向上参考 · 同进度'),('ironman-up-reference-compare-versions','新增向上参考 · 上一版对照')]
items += [(n+'-touch-distances',t+' · 双方向三距离') for n,t in [('ironman','钢铁侠'),('attachment','添加附件'),('color','调整颜色')]]
items += [(n+'-eight-directions',t+' · 八方向') for n,t in [('ironman','钢铁侠'),('attachment','添加附件'),('color','调整颜色')]]
items += [(n+'-compare-versions',t+' · 上一版对照') for n,t in [('ironman','钢铁侠'),('thanos','灭霸'),('kobe','科比'),('attachment','添加附件'),('color','调整颜色')]]
html='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>真实触点与局部释放 · 验收</title>
<style>:root{color-scheme:dark}body{max-width:1500px;margin:auto;padding:22px;background:#0e141e;color:#e8eff8;font:16px/1.6 "Microsoft YaHei",sans-serif}h1{font-size:27px;margin:0}p{color:#afbed0;margin:10px 0}video{display:block;width:100%;max-height:71vh;background:#070b11}button,a{display:inline-block;background:#243348;color:#edf3fb;border:1px solid #465b73;border-radius:5px;padding:7px 11px;margin:4px 5px 4px 0;cursor:pointer;font:inherit;text-decoration:none}button[aria-pressed=true]{border-color:#6ee1d6;background:#254b51}#status{color:#aabed2;margin-left:10px}h2{font-size:19px;margin:14px 0 8px}#note{font-size:14px}</style>
<h1>真实触点输运与局部释放</h1><p>较远触点直接增加粒子的位移和速度，动画仍为一秒。释放从多个错开的局部区域扩展；方向不再把整个前沿旋转成贯穿宽高的轮廓。</p>
<h2 id="title"></h2><video id="video" controls muted loop playsinline preload="metadata"></video>
<div id="phases"></div><div id="speeds"></div><div id="choices"></div><p id="note"></p><a id="download" download>保存此视频</a><a href="index.html">全部视频</a><span id="status"></span>
<script>const items=__ITEMS__;const v=document.querySelector('#video');let current=0,rate=.5;
function choose(i,play=false,p=0){current=i;const file=items[i][0]+'-'+rate+'x.mp4';v.src=file+'?targeted-release-final';document.querySelector('#title').textContent=items[i][1];document.querySelector('#download').href=file;history.replaceState(null,'','#'+file);document.querySelectorAll('#choices button').forEach((b,j)=>b.setAttribute('aria-pressed',j===i));document.querySelectorAll('#speeds button').forEach((b,j)=>b.setAttribute('aria-pressed',[1,.5][j]===rate));document.querySelector('#note').textContent=items[i][0].includes('touch-distances')?'上排左上、下排向上；近、中、远是控件边缘外短边的 0.15、0.65、1.5 倍。每排使用同一比例、素材、种子和进度，圆圈标出触点。距离对比扩大了观察范围，深色空白是原截图外区域，方便看到远点粒子的完整位移；没有改变运动或时长。':items[i][0].includes('up-reference')?'新参考已去除手持相机运动并透视对齐，完整前景由两个时刻合成。参考区间映射为一秒用于形态比较；原片速度对照在全部视频中。展示使用固定种子 2，其它素材使用同一套规则。':'同一素材、种子与进度；上一版使用改动前冻结帧。多起点、触点输运、卷动与材质规则用于所有素材。';v.onloadedmetadata=()=>{v.currentTime=(.35+p)/rate;if(play)v.play().catch(()=>{});};}
for(const [i,item] of items.entries()){const b=document.createElement('button');b.textContent=item[1];b.onclick=()=>choose(i,true);document.querySelector('#choices').append(b);}
for(const t of [.16,.32,.48,.64,.80]){const b=document.createElement('button');b.textContent='进度 '+t.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+t)/rate;};document.querySelector('#phases').append(b);}
for(const speed of [1,.5]){const b=document.createElement('button');b.textContent=speed+' 倍速文件';b.onclick=()=>{const p=Math.max(0,Math.min(1,v.currentTime*rate-.35));rate=speed;choose(current,!v.paused,p);};document.querySelector('#speeds').append(b);}
for(const [label,delta] of [['上一帧',-1],['下一帧',1]]){const b=document.createElement('button');b.textContent=label;b.onclick=()=>{v.pause();v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+delta/60));};document.querySelector('#phases').append(b);}
v.ontimeupdate=()=>document.querySelector('#status').textContent='动画进度 '+Math.max(0,Math.min(1,v.currentTime*rate-.35)).toFixed(3);
const hash=decodeURIComponent(location.hash.slice(1));if(hash.endsWith('-1x.mp4'))rate=1;choose(Math.max(0,items.findIndex(item=>hash===item[0]+'-'+rate+'x.mp4')));</script></html>'''
(HERE/'videos/targeted-release.html').write_text(html.replace('__ITEMS__',json.dumps(items,ensure_ascii=False)),'utf-8')
old=HERE/'videos/flow-shaping.html';archive=HERE/'archive/before-targeted-release/flow-shaping.html'
if old.exists() and not archive.exists():shutil.copy2(old,archive)
old.write_text('<!doctype html><meta charset="utf-8"><title>前往当前验收页</title><a href="targeted-release.html">查看当前验收页</a><script>location.replace("targeted-release.html"+location.hash)</script>','utf-8')
print('本轮重点验收页已生成')
