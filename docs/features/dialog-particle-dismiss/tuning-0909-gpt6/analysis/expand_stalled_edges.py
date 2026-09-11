"""只读扫描多个种子与方向，导出带固定候选标线的逐例视频。

标线来自已释放材料的实际位置与局部密度，不参与运动积分。
这不是根因判定器：低速或短期形状稳定只能作为人工复核的候选。
"""
from pathlib import Path
import argparse, hashlib, json, math, subprocess, sys, time
import cv2
import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import gaussian_filter, map_coordinates

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import code_hash, FFMPEG

REPORT = HERE / 'analysis/stalled-edges-expanded'
VIDEOS = HERE / 'videos'
SCENES = ['ironman', 'thanos', 'kobe', 'language', 'color', 'attachment', 'attachment-image']
SEEDS = [0, 1, 7, 23]
ANGLES = list(range(0, 360, 45))
DIRECTIONS = ['右', '右上', '上', '左上', '左', '左下', '下', '右下']
W, H, FPS = 1600, 1000, 60
COLORS = {'original': (255, 104, 100), 'contour': (255, 208, 83), 'user': (64, 229, 234)}
BG = (14, 20, 30)
FONT = {s: ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', s) for s in [18, 20, 22, 24, 28]}
VERSION = 3


def dump(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), 'utf-8')


def case_list():
    cases = []
    for name in SCENES:
        for seed in SEEDS:
            for angle in ANGLES:
                cases.append(dict(scene=name, seed=seed, angle=angle, anchor=False))
    cases += [dict(scene='ironman', seed=0, angle=122, anchor=True),
              dict(scene='attachment', seed=1, angle=65, anchor=True)]
    for n, c in enumerate(cases):
        c['id'] = f'C{n + 1:03d}'
        c['key'] = f'edge-{c["scene"]}-s{c["seed"]:02d}-d{c["angle"]:03d}'
        c['direction_title'] = DIRECTIONS[c['angle'] // 45] if c['angle'] in ANGLES else '原示例方向'
    return cases


def visibility(base, alpha, t):
    age = t - base[:, 2]
    life = base[:, 6]
    start = np.maximum(life - .075, life * .55)
    f = np.clip((age - start) / np.maximum(life - start, 1e-6), 0, 1)
    fade = (1 - f * f * (3 - 2 * f)) * alpha
    # 与正式淡出公式一致；排除刚释放及太暗的材料中心。
    return (age > .06) & (age < life) & (fade > .15), fade


def runs(points, good, minimum=8, closed=True):
    """沿轮廓顺序切分连续合格区段，不把离散点任意连成边界。"""
    points, good = np.asarray(points), np.asarray(good, bool)
    if not len(points) or not good.any():
        return []
    if good.all():
        return [np.vstack([points, points[:1]])] if closed else [points]
    if closed:
        shift = -int(np.flatnonzero(~good)[0])
        good, points = np.roll(good, shift), np.roll(points, shift, axis=0)
    edges = np.flatnonzero(np.diff(np.r_[False, good, False].astype(int)))
    return [points[a:b] for a, b in zip(edges[::2], edges[1::2]) if b-a >= minimum]


def arc_rect(rect, radius):
    x, y, x1, y1 = rect
    r = radius
    # 均匀约 2 px 采样，含真正的圆角，而不是矩形包围框。
    pts = []
    def line(a, b):
        pts.extend(np.linspace(a, b, max(2, round(np.linalg.norm(np.subtract(a, b))/2)), endpoint=False))
    def arc(center, begin):
        for a in np.linspace(begin, begin + math.pi/2, max(8, round(r*.8)), endpoint=False):
            pts.append(np.array(center) + r*np.array([math.cos(a), math.sin(a)]))
    line((x+r, y), (x1-r, y)); arc((x1-r, y+r), -math.pi/2)
    line((x1, y+r), (x1, y1-r)); arc((x1-r, y1-r), 0)
    line((x1-r, y1), (x+r, y1)); arc((x+r, y1-r), math.pi/2)
    line((x, y1-r), (x, y+r)); arc((x+r, y+r), math.pi)
    return np.asarray(pts, np.float32)


def sample(a, p):
    return map_coordinates(a, [p[:, 1], p[:, 0]], order=1, mode='constant', cval=0)


def make_maps(points, visible, fade, velocity, slow, shape, scale):
    xy = np.rint(points / scale).astype(int)
    keep = visible & (xy[:, 0] >= 0) & (xy[:, 0] < shape[1]) & (xy[:, 1] >= 0) & (xy[:, 1] < shape[0])
    ix = xy[keep, 1]*shape[1]+xy[keep, 0]
    weights = fade[keep]
    def grid(w):
        a = np.bincount(ix, weights=w, minlength=shape[0]*shape[1]).reshape(shape)
        return gaussian_filter(a, 1.35)
    den = grid(weights)
    sl = grid(weights * slow[keep])
    vx, vy = grid(weights * velocity[keep, 0]), grid(weights * velocity[keep, 1])
    return den, sl, vx/np.maximum(den, 1e-5), vy/np.maximum(den, 1e-5)


def simplify(points, epsilon=1.2):
    out = cv2.approxPolyDP(np.asarray(points, np.float32).reshape(-1, 1, 2), epsilon, False)
    return np.round(out[:, 0], 2).tolist()


def dense_line(points):
    p=np.array(points)
    return np.concatenate([np.linspace(a,b,max(2,math.ceil(np.linalg.norm(b-a)/4))) for a,b in zip(p[:-1],p[1:])])


def overlaps(points, other):
    p,q=dense_line(points),dense_line(other)
    distance=np.linalg.norm(p[:,None]-q[None],axis=2).min(axis=1)
    return np.mean(distance<12)>.45


def select_candidates(candidates, max_count=8):
    """同一条边只保留一个参考时刻，避免多条近似标线遮住粒子。"""
    candidates.sort(key=lambda x: x['score'], reverse=True)
    selected = []
    for c in candidates:
        duplicate = False
        for old in selected:
            if old['kind'] != c['kind']:
                continue
            if overlaps(c['points'],old['points']):
                duplicate = True
                break
        if not duplicate:
            selected.append(c)
        if len(selected) >= max_count:
            break
    selected.sort(key=lambda c: (c['kind'] != 'original', -c['score']))
    return selected


def user_marks(c):
    if not c['anchor']:
        return []
    if c['scene'] == 'ironman':
        return [dict(kind='user', points=[[84, 341], [84, 353], [79, 366], [74, 380],
                       [71, 397], [74, 423], [76, 450]], phase=.675,
                       label='你补充：素材内屏幕左侧', score=1e6),
                dict(kind='original', points=[[126,594],[126,706]]+
                     [[155-29*math.cos(a),706+29*math.sin(a)] for a in np.linspace(0,math.pi/2,16)]+[[193,735]],
                     phase=.675,label='上轮标出的左下圆角',score=1e6),
                dict(kind='original', points=[[594,592],[594,706]]+
                     [[565+29*math.cos(a),706+29*math.sin(a)] for a in np.linspace(0,math.pi/2,16)]+[[512,735]],
                     phase=.675,label='上轮标出的右下圆角',score=1e6)]
    points = [[98, 790], [98, 557]]
    points += [[127-29*math.cos(a), 557-29*math.sin(a)] for a in np.linspace(0, math.pi/2, 16)]
    points += [[220, 528]]
    return [dict(kind='user', points=points, phase=.65, label='你补充：原控件左上边缘', score=1e6),
            dict(kind='original',points=[[98,878],[98,1022]]+
                 [[127-29*math.cos(a),1022+29*math.sin(a)] for a in np.linspace(0,math.pi/2,16)]+[[203,1051]],
                 phase=.65,label='上轮标出的左下圆角',score=1e6),
            dict(kind='user', points=[[134,573],[169,566],[205,554],[235,543],[257,535],[271,521],
                 [279,510],[286,521],[302,531],[331,527],[365,522],[405,512],[448,505],[482,500]],
                 phase=.65, label='上轮标出的后段弯边', score=1e6)]


def scan_case(ctx, c, model_hash, render_hash):
    r = Renderer(c['scene'], direction=c['angle'], seed=c['seed'], quality=2, ctx=ctx)
    base = r.base.copy()
    offset = np.array(r.meta['rect'][:2])
    fg = np.array(Image.open(r.directory/'foreground.png').convert('RGBA'))
    alpha = fg[np.clip(base[:, 1].astype(int), 0, r.ch-1), np.clip(base[:, 0].astype(int), 0, r.cw-1), 3]/255.
    positions = np.empty((61, r.n, 2), np.float32)
    velocities = np.empty_like(positions)
    for i in range(61):
        r.seek(i/60)
        state = np.frombuffer(r.state.read(), np.float32).reshape(-1, 8)
        positions[i], velocities[i] = state[:, :2]+offset, state[:, 4:6]
    scale = max(2, r.span/192)
    shape = (math.ceil(r.h/scale)+1, math.ceil(r.w/scale)+1)
    maps, stats = {}, []
    for i in range(12, 59):
        vis, fade = visibility(base, alpha, i/60)
        old, _ = visibility(base, alpha, (i-8)/60)
        move = np.linalg.norm(positions[i]-positions[i-8], axis=1)
        slow = vis & old & (move < r.span*.0015)
        outside = vis & ((positions[i, :, 0] < 0) | (positions[i, :, 0] >= r.w) | (positions[i, :, 1] < 0) | (positions[i, :, 1] >= r.h))
        stats.append(dict(time=round(i/60, 4), visible=int(vis.sum()), slow=int(slow.sum()), outside_frame=int(outside.sum())))
        maps[i] = make_maps(positions[i], vis, fade, velocities[i], slow, shape, scale)
    outline = arc_rect(r.meta['rect'], r.meta['radius'])/scale
    tangent=np.roll(outline,-1,axis=0)-np.roll(outline,1,axis=0)
    normals=np.column_stack((tangent[:,1],-tangent[:,0]))/np.maximum(np.linalg.norm(tangent,axis=1,keepdims=True),1e-5)
    candidates = []
    for i in [27, 33, 39, 45, 51, 56]:
        den, sl, vx, vy = maps[i]
        # 原圆角轮廓只选有成熟、低速材料支撑的连续区段。
        # 材料中心在几何边缘内侧，不能要求中心恰好落在边线像素上。
        support=np.max([sample(sl,outline+normals*k) for k in [-3,-2,-1,0,1]],axis=0)
        support_old=np.max([sample(maps[i-4][1],outline+normals*k) for k in [-3,-2,-1,0,1]],axis=0)
        original_good = (support > .025) & (support_old > .012)
        persistent=np.ones(len(outline),bool)
        for k in [i-8,i-4,i]:
            inside=np.max([sample(maps[k][0],outline-normals*z) for z in [1,2,3]],axis=0)
            outside=np.max([sample(maps[k][0],outline+normals*z) for z in [2,3,4]],axis=0)
            persistent &= (inside>.30)&(outside<np.maximum(.14,inside*.32))
        original_good |= persistent
        original_good = cv2.morphologyEx(original_good.astype('uint8').reshape(1, -1), cv2.MORPH_CLOSE, np.ones((1, 17), np.uint8))[0].astype(bool)
        for segment in runs(outline, original_good, minimum=10):
            length = np.linalg.norm(np.diff(segment, axis=0), axis=1).sum()*scale
            candidates.append(dict(kind='original', points=simplify(segment*scale), phase=round(i/60, 4),
                score=float(length*(.4+sample(sl, segment).mean())), label='原控件边缘附近的低速／稳定轮廓'))
        # 外包络采用成熟粒子密度；跨 133 ms 检查几何稳定，同时容许沿线滑动。
        threshold = .55
        masks = []
        for k in [i-8, i-4, i]:
            mask = (maps[k][0] > threshold).astype('uint8')
            mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3,3), np.uint8))
            masks.append(mask)
        distances = []
        for mask in masks[:2]:
            edge = cv2.morphologyEx(mask, cv2.MORPH_GRADIENT, np.ones((3,3), np.uint8))
            distances.append(cv2.distanceTransform(1-edge, cv2.DIST_L2, 3))
        gy, gx = np.gradient(den)
        contours, _ = cv2.findContours(masks[-1], cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        for contour in contours:
            p = contour[:, 0].astype(float)
            if len(p) < 22:
                continue
            nx, ny = sample(gx, p), sample(gy, p)
            normal = np.abs(sample(vx, p)*nx+sample(vy, p)*ny)/np.maximum(np.hypot(nx, ny), 1e-5)
            stationary = (sample(distances[0], p) < 1.6) & (sample(distances[1], p) < 1.4)
            good = stationary & ((normal < r.span*.024) | (sample(sl, p) > .07))
            # 整幅栅格边缘是观察窗口，只在人工补充示例中作为定位参照。
            good &= (p[:,0] > 1.5) & (p[:,0] < shape[1]-2.5) & (p[:,1] > 1.5) & (p[:,1] < shape[0]-2.5)
            x,y,x1,y1 = np.asarray(r.meta['rect'])/scale
            near = np.minimum.reduce([abs(p[:,0]-x), abs(p[:,0]-x1), abs(p[:,1]-y), abs(p[:,1]-y1)]) < 2.6
            good &= ~near
            for segment in runs(p, good, minimum=10):
                length = np.linalg.norm(np.diff(segment, axis=0), axis=1).sum()*scale
                candidates.append(dict(kind='contour', points=simplify(segment*scale), phase=round(i/60, 4),
                    score=float(length*(.35+sample(sl, segment).mean())), label='短期稳定的粒群外缘'))
    automatic=select_candidates([a for a in candidates if a['kind']=='original'],max_count=5)
    automatic+=select_candidates([a for a in candidates if a['kind']=='contour'],max_count=3)
    anchors=user_marks(c)
    automatic=[a for a in automatic if not any(overlaps(a['points'],b['points']) for b in anchors)]
    annotations = anchors + automatic
    for n, a in enumerate(annotations):
        a['id'] = str(n+1)
        p = np.asarray(a['points'])
        a['bbox'] = [float(p[:,0].min()),float(p[:,1].min()),float(p[:,0].max()),float(p[:,1].max())]
        a['from_phase'] = max(.20, round(a['phase']-.20, 4))
        a['to_phase'] = 1.0
        a['basis']='人工定位，包含用户补充或上轮确认位置' if a in anchors else '实际释放后的位置／密度扫描候选；未判定根因'
    # 保留画面边缘上下文，避免裁切图像制造新的视觉边界。
    x,y,x1,y1 = r.meta['rect']
    lo = max(0, int(y-r.span*.32)); hi = min(r.h, int(y1+r.span*.32))
    crop = [0, lo, r.w, hi]
    result = dict(**c, title=r.meta['title'], model_hash=model_hash, code_hash=render_hash, analysis_version=VERSION,
        frame=[r.w,r.h], rect=r.meta['rect'], crop=crop, radius=r.meta['radius'], particles=r.n,
        annotations=annotations, stats=stats, peak_slow=max(x['slow'] for x in stats),
        peak_outside=max(x['outside_frame'] for x in stats),
        marking_status='有待确认候选' if annotations else '扫描未找到可靠连续候选，保留原画复核')
    # 选多个时刻的原画，供生成总览与人工检查，不为每例保留巨大无损帧缓存。
    preview = Image.new('RGB',(560*3,660),BG)
    for j,t in enumerate([.45,.65,.85]):
        im = r.render(t)
        tile = compose(result, im, t)
        Image.fromarray(tile).resize((560,350)).save(REPORT/f'{c["key"]}-p{round(t*100):02d}.jpg', quality=94)
        # 检查图只展示标注侧，保留一行状态供定位。
        side = Image.fromarray(tile[:, W//2:])
        side.thumbnail((560,660));preview.paste(side,(j*560+(560-side.width)//2,0))
    preview.save(REPORT/f'{c["key"]}-review.jpg',quality=95)
    phase = max(.60,annotations[0]['phase']) if annotations else .75
    im = compose(result, r.render(phase), phase)
    Image.fromarray(im).resize((800,500)).save(VIDEOS/f'{c["key"]}.jpg', quality=92)
    r.close()
    dump(REPORT/f'{c["key"]}.json',result)
    print(c['id'],c['key'],'候选',len(annotations),'低速',result['peak_slow'],'出画',result['peak_outside'],flush=True)
    return result


def layout(c):
    x,y,x1,y1 = c['crop']
    scale = min(770/(x1-x), 794/(y1-y))
    tw,th = round((x1-x)*scale),round((y1-y)*scale)
    return dict(x=15+(770-tw)//2, y=108+(794-th)//2, w=tw,h=th,scale=scale)


def compose(c, raw, phase):
    result = Image.new('RGB',(W,H),BG)
    d = ImageDraw.Draw(result)
    title = f'{c["id"]}  {c["title"]}  ·  种子 {c["seed"]}  ·  {c["direction_title"]} {c["angle"]}°'
    d.text((22,15),title,font=FONT[28],fill=(239,244,250))
    d.text((22,59),'左：同一帧原画',font=FONT[22],fill=(173,190,207))
    d.text((822,59),'右：固定候选标线',font=FONT[22],fill=(173,190,207))
    x,y,x1,y1=c['crop']; lay=layout(c)
    tile=np.array(Image.fromarray(raw[y:y1,x:x1]).resize((lay['w'],lay['h']),Image.Resampling.LANCZOS))
    result.paste(Image.fromarray(tile),(lay['x'],lay['y']))
    marked=tile.copy()
    labels=[]
    for a in c['annotations']:
        if phase < a['from_phase']:
            continue
        p=(np.asarray(a['points'])-[x,y])*lay['scale']
        p=np.rint(p).astype(np.int32).reshape(-1,1,2)
        cv2.polylines(marked,[p],False,(7,12,20),5,cv2.LINE_AA)
        cv2.polylines(marked,[p],False,COLORS[a['kind']],2,cv2.LINE_AA)
        labels.append((p[len(p)//2,0],a))
    result.paste(Image.fromarray(marked),(lay['x']+800,lay['y']))
    d=ImageDraw.Draw(result)
    for p,a in labels:
        tx=int(p[0])+lay['x']+800;ty=int(p[1])+lay['y']
        tx=max(811,min(1563,tx+5));ty=max(111,min(875,ty-24))
        d.rounded_rectangle((tx-3,ty-1,tx+21,ty+26),5,fill=BG)
        d.text((tx,ty),a['id'],font=FONT[20],fill=COLORS[a['kind']])
    d.text((22,920),'红：原控件边缘候选',font=FONT[22],fill=COLORS['original'])
    d.text((328,920),'黄：其他稳定外缘候选',font=FONT[22],fill=COLORS['contour'])
    d.text((666,920),'青：你补充／此前定位',font=FONT[22],fill=COLORS['user'])
    d.text((1250,920),f'动画进度 t={phase:.3f}',font=FONT[22],fill=(239,244,250))
    note='线条固定在候选参考位置，便于观察形状是否停住；尚未修改动画。'
    if not c['annotations']:note='本组未检出可靠连续候选，保留原画请继续复核；不表示不存在问题。'
    d.text((22,960),note,font=FONT[18],fill=(160,178,197))
    return np.asarray(result)


def encode_case(ctx,c,force=False):
    path=VIDEOS/f'{c["key"]}.mp4'
    manifest=REPORT/f'{c["key"]}-video.json'
    if not force and path.exists() and manifest.exists():
        old=json.loads(manifest.read_text('utf-8'))
        if old.get('code_hash')==c['code_hash'] and old.get('analysis_version')==VERSION:
            return old
    r=Renderer(c['scene'],direction=c['angle'],seed=c['seed'],quality=2,ctx=ctx)
    temp=path.with_suffix('.encoding.mp4')
    command=[FFMPEG,'-hide_banner','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}',
      '-r',str(FPS),'-i','pipe:0','-an','-vf','scale=out_color_matrix=bt709:in_range=full:out_range=limited',
      '-c:v','libx264','-threads','4','-preset','fast','-crf','17','-pix_fmt','yuv420p','-color_range','tv',
      '-color_primaries','bt709','-color_trc','bt709','-colorspace','bt709','-movflags','+faststart',str(temp)]
    # 0.5 倍基础片：120 个实际时间样本在 60 fps 下播放，首尾短停留。
    phases=[0.]*12+[i/120 for i in range(121)]+[1.]*18
    with (REPORT/'encoding.log').open('ab') as log:
        process=subprocess.Popen(command,stdin=subprocess.PIPE,stderr=log)
        last=-1;frame=None
        try:
            for phase in phases:
                if phase!=last:
                    frame=compose(c,r.render(phase),phase).tobytes();last=phase
                process.stdin.write(frame)
        finally:
            process.stdin.close();r.close()
        if process.wait()!=0:raise RuntimeError(f'编码失败：{c["key"]}')
    temp.replace(path)
    meta=dict(file=path.name,poster=f'{c["key"]}.jpg',title=f'{c["id"]} {c["title"]} 种子{c["seed"]} 方向{c["angle"]}°',
      purpose='固定边缘位置确认，非修复版本',model_hash=c['model_hash'],code_hash=c['code_hash'],analysis_version=VERSION,
      sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size,frames=len(phases),fps=FPS,
      width=W,height=H,seconds=len(phases)/FPS,case_id=c['id'],base_rate=.5,lead_in=.2,layout=layout(c))
    dump(manifest,meta)
    print('视频',c['id'],round(meta['bytes']/1024**2,2),'MiB',flush=True)
    return meta


def main():
    p=argparse.ArgumentParser();p.add_argument('--mode',choices=['scan','encode','all'],default='all')
    p.add_argument('--ids',nargs='*');p.add_argument('--force',action='store_true');args=p.parse_args()
    REPORT.mkdir(parents=True,exist_ok=True)
    model_hash,render_hash=model_fingerprint(),code_hash()
    cases=case_list()
    if args.ids:cases=[c for c in cases if c['id'] in args.ids]
    ctx=moderngl.create_standalone_context(require=430)
    try:
        for c in cases:
            saved=REPORT/f'{c["key"]}.json'
            result=json.loads(saved.read_text('utf-8')) if saved.exists() else None
            valid=result and result.get('code_hash')==render_hash and result.get('analysis_version')==VERSION
            if args.mode in ['scan','all'] and (args.force or not valid):
                result=scan_case(ctx,c,model_hash,render_hash)
            if args.mode in ['encode','all']:
                if result is None:raise ValueError(f'尚未扫描：{c["id"]}')
                encode_case(ctx,result,args.force)
    finally:ctx.release()
    assert model_fingerprint()==model_hash and code_hash()==render_hash,'诊断期间正式模型发生变化'
    print('本批完成',len(cases),'组；正式模型与渲染代码散列未变化。',flush=True)


if __name__=='__main__':main()
