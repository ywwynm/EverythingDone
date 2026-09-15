"""录音弹窗（准备态）的两件事：FableSol 表面的色彩空间，以及关闭时的消散抓图。

只向输入系统注入真实点击与按键，不发业务 Intent、不改任何系统设置、不授予任何权限。
判据来自三处：应用自己的 debug 日志（必须在跑之前另起一条 `logcat -v time` 流到文件）、
`dumpsys SurfaceFlinger` 里那一层的 dataspace / 像素格式 / 合成方式，以及系统截屏。

用法（Bash，先 `export MSYS_NO_PATHCONV=1` 与 `PYTHONUTF8=1`）：

    python device_recording_dialog.py --serial 392QBFED2226B --run before1
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'docs/features/particle-animation-performance'))

ADD_ATTACHMENT = ('Add attachment', '添加附件', '新增附件', '增加附件')
PLAY_AUDIO_ROW = 'tv_record_audio_as_bt'
VISUALIZER = 'voice_visualizer'

paths = None  # device_dismiss_paths，解析完 --serial 再导入


def adb(*args):
    return subprocess.run([paths.ui.ADB, '-s', paths.ui.SERIAL, *map(str, args)],
                          capture_output=True, check=True).stdout


def text(*args):
    return adb(*args).decode(errors='replace')


def surfaceflinger_layers(keyword):
    """含 [keyword] 的图层条目。dumpsys SurfaceFlinger 的层名里带包名与 View 类名。"""
    out = text('shell', 'dumpsys', 'SurfaceFlinger')
    hits = []
    for block in out.split('+ ')[1:]:
        head = block.splitlines()[0]
        if keyword.lower() not in head.lower():
            continue
        fields = {}
        for key in ('dataspace', 'pixelformat', 'composition type', 'api',
                    'z-order', 'crop', 'transform'):
            found = re.search(re.escape(key) + r'[=:]\s*([^\s,\]]+)', block, re.I)
            if found:
                fields[key] = found.group(1)
        hits.append({'层': head.strip()[:200], **fields,
                     '原文': '\n'.join(block.splitlines()[:14])})
    return hits


def layer_dump(keyword, out_dir, label):
    """整段原文落盘：SurfaceFlinger 的字段名各厂商不一致，解析不到时要能回头看。"""
    out = text('shell', 'dumpsys', 'SurfaceFlinger')
    (out_dir / ('sf-%s.txt' % label)).write_text(out, encoding='utf-8')
    return [line for line in out.splitlines()
            if keyword.lower() in line.lower() or 'Display 0' in line][:40]


def screencap(out_dir, label):
    """adb pull 不可信，按内容哈希校验（见 .claude/rules/adb.md）。"""
    remote = '/sdcard/fablesol-shot.png'
    dest = out_dir / ('%s.png' % label)
    for _ in range(3):
        adb('shell', 'screencap', '-p', remote)
        digest = text('shell', 'md5sum', remote).strip().split()[0]
        adb('pull', remote, str(dest))
        import hashlib
        local = hashlib.md5(dest.read_bytes()).hexdigest()
        if local == digest:
            adb('shell', 'rm', '-f', remote)
            return str(dest)
    adb('shell', 'rm', '-f', remote)
    raise RuntimeError('截图三次都没校验通过')


def start_screenrecord(out_dir, label, seconds=8):
    """录屏必须在关闭动作之前开起来。screenrecord 是变帧率，逐帧时间戳另行读取。"""
    remote = '/sdcard/rec-%s.mp4' % label
    adb('shell', 'rm', '-f', remote)
    proc = subprocess.Popen(
        [paths.ui.ADB, '-s', paths.ui.SERIAL, 'shell', 'screenrecord',
         '--bit-rate', '20000000', '--time-limit', str(seconds), remote],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.4)   # 录制真正开始需要一点时间
    return proc, remote


def stop_screenrecord(proc, remote, out_dir, label):
    """按 Ctrl-C 语义停不了 adb shell 的子进程，直接等它自己到时限最稳。"""
    proc.wait(timeout=30)
    dest = out_dir / ('rec-%s.mp4' % label)
    import hashlib
    for _ in range(3):
        digest = text('shell', 'md5sum', remote).strip().split()[0]
        adb('pull', remote, str(dest))
        if hashlib.md5(dest.read_bytes()).hexdigest() == digest:
            adb('shell', 'rm', '-f', remote)
            return str(dest)
    raise RuntimeError('录屏三次都没校验通过')


def open_recording_dialog(detail):
    paths.tap(paths.node(detail, desc=ADD_ATTACHMENT))
    attach = paths.dump(PLAY_AUDIO_ROW)
    time.sleep(1.0)
    paths.tap(paths.node(attach, rid=PLAY_AUDIO_ROW))
    return paths.dump(VISUALIZER)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--settle', type=float, default=2.0)
    parser.add_argument('--gap', type=float, default=3.0)
    parser.add_argument('--closes', default='back,outside')
    parser.add_argument('--record', type=int, default=0,
                        help='录屏秒数；0 = 不录。关闭动作前开始，到时限自停。')
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_-]{1,80}', args.run)
    os.environ['THING_ANIMATION_SERIAL'] = args.serial
    global paths
    import device_dismiss_paths as paths  # noqa: E402
    sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))
    import device_ui  # noqa: E402
    paths.ui = device_ui

    out = REPO / 'tmp/fablesol-recording-dialog' / args.serial / args.run
    out.mkdir(parents=True, exist_ok=True)
    screen = paths.screen_size()
    paths.mark('%s.begin.screen=%dx%d' % (args.run, *screen))
    home = paths.launch()
    paths.open_detail(home, None)

    records = []
    closers = args.closes.split(',')
    try:
        for index in range(args.rounds):
            for how in closers:
                key = 'rec-%s' % how
                detail = paths.dump('et_content', forbid=VISUALIZER)
                paths.mark('%s.%d.open' % (key, index))
                opened = open_recording_dialog(detail)
                time.sleep(args.settle)
                record = {'键': key, '轮次': index,
                          '弹窗窗口': paths.bounds(paths.window_of(opened, VISUALIZER))}
                if index == 0:
                    label = '%s-%d' % (key, index)
                    record['图层'] = surfaceflinger_layers('everythingdone')
                    record['图层原文'] = layer_dump('everythingdone', out, label)
                    record['截图'] = screencap(out, label)
                recorder = None
                if args.record:
                    recorder = start_screenrecord(out, '%s-%d' % (key, index), args.record)
                paths.mark('%s.%d.close' % (key, index))
                if how == 'back':
                    adb('shell', 'input', 'keyevent', '4')
                else:
                    x, y = paths.outside_point(opened, VISUALIZER, screen)
                    adb('shell', 'input', 'tap', x, y)
                    record['点外部坐标'] = [x, y]
                try:
                    paths.dump('et_content', forbid=VISUALIZER, tries=20)
                except RuntimeError:
                    record['关闭未命中'] = True
                    adb('shell', 'input', 'keyevent', '4')
                    paths.dump('et_content', forbid=VISUALIZER)
                if recorder is not None:
                    record['录屏'] = stop_screenrecord(*recorder, out, '%s-%d' % (key, index))
                paths.mark('%s.%d.done' % (key, index))
                records.append(record)
                print(json.dumps({k: v for k, v in record.items() if k != '图层原文'},
                                 ensure_ascii=False), flush=True)
                time.sleep(args.gap)
    finally:
        paths.mark('%s.end' % args.run)
        (out / 'cases.json').write_text(json.dumps(records, ensure_ascii=False, indent=2),
                                        encoding='utf-8')
        paths.back_to_home()
        paths.soft('shell', 'rm', '-f', paths.UI_DUMP)
    print('写入', out / 'cases.json', flush=True)


if __name__ == '__main__':
    main()
