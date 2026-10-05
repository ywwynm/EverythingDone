"""华为原生逐项等价实现（harmony-native）的十二段预渲染对照，120 fps，1× 即真实时长。

两列：原片 / 原生实现（干净渲染）。原片是 0.5–4 Mbps 的手机录屏或屏摄，带着编码失真（小颗粒的颜色被 4:2:0 色度采样平均掉、
细节被抹掉）；按用户的验收口径（2026-10-05 晚）不模拟编码失真，此前的第三列「按原片码率重编码」已去掉。
"""
from pathlib import Path
import argparse
import hashlib
import html
import json
import subprocess
import sys
import time

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from desktop_inputs import REFS, META, make_renderer, animation_inputs
from match_refs import RefFrames

OUT = ROOT / 'analysis/match-cache/takeover-20261004/harmony-native'
MODEL = 'harmony-native'
FPS = 120
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 20)


def identity():
    paths = ['harmony_native.py', 'desktop_inputs.py', 'native_reference_inputs.json',
             'shaders/webgl-noise/noise3D.glsl', 'analysis/export_harmony_native_review.py']
    return {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in paths}


def reference_codec(m):
    """原片的编码器、帧率与动画时段实际码率（整段平均码率含大量静止画面，远低于动画时段）。"""
    ref = m['reference']
    out = subprocess.run(['C:/ffmpeg/bin/ffprobe.exe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                          'stream=codec_name,r_frame_rate', '-of', 'json', ref['path']],
                         capture_output=True, text=True, encoding='utf-8', check=True).stdout
    s = json.loads(out)['streams'][0]
    num, den = s['r_frame_rate'].split('/')
    fps = float(num) / float(den)
    out = subprocess.run(['C:/ffmpeg/bin/ffprobe.exe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
                          'packet=pts_time,size', '-of', 'csv=p=0', ref['path']],
                         capture_output=True, text=True, encoding='utf-8', check=True).stdout
    total = n = 0
    for line in out.split():
        t, size = line.split(',')[:2]
        if ref['start'] <= float(t) <= ref['end']:
            total += int(size); n += 1
    return dict(codec=s['codec_name'], fps=fps, total_bits=total * 8, packets=n, bitrate=int(total * 8 / max(n, 1) * fps))


def encoded_like_reference(model, ref, m, box):
    """模型在原片每一帧的进度上渲染，按原片编码参数重编码再解码，返回与原片帧一一对应的裁剪。"""
    codec = reference_codec(m)
    w, h = m['frame']
    # 原片窗口里有大量重复帧（录屏卡顿、帧率高于内容），重复帧几乎不占码率；重编码只送独立帧，
    # 码率要按「窗口总比特 / 独立帧数」折算，否则每个独立帧分到的比特偏少（钢铁侠 313 包对 122 个独立帧，差 2.6 倍）
    codec['bitrate'] = int(codec['total_bits'] / max(len(ref.progress), 1) * codec['fps'])
    br = str(codec['bitrate'])
    lib = 'libx265' if codec['codec'] == 'hevc' else 'libx264'
    tmp = OUT / f'.{name_of(m)}-reencode.mp4'
    enc = subprocess.Popen(['C:/ffmpeg/bin/ffmpeg.exe', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                            '-s', f'{w}x{h}', '-r', str(codec['fps']), '-i', '-', '-an', '-c:v', lib, '-b:v', br,
                            '-bufsize', str(int(br) * 2), '-pix_fmt', 'yuv420p', str(tmp)], stdin=subprocess.PIPE)
    try:
        for p in ref.progress:
            enc.stdin.write(np.ascontiguousarray(model.render(float(p))).tobytes())
    finally:
        enc.stdin.close(); code = enc.wait()
    if code:
        raise RuntimeError(f'重编码失败：{name_of(m)}')
    raw = subprocess.run(['C:/ffmpeg/bin/ffmpeg.exe', '-v', 'error', '-i', str(tmp), '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                          '-'], capture_output=True, check=True).stdout
    tmp.unlink()
    frames = np.frombuffer(raw, np.uint8).reshape(-1, h, w, 3)
    if len(frames) != len(ref.progress):
        raise RuntimeError(f'重编码帧数不符：{len(frames)} ≠ {len(ref.progress)}')
    return [f[box[1]:box[3], box[0]:box[2]].copy() for f in frames], codec


def name_of(m):
    return m.get('name') or Path(m['reference']['path']).stem


def export(name, ctx):
    m = META[name]
    inputs = animation_inputs(name, 'ref', m['seed'])
    model = make_renderer(inputs, MODEL, ctx)
    ref = RefFrames(name)
    w, h = m['frame']; x, y, x1, y1 = m['rect']
    pad = round(min(x1 - x, y1 - y) * .23)
    box = [max(0, x - pad), max(0, y - pad), min(w, x1 + pad), min(h, y1 + pad)]
    cw, ch = box[2] - box[0], box[3] - box[1]; cw -= cw % 2; ch -= ch % 2
    box[2], box[3] = box[0] + cw, box[1] + ch
    codec = None
    head = Image.new('RGB', (cw * 2, 36), '#10151d')
    for i, title in enumerate(['华为原片', '华为原生 · 逐项等价']):
        ImageDraw.Draw(head).text((i * cw + 8, 3), title, font=FONT, fill='white')
    steps = max(2, round(model.playback_duration * FPS))
    ffmpeg = subprocess.Popen(['C:/ffmpeg/bin/ffmpeg.exe', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                               '-s', f'{cw * 2}x{ch + 36}', '-r', str(FPS), '-i', '-', '-an', '-c:v', 'libx264',
                               '-preset', 'veryfast', '-crf', '16', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                               str(OUT / f'{name}.mp4')], stdin=subprocess.PIPE)
    keys = {round(p * steps) for p in [.15, .3, .45, .6, .75, .9]}
    # 原片相邻独立帧的进度间隔；离最近一帧超过 1.5 个间隔说明原片在这里没有新画面（录屏卡顿或录制结束）
    ref_step = float(np.median(np.diff(ref.progress)))
    sheets = []; peak = 0; valid = m['reference'].get('valid_progress', 1.)
    try:
        for i in range(steps + 1):
            p = i / steps
            frame = model.render(p)
            diag = model.diagnostics(); peak = max(peak, diag['active_count'])
            j, near = ref.nearest(p)
            crops = [np.asarray(near)[box[1]:box[3], box[0]:box[2], :3], frame[box[1]:box[3], box[0]:box[2]]]
            joined = Image.fromarray(np.concatenate([np.asarray(head), np.concatenate(crops, axis=1)], axis=0))
            draw = ImageDraw.Draw(joined)
            draw.text((12, 40), f'p={p:.2f}', font=FONT, fill='white', stroke_width=1, stroke_fill='black')
            if p > valid:
                draw.rectangle((0, 36, cw, 75), fill='#4f2923')
                draw.text((12, 42), '原片在此之前已截断，后续不参与比较', font=FONT, fill='white')
            elif abs(float(ref.progress[j]) - p) > 1.5 * ref_step:
                draw.rectangle((0, 36, cw, 75), fill='#4f2923')
                draw.text((12, 42), f'原片此处没有新帧，显示的是 p={float(ref.progress[j]):.2f}', font=FONT, fill='white')
            ffmpeg.stdin.write(np.asarray(joined).tobytes())
            if i in keys:
                small = joined.copy(); small.thumbnail((1200, 700)); sheets.append(small)
            if i == round(.5 * steps):
                joined.save(OUT / f'{name}-poster.jpg', quality=94)
    finally:
        ffmpeg.stdin.close(); code = ffmpeg.wait()
        desc = model.describe()
        model.close()
    if code:
        raise RuntimeError(f'编码失败：{name}')
    canvas = Image.new('RGB', (max(s.width for s in sheets), sum(s.height for s in sheets)), '#10151d')
    yy = 0
    for s in sheets:
        canvas.paste(s, (0, yy)); yy += s.height
    canvas.save(OUT / f'{name}-sheet.jpg', quality=94)
    desc = json.loads(json.dumps(desc, default=float))
    return dict(name=name, title=m.get('title', name), frames=steps + 1, fps=FPS, duration=steps / FPS,
                model=desc, peak_active=peak, valid_progress=valid, crop=box, reencode=codec)


def page(report):
    options = ''.join(f'<option value="{r["name"]}">{html.escape(r["title"])}</option>' for r in report['scenes'])
    return '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>华为原生 · 十二段对照</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#10151d;color:#e6edf5;font:14px/1.75 "Microsoft YaHei",sans-serif}main{max-width:1500px;margin:auto;padding:24px}h1{font-size:22px;margin:0}p{color:#a8b8cb}nav{display:flex;gap:12px;flex-wrap:wrap;align-items:center;margin:14px 0}select,button{font:inherit;background:#243349;color:#e6edf5;border:1px solid #40546c;border-radius:6px;padding:6px 12px}a{color:#8ac4ff}video{display:block;width:100%;max-height:72vh;background:#080b10}input{flex:1;min-width:140px}details img{max-width:100%;display:block;margin:16px auto}.note{padding:12px 16px;background:#1c2634;border-radius:8px}summary{cursor:pointer}</style>
<main><h1>华为原生 · 十二段对照</h1>
<p>左：华为参考　右：按镜像宿主机器码逐项实现的粒子消蚀（harmony_native.py），干净渲染。原片带着编码失真（小颗粒的颜色被 4:2:0 色度采样平均掉，看上去比模型偏白；细节被抹掉），按验收口径不模拟。</p>
<p class="note">运动、消蚀、寿命、尺寸与合成顺序全部取自华为实现（源码逐项对照见 replica 文档第 26 节）。每段只估计那一次动画的输入：录屏各段按「逐颗重合」（源码让每颗粒子的出生位置与轨迹由索引哈希决定，模型与原片的颗粒层同时刻互相关）定出设备比例、扩展尺寸、横风与消蚀噪声格点值；逐颗重合不收敛的段（旧蜘蛛侠、向上钢铁侠、参考 5、参考 3）按粗尺度画面差与解除时刻图估计。录像的时间轴不均匀（手机卡顿时录下的重复帧被去掉、剪辑变速），除科比外每段改用逐帧测得的时间表：每个原片帧在设备时刻 ±14 帧内按逐颗重合（附近取粗尺度最好的一帧）或粗尺度定出设备时刻，再做保序回归，开场（进度 0～0.15 的碎裂起点）因此与原片对齐。粒子数按索引区间逐颗重合实测为 4 万（源码 4×4 卡片档）；参考 3、参考 5、向上钢铁侠、旧蜘蛛侠的颗粒落点无法逐颗对上（出生 0.1 秒内的颗粒在任何平移、8 种坐标约定下都没有峰，参考 5、旧蜘蛛侠的消蚀时序误差只有 7～9 毫秒，属这几台设备的随机对应与源码不同），只做到统计相似；参考 5 按浅色背景上的暗点数与整体压暗量取 2 万（源码缺省档），参考 3 按画面取 5 万（源码最大一档）。粒子绘制后端在镜像中缺失，栅格化取系统镜像的软点纹理 dot01.png、加光、颗粒颜色按已预乘直接累加；颗粒的生长与衰减一半体现为尺寸、一半体现为不透明度（半宽 0.7 × radius × √f、不透明度乘 √f，f = scale/radius），末段颗粒的亮度因此与原片接近；寿命最后 20% 里颗粒遮挡背景的程度再随剩余寿命减弱、颜色不变（两项都是对缺失后端的经验推断，依据是不受颗粒落点影响的颗粒总光量）。参考 2 的设备颗粒层按 src-over 合成（alpha ×0.9），加光混合会让重叠颗粒在深洋红背景上加到发白；参考 5 的设备按标准 src-over（颜色乘随机 alpha，alpha ×1.15），浅色背景上的深蓝颗粒与原片同样深；钢铁侠这台设备寿命末段的颗粒只缩小、不变暗（同样处理会让科比、参考 6 末段过亮，属设备差别）。消蚀噪声格点值另按「卡片还在不在」逐点细化（不降低逐颗重合的改动才采用）；向上钢铁侠头发一带的格点按开场几帧的卡片覆盖率推迟（原片开场卡片上部仍不透明）。手持屏摄的颗粒层另加相机口径；原片里盖在颗粒上面的状态栏图标、灵动胶囊、视频字幕与水印，以及开场时还在淡出的确认框与按「移除」的手指（逐帧；确认框淡到抠不出来之后按淡出系数叠它的半透明图案），按原片提取后叠在最上层；手持段随手机晃动的屏幕反光、卡片背后壁纸的重建误差与录屏里的界面过渡，按原片逐帧量出低频残差场补上（只在卡片与颗粒以外拟合）。视频 120 fps，1× 即真实时长。</p>
<nav><label>序列 <select id="scene">''' + options + '''</select></label><label>速度 <select id="speed"><option value="1">1×</option><option value="0.5">0.5×</option><option value="0.25" selected>0.25×</option></select></label><button id="play">播放 / 暂停</button><a href="/latest.html">返回实时预览 ↗</a><a href="report.json">验证数据</a></nav>
<video id="video" controls muted playsinline preload="auto"></video><nav><button id="prev">前一帧</button><button id="next">后一帧</button><input id="progress" type="range" min="0" max="120" step="1" value="0" aria-label="动画进度"><output id="time">0.00</output></nav>
<p id="info"></p><p id="limit"></p>
<details open><summary>关键帧：六个阶段</summary><img id="sheet" alt="关键帧对照"></details></main>
<script>const report=''' + json.dumps(report, ensure_ascii=False) + ''';const $=id=>document.getElementById(id),v=$('video');let row;
function update(){const p=Math.min(1,v.currentTime/row.duration);$('progress').value=Math.round(p*(row.frames-1));$('time').textContent=p.toFixed(2);$('limit').textContent=p>row.valid_progress?'原片已截断：左栏后续冻结画面不能用于判断相似度。':(row.valid_progress<1?'原片仅录到 '+row.valid_progress.toFixed(2)+' 进度。':'');}
function select(){row=report.scenes.find(r=>r.name==$('scene').value);v.pause();v.src=row.name+'.mp4';v.poster=row.name+'-poster.jpg';v.load();v.playbackRate=Number($('speed').value);$('progress').max=row.frames-1;$('progress').value=0;$('time').textContent='0.00';$('sheet').src=row.name+'-sheet.jpg';const i=row.model.inputs;$('info').textContent='候选粒子 '+i.count.toLocaleString()+' · 峰值活动粒子 '+row.peak_active.toLocaleString()+' · 横风 '+Math.round(i.wind_x)+' · 设备比例 '+i.scale+' · 扩展 '+row.model.canvas.join('×')+' · 进度 1 = '+i.span.toFixed(2)+' 秒'+(row.reencode?' · 重编码 '+row.reencode.codec+' '+(row.reencode.bitrate/1e6).toFixed(2)+' Mbps @ '+row.reencode.fps+' fps':'');update();}
function seek(t){v.pause();v.currentTime=Math.min(row.duration,Math.max(0,t));}
$('scene').onchange=select;$('speed').onchange=()=>v.playbackRate=Number($('speed').value);$('play').onclick=()=>v.paused?v.play():v.pause();$('progress').oninput=()=>seek(Number($('progress').value)/(row.frames-1)*row.duration);$('prev').onclick=()=>seek(v.currentTime-1/row.fps);$('next').onclick=()=>seek(v.currentTime+1/row.fps);v.ontimeupdate=update;select();</script></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenes', nargs='+', default=REFS)
    parser.add_argument('--page-only', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.page_only:
        report = json.loads((OUT / 'report.json').read_text('utf-8'))
        rows = {r['name']: r for r in report.get('scenes', [])}
        for name in REFS:
            f = OUT / f'row-{name}.json'
            if f.exists() and f.stat().st_mtime >= (OUT / 'report.json').stat().st_mtime - 3600 * 24:
                rows[name] = json.loads(f.read_text('utf-8'))
        report['scenes'] = [rows[n] for n in REFS if n in rows]
        report['identity'] = identity()
        (OUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    else:
        old = {}
        if (OUT / 'report.json').exists():
            old = {r['name']: r for r in json.loads((OUT / 'report.json').read_text('utf-8')).get('scenes', [])}
        report = dict(identity=identity(), scenes=[], model=MODEL)
        ctx = moderngl.create_standalone_context(require=430)
        report['renderer'] = ctx.info['GL_RENDERER']
        try:
            for name in REFS:
                if name in args.scenes:
                    begin = time.perf_counter(); row = export(name, ctx)
                    # 每段另存一份，多个导出进程并行时 --page-only 按段合并，避免互相覆盖 report.json
                    (OUT / f'row-{name}.json').write_text(json.dumps(row, ensure_ascii=False, indent=2), 'utf-8')
                    print(name, '活动峰值', row['peak_active'], '耗时', round(time.perf_counter() - begin, 2), flush=True)
                elif name in old:
                    row = old[name]
                else:
                    continue
                report['scenes'].append(row)
                (OUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
        finally:
            ctx.release()
    (OUT / 'index.html').write_text(page(report), 'utf-8')


if __name__ == '__main__':
    main()
