"""生成本轮审阅页，距离三栏可以保持同一进度、同一位置即时切换。"""
from pathlib import Path
import json,sys,datetime
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import code_hash

GROUPS=[('弧边与流动',[('ironman-rim-detail','钢铁侠 · 弧边放大'),('ironman-curve-split','钢铁侠 · 整体与局部')]),
('触点远近',[(f'{n}-distance-{d}',f'{t} · {label} · 近中远') for n,t in [('ironman','钢铁侠'),('attachment','添加附件'),('color','调整颜色')] for d,label in [('upper-left','左上'),('up','向上')]]),
('其它验收',[(n+'-family-reference',t) for n,t in [('ironman','原版钢铁侠'),('ironman-up-reference','向上钢铁侠'),('thanos','灭霸'),('kobe','科比')]]+
[(n+'-eight-directions',t+' · 八方向') for n,t in [('ironman','钢铁侠'),('attachment','添加附件'),('color','调整颜色')]])]

HTML='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>弧边展开与触点远近</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:auto;padding:20px;max-width:1640px;background:#0e141e;color:#e8eff8;font:16px/1.65 "Microsoft YaHei",sans-serif}h1{font-size:28px;margin:0}h2{font-size:20px;margin:15px 0 8px}h3{font-size:15px;margin:12px 0 3px;font-weight:500;color:#aec1d5}p{color:#aebed0;margin:8px 0}.review{background:#0e141e}#stage{overflow:hidden;position:relative;margin:auto;background:#070b11}video{display:block;width:100%;max-width:none}button,a{display:inline-block;border:1px solid #485e77;background:#223348;color:#eef4fb;border-radius:5px;padding:7px 12px;margin:4px 5px 4px 0;cursor:pointer;font:inherit;text-decoration:none}button[aria-pressed=true]{background:#244e53;border-color:#70e2d6}button:focus-visible,a:focus-visible{outline:2px solid #70e2d6}#distance-mode[hidden]{display:none}#status{margin-left:10px;color:#b1c4d6}#error{color:#ffb1ab}#stamp{font-size:13px}#note{font-size:14px}.controls{margin-top:8px}@media(max-width:600px){body{padding:12px}h1{font-size:24px}button,a{padding:6px 9px;font-size:14px}}</style>
<h1>弧边展开与触点远近</h1><p>先看横向弧边的弯曲和连续流动，再比较近、中、远的实际移动。距离视频可三栏同时播放，也可在同一位置即时切换。</p><p id="stamp">__STAMP__</p>
<section class="review"><h2 id="title"></h2><div id="stage"><video id="video" muted loop playsinline preload="metadata"></video></div>
<div class="controls"><button id="play">播放</button><span id="phases"></span><span id="status"></span></div><div id="distance-mode" hidden></div><div id="rates"></div></section>
<p id="error" role="alert"></p><p id="note"></p><a id="download" download>保存此视频</a><a href="flow-family.html">共同模型与其它组合</a><a href="index.html">全部视频</a><div id="choices"></div>
<script>const groups=__GROUPS__,code='__HASH__',items=groups.flatMap(g=>g[1]);const v=document.querySelector('#video'),stage=document.querySelector('#stage');let selected=0,rate=.5,column=-1;
function progress(){return Math.max(0,Math.min(1,v.currentTime*rate-.35));}
function layout(){if(!v.videoWidth)return;const single=column>=0,ratio=v.videoWidth/v.videoHeight/(single?3:1),width=Math.min(document.querySelector('.review').clientWidth,Math.max(220,window.innerHeight-270)*ratio);stage.style.width=width+'px';stage.style.height=width/ratio+'px';v.style.width=single?'300%':'100%';v.style.transform=single?'translateX('+(-column*100/3)+'%)':'none';}
function mode(n,scroll=true){column=n;document.querySelectorAll('#distance-mode button').forEach((b,i)=>b.setAttribute('aria-pressed',i-1===n));layout();if(scroll)document.querySelector('.review').scrollIntoView({block:'start'});}
function choose(i,autoplay=false,p=.55){selected=i;const [prefix,title]=items[i],distance=prefix.includes('-distance-'),file=prefix+'-'+rate+'x.mp4';mode(-1,false);document.querySelector('#error').textContent='';document.querySelector('#title').textContent=title;document.querySelector('#distance-mode').hidden=!distance;document.querySelector('#download').href=file+'?v='+code;document.querySelector('#note').textContent=distance?'圆圈均为手机屏幕内、弹窗外的实际触点。三栏保持同一素材、角度、种子、尺寸和动画时长；近中远只改变运动输入。点“近 / 中 / 远”可在相同位置、相同进度切换，便于判断差异。':prefix.includes('rim-detail')?'三栏依次为华为参考、本轮调整前、本轮共同模型。固定同一位置放大；观察横向弧边向外展开以及下方拖尾。模型没有额外绘制亮边。':prefix.includes('curve-split')?'上排看整体、下排看同一位置放大。三栏使用同一相位；参考区间按一秒归一化，不表示华为原文件时长。':'全部使用共同模型。参考示例的方向和种子用于展示形态，原片触点无法确认；不是按图片名称使用独立参数。';document.querySelectorAll('#choices button').forEach((b,j)=>b.setAttribute('aria-pressed',j===i));document.querySelectorAll('#rates button').forEach((b,j)=>b.setAttribute('aria-pressed',[1,.5][j]===rate));v.onloadedmetadata=()=>{layout();document.querySelector('.review').scrollIntoView({block:'start'});v.currentTime=(.35+p)/rate;if(autoplay)v.play().catch(()=>{});};v.src=file+'?v='+code;history.replaceState(null,'','#'+file);}
let j=0;for(const [name,entries] of groups){const title=document.createElement('h3');title.textContent=name;document.querySelector('#choices').append(title);for(const entry of entries){const i=j++,b=document.createElement('button');b.textContent=entry[1];b.onclick=()=>choose(i,true,0);document.querySelector('#choices').append(b);}}
for(const t of [.35,.43,.55,.63,.72]){const b=document.createElement('button');b.textContent='进度 '+t.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+t)/rate;};document.querySelector('#phases').append(b);}
for(const [name,delta] of [['上一帧',-1],['下一帧',1]]){const b=document.createElement('button');b.textContent=name;b.onclick=()=>{v.pause();v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+delta/60));};document.querySelector('#phases').append(b);}
for(const [i,name] of ['三栏对照','近','中','远'].entries()){const b=document.createElement('button');b.textContent=name;b.onclick=()=>mode(i-1);document.querySelector('#distance-mode').append(b);}
for(const speed of [1,.5]){const b=document.createElement('button');b.textContent=speed+' 倍速文件';b.onclick=()=>{const p=progress(),playing=!v.paused;rate=speed;choose(selected,playing,p);};document.querySelector('#rates').append(b);}
document.querySelector('#play').onclick=()=>v.paused?v.play():v.pause();v.onplay=()=>document.querySelector('#play').textContent='暂停';v.onpause=()=>document.querySelector('#play').textContent='播放';v.ontimeupdate=()=>document.querySelector('#status').textContent='当前进度 '+progress().toFixed(3);v.onerror=()=>document.querySelector('#error').textContent='视频未能载入，请刷新页面重试。';window.addEventListener('resize',layout);
function openHash(){const hash=decodeURIComponent(location.hash.slice(1));rate=hash.endsWith('-1x.mp4')?1:.5;choose(Math.max(0,items.findIndex(([prefix])=>hash===prefix+'-'+rate+'x.mp4')));}window.addEventListener('hashchange',openHash);openHash();
</script></html>'''

def main():
    for _,entries in GROUPS:
        for name,_ in entries:
            for rate in ['1','0.5']:assert (HERE/f'videos/{name}-{rate}x.mp4').is_file()
    page=HTML.replace('__GROUPS__',json.dumps(GROUPS,ensure_ascii=False)).replace('__HASH__',code_hash())
    page=page.replace('__STAMP__','更新于 '+datetime.datetime.now().strftime('%Y-%m-%d %H:%M')+' · 1 倍与 0.5 倍文件独立导出')
    (HERE/'videos/rim-flow.html').write_text(page,'utf-8');print('弧边与近远审阅页已生成')

if __name__=='__main__':main()
