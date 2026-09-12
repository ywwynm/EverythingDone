"""生成无需打包的本地视频审阅页。"""
import json
from pathlib import Path
HERE=Path(__file__).resolve().parent

HTML=r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>粒子消散 · 修正前沿衔接与局部流动</title>
<style>
:root{color-scheme:dark;--bg:#0e141e;--card:#151e2b;--line:#2d3a4d;--text:#e8eff8;--muted:#a7b8ce;--accent:#63d7cf}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 "Microsoft YaHei",system-ui,sans-serif}
header{max-width:1560px;margin:auto;padding:26px 32px 20px;border-bottom:1px solid var(--line)}.eyebrow{font:12px/1.5 ui-monospace,monospace;letter-spacing:2px;color:var(--accent)}h1{font-size:28px;font-weight:600;margin:6px 0}p{margin:6px 0;color:var(--muted)}main{max-width:1560px;margin:auto;padding:22px 32px;display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:24px}
.player{min-width:0}.topline{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}h2{font-size:19px;font-weight:500;margin:0}.pill{font-size:12px;color:var(--accent);white-space:nowrap;border:1px solid #3a655f;padding:3px 10px;border-radius:20px}video{display:block;width:100%;max-height:70vh;background:#090d14;border:1px solid var(--line);border-radius:10px}.toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:14px 0 10px}button,select,a.button{font:inherit;color:var(--text);background:#213047;border:1px solid #3c4f6b;border-radius:6px;padding:7px 12px;text-decoration:none;cursor:pointer}button:hover,a.button:hover{background:#2b405a}button:focus-visible,select:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:3px}button.primary{background:#2b635f;border-color:#438781}#clock{margin-left:auto;font:13px ui-monospace,monospace;color:var(--muted)}.note{min-height:54px;font-size:13px}.fine{font-size:12px;color:var(--muted);padding-top:10px;border-top:1px solid var(--line)}aside{min-width:0}.filters{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:13px}.filters label:first-child{grid-column:1/-1}label{font-size:12px;color:var(--muted)}select{display:block;width:100%;font-size:13px;margin-top:5px;padding:8px}.list{max-height:72vh;overflow:auto;padding-right:4px}.item{width:100%;text-align:left;display:block;padding:10px 12px;margin-bottom:7px;background:var(--card);border-color:var(--line);font-size:13px}.item small{display:block;color:var(--muted);margin-top:2px}.item[aria-current=true]{border-color:var(--accent);background:#1d343e}.item[aria-current=true] small{color:#b5d9d9}.count{font-size:12px;color:var(--muted);margin:0 0 10px}.empty{padding:20px;color:var(--muted)}
@media(max-width:900px){main{grid-template-columns:1fr;padding:18px}header{padding:20px 18px}video{max-height:65vh}.list{max-height:330px}.filters{grid-template-columns:2fr 2fr 1fr}.filters label:first-child{grid-column:auto}.topline{align-items:flex-start}h2{font-size:16px}}
</style></head><body>
<header><div class="eyebrow">EVERYTHINGDONE / SHARED RULES</div><h1>粒子消散 · 修正前沿衔接与局部流动</h1><p>先看完整画面的前沿位置、角部收束与中心纹理，再看局部弧边和拖尾；四段参考、八方向和屏幕内三距离采用同一模型。</p><p><a href="rim-flow.html">本轮重点视频与逐帧对照</a> · 此前模型使用本轮调整前归档的原始渲染帧。</p></header>
<main><section class="player" aria-label="视频审阅"><div class="topline"><h2 id="title"></h2><span class="pill" id="badge"></span></div>
<video id="player" controls loop muted playsinline preload="metadata"></video>
<div class="toolbar"><button class="primary" id="toggle">播放 / 暂停</button><button id="back">上一帧</button><button id="next">下一帧</button><button id="replay">从头播放</button><button id="full">全屏</button><span id="clock">0.000 / 0.000 秒</span></div>
<p class="note" id="note"></p><div class="toolbar"><a class="button" id="download" download>保存此视频</a><label><input type="checkbox" checked id="loop"> 自动重播</label></div>
<p class="fine">模型动画基准为 1.00 秒，另有开头与结尾停留。0.5 倍速视频已经按慢放导出，播放器保持 1 倍播放即可。左右方向键逐帧，空格暂停。截图遮挡区域的重建范围与时间口径见各视频标注。</p>
</section><aside aria-label="视频目录"><div class="filters"><label>场景<select id="scene"><option value="all">所有场景</option></select></label><label>类型<select id="kind"><option value="all">所有类型</option></select></label><label>速度<select id="rate"><option value="all">全部</option><option value="1">1 倍</option><option value="0.5">0.5 倍</option></select></label></div><p class="count" id="count"></p><div class="list" id="list"></div></aside></main>
<script>
const manifest=__MANIFEST__;
const kinds={'pixel-difference':'逐帧像素与结构差异','surface-whole':'完整画面 · 三栏对照','upper-corner':'左上角局部','rim-detail':'弧边局部','curve-split':'整体与局部','distance-focus':'单方向近中远','family-reference':'华为参考 · 形态示例','flow-family':'四组共同输入','touch-distances':'双方向 · 三距离','seed-variants':'同向六次随机消散','compare-control':'认可控制组对照','compare-versions':'此前模型对比','compare-phase':'华为对照 · 进度对齐','compare-file':'华为对照 · 文件时间','compare-source':'源素材对照','animation':'独立动画','eight-directions':'八方向同屏'};
const order=['ironman','ironman-up-reference','thanos','kobe','language','color','attachment','attachment-image',...new Set(manifest.videos.map(v=>v.scene).filter(s=>s.startsWith('holdout-')))];
const kindOrder=['surface-whole','pixel-difference','curve-split','upper-corner','rim-detail','distance-focus','family-reference','flow-family','touch-distances','eight-directions','compare-versions','compare-phase','compare-source','animation','seed-variants','compare-control','compare-file'];
const files=manifest.videos.sort((a,b)=>order.indexOf(a.scene)-order.indexOf(b.scene)||kindOrder.indexOf(a.kind)-kindOrder.indexOf(b.kind)||b.rate-a.rate);
const $=id=>document.getElementById(id);const video=$('player');let current=null;
for(const name of order){const v=files.find(v=>v.scene===name);if(v)$('scene').add(new Option(v.title,name));}
for(const k of kindOrder)$('kind').add(new Option(kinds[k],k));
function filtered(){return files.filter(v=>($('scene').value==='all'||v.scene===$('scene').value)&&($('kind').value==='all'||v.kind===$('kind').value)&&($('rate').value==='all'||v.rate===Number($('rate').value)));}
function renderList(){const list=$('list');list.replaceChildren();const shown=filtered();$('count').textContent=`${shown.length} / ${files.length} 个视频 · H.264 · 60 帧/秒`;for(const v of shown){const b=document.createElement('button');b.className='item';b.setAttribute('aria-current',String(current&&current.file===v.file));b.textContent=`${v.title} · ${v.rate} 倍`;const s=document.createElement('small');s.textContent=`${kinds[v.kind]} · ${v.duration.toFixed(2)} 秒`;b.append(s);b.onclick=()=>select(v,true);list.append(b);}if(!shown.length){const p=document.createElement('p');p.className='empty';p.textContent='当前筛选没有对应视频。';list.append(p);}}
function select(v,play=false){current=v;video.src=v.file+'?v='+manifest.code_hash;video.poster=v.poster+'?v='+manifest.code_hash;video.playbackRate=1;$('title').textContent=`${v.title}｜${kinds[v.kind]}`;$('badge').textContent=`${v.rate} 倍 · 60 帧/秒`;$('download').href=v.file;history.replaceState(null,'','#'+v.file);const notes={'seed-variants':'六格使用同一素材、同一方向及同一套运动和粒子规则，分别采用种子 0～5。位置、范围和释放顺序每次抽样，一次动画内固定；视频重播仍是这六次固定样本，桌面预览和实机每次关闭会重新变化。','compare-control':'三栏依次为华为参考、用户认可的观测运动控制组、本轮共同模型。用于检查提炼之后是否保留了轮廓与连续运动；中间列是冻结的对照产物。','compare-versions':'按相同进度比较本轮调整前后的相同素材和种子；观察前沿衔接、角部收束与中心原纹理的交接。照片另有华为参考列（映射为 1 秒）。','compare-phase':'左右按相同进度比较。华为参考区间重新映射到 1 秒；这不是参考录屏的文件原速。','compare-file':'左侧保留参考文件时间，右侧按 1 秒模型时长播放后停留。录屏可能已经慢放，不能据此推断实机动画时长。','compare-source':'左侧为静态源素材，右侧为统一规则生成的动画；构造样本与真实截图由场景名称区分。此列不提供同内容的华为运动参考。','animation':'完整前景和对应背景。未释放的内容保持原位，随后逐渐成为独立颗粒。','eight-directions':'同一画面内同时播放八个方向；模型本身支持连续任意角度。可在桌面预览程序中点击触点测试。'};$('note').textContent=notes[v.kind]+(v.scene==='thanos'?' 灭霸原片在转黑前截止，最后仍有轻微粒子。':'');renderList();if(play)video.play().catch(()=>{});}
for(const id of ['scene','kind','rate'])$(id).onchange=renderList;
const toggle=()=>{if(video.paused)video.play().catch(()=>{});else video.pause();};
const step=delta=>{video.pause();video.currentTime=Math.max(0,Math.min(video.duration||0,video.currentTime+delta/60));};
$('toggle').onclick=toggle;$('back').onclick=()=>step(-1);$('next').onclick=()=>step(1);$('replay').onclick=()=>{video.currentTime=0;video.play().catch(()=>{});};$('full').onclick=()=>video.requestFullscreen();$('loop').onchange=e=>video.loop=e.target.checked;
const updateClock=()=>{$('clock').textContent=`${video.currentTime.toFixed(3)} / ${(video.duration||0).toFixed(3)} 秒`;};
video.ontimeupdate=updateClock;video.onloadedmetadata=updateClock;video.ondurationchange=updateClock;
document.addEventListener('keydown',e=>{if(['SELECT','INPUT','BUTTON'].includes(document.activeElement.tagName))return;if(e.key==='ArrowRight'){e.preventDefault();step(1);}else if(e.key==='ArrowLeft'){e.preventDefault();step(-1);}else if(e.code==='Space'){e.preventDefault();toggle();}});
select(files.find(v=>v.file===decodeURIComponent(location.hash.slice(1)))||files.find(v=>v.file==='ironman-seed-variants-0.5x.mp4')||files[0]);
</script></body></html>'''

def main():
    from export_videos import code_hash
    data=json.loads((HERE/'videos/manifest.json').read_text(encoding='utf-8'))
    current_hash=code_hash()
    assert data['code_hash']==current_hash,'标准视频尚未导出当前模型'
    # 未重新导出的历史产物仍保留在磁盘，不混入当前模型的验收目录。
    data['videos']=[v for v in data['videos'] if v.get('code_hash')==current_hash]
    for filename in ['family-videos.json','release-filament-videos.json']:
        path=HERE/'videos'/filename
        if not path.exists():continue
        extra=json.loads(path.read_text('utf-8'))
        if extra.get('code_hash')!=current_hash:continue
        for item in extra['videos']:
            if filename=='release-filament-videos.json':
                number=item['file'].split('-')[3]
                item=dict(item,scene='user-device-'+number,title='添加附件 · 真机输入 '+number,kind='recording-reproduction')
            data['videos'].append(item)
    for video in data['videos']:
        if video['scene'] in {'holdout-coffee','holdout-colored-panel'}:
            video['title']=video['title'].replace('本轮留出','回归验证')
    page=HTML.replace('__MANIFEST__',json.dumps(data,ensure_ascii=False))
    page=page.replace('粒子消散 · 修正前沿衔接与局部流动','粒子消散 · 约束内部汇聚，保留边缘卷动')
    page=page.replace('先看完整画面的前沿位置、角部收束与中心纹理，再看局部弧边和拖尾；四段参考、八方向和屏幕内三距离采用同一模型。','钢铁侠直接对照华为原片；三个确定的细缕反例及其它素材、方向、种子使用同一套计算。先看完整动画，再逐帧检查。')
    page=page.replace('<a href="rim-flow.html">本轮重点视频与逐帧对照</a> · 此前模型使用本轮调整前归档的原始渲染帧。','<a href="pressure-flow.html">本轮 12 组完整过程对照</a> · 照片与华为原片比较，白底弹窗与此前缺陷比较。')
    page=page.replace("const order=[", "const order=['user-device-1','user-device-2','user-device-3',")
    page=page.replace("const kindOrder=[", "const kindOrder=['recording-reproduction',")
    page=page.replace("const kinds={", "const kinds={'recording-reproduction':'真机素材 · 修复前后',")
    page=page.replace("files.find(v=>v.file==='ironman-seed-variants-0.5x.mp4')", "files.find(v=>v.file==='ironman-compare-phase-0.5x.mp4')")
    page=page.replace("const notes={'seed-variants':", "const notes={'curve-split':'上排整体、下排局部放大；原片、调整前和当前共同模型同步播放，重点观察横向弧边与下方拖尾的分离。','family-reference':'共同模型的四个形态示例。种子经过参考观察筛选，不计作独立留出验证；原片触点不能确认。左侧按进度对齐，不代表文件原速。','flow-family':'四组方向和随机输入原样用于钢铁侠、添加附件、调整颜色；材质、时长和全部模型规则相同。','touch-distances':'同一素材、种子和实际方向下，在手机屏幕内、控件外选近、中、远三点。视频标出实际角度和像素距离；远近适度影响速度、位移和偏转，不把粒子聚集到触点。','seed-variants':")
    page=page.replace("const notes={", "const notes={'rim-detail':'固定区域放大，参考、调整前和当前模型同步播放；观察横向弧边逐渐弯出及下方拖尾。','distance-focus':'同一素材、种子与方向的近中远对照，显示比例和进度相同；重点页可在同一位置切换三种距离。',")
    page=page.replace("const notes={", "const notes={'pixel-difference':'依次为华为参考、共同模型、原始 RGB 差异和低频结构差异。亮处误差更大；画面没有配准形变、重排参考时间或遮蔽问题区域。水印与系统时钟变化保留显示，动画区误差另行统计。','surface-whole':'三栏依次为华为参考、本轮调整前和当前共同模型；先观察完整画面的连续运动，再检查局部。','upper-corner':'固定左上角位置放大；与完整画面使用同一批渲染帧和同一进度。',")
    if (HERE/'videos/stalled-edges.html').is_file():
        page=page.replace('</header>','<p><a class="button" href="stalled-edges.html">历史定位：边缘停滞标线</a></p></header>',1)
    page=page.replace("const notes={", "const notes={'recording-reproduction':'使用用户录像中的面板和背景，比较修复前后的同一组输入。原录像种子未知，此处复现相近释放布局，不冒称逐粒子重放。',")
    page=page.replace('</header>','<p><a class="button" href="pressure-flow.html">本轮：孤立细缕与原参考验收</a></p></header>',1)
    (HERE/'videos/index.html').write_text(page,encoding='utf-8')
    print('视频审阅页已生成：',len(data['videos']),'个视频')
if __name__=='__main__':main()
