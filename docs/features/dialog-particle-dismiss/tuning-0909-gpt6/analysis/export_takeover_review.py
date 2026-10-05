"""生成 12 段固定输入的预渲染对照；与实时预览共用 desktop_inputs。

输出保留在忽略的 match-cache/takeover-20261004，不覆盖历史成片。
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import time
import cv2
import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT/'analysis')]
from desktop_inputs import REFS, META, MODELS, animation_inputs, make_renderer
from match_refs import RefFrames
from unified_model import model_fingerprint

OUT = ROOT/'analysis/match-cache/takeover-20261004'
FPS = 120
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 23)


def identity():
    h = hashlib.sha256(model_fingerprint().encode())
    for name in ['desktop_candidate.py', 'desktop_material.py', 'desktop_inputs.py', 'v2_inputs.py', 'analysis/export_takeover_review.py']:
        h.update((ROOT/name).read_bytes())
    return h.hexdigest()


def export_scene(name, ctx, models=('baseline', 'candidate')):
    m = META[name]
    inputs = animation_inputs(name, 'ref', m['seed'])
    renderers = [make_renderer(inputs, key, ctx) for key in models]
    columns = len(models)+1
    ref = RefFrames(name)
    w, h = m['frame']
    x, y, x1, y1 = m['rect']
    pad = round(min(x1-x, y1-y)*.24)
    box = [max(0, x-pad), max(0, y-pad), min(w, x1+pad), min(h, y1+pad)]
    cw, ch = box[2]-box[0], box[3]-box[1]
    ch -= ch % 2
    cw -= cw % 2
    box[2], box[3] = box[0]+cw, box[1]+ch
    header = Image.new('RGB', (cw*columns, 36), '#10151d')
    draw = ImageDraw.Draw(header)
    for i, title in enumerate(['华为原片']+[MODELS[key][0] for key in models]):
        draw.text((i*cw+12, 2), title, font=FONT, fill='#e6edf5')
    header = np.array(header)
    process = subprocess.Popen(['C:/ffmpeg/bin/ffmpeg.exe', '-y', '-v', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                                '-s', f'{cw*columns}x{ch+36}', '-r', str(FPS), '-i', '-', '-an', '-c:v', 'libx264',
                                '-preset', 'veryfast', '-crf', '17', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(OUT/f'{name}.mp4')], stdin=subprocess.PIPE)
    sheets = []
    evidence = []
    region_scores = []
    try:
        for f in range(FPS+1):
            p = f/FPS
            frames = [np.asarray(ref.nearest(p)[1])] + [r.render(p) for r in renderers]
            crops = [im[box[1]:box[3], box[0]:box[2], :3] for im in frames]
            joined = np.concatenate([header, np.concatenate(crops, axis=1)], axis=0)
            process.stdin.write(np.ascontiguousarray(joined).tobytes())
            if f in [24, 36, 48, 60, 72, 84, 96]:
                row = Image.fromarray(joined)
                row.thumbnail((1200, 800))
                sheets.append(row)
            if f in range(30, 91, 5):
                # 邻接时刻表用于目视连续性，不以平均分替代原片。
                small = Image.fromarray(joined)
                small.thumbnail((1000, 450))
                evidence.append((p, small.copy()))
            if f == 48:
                Image.fromarray(joined).save(OUT/f'{name}-poster.jpg', quality=95)
            if f == 60:
                # 无损帧为在线 / 离线同口径核对使用。
                for i, key in enumerate(models):
                    np.save(OUT/f'{name}-{key}-050.npy', frames[i+1])
            if f in [30, 48, 66, 84] and m.get('background_truth'):
                a = cv2.GaussianBlur(crops[0].astype('float32'), (0, 0), 2)
                region_scores.append([p] + [float(np.abs(cv2.GaussianBlur(c.astype('float32'), (0, 0), 2)-a).mean()) for c in crops[1:]])
    finally:
        process.stdin.close()
        code = process.wait()
        for r in renderers:
            r.close()
    if code:
        raise RuntimeError(f'视频编码失败：{name}')
    for filename, rows in [('sheet', [(i, s) for i, s in enumerate(sheets)]), ('continuity', evidence)]:
        width = max(im.width for _, im in rows)
        canvas = Image.new('RGB', (width, sum(im.height+28 for _, im in rows)), '#10151d')
        y = 0
        for p, im in rows:
            ImageDraw.Draw(canvas).text((8, y), f'{p:.3f}' if filename == 'continuity' else ['0.20','0.30','0.40','0.50','0.60','0.70','0.80'][int(p)], font=FONT, fill='white')
            canvas.paste(im, (0, y+28))
            y += im.height+28
        canvas.save(OUT/f'{name}-{filename}.jpg', quality=94)
    return dict(name=name, title=m.get('title', name), inputs=inputs, models=list(models), particles=[r.n for r in renderers],
                crop=box, frames=FPS+1, fps=FPS, background_truth=m.get('background_truth', False),
                blurred_rgb_mae=region_scores)


def page(report):
    options=''.join(f'<option value="{r["name"]}">{r["title"]}</option>' for r in report)
    return '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>粒子消散 · 预渲染对照</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#10151d;color:#e6edf5;font:14px/1.7 "Microsoft YaHei",sans-serif}main{max-width:1440px;margin:auto;padding:24px}h1{font-size:22px;margin:0}p{color:#9baec3}nav{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:16px 0}select,button{padding:7px 12px;background:#223146;border:1px solid #40546c;border-radius:7px;color:#e6edf5;font:inherit}a{color:#83bfff}video{display:block;width:100%;max-height:70vh;background:#090c12;border-radius:10px}input{flex:1;min-width:150px;accent-color:#83bfff}output{width:40px}details img{width:100%;margin-top:12px}.note{font-size:12px}</style>
<main><h1>粒子消散 · 预渲染对照</h1><p>左：华为原片　中：Android 同规则基准　右：10 月 4 日多起点候选</p><nav><label>序列 <select id="scene" aria-label="序列">'''+options+'''</select></label><label>速度 <select id="speed" aria-label="速度"><option value="1">1×</option><option value="0.5" selected>0.5×</option><option value="0.25">0.25×</option></select></label><a href="/">实时预览 ↗</a><a id="download" download>下载本段</a></nav>
<video id="video" muted playsinline preload="auto" controls></video><nav><button id="prev">前一帧</button><button id="next">后一帧</button><input id="progress" type="range" min="0" max="120" step="1" value="0" aria-label="动画进度"><output id="time">0.00</output></nav>
<p id="info"></p><p class="note">候选使用相同的运动与材质规则，参考输入仅提供早期人工观测起点；没有按序列读取拟合运动场。粒群密度、释放轮廓和旧钢铁侠弧边仍有差距。本页为控件附近的原尺寸裁剪，共 121 帧。参考 2、3 的背景重建不完整。</p>
<details><summary>关键帧对照</summary><img id="sheet" alt="关键帧对照"></details><details><summary>相邻时刻连续性（0.25–0.75）</summary><img id="continuity" alt="相邻时刻对照"></details></main>
<script>const report='''+json.dumps(report,ensure_ascii=False)+''';const $=id=>document.getElementById(id),v=$('video');function select(){const name=$('scene').value,r=report.find(x=>x.name===name);v.src='/review/'+name+'.mp4';v.poster='/review/'+name+'-poster.jpg';v.playbackRate=Number($('speed').value);$('download').href=v.src;$('sheet').src='/review/'+name+'-sheet.jpg';$('continuity').src='/review/'+name+'-continuity.jpg';$('info').textContent='粒子总数：基准 '+r.particles[0].toLocaleString()+' → 候选 '+r.particles[1].toLocaleString()+'（减少 '+(100*(1-r.particles[1]/r.particles[0])).toFixed(1)+'%） · 固定种子 '+r.inputs.seed;$('progress').value=0;$('time').textContent='0.00';}function seek(p){v.pause();v.currentTime=Math.max(0,Math.min(1,p));}$('scene').onchange=select;$('speed').onchange=()=>v.playbackRate=Number($('speed').value);$('progress').oninput=()=>seek(Number($('progress').value)/120);$('prev').onclick=()=>seek(v.currentTime-1/120);$('next').onclick=()=>seek(v.currentTime+1/120);v.ontimeupdate=()=>{$('progress').value=Math.round(Math.min(1,v.currentTime)*120);$('time').textContent=Math.min(1,v.currentTime).toFixed(2);};$('scene').value='reference-0926-5';select();</script></html>'''


def write_page(report, material_study=False, baseline_recovery=False):
    html=page(report)
    if material_study:
        html=html.replace('左：华为原片　中：Android 同规则基准　右：10 月 4 日多起点候选',
            '从左到右：华为原片／Android 基准／多起点候选／材质实验（未验收）')
        html=html.replace('本页为控件附近的原尺寸裁剪，共 121 帧。',
            '材质实验延长早生材料留存，减少实例并保留源色；新 5 中段改善，但部分尾段过密，旧弧边与传播形状尚未解决。它是可选研究模型。本页为控件附近的原尺寸裁剪，共 121 帧。')
        html=html.replace("'/review/'", "'/review/material/'")
        html=html.replace("+'（减少 '", "+' → 材质实验 '+r.particles[2].toLocaleString()+'（候选比基准减少 '")
    if baseline_recovery:
        html=html.replace('左：华为原片　中：Android 同规则基准　右：10 月 4 日多起点候选',
            '从左到右：华为原片／Android 基准／当前默认：基准较少颗粒版／此前保色实验')
        html=html.replace('候选使用相同的运动与材质规则，参考输入仅提供早期人工观测起点；没有按序列读取拟合运动场。粒群密度、释放轮廓和旧钢铁侠弧边仍有差距。',
            '当前默认恢复原基准的释放、卷动与材质规则，仅减少采样数量；先保住旧钢铁侠已经具备的弧形与流动。此前保色实验保留为对照，其释放轮廓导致旧序列退步。其它参考的起点、轮廓及中后段材料仍有明显差异，优化尚未完成。')
        html=html.replace("'/review/'", "'/review/recovery/'")
        html=html.replace("' → 候选 '", "' → 当前默认 '")
        html=html.replace("$('scene').value='reference-0926-5'", "$('scene').value='ironman'")
    # 原片缺失区间不能把冻结的末帧当成有效参考。仅改变页面提示，不改成片或模型。
    limits={name:float(META[name]['reference'].get('valid_progress',1.)) for name in REFS}
    html=html.replace('<p id="info"></p>', '<p id="info"></p><p id="referenceNote" role="status"></p>')
    script='''<script>const referenceLimits='''+json.dumps(limits)+''';
function showReferenceLimit(){const limit=referenceLimits[document.getElementById('scene').value]??1;
document.getElementById('referenceNote').textContent=limit<1?(v.currentTime>limit?'原片已截断，左栏停留在最后可用帧；当前进度不能用于相似度判断。':'原片仅录到 '+limit.toFixed(2)+' 进度；其后的冻结画面不参与比较。'):'';}
v.addEventListener('timeupdate',showReferenceLimit);document.getElementById('scene').addEventListener('change',showReferenceLimit);showReferenceLimit();</script>'''
    html=html.replace('</html>',script+'</html>')
    (OUT/'index.html').write_text(html,'utf-8')
    (ROOT/'analysis/match-cache/takeover-20261004/reference-limits.json').write_text(json.dumps(limits),'utf-8')


def main():
    global OUT
    parser=argparse.ArgumentParser()
    parser.add_argument('--scenes', nargs='*', default=REFS)
    parser.add_argument('--material-study', action='store_true')
    parser.add_argument('--baseline-recovery', action='store_true', help='导出基准、较少颗粒基准与此前保色实验的回归对照')
    parser.add_argument('--pages-only', action='store_true', help='只更新页面和参考有效区间，保留成片及其生成身份')
    args=parser.parse_args()
    if args.material_study and args.baseline_recovery:
        parser.error('--material-study 与 --baseline-recovery 不能同时使用')
    models=('baseline','sparse','material') if args.baseline_recovery else (('baseline','candidate','material') if args.material_study else ('baseline','candidate'))
    if args.material_study:
        OUT=OUT/'material'
    if args.baseline_recovery:
        OUT=OUT/'recovery'
    OUT.mkdir(parents=True, exist_ok=True)
    if args.pages_only:
        saved=json.loads((OUT/'report.json').read_text('utf-8'))
        write_page(saved['scenes'],args.material_study,args.baseline_recovery)
        print('页面已更新；成片及 report.json 的生成身份保持原样')
        return
    ctx=moderngl.create_standalone_context(require=430)
    report=[]
    try:
        for name in args.scenes:
            started=time.perf_counter()
            row=export_scene(name, ctx, models)
            report.append(row)
            print(name, row['particles'], round(time.perf_counter()-started, 2), '秒', flush=True)
    finally:
        ctx.release()
    payload=dict(identity=identity(), scenes=report)
    (OUT/'report.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2), 'utf-8')
    write_page(report,args.material_study,args.baseline_recovery)


if __name__=='__main__':
    main()
