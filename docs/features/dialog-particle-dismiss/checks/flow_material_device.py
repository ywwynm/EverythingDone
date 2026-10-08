"""流体材质的固定输入真机对照；只调用调试包的离屏探针，不访问记事。"""
import argparse
import json
from pathlib import Path
import subprocess
import time

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / 'tmp/flow-material-20261008'
ADB = r'E:\AndroidSDK\platform-tools\adb.exe'
PACKAGE = 'com.ywwynm.everythingdone'
REMOTE = f'/sdcard/Android/data/{PACKAGE}/files/particle-unified'
FRAMES = (0, 10, 15, 18, 20, 34, 40, 48, 60)


def adb(serial, *args, check=True):
    return subprocess.run([ADB, '-s', serial, *map(str, args)], check=check,
                          capture_output=True).stdout.decode('utf-8', errors='replace').strip()


def capture(serial, label, reverse=False, legacy=False):
    folder = OUT / label
    folder.mkdir(parents=True, exist_ok=True)
    # Flyme 覆盖安装后会拒绝仅靠后台广播启动的探针；先经公开首页入口启动进程。
    adb(serial, 'shell', 'am', 'start', '-W', '-n', PACKAGE+'/.activities.ThingsActivity')
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 32)
    for scene, bg, ink in [('holdout-compact-dialog', '#f4f5f8', '#29344a'),
                            ('holdout-dark', '#262a33', '#e0e4ec')]:
        source = Image.new('RGBA', (630, 440))
        draw = ImageDraw.Draw(source)
        draw.rounded_rectangle((0, 0, 629, 439), radius=32, fill=bg)
        draw.text((40, 38), '流体粒子 · 材质验证', font=font, fill='#3986e8')
        draw.text((40, 110), '保留流动、柔化颗粒边缘', font=font, fill=ink)
        draw.text((40, 165), '文字与色块来自同一张快照', font=font, fill=ink)
        for i, color in enumerate(('#e85d6c', '#338bd2', '#69ac91', '#f0b751')):
            draw.rounded_rectangle((40+i*140, 245, 150+i*140, 340), radius=14, fill=color)
        source.save(folder / (scene + '.png'))
        meta = dict(frame=[1080, 1080], rect=[225, 340, 855, 780], direction=135,
                    seed=909602, touch_gap=.8)
        (folder / (scene + '.json')).write_text(json.dumps(meta), 'utf-8')
        adb(serial, 'shell', 'mkdir', '-p', REMOTE)
        # 若旧测试目录有同名输入，保留一份，避免丢失此前调试材料。
        backup = folder / 'previous-inputs'
        backup.mkdir(exist_ok=True)
        for ext in ('.png', '.json'):
            if not (backup/(scene+ext)).exists():
                adb(serial, 'pull', REMOTE+'/'+scene+ext, backup/(scene+ext), check=False)
            adb(serial, 'push', folder/(scene+ext), REMOTE+'/'+scene+ext)
        identity = label + '-' + scene + '-' + str(time.time_ns())
        args = ['shell', 'am', 'broadcast', '-n', PACKAGE+'/.views.particledismiss.ParticleMicroflakeProbeReceiver',
                '--es', 'scene', scene, '--es', 'checkId', identity]
        if reverse:
            args += ['--ez', 'reverseCheck', 'true', '--ei', 'reverseSamples', '16']
        if legacy:
            args += ['--ez', 'legacyMaterial', 'true']
        adb(serial, *args)
        for _ in range(120):
            report = adb(serial, 'shell', 'cat', REMOTE+'/generated/'+scene+'.json', check=False)
            try:
                done = json.loads(report)
            except ValueError:
                done = {}
            if done.get('checkId') == identity:
                if 'error' in done:
                    raise RuntimeError(done)
                break
            failed = adb(serial, 'shell', 'cat', REMOTE+'/generated/failed-check.json', check=False)
            if identity in failed:
                raise RuntimeError(failed)
            time.sleep(.5)
        else:
            raise RuntimeError('探针未完成：'+report)
        for suffix in [f'-{i}.png' for i in FRAMES] + ['.json', '-materials.f32', '-pigment.f32',
                          '-state018.f32', '-state034.f32', '-state040.f32']:
            adb(serial, 'pull', REMOTE+'/generated/'+scene+suffix, folder/('render-'+scene+suffix))
        result = json.loads((folder/('render-'+scene+'.json')).read_text('utf-8'))
        print(json.dumps({k: result.get(k) for k in ('scene', 'renderer', 'count', 'prepareMs',
                                                    'reverseVerified')}, ensure_ascii=False), flush=True)
        for ext in ('.png', '.json'):
            old = backup/(scene+ext)
            if old.exists():
                adb(serial, 'push', old, REMOTE+'/'+scene+ext)
    sheet(folder)


def sheet(folder):
    tiles = []
    for scene, background in [('holdout-compact-dialog', '#d9dde4'), ('holdout-dark', '#101218')]:
        for frame in (10, 18, 20, 34):
            img = Image.open(folder/f'render-{scene}-{frame}.png').convert('RGBA')
            canvas = Image.new('RGBA', img.size, background)
            canvas.alpha_composite(img)
            tiles.append(canvas.crop((150, 190, 930, 890)).resize((390, 350)))
    result = Image.new('RGB', (1560, 700))
    for i, tile in enumerate(tiles):
        result.paste(tile, ((i % 4)*390, (i // 4)*350))
    result.save(folder/'sheet.png')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True, choices=('9018f404', '192.168.100.67:42641'))
    parser.add_argument('--label', required=True)
    parser.add_argument('--reverse', action='store_true')
    parser.add_argument('--legacy', action='store_true')
    args = parser.parse_args()
    capture(args.serial, args.label, args.reverse, args.legacy)
