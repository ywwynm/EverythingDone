"""生成无需打包的本地视频审阅页。"""
import json
from pathlib import Path
HERE=Path(__file__).resolve().parent

HTML=r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>粒子消散 · 共同释放与输运</title>
<style>
:root{color-scheme:dark;--bg:#0e141e;--card:#151e2b;--line:#2d3a4d;--text:#e8eff8;--muted:#a7b8ce;--accent:#63d7cf}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 "Microsoft YaHei",system-ui,sans-serif}
header{max-width:1560px;margin:auto;padding:26px 32px 20px;border-bottom:1px solid var(--line)}.eyebrow{font:12px/1.5 ui-monospace,monospace;letter-spacing:2px;color:var(--accent)}h1{font-size:28px;font-weight:600;margin:6px 0}p{margin:6px 0;color:var(--muted)}main{max-width:1560px;margin:auto;padding:22px 32px;display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:24px}
.player{min-width:0}.topline{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}h2{font-size:19px;font-weight:500;margin:0}.pill{font-size:12px;color:var(--accent);white-space:nowrap;border:1px solid #3a655f;padding:3px 10px;border-radius:20px}video{display:block;width:100%;max-height:70vh;background:#090d14;border:1px solid var(--line);border-radius:10px}.toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:14px 0 10px}button,select,a.button{font:inherit;color:var(--text);background:#213047;border:1px solid #3c4f6b;border-radius:6px;padding:7px 12px;text-decoration:none;cursor:pointer}button:hover,a.button:hover{background:#2b405a}button:focus-visible,select:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:3px}button.primary{background:#2b635f;border-color:#438781}#clock{margin-left:auto;font:13px ui-monospace,monospace;color:var(--muted)}.note{min-height:54px;font-size:13px}.fine{font-size:12px;color:var(--muted);padding-top:10px;border-top:1px solid var(--line)}aside{min-width:0}.filters{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:13px}.filters label:first-child{grid-column:1/-1}label{font-size:12px;color:var(--muted)}select{display:block;width:100%;font-size:13px;margin-top:5px;padding:8px}.list{max-height:72vh;overflow:auto;padding-right:4px}.item{width:100%;text-align:left;display:block;padding:10px 12px;margin-bottom:7px;background:var(--card);border-color:var(--line);font-size:13px}.item small{display:block;color:var(--muted);margin-top:2px}.item[aria-current=true]{border-color:var(--accent);background:#1d343e}.item[aria-current=true] small{color:#b5d9d9}.count{font-size:12px;color:var(--muted);margin:0 0 10px}.empty{padding:20px;color:var(--muted)}
@media(max-width:900px){main{grid-template-columns:1fr;padding:18px}header{padding:20px 18px}video{max-height:65vh}.list{max-height:330px}.filters{grid-template-columns:2fr 2fr 1fr}.filters label:first-child{grid-column:auto}.topline{align-items:flex-start}h2{font-size:16px}}
</style></head><body>
<header><div class="eyebrow">EVERYTHINGDONE / SHARED RULES</div><h1>粒子消散 · 共同释放与输运</h1><p>沿用户认可的观测运动效果继续优化，照片与弹窗共用释放和运动规则。可比较华为参考、认可的控制组及本轮效果；另外保留此前发布版对照。新增灰度照片和横向弹窗作为冻结后的留出验证。</p></header>
<main><section class="player" aria-label="视频审阅"><div class="topline"><h2 id="title"></h2><span class="pill" id="badge"></span></div>
<video id="player" controls loop muted playsinline preload="metadata"></video>
<div class="toolbar"><button class="primary" id="toggle">播放 / 暂停</button><button id="back">上一帧</button><button id="next">下一帧</button><button id="replay">从头播放</button><button id="full">全屏</button><span id="clock">0.000 / 0.000 秒</span></div>
<p class="note" id="note"></p><div class="toolbar"><a class="button" id="download" download>保存此视频</a><label><input type="checkbox" checked id="loop"> 自动重播</label></div>
<p class="fine">模型动画基准为 1.00 秒，另有开头与结尾停留。0.5 倍速视频已经按慢放导出，播放器保持 1 倍播放即可。左右方向键逐帧，空格暂停。截图遮挡区域的重建范围与时间口径见各视频标注。</p>
</section><aside aria-label="视频目录"><div class="filters"><label>场景<select id="scene"><option value="all">所有场景</option></select></label><label>类型<select id="kind"><option value="all">所有类型</option></select></label><label>速度<select id="rate"><option value="all">全部</option><option value="1">1 倍</option><option value="0.5">0.5 倍</option></select></label></div><p class="count" id="count"></p><div class="list" id="list"></div></aside></main>
<script>
const manifest=__MANIFEST__;
const kinds={'compare-control':'认可控制组对照','compare-versions':'此前模型对比','compare-phase':'华为对照 · 进度对齐','compare-file':'华为对照 · 文件时间','compare-source':'源素材对照','animation':'独立动画','eight-directions':'八方向同屏'};
const order=['ironman','thanos','kobe','language','color','attachment','attachment-image',...new Set(manifest.videos.map(v=>v.scene).filter(s=>s.startsWith('holdout-')))];
const kindOrder=['compare-control','compare-versions','compare-phase','compare-source','animation','eight-directions','compare-file'];
const files=manifest.videos.sort((a,b)=>order.indexOf(a.scene)-order.indexOf(b.scene)||kindOrder.indexOf(a.kind)-kindOrder.indexOf(b.kind)||b.rate-a.rate);
const $=id=>document.getElementById(id);const video=$('player');let current=null;
for(const name of order){const v=files.find(v=>v.scene===name);if(v)$('scene').add(new Option(v.title,name));}
for(const k of kindOrder)$('kind').add(new Option(kinds[k],k));
function filtered(){return files.filter(v=>($('scene').value==='all'||v.scene===$('scene').value)&&($('kind').value==='all'||v.kind===$('kind').value)&&($('rate').value==='all'||v.rate===Number($('rate').value)));}
function renderList(){const list=$('list');list.replaceChildren();const shown=filtered();$('count').textContent=`${shown.length} / ${files.length} 个视频 · H.264 · 60 帧/秒`;for(const v of shown){const b=document.createElement('button');b.className='item';b.setAttribute('aria-current',String(current&&current.file===v.file));b.textContent=`${v.title} · ${v.rate} 倍`;const s=document.createElement('small');s.textContent=`${kinds[v.kind]} · ${v.duration.toFixed(2)} 秒`;b.append(s);b.onclick=()=>select(v,true);list.append(b);}if(!shown.length){const p=document.createElement('p');p.className='empty';p.textContent='当前筛选没有对应视频。';list.append(p);}}
function select(v,play=false){current=v;video.src=v.file+'?v='+manifest.code_hash;video.poster=v.poster+'?v='+manifest.code_hash;video.playbackRate=1;$('title').textContent=`${v.title}｜${kinds[v.kind]}`;$('badge').textContent=`${v.rate} 倍 · 60 帧/秒`;$('download').href=v.file;history.replaceState(null,'','#'+v.file);const notes={'compare-control':'三栏依次为华为参考、用户认可的观测运动控制组、本轮共同模型。用于检查提炼之后是否保留了轮廓与连续运动；中间列是冻结的对照产物。','compare-versions':'按相同进度比较此前发布版与本轮共同模型；两版都没有运行时人物专用配置。照片另有华为参考列（映射为 1 秒）。','compare-phase':'左右按相同进度比较。华为参考区间重新映射到 1 秒；这不是参考录屏的文件原速。','compare-file':'左侧保留参考文件时间，右侧按 1 秒模型时长播放后停留。录屏可能已经慢放，不能据此推断实机动画时长。','compare-source':'左侧为静态源素材，右侧为统一规则生成的动画；构造样本与真实截图由场景名称区分。此列不提供同内容的华为运动参考。','animation':'完整前景和对应背景。未释放的内容保持原位，随后逐渐成为独立颗粒。','eight-directions':'同一画面内同时播放八个方向；模型本身支持连续任意角度。可在桌面预览程序中点击触点测试。'};$('note').textContent=notes[v.kind]+(v.scene==='thanos'?' 灭霸原片在转黑前截止，最后仍有轻微粒子。':'');renderList();if(play)video.play().catch(()=>{});}
for(const id of ['scene','kind','rate'])$(id).onchange=renderList;
const toggle=()=>{if(video.paused)video.play().catch(()=>{});else video.pause();};
const step=delta=>{video.pause();video.currentTime=Math.max(0,Math.min(video.duration||0,video.currentTime+delta/60));};
$('toggle').onclick=toggle;$('back').onclick=()=>step(-1);$('next').onclick=()=>step(1);$('replay').onclick=()=>{video.currentTime=0;video.play().catch(()=>{});};$('full').onclick=()=>video.requestFullscreen();$('loop').onchange=e=>video.loop=e.target.checked;
const updateClock=()=>{$('clock').textContent=`${video.currentTime.toFixed(3)} / ${(video.duration||0).toFixed(3)} 秒`;};
video.ontimeupdate=updateClock;video.onloadedmetadata=updateClock;video.ondurationchange=updateClock;
document.addEventListener('keydown',e=>{if(['SELECT','INPUT','BUTTON'].includes(document.activeElement.tagName))return;if(e.key==='ArrowRight'){e.preventDefault();step(1);}else if(e.key==='ArrowLeft'){e.preventDefault();step(-1);}else if(e.code==='Space'){e.preventDefault();toggle();}});
select(files.find(v=>v.file===decodeURIComponent(location.hash.slice(1)))||files.find(v=>v.file==='ironman-compare-control-0.5x.mp4')||files[0]);
</script></body></html>'''

def main():
    data=json.loads((HERE/'videos/manifest.json').read_text(encoding='utf-8'))
    for video in data['videos']:
        if video['scene'] in {'holdout-coffee','holdout-colored-panel'}:
            video['title']=video['title'].replace('本轮留出','回归验证')
    (HERE/'videos/index.html').write_text(HTML.replace('__MANIFEST__',json.dumps(data,ensure_ascii=False)),encoding='utf-8')
    print('视频审阅页已生成：',len(data['videos']),'个视频')
if __name__=='__main__':main()
