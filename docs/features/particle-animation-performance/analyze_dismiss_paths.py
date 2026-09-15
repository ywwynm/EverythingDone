"""把 `device_dismiss_paths.py` 那一轮的 logcat 切成「关闭方式 × 是否开播」的表。

用法：
    python analyze_dismiss_paths.py <logcat 文件> --run phaseA2

按 `PhaseAMark` 标记切段，每段内取应用自己的 debug 日志判定结果。
logcat 文件必须是跑之前就开着的 `adb logcat -v time` 流——事后 `logcat -d`
在魅族（主缓冲 256 KiB）上捞不回来。
"""
import argparse
import json
import re
from pathlib import Path

MARK = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+I/PhaseAMark\s*\(\s*\d+\):\s*(\S+)')
LINE = re.compile(r'^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+[IWE]/ParticleMicroflake\s*\(\s*\d+\):\s*(.*)$')
DONE = re.compile(r'完成 count=(\d+) frames=(\d+) elapsedMs=([\d.]+) p90Ms=([\d.]+) maxMs=([\d.]+)')
APPEAR = re.compile(r'出现完成 particles=(\d+) frames=(\d+) elapsedMs=([\d.]+) p90Ms=([\d.]+) maxMs=([\d.]+)')
FIRST = re.compile(r'首帧上屏 requestToVisibleMs=([\d.]+)')
ACCEPT = re.compile(r'消散受理：(.*)$')
FAILURES = ('消散未受理：', '消散未开播：', '消散降级淡出：', '消散未接管：', '粒子动画回退')


def parse(path, run):
    """→ [(段标记, [该段内的应用日志行])]，段从 `<case>.<i>.close` 起到下一个标记止。"""
    segments = []
    current = None
    started = run is None
    for raw in Path(path).read_text(encoding='utf-8', errors='replace').splitlines():
        mark = MARK.match(raw)
        if mark:
            label = mark.group(2)
            if run is not None and label.startswith(run + '.begin'):
                started, segments = True, []
                current = None
                continue
            if run is not None and label == run + '.end':
                break
            if not started:
                continue
            current = (label, [])
            segments.append(current)
            continue
        if not started or current is None:
            continue
        line = LINE.match(raw)
        if line:
            current[1].append((line.group(1), line.group(2)))
    return segments


CLOSE = re.compile(r'^([a-z-]+)\.(\d+)\.(close(?:-recording)?)$')
ROUTE = re.compile(r'^抓图路径：(.*)$')


def summarise(segments):
    rows = []
    for label, lines in segments:
        hit = CLOSE.match(label)
        if not hit:
            continue
        row = {'场景': hit.group(1) + ('（录音弹窗）' if hit.group(3).endswith('recording') else ''),
               '轮次': int(hit.group(2)), '标记': label, '是否开播': False}
        for _, text in lines:
            route = ROUTE.match(text)
            if route and '路径' not in row:
                # 取段内**第一条**：弹窗→弹窗那一格里，后面那条是下一个弹窗出现时的抓图。
                row['路径'] = route.group(1)
            accept = ACCEPT.search(text)
            if accept:
                for item in accept.group(1).split():
                    if '=' in item:
                        key, value = item.split('=', 1)
                        row[key] = value
            if FIRST.search(text):
                row['是否开播'] = True
                row['首帧ms'] = float(FIRST.search(text).group(1))
            done = DONE.search(text)
            if done:
                row.update({'片数': int(done.group(1)), '帧数': int(done.group(2)),
                            '时长ms': float(done.group(3)), 'p90ms': float(done.group(4)),
                            '最大ms': float(done.group(5))})
            appear = APPEAR.search(text)
            if appear:
                row.update({'出现片数': int(appear.group(1)), '出现帧数': int(appear.group(2)),
                            '出现p90ms': float(appear.group(4))})
            for prefix in FAILURES:
                if text.startswith(prefix):
                    row.setdefault('失败', []).append(text)
        rows.append(row)
    return rows


def table(rows):
    head = ['场景', '轮次', '开播', '路径', '帧数', 'p90ms', '最大ms', '首帧ms', '片数', '原因']
    out = ['| ' + ' | '.join(head) + ' |', '|' + '---|' * len(head)]
    for row in rows:
        route = row.get('路径', '-')
        # 离屏那条路会先记「离屏异步」再由硬件抓图细化成「硬件离屏」，报表里归一成一个名字。
        if route.startswith('离屏'):
            route = '离屏硬件抓图'
        if row.get('有涟漪') == 'true':
            route += '（有涟漪）'
        reason = '；'.join(row.get('失败', [])) or ''
        out.append('| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s |' % (
            row['场景'], row['轮次'], '是' if row['是否开播'] else '**否**', route,
            row.get('帧数', '-'), row.get('p90ms', '-'), row.get('最大ms', '-'),
            round(row['首帧ms'], 1) if '首帧ms' in row else '-',
            row.get('片数', '-'), reason))
    return '\n'.join(out)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('log')
    parser.add_argument('--run', default=None)
    parser.add_argument('--json', default=None)
    args = parser.parse_args()
    result = summarise(parse(args.log, args.run))
    print(table(result))
    if args.json:
        Path(args.json).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
