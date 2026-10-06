"""飘散：Android 与桌面实现（harmony_native.py）逐帧对照。

同一组输入（快照字节、格点值、风、粒子数、扩展画布、湍流换算与偏移、内容强调、合成方式）分别交给
- Android：R5CW20BLNKL 上的 `DriftParityRenderActivity`（正式的模型、渲染器与着色器，离屏出帧，纯色底）；
- 桌面：`harmony_native.NativeRenderer`（逐帧推进的源码复刻，ss = 1、设备像素 1:1、同一纯色底），
在同一组设备时刻 T = k / 120 出图，逐像素比较。快照按 Android 的真实路径解码成预乘字节后，桌面直接用设备导出的那一份。

用法（conda 环境 everythingdone）：
    python -X utf8 drift_parity.py --run p2 [--cases ironman-ref,ref5-over,...] [--reuse]
产物在 tmp/harmony-particle-android-port/parity/<run>/。
"""
import argparse
import hashlib
import json
import math
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
TUNING = ROOT / 'docs/features/dialog-particle-dismiss/tuning-0909-gpt6'
sys.path.insert(0, str(TUNING))
import harmony_native as hn  # noqa: E402
import android_drift as ad  # noqa: E402

ADB = r'E:\AndroidSDK\platform-tools\adb.exe'
SERIAL = 'R5CW20BLNKL'
PACKAGE = 'com.ywwynm.everythingdone'
DEVICE_ROOT = f'/sdcard/Android/data/{PACKAGE}/files/drift-parity'
OUT = ROOT / 'tmp/harmony-particle-android-port/parity'
HZ = 120.0
FRAMES = list(range(6, 163, 6))          # 设备帧 6 … 162（0.05 … 1.35 秒）
STYLES = json.loads((ROOT / 'shared/particle-dismiss/drift-styles.json').read_text('utf-8'))


def adb(*args, check=True, capture=True):
    r = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=capture)
    if check and r.returncode != 0:
        raise RuntimeError('adb %s 失败：%s' % (' '.join(args), r.stderr.decode('utf-8', 'replace')))
    return r.stdout.decode('utf-8', 'replace') if capture else ''


def md5(path):
    return hashlib.md5(Path(path).read_bytes()).hexdigest()


def pull_verified(remote, local):
    for attempt in range(4):
        adb('pull', remote, str(local))
        remote_md5 = adb('shell', 'md5sum', remote).split()[0]
        if md5(local) == remote_md5:
            return
    raise RuntimeError('拉取校验失败：' + remote)


# ---------------------------------------------------------------- 输入

def font(size):
    for name in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def reference_snapshot(scene):
    """与 NativeRenderer 相同：场景前景（有修过的副本就用副本）按设备比例 BICUBIC 缩放。"""
    d = TUNING / 'assets' / scene
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    clean = d / 'foreground-clean.png'
    image = Image.open(clean if clean.exists() else d / 'foreground.png').convert('RGBA')
    e = hn.reference_inputs()[scene]
    x0, y0, x1, y1 = meta['rect']
    s = e['scale']
    snap = (max(2, int(round((x1 - x0) * s))), max(2, int(round((y1 - y0) * s))))
    return image.resize(snap, Image.BICUBIC), e


def dialog_snapshot(width, height, dark):
    """自有弹窗的样子：本体白（浅色）或深灰（深色），红色标题、蓝色按钮、灰色正文，圆角。"""
    panel = (48, 48, 48, 255) if dark else (255, 255, 255, 255)
    text = (200, 200, 200, 255) if dark else (90, 90, 90, 255)
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    mask = Image.new('L', (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius=56, fill=255)
    body = Image.new('RGBA', (width, height), panel)
    d = ImageDraw.Draw(body)
    d.text((72, 64), '删除这件记事？', fill=(229, 57, 53, 255), font=font(84))
    for i in range(4):
        d.rectangle((72, 230 + i * 70, width - 72 - (i == 3) * 300, 262 + i * 70), fill=text)
    d.rounded_rectangle((width - 560, height - 170, width - 320, height - 70), radius=50, fill=(30, 136, 229, 255))
    d.rounded_rectangle((width - 290, height - 170, width - 72, height - 70), radius=50, fill=(67, 160, 71, 255))
    img.paste(body, (0, 0), mask)
    return img


def lattice_size(w, h):
    u = 205.0 / (w * h) + 0.002
    return min(int(math.floor(w * u)) + 2, 8), min(int(math.floor(h * u)) + 2, 8)


def emit_frame(sw, sh, ew, eh):
    return [(math.floor(ew) * 0.5 - sw * 0.5) * 0.5, (math.floor(eh) * 0.5 - sh * 0.5) * 0.5, sw * 0.5, sh * 0.5]


def count_for(w, h):
    return int(np.clip(round(40000.0 * (w * h) / 1.3e6 / 1000.0) * 1000, 10000, 50000))


def case_inputs(name):
    """返回 (快照 PIL, spec dict)。spec 与设备端 DriftParityRenderActivity 读的相同。"""
    if name in ('ironman-ref', 'ref5-over'):
        scene = 'ironman' if name == 'ironman-ref' else 'reference-0926-5'
        img, e = reference_snapshot(scene)
        ro = e.get('raster_override') or {}
        over = ro.get('blend') == 'over'
        spec = dict(lattice=dict(width=len(e['lattice'][0]), height=len(e['lattice']),
                                 values=[float(v) for row in e['lattice'] for v in row]),
                    # 宿主 u_wind = (wx, 40000) × 0.5，aeWind = (u_wind.x, −u_wind.y)
                    wind=[e['wind_x'] * 0.5, -20000.0], count=int(e['count']),
                    fadeSizePower=float(ro.get('fade_size_power', 0.5)) if ro.get('size_source') == 'radius_alpha_split' else 0.5,
                    fadeAlphaPower=float(ro.get('fade_alpha_power', 0.5)) if ro.get('size_source') == 'radius_alpha_split' else 0.5,
                    overAlphaMul=float(ro.get('alpha_mul', 1.15)), additive=not over, content=False,
                    expansion=[float(v) for v in e['expansion']],
                    background=[236, 236, 236] if over else [0, 0, 0], style=scene)
        return img, spec
    rng = np.random.RandomState(20261005 + sum(map(ord, name)))
    if name in ('dialog-light', 'dialog-dark', 'dialog-dark-size', 'dialog-light-near'):
        dark = name not in ('dialog-light', 'dialog-light-near')
        w, h = 1225, 760
        img = dialog_snapshot(w, h, dark)
        lw, lh = lattice_size(w, h)
        # 方向 135°（左上），风力取源码范围内的一个值，转到这个方向
        a = math.radians(135.0)
        mag = 0.5 * math.hypot(rng.uniform(-30000, 30000), 40000.0)
        spec = dict(lattice=dict(width=lw, height=lh, values=[float(v) for v in rng.uniform(0, 1, lw * lh)]),
                    wind=[math.cos(a) * mag, -math.sin(a) * mag], count=count_for(w, h),
                    fadeSizePower=0.5, fadeAlphaPower=0.5, overAlphaMul=1.15, additive=dark, content=True,
                    turbulenceOffset=[float(rng.uniform(0, 64)), float(rng.uniform(0, 64))],
                    expansion=[1440.0, min(3088 * 0.85, 2400.0)],
                    background=[24, 24, 24] if dark else [244, 244, 244], style=name)
        if name == 'dialog-dark-size':
            # 设置里的「粒子大小」1.6×、「粒子数量」0.4×（数量按 Android 的公式：截好之后再乘）
            spec.update(sizeScale=1.6, count=ad.count(w, h, np.float32(0.4)))
        if name == 'dialog-light-near':
            # 点在弹窗外很近处：终点离边缘 0.1 个短边，颗粒往那一点收拢（风力倍数由输入核对覆盖，这里只看偏转）
            spec.update(touchGap=0.1)
        return img, spec
    if name == 'ironman-style-dialog':
        # 旧钢铁侠样式用在另一尺寸的卡片上：格点按样式（直接用参考的 4×4）、湍流换算到参考的发射区、风转到 150°
        st = next(s for s in STYLES['styles'] if s['name'] == 'ironman')
        w, h = 1225, 760
        img = dialog_snapshot(w, h, False)
        a = math.radians(150.0)
        spec = dict(lattice=dict(width=len(st['lattice'][0]), height=len(st['lattice']),
                                 values=[float(v) for row in st['lattice'] for v in row]),
                    wind=[math.cos(a) * st['wind'], -math.sin(a) * st['wind']], count=count_for(w, h),
                    fadeSizePower=float(st.get('fadeSizePower', 0.5)), fadeAlphaPower=float(st.get('fadeAlphaPower', 0.5)),
                    overAlphaMul=float(st.get('overAlphaMul', 1.15)), additive=True, content=True,
                    turbulenceFrame=emit_frame(st['size'][0], st['size'][1], st['expansion'][0], st['expansion'][1]),
                    expansion=[1440.0, min(3088 * 0.85, 2400.0)], background=[16, 16, 28], style='ironman')
        return img, spec
    raise ValueError(name)


def finish_spec(img, spec):
    exp = spec['expansion']
    canvas = (int(exp[0]), int(exp[1]))
    w, h = img.size
    # 输出 = 扩展画布；卡片居中（左上角可能落在半像素上，两边都按片元中心精确换算）
    spec.update(snapshot='snapshot.png', output=list(canvas),
                origin=[(canvas[0] - w) * 0.5, (canvas[1] - h) * 0.5],
                times=[k / HZ for k in FRAMES])
    return spec


# ---------------------------------------------------------------- 设备

def run_device(name, folder, img, spec):
    remote = f'{DEVICE_ROOT}/{folder.parent.name}-{name}'
    adb('shell', 'rm', '-rf', remote)
    adb('shell', 'mkdir', '-p', remote)
    img.save(folder / 'snapshot.png')
    (folder / 'spec.json').write_text(json.dumps(spec, ensure_ascii=False), 'utf-8')
    adb('push', str(folder / 'snapshot.png'), remote + '/')
    adb('push', str(folder / 'spec.json'), remote + '/')
    adb('shell', 'am', 'start', '-n', f'{PACKAGE}/.views.particledismiss.DriftParityRenderActivity',
        '--es', 'run', f'{folder.parent.name}-{name}')
    deadline = time.time() + 240
    while time.time() < deadline:
        listing = adb('shell', 'ls', remote)
        if 'error.txt' in listing:
            pull_verified(remote + '/error.txt', folder / 'error.txt')
            raise RuntimeError('设备导出失败：\n' + (folder / 'error.txt').read_text('utf-8'))
        if 'result.json' in listing:
            break
        time.sleep(1.0)
    else:
        raise RuntimeError('设备导出超时：' + name)
    time.sleep(0.5)
    device = folder / 'device'
    device.mkdir(exist_ok=True)
    pull_verified(remote + '/result.json', device / 'result.json')
    result = json.loads((device / 'result.json').read_text('utf-8'))
    pull_verified(remote + '/snapshot.rgba', device / 'snapshot.rgba')
    for f in result['frames']:
        pull_verified(f'{remote}/{f}', device / f)
    adb('shell', 'rm', '-rf', remote)
    return result


# ---------------------------------------------------------------- 桌面

def run_desktop(folder, spec, result, ctx):
    w, h = result['width'], result['height']
    premul = np.frombuffer((folder / 'device/snapshot.rgba').read_bytes(), np.uint8).reshape(h, w, 4).astype(np.float64)
    a = premul[..., 3:4]
    straight = np.where(a > 0, premul[..., :3] / np.maximum(a, 1.0), 0.0)
    straight = np.concatenate([np.minimum(straight, 1.0), a / 255.0], axis=2).astype(np.float32)
    canvas = tuple(spec['output'])
    ox, oy = spec['origin']
    total = int(result['total'])
    inputs = hn.NativeInputs(scale=1.0, expansion=tuple(spec['expansion']), count=total, wind_x=0.0,
                             lattice=tuple(tuple(spec['lattice']['values'][j * spec['lattice']['width']:
                                                                           (j + 1) * spec['lattice']['width']])
                                           for j in range(spec['lattice']['height'])),
                             hz=HZ, span=1.5, offset=0.0, seed=1)
    if spec['additive']:
        raster = hn.LIVE_RASTER
        if (spec['fadeSizePower'], spec['fadeAlphaPower']) != (0.5, 0.5):
            raster = hn.replace(raster, size_source='radius_alpha_split', fade_size_power=spec['fadeSizePower'],
                                fade_alpha_power=spec['fadeAlphaPower'])
    else:
        raster = hn.replace(hn.LIVE_RASTER, blend='over', alpha_mul=spec['overAlphaMul'], alpha_extra=0.0,
                            premul_as_is=False)
        if (spec['fadeSizePower'], spec['fadeAlphaPower']) != (0.5, 0.5):
            raster = hn.replace(raster, size_source='radius_alpha_split', fade_size_power=spec['fadeSizePower'],
                                fade_alpha_power=spec['fadeAlphaPower'])
    bg = Image.new('RGB', canvas, tuple(spec['background']))
    pil = Image.fromarray((straight * 255.0 + 0.5).clip(0, 255).astype(np.uint8), 'RGBA')
    r = hn.NativeRenderer('android-parity', inputs, raster=raster, ctx=ctx, ss=1, image=pil, background=bg,
                          rect=(ox, oy, ox + w, oy + h), frame=canvas)
    assert r.snap == (w, h) and r.canvas == (int(spec['expansion'][0]), int(spec['expansion'][1])), (r.snap, r.canvas)
    r.image_tex.write(straight.tobytes())          # 设备解码出来的同一份预乘字节（换成直通浮点）
    frame = spec.get('turbulenceFrame')
    if frame:
        ew, eh = w * 0.5, h * 0.5
        ex, ey = r.position
        sx, sy = frame[2] / ew, frame[3] / eh
        turb_scale, turb_shift = (sx, sy), (frame[0] - ex * sx, frame[1] - ey * sy)
    else:
        turb_scale, turb_shift = (1.0, 1.0), (0.0, 0.0)
    content = result['content'] if spec.get('content') else None
    r.android_extras(turb_scale=turb_scale, turb_shift=turb_shift, turb_offset=spec.get('turbulenceOffset', (0, 0)),
                     content=content, base_count=int(result['count']), total_seconds=result['totalSeconds'],
                     clamp_alpha=not spec['additive'], wind=spec['wind'], size_scale=spec.get('sizeScale', 1.0),
                     touch_gap=spec.get('touchGap'))
    desk = folder / 'desktop'
    desk.mkdir(exist_ok=True)
    frames = []
    for k, t in zip(FRAMES, spec['times']):
        out = r.render_time(t)
        Image.fromarray(out).save(desk / ('frame-%03d.png' % len(frames)))
        frames.append(out)
    r.close()
    return frames


# ---------------------------------------------------------------- 比较

def compare(folder, result, desktop):
    rows = []
    for i, d in enumerate(desktop):
        a = np.asarray(Image.open(folder / 'device' / result['frames'][i]).convert('RGB')).astype(np.int16)
        diff = np.abs(a - d.astype(np.int16)).max(axis=2)
        rows.append(dict(frame=FRAMES[i], t=round(FRAMES[i] / HZ, 4), mean=float(diff.mean()),
                         p99=float(np.percentile(diff, 99)), p999=float(np.percentile(diff, 99.9)), max=int(diff.max()),
                         over2=float((diff > 2).mean()), over8=float((diff > 8).mean()), over32=float((diff > 32).mean()),
                         lit_android=float((a.max(axis=2) != a[0, 0].max()).mean())))
    return rows


def sheet(folder, result, desktop, picks, title):
    cell = 300
    imgs = []
    for i in picks:
        a = Image.open(folder / 'device' / result['frames'][i]).convert('RGB')
        d = Image.fromarray(desktop[i])
        diff = np.abs(np.asarray(a).astype(np.int16) - desktop[i].astype(np.int16)).max(axis=2)
        heat = Image.fromarray(np.clip(diff * 4, 0, 255).astype(np.uint8)).convert('RGB')
        imgs.append((a, d, heat, FRAMES[i]))
    w0, h0 = imgs[0][0].size
    ch = round(cell * h0 / w0)
    head = 66
    out = Image.new('RGB', (110 + cell * len(imgs), head + ch * 3), (250, 250, 250))
    dr = ImageDraw.Draw(out)
    f = font(20)
    dr.text((8, 8), title, fill=(20, 20, 20), font=f)
    for c, (a, d, heat, k) in enumerate(imgs):
        x = 110 + c * cell
        dr.text((x + 110, 40), 'T=%.3f' % (k / HZ), fill=(60, 60, 60), font=font(16))
        for r_, im in enumerate((a, d, heat)):
            out.paste(im.resize((cell, ch), Image.LANCZOS), (x, head + r_ * ch))
    for r_, label in enumerate(('Android', '桌面', '差×4')):
        dr.text((8, head + r_ * ch + ch // 2), label, fill=(20, 20, 20), font=font(22))
    return out


def crop_sheet(folder, result, desktop, i, box, title, zoom=3):
    a = Image.open(folder / 'device' / result['frames'][i]).convert('RGB').crop(box)
    d = Image.fromarray(desktop[i]).crop(box)
    w, h = a.size
    out = Image.new('RGB', (w * zoom * 2 + 30, h * zoom + 40), (250, 250, 250))
    dr = ImageDraw.Draw(out)
    dr.text((8, 8), title, fill=(20, 20, 20), font=font(18))
    out.paste(a.resize((w * zoom, h * zoom), Image.NEAREST), (0, 40))
    out.paste(d.resize((w * zoom, h * zoom), Image.NEAREST), (w * zoom + 30, 40))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--cases', default='ironman-ref,ref5-over,dialog-light,dialog-dark,ironman-style-dialog')
    p.add_argument('--reuse', action='store_true', help='用已拉回的设备帧，只重跑桌面与比较')
    a = p.parse_args()
    import moderngl
    ctx = moderngl.create_standalone_context(require=430)
    summary = {}
    for name in a.cases.split(','):
        folder = OUT / a.run / name
        folder.mkdir(parents=True, exist_ok=True)
        img, spec = case_inputs(name)
        spec = finish_spec(img, spec)
        if a.reuse and (folder / 'device/result.json').exists():
            result = json.loads((folder / 'device/result.json').read_text('utf-8'))
            spec = json.loads((folder / 'spec.json').read_text('utf-8'))
        else:
            t0 = time.time()
            result = run_device(name, folder, img, spec)
            print(name, '设备导出', '%.1f s' % (time.time() - t0), '颗粒', result['count'], '+', result['total'] - result['count'],
                  '本体置信度 %.2f' % result['content']['panelWeight'], flush=True)
        desktop = run_desktop(folder, spec, result, ctx)
        rows = compare(folder, result, desktop)
        (folder / 'compare.json').write_text(json.dumps(dict(result=result, rows=rows), ensure_ascii=False, indent=1), 'utf-8')
        worst = max(rows, key=lambda r: r['p999'])
        summary[name] = dict(mean=max(r['mean'] for r in rows), p99=max(r['p99'] for r in rows),
                             p999=worst['p999'], over8=max(r['over8'] for r in rows), over32=max(r['over32'] for r in rows),
                             max=max(r['max'] for r in rows), worst_frame=worst['frame'])
        print(name, json.dumps(summary[name]), flush=True)
        picks = [i for i, k in enumerate(FRAMES) if k in (18, 36, 54, 72, 96, 120, 144)]
        sheet(folder, result, desktop, picks, '%s：Android（上）与桌面（中）同一输入、同一设备时刻；下行为逐像素差 ×4' % name
              ).save(folder / 'sheet.png')
    (OUT / a.run / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
