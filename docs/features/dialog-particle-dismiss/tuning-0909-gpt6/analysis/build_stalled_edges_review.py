"""集中展示多素材、多种子、多方向的只读边缘诊断视频。"""
from pathlib import Path
import json, sys
from collections import Counter
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(Path(__file__).resolve().parent))
from expand_stalled_edges import case_list, REPORT, VIDEOS, SCENES, SEEDS, ANGLES


HTML=r'''<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>粒子固定边缘 · 多种子与方向复核</title>
<style>
:root{color-scheme:dark;--bg:#0e141e;--panel:#172230;--line:#314255;--text:#eef3f9;--muted:#a3b5c9;--red:#ff6864;--gold:#ffd053;--cyan:#40e5ea}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.65 'Microsoft YaHei',sans-serif}main{max-width:1650px;margin:auto;padding:24px}h1{font-size:26px;margin:0 0 8px;font-weight:600}h2{font-size:19px;margin:12px 0 6px}p{margin:6px 0;color:var(--muted)}button,select,a,input,textarea{font:inherit}button,select,a.button{background:var(--panel);color:var(--text);border:1px solid var(--line);border-radius:6px;padding:6px 11px;cursor:pointer;text-decoration:none}button:hover,a.button:hover{border-color:#8aaec9}button.active{background:#294f63;border-color:var(--cyan)}button:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--cyan)}.row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:10px 0}.muted{color:var(--muted)}.legend span{margin-right:22px}.red{color:var(--red)}.gold{color:var(--gold)}.cyan{color:var(--cyan)}#stage{background:#080d13;border:1px solid var(--line);border-radius:9px;overflow:hidden;text-align:center}video,canvas{display:block;width:100%;max-height:72vh;margin:auto;object-fit:contain;background:#0e141e}canvas{display:none}#title{flex:1;font-size:20px;font-weight:600}#scrub{flex:1;min-width:200px;accent-color:var(--cyan)}#phase{font-variant-numeric:tabular-nums;min-width:90px}#annotations button{font-size:14px;padding:5px 9px}#candidateNote{min-height:24px}#feedback{border-top:1px solid var(--line);padding-top:9px}textarea{width:min(700px,100%);height:45px;background:#111b27;color:var(--text);border:1px solid var(--line);border-radius:5px;padding:7px;resize:vertical}#matrix{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:10px;margin-top:12px}.card{padding:0;text-align:left;overflow:hidden}.card img{display:block;width:100%;height:142px;object-fit:contain;background:#0e141e}.card strong,.card small{display:block;padding:0 9px}.card strong{font-size:14px}.card small{color:var(--muted);padding-bottom:7px}.card.current{border-color:var(--cyan)}details{margin-top:14px;border-top:1px solid var(--line);padding-top:12px}summary{cursor:pointer;font-size:18px}.status{font-size:13px;padding:2px 8px;border-radius:4px;background:#223143}.review-yes{border-color:#53b691}.review-no{border-color:#cd8b89}.review-unsure{border-color:#d7b663}#progress{margin-left:auto}label{display:inline-flex;align-items:center;gap:6px}.spacer{flex:1}@media(max-width:700px){main{padding:14px}h1{font-size:21px}#title{flex-basis:100%}video,canvas{max-height:62vh}#matrix{grid-template-columns:repeat(2,1fr)}}
</style>
<main>
<h1>粒子固定边缘：多种子与方向复核</h1>
<p>七个素材 × 四个种子（0、1、7、23）× 八个方向，共 224 组；另补两组原示例，共 226 组。每组均有原画与编号标线，动画模型保持原样。</p>
<p class="legend"><span class="red">红：原控件边缘附近</span><span class="gold">黄：其他稳定外缘</span><span class="cyan">青：你补充／此前定位</span>固定标线用于确认位置，尚未判定根因。</p>
<div class="row" id="anchors"></div>
<div class="row"><span id="title"></span><span id="caseStatus" class="status"></span><a class="button" id="download" download>下载本组视频</a></div>
<div id="stage"><video id="video" controls autoplay muted loop playsinline preload="metadata"></video><canvas id="zoom" width="1600" height="1000"></canvas></div>
<div class="row"><button id="previous">上一组</button><button id="next">下一组</button><button id="play">播放／暂停</button><button id="framePrev">上一帧</button><button id="frameNext">下一帧</button><span class="muted">动画速度</span><button data-rate="1">1 倍</button><button data-rate="0.5" class="active">0.5 倍</button><button data-rate="0.25">0.25 倍</button><label><input id="autoNext" type="checkbox">顺序播放当前筛选</label></div>
<div class="row"><span id="phase">t=0.000</span><input aria-label="动画进度" id="scrub" type="range" min="0" max="120" step="1" value="0"><button id="full">完整画面</button><button id="focusFrame">候选参考帧</button></div>
<div class="row" id="annotations"></div><p id="candidateNote"></p>
<div id="feedback"><div class="row"><span>本组观察：</span><button data-review="yes">是我说的现象</button><button data-review="no">标得不对</button><button data-review="unsure">还不确定</button><button data-review="">清除记录</button><span id="progress" class="muted"></span></div><div class="row"><textarea id="note" aria-label="本组备注" placeholder="可填写标线编号或漏标位置，例如：1、3 是；左上还漏了一处。"></textarea><button id="saveNotes">导出统一反馈</button><span class="muted">记录保存在本机浏览器，不会自动发送。</span></div></div>
<details><summary>全部案例 · 按素材、种子、方向筛选</summary><div class="row"><label>素材<select id="scene"><option value="">全部素材</option></select></label><label>种子<select id="seed"><option value="">全部种子</option><option>0</option><option>1</option><option>7</option><option>23</option></select></label><label>方向<select id="angle"><option value="">全部方向</option></select></label><label>记录<select id="review"><option value="">全部</option><option value="pending">尚未记录</option><option value="yes">是我说的现象</option><option value="no">标得不对</option><option value="unsure">还不确定</option></select></label><button id="reset">清除筛选</button><span id="count" class="muted"></span></div><div id="matrix"></div></details>
<div class="row"><a class="button" href="stalled-edges-initial.html">上轮两个示例</a><a class="button" href="index.html">全部动画对比</a></div>
</main>
<script>
const DATA=__DATA__;
const $=s=>document.querySelector(s), v=$('#video'), canvas=$('#zoom'), ctx=canvas.getContext('2d');
const COLORS={original:'#ff6864',contour:'#ffd053',user:'#40e5ea'};
const KEY='particle-stalled-edges-review-'+DATA.model_hash.slice(0,12);
let feedback={};try{feedback=JSON.parse(localStorage.getItem(KEY)||'{}')}catch{}
let current, selected=null, rate=.5, filtered=DATA.cases, changing=false;
const phase=()=>Math.max(0,Math.min(1,(v.currentTime-.2)*.5));
const stateText={yes:'已记录：是我说的现象',no:'已记录：标得不对',unsure:'已记录：还不确定'};
const persist=()=>{try{localStorage.setItem(KEY,JSON.stringify(feedback))}catch{}updateFeedback()};
for(const c of DATA.cases.filter(c=>c.anchor)){
 const b=document.createElement('button');b.textContent=c.id+' '+(c.scene==='ironman'?'你补充的屏幕左侧':'你补充的左上边缘');b.onclick=()=>choose(c.id);$('#anchors').append(b);
}
for(const [name,title] of DATA.scenes){const o=new Option(title,name);$('#scene').add(o)}
for(const [angle,title] of DATA.directions){$('#angle').add(new Option(title+' '+angle+'°',String(angle)))}
function updateFeedback(){
 const f=feedback[current?.id]||{};
 $('#caseStatus').textContent=stateText[f.status]||'待你确认';
 for(const b of document.querySelectorAll('[data-review]'))b.classList.toggle('active',!!b.dataset.review&&b.dataset.review===f.status);
 $('#progress').textContent='已记录 '+Object.values(feedback).filter(x=>x.status||x.note).length+' / '+DATA.cases.length+' 组';
}
function filter(){
 const scene=$('#scene').value,seed=$('#seed').value,angle=$('#angle').value,review=$('#review').value;
 filtered=DATA.cases.filter(c=>(!scene||c.scene===scene)&&(!seed||String(c.seed)===seed)&&(!angle||String(c.angle)===angle)&&(!review||(review==='pending'?!feedback[c.id]?.status:feedback[c.id]?.status===review)));
 $('#count').textContent=filtered.length+' 组';const grid=$('#matrix');grid.replaceChildren();
 for(const c of filtered){
  const b=document.createElement('button');b.className='card '+(c.id===current?.id?'current ':'')+(feedback[c.id]?.status?'review-'+feedback[c.id].status:'');b.dataset.id=c.id;
  const img=new Image();img.src=c.video.poster;img.loading='lazy';img.alt=c.id+' 候选边缘预览';
  const strong=document.createElement('strong');strong.textContent=c.id+' '+c.title;
  const small=document.createElement('small');small.textContent='种子 '+c.seed+' · '+c.direction_title+' '+c.angle+'° · '+c.annotations.length+' 条';
  b.append(img,strong,small);b.onclick=()=>choose(c.id);grid.append(b);
 }
}
function choose(id){
 const c=DATA.cases.find(c=>c.id===id);if(!c)return;
 const hadCurrent=!!current;
 current=c;selected=null;changing=true;location.hash=c.id;
 $('#title').textContent=c.id+' '+c.title+' · 种子 '+c.seed+' · '+c.direction_title+' '+c.angle+'°';
 v.poster=c.video.poster;v.src=c.video.file+'?v='+c.video.sha256.slice(0,12);v.playbackRate=rate/.5;
 $('#download').href=c.video.file;$('#note').value=feedback[c.id]?.note||'';
 $('#annotations').replaceChildren();
 for(const a of c.annotations){
  const b=document.createElement('button');b.textContent='放大 '+a.id+' · '+a.label;b.style.color=COLORS[a.kind];
  b.onclick=()=>{selected=a;displayMode();v.currentTime=.2+Math.max(0,a.phase-.16)/.5;v.play().catch(()=>{});$('#stage').scrollIntoView({block:'start'});};$('#annotations').append(b);
 }
 displayMode();updateFeedback();filter();v.play().catch(()=>{});changing=false;if(hadCurrent)$('#stage').scrollIntoView({block:'start'});
}
function displayMode(){
 v.style.display=selected?'none':'block';canvas.style.display=selected?'block':'none';
 $('#candidateNote').textContent=selected?'标线 '+selected.id+'：'+selected.label+'；参考 t='+selected.phase.toFixed(3)+'。放大显示同一段视频；可逐帧检查，或回到完整画面。':'点击任一标线编号放大。编号只在本组内使用；标线出现后位置固定，原画继续播放。';
}
function navigate(delta){
 const list=filtered.length?filtered:DATA.cases;let i=list.findIndex(c=>c.id===current.id);if(i<0)i=delta>0?-1:0;choose(list[(i+delta+list.length)%list.length].id);
}
function seek(p,pause=true){if(pause)v.pause();v.currentTime=.2+Math.max(0,Math.min(1,p))/.5}
function zoomRegion(){
 const b=selected.bbox,c=current.crop;let w=Math.max(180,b[2]-b[0]+110),h=Math.max(190,b[3]-b[1]+110);
 const aspect=770/830;if(w/h<aspect)w=h*aspect;else h=w/aspect;
 w=Math.min(w,c[2]-c[0]);h=Math.min(h,c[3]-c[1]);
 const x=Math.max(c[0],Math.min(c[2]-w,(b[0]+b[2]-w)/2)),y=Math.max(c[1],Math.min(c[3]-h,(b[1]+b[3]-h)/2));
 return{x,y,w,h};
}
function draw(){
 if(current){const p=phase();$('#phase').textContent='t='+p.toFixed(3);if(document.activeElement!==$('#scrub'))$('#scrub').value=Math.round(p*120);
  if(selected&&v.readyState>=2){
   const b=zoomRegion(),l=current.video.layout,c=current.crop;
   const sx=l.x+(b.x-c[0])*l.scale,sy=l.y+(b.y-c[1])*l.scale,sw=b.w*l.scale,sh=b.h*l.scale;
   ctx.fillStyle='#0e141e';ctx.fillRect(0,0,1600,1000);ctx.fillStyle='#eef3f9';ctx.font='24px Microsoft YaHei';ctx.fillText(current.id+' '+current.title+' · 标线 '+selected.id,24,36);
   ctx.fillStyle='#a3b5c9';ctx.font='22px Microsoft YaHei';ctx.fillText('左：原画局部',24,80);ctx.fillText('右：同一区域及标线',824,80);
   const s=Math.min(770/sw,830/sh),dw=sw*s,dh=sh*s,dx=15+(770-dw)/2,dy=106+(830-dh)/2;
   for(const col of [0,1])ctx.drawImage(v,sx+col*800,sy,sw,sh,dx+col*800,dy,dw,dh);
   ctx.fillStyle=COLORS[selected.kind];ctx.fillText(selected.label,24,974);ctx.fillStyle='#eef3f9';ctx.fillText('t='+p.toFixed(3),1420,974);
  }
 }requestAnimationFrame(draw);
}
$('#previous').onclick=()=>navigate(-1);$('#next').onclick=()=>navigate(1);$('#play').onclick=()=>v.paused?v.play().catch(()=>{}):v.pause();
$('#framePrev').onclick=()=>{v.pause();v.currentTime=Math.max(0,v.currentTime-1/60)};$('#frameNext').onclick=()=>{v.pause();v.currentTime=Math.min(v.duration||0,v.currentTime+1/60)};
for(const b of document.querySelectorAll('[data-rate]'))b.onclick=()=>{rate=Number(b.dataset.rate);v.playbackRate=rate/.5;for(const x of document.querySelectorAll('[data-rate]'))x.classList.toggle('active',x===b)};
$('#scrub').oninput=()=>seek(Number($('#scrub').value)/120);$('#full').onclick=()=>{selected=null;displayMode();$('#stage').scrollIntoView({block:'start'})};$('#focusFrame').onclick=()=>{seek((selected||current.annotations[0])?.phase||.75);$('#stage').scrollIntoView({block:'start'})};
$('#autoNext').onchange=()=>v.loop=!$('#autoNext').checked;v.onended=()=>{if($('#autoNext').checked)navigate(1)};
v.onloadedmetadata=()=>{v.playbackRate=rate/.5};
for(const id of ['scene','seed','angle','review'])$('#'+id).onchange=filter;
$('#reset').onclick=()=>{for(const id of ['scene','seed','angle','review'])$('#'+id).value='';filter()};
for(const b of document.querySelectorAll('[data-review]'))b.onclick=()=>{if(b.dataset.review){feedback[current.id]={...feedback[current.id],status:b.dataset.review}}else{delete feedback[current.id];$('#note').value=''}persist();filter()};
$('#note').oninput=()=>{feedback[current.id]={...feedback[current.id],note:$('#note').value};persist()};
$('#saveNotes').onclick=()=>{
 const lines=['粒子固定边缘复核','模型：'+DATA.model_hash,''];
 for(const c of DATA.cases){const f=feedback[c.id];if(!f?.status&&!f?.note)continue;lines.push(c.id+' '+c.title+' / 种子 '+c.seed+' / '+c.angle+'°',stateText[f.status]||'已填写备注',f.note||'','')}
 const url=URL.createObjectURL(new Blob([lines.join('\n')],{type:'text/plain;charset=utf-8'}));const a=document.createElement('a');a.href=url;a.download='粒子固定边缘-统一反馈.txt';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
window.onhashchange=()=>{if(!changing&&location.hash.slice(1)!==current?.id)choose(location.hash.slice(1))};
document.addEventListener('keydown',e=>{if(['INPUT','TEXTAREA','SELECT'].includes(document.activeElement.tagName))return;if(e.key==='ArrowRight'){$('#frameNext').click();e.preventDefault()}if(e.key==='ArrowLeft'){$('#framePrev').click();e.preventDefault()}});
choose(DATA.cases.some(c=>c.id===location.hash.slice(1))?location.hash.slice(1):'C225');requestAnimationFrame(draw);
</script></html>'''


def main():
    cases=[];diagnostics=json.loads((VIDEOS/'diagnostics.json').read_text('utf-8'))
    videos=[]
    for c in case_list():
        row=json.loads((REPORT/f'{c["key"]}.json').read_text('utf-8'))
        video=json.loads((REPORT/f'{c["key"]}-video.json').read_text('utf-8'))
        assert (VIDEOS/video['file']).exists()
        videos.append(video);row['video']=video;row.pop('stats');cases.append(row)
    counts=Counter(a['kind'] for c in cases for a in c['annotations'])
    summary=dict(cases=len(cases),sweep_cases=sum(not c['anchor'] for c in cases),seeds=SEEDS,angles=ANGLES,
        counts=dict(counts),no_candidate=[c['id'] for c in cases if not c['annotations']],
        cases_with_outside_particles=sum(c['peak_outside']>0 for c in cases),
        bytes=sum(v['bytes'] for v in videos),model_hash=cases[0]['model_hash'],code_hash=cases[0]['code_hash'])
    data=summary|dict(cases=cases,scenes=[(s,next(c['title'] for c in cases if c['scene']==s)) for s in SCENES],
        directions=list(zip(ANGLES,['右','右上','上','左上','左','左下','下','右下'])))
    dump=lambda path,obj:path.write_text(json.dumps(obj,ensure_ascii=False,indent=2),'utf-8')
    dump(REPORT/'summary.json',summary);dump(REPORT/'review-manifest.json',data)
    files={v['file'] for v in videos};diagnostics['videos']=[v for v in diagnostics['videos'] if v['file'] not in files]+videos
    dump(VIDEOS/'diagnostics.json',diagnostics)
    old=VIDEOS/'stalled-edges-initial.html'
    if not old.exists():old.write_text((VIDEOS/'stalled-edges.html').read_text('utf-8'),'utf-8')
    (VIDEOS/'stalled-edges.html').write_text(HTML.replace('__DATA__',json.dumps(data,ensure_ascii=False)),'utf-8')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
    for scene in SCENES:
        # 四个种子的八方向总览。每格取原视频标注侧，不合成不存在的轨迹。
        sheet=Image.new('RGB',(8*340,4*490),'#0e141e');d=ImageDraw.Draw(sheet)
        for c in [x for x in cases if x['scene']==scene and not x['anchor']]:
            col=ANGLES.index(c['angle']);row=SEEDS.index(c['seed'])
            im=Image.open(VIDEOS/c['video']['poster']);im=im.crop((400,0,800,500));im.thumbnail((338,445))
            sheet.paste(im,(col*340+(340-im.width)//2,row*490+35))
            d.text((col*340+8,row*490+3),f'{c["id"]} S{c["seed"]} {c["angle"]}°',font=font,fill='#eef3f9')
        sheet.save(REPORT/f'overview-{scene}.jpg',quality=95)
    print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)


if __name__=='__main__':main()
