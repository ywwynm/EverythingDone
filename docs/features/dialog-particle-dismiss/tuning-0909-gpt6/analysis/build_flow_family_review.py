"""生成保留旧参考、补充新形态和真实距离的联合审阅页。"""
from pathlib import Path
import json

HERE = Path(__file__).resolve().parents[1]
GROUPS = [
    ('本轮重点', [('ironman-curve-split','钢铁侠 · 弧边与拖尾分离')]),
    ('四段参考', [(n + '-family-reference', t) for n, t in [
        ('ironman', '原版钢铁侠 · 连贯卷边'), ('ironman-up-reference', '向上钢铁侠 · 分离起点'),
        ('thanos', '灭霸 · 多区域释放'), ('kobe', '科比 · 侧部扩展')]]),
    ('相同输入用于不同素材', [(n + '-flow-family', t + ' · 四组形态') for n, t in [
        ('ironman', '钢铁侠'), ('attachment', '添加附件'), ('color', '调整颜色')]]),
    ('屏幕内触点', [(n + '-touch-distances', t + ' · 双方向三距离') for n, t in [
        ('ironman', '钢铁侠'), ('attachment', '添加附件'), ('color', '调整颜色')]]),
    ('八方向', [(n + '-eight-directions', t + ' · 八方向') for n, t in [
        ('ironman', '钢铁侠'), ('attachment', '添加附件'), ('color', '调整颜色')]]),
    ('改动前后', [(n + '-compare-versions', t + ' · 此前模型对照') for n, t in [
        ('ironman', '钢铁侠'), ('thanos', '灭霸'), ('kobe', '科比'), ('attachment', '添加附件'), ('color', '调整颜色')]])]

HTML = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>共同流动与触点变化 · 粒子消散</title>
<style>:root{color-scheme:dark}body{max-width:1500px;margin:auto;padding:22px;background:#0e141e;color:#e8eff8;font:15px/1.6 "Microsoft YaHei",sans-serif}h1{font-size:27px;margin:0}p{color:#afbed0;margin:10px 0}video{display:block;width:100%;max-height:70vh;background:#070b11}button,a{display:inline-block;background:#243348;color:#edf3fb;border:1px solid #465b73;border-radius:5px;padding:7px 11px;margin:4px 5px 4px 0;cursor:pointer;font:inherit;text-decoration:none}button[aria-pressed=true]{border-color:#6ee1d6;background:#254b51}h2{font-size:19px;margin:14px 0 8px}h3{font-size:14px;font-weight:500;color:#aabed2;margin:12px 0 3px}#status{color:#aabed2;margin-left:10px}#note{font-size:14px;min-height:45px}button:focus-visible,a:focus-visible{outline:2px solid #6ee1d6;outline-offset:3px}</style>
<h1>保留连贯卷边，补充局部流动</h1><p>四段参考放在一起比较；同样的四组方向与随机输入，也用于钢铁侠、添加附件和调整颜色。远近只适度改变速度和位移，距离演示的触点均位于手机屏幕内、控件外。</p>
<h2 id="title"></h2><video id="video" controls muted loop playsinline preload="metadata"></video>
<div id="phases"></div><div id="speeds"></div><p id="note"></p><a id="download" download>保存此视频</a><a href="index.html">全部视频</a><span id="status"></span><div id="choices"></div>
<script>const groups=__GROUPS__,items=groups.flatMap(g=>g[1]);const v=document.querySelector('#video');let current=0,rate=.5;
function choose(i,play=false,p=0){current=i;const file=items[i][0]+'-'+rate+'x.mp4';v.src=file+'?flow-family-final';document.querySelector('#title').textContent=items[i][1];document.querySelector('#download').href=file;history.replaceState(null,'','#'+file);document.querySelectorAll('#choices button').forEach((b,j)=>b.setAttribute('aria-pressed',j===i));document.querySelectorAll('#speeds button').forEach((b,j)=>b.setAttribute('aria-pressed',[1,.5][j]===rate));const name=items[i][0];document.querySelector('#note').textContent=name.includes('touch-distances')?'每排保持同一素材、种子和实际方向；近、中、远按屏幕内可触摸背景生成。视频标出实际角度和边缘外像素距离。钢铁侠上方可用背景较窄，距离差异相应较小。':name.includes('family-reference')?'参考按动画进度对齐，不代表原片时长。示例输入用于展示共同模型中的不同形态可能性；参考触点不可确认，也不据此声称复原了华为内部算法。向上参考经过相机配准，前景由两个时刻合成。':name.includes('flow-family')?'每格使用同一套运动、释放和材质规则。这四组输入原样用于三个素材；没有按图片名称选择模型。这里的示例经过参考观察筛选，其它随机结果可在全部视频中查看。':name.includes('compare-versions')?'基线是加入新向上参考之前的冻结模型。按相同素材、种子和进度比较，观察此前连贯卷动是否保留；过强目标汇聚的被否定候选不作为基线。':'同一素材和随机输入，连续模型分别取八个方向。重点观察局部释放、边缘变形和尾段连续运动。';v.onloadedmetadata=()=>{v.currentTime=(.35+p)/rate;if(play)v.play().catch(()=>{});};}
v.addEventListener('loadedmetadata',()=>document.querySelector('#title').scrollIntoView({block:'start'}));
let index=0;for(const [title,entries] of groups){const h=document.createElement('h3');h.textContent=title;document.querySelector('#choices').append(h);for(const entry of entries){const i=index++,b=document.createElement('button');b.textContent=entry[1];b.onclick=()=>choose(i,true);document.querySelector('#choices').append(b);}}
for(const t of [.20,.35,.48,.63,.78]){const b=document.createElement('button');b.textContent='进度 '+t.toFixed(2);b.onclick=()=>{v.pause();v.currentTime=(.35+t)/rate;};document.querySelector('#phases').append(b);}
for(const [label,delta] of [['上一帧',-1],['下一帧',1]]){const b=document.createElement('button');b.textContent=label;b.onclick=()=>{v.pause();v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+delta/60));};document.querySelector('#phases').append(b);}
for(const speed of [1,.5]){const b=document.createElement('button');b.textContent=speed+' 倍速文件';b.onclick=()=>{const p=Math.max(0,Math.min(1,v.currentTime*rate-.35));rate=speed;choose(current,!v.paused,p);};document.querySelector('#speeds').append(b);}
v.ontimeupdate=()=>document.querySelector('#status').textContent='动画进度 '+Math.max(0,Math.min(1,v.currentTime*rate-.35)).toFixed(3);
const hash=decodeURIComponent(location.hash.slice(1));if(hash.endsWith('-1x.mp4'))rate=1;choose(Math.max(0,items.findIndex(item=>hash===item[0]+'-'+rate+'x.mp4')));</script></html>'''


def main():
    for _, entries in GROUPS:
        for prefix, _ in entries:
            for rate in ['1', '0.5']:
                assert (HERE / 'videos' / f'{prefix}-{rate}x.mp4').is_file()
    manifest=json.loads((HERE/'videos/manifest.json').read_text('utf-8'))
    family=json.loads((HERE/'videos/family-videos.json').read_text('utf-8'))
    assert manifest['code_hash']==family['code_hash']
    page=HTML.replace('__GROUPS__', json.dumps(GROUPS, ensure_ascii=False))
    page=page.replace('保留连贯卷边，补充局部流动','弧边与拖尾分离，近远改变移动速度')
    page=page.replace('钢铁侠上方可用背景较窄，距离差异相应较小。','按该方向的可用屏幕背景归一化远近，窄背景也有可见的速度差异。')
    page=page.replace('基线是加入新向上参考之前的冻结模型。按相同素材、种子和进度比较，观察此前连贯卷动是否保留；过强目标汇聚的被否定候选不作为基线。','左侧基线是本轮调整前的冻结画面，使用相同素材和种子。观察弧边分支、拖尾与卷动的变化。')
    page=page.replace("name.includes('touch-distances')?", "name.includes('curve-split')?'上排为整体、下排为相同位置放大；观察一条弧边逐渐呈现横向轮廓和下方拖尾。三栏是参考、本轮调整前、本轮共同模型；未给模型额外绘制亮边。':name.includes('touch-distances')?")
    page=page.replace('[.20,.35,.48,.63,.78]','[.31,.43,.55,.65,.78]')
    page=page.replace('?flow-family-final','?v='+manifest['code_hash'])
    (HERE / 'videos/flow-family.html').write_text(page, 'utf-8')
    for old in ['targeted-release.html', 'flow-shaping.html']:
        (HERE / 'videos' / old).write_text('<!doctype html><meta charset="utf-8"><title>前往当前验收页</title><a href="flow-family.html">查看当前验收页</a><script>location.replace("flow-family.html"+location.hash)</script>', 'utf-8')
    print('共同流动审阅页已生成')


if __name__ == '__main__':
    main()
