"""第十批（粒子动画设置页第六轮三条）的真机取证驱动。

只经真实系统输入操作，不发业务 Intent、不改任何系统设置、不碰记事数据。
截图一律「设备端 screencap + md5 校验 + pull」（见 .claude/rules/adb.md）。

三个子模式，各自对应用户的三条：

* `--mode gradient`：**状态栏与顶栏的渐变是否连成一条**。取顶栏上沿那条交界线，
  比较「交界线上下相邻两行」的逐像素色差，并用「状态栏内部相邻两行」作**噪声底**
  （同一条渐变里相邻行本来就有一档色差）。分开铺两条渐变时交界处会跳一大截，
  铺成一条时交界处的色差应当落回噪声底。
* `--mode slider`：**滑动条两端的对齐**。把滑杆拖到 0 / 中间 / 最大值各截一张：两端量
  **滑块外缘**，中间量**轨道两端**（滑块让开了两端的轨道才露出来），都与上方标题左缘 /
  右侧读数右缘（uiautomator bounds）比。两者的差之和恒等于滑块半径，只能分配，所以
  必须分别报。跑之前备份 `shared_prefs/particle_tuning.xml`，跑完写回并读回核对。
* `--mode edges`：**右缘对齐**。粒子大小读数、流动精细度读数、颜色精度胶囊、勾选框
  四项，各量「控件 bounds 右缘」与「墨迹右缘」到屏幕右缘的距离。

`--mode all` 依次跑这三个。

用法：
    THING_ANIMATION_SERIAL=<序列号> python device_batch10_ui.py --run b10-ui --mode all --tag after
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(REPO / 'docs/features/thing-creation-animation'))

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

import device_particle_perf as d  # noqa: E402
import device_particle_tuning as t  # noqa: E402
import device_batch8_ui as b8  # noqa: E402
import device_ui as ui  # noqa: E402

PREFS_PATH = 'shared_prefs/particle_tuning.xml'


def density():
    raw = t.soft('shell', 'wm', 'density').stdout.decode('utf-8', 'replace')
    got = re.findall(r'Override density:\s*(\d+)', raw) or \
        re.findall(r'Physical density:\s*(\d+)', raw)
    assert got, '取不到屏幕密度'
    return int(got[-1]) / 160.0


def bounds_of(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds'))))


def collect(ids, tries=40, height=None):
    """把这一批控件的 bounds 收齐（页面上一直有粒子动画在播，一次 dump 收不全）。"""
    found = {}
    for _ in range(tries):
        for n in d.dump().iter('node'):
            rid = (n.get('resource-id') or '')
            for want in ids:
                if not rid.endswith('/' + want) or want in found:
                    continue
                box = bounds_of(n)
                if height and (box[3] >= height - 2 or box[1] <= 0):
                    continue
                found[want] = box
        if len(found) == len(ids):
            break
        time.sleep(.3)
    return found


# ---- ① 状态栏与顶栏的渐变交界 ----


def toolbar_top(tries=40):
    """顶栏上沿 = 状态栏占位 View 的下沿 = 两块的交界线。

    `actionbar` 这个 Toolbar 在 uiautomator 里一般是有的；退一步用顶栏里的返回按钮
    （`content-desc` 为「向上导航」一类）按 `?attr/actionBarSize` 反推。
    两条都拿不到时返回 None，由调用方判失败——**不猜坐标**。
    """
    nav_keys = ('Navigate up', '向上导航', '转到上一层级', 'Open navigation drawer')
    for _ in range(tries):
        tree = d.dump()
        for n in tree.iter('node'):
            if (n.get('resource-id') or '').endswith('/actionbar'):
                box = bounds_of(n)
                return box[1], box[3], 'actionbar'
        for n in tree.iter('node'):
            if (n.get('content-desc') or '') in nav_keys:
                y1, y2 = bounds_of(n)[1], bounds_of(n)[3]
                mid = (y1 + y2) // 2
                half = int(28 * density())
                return mid - half, mid + half, 'nav-button'
        time.sleep(.3)
    return None, None, None


def row_delta(img, y0, y1, x_from, x_to):
    """两行像素的逐点色差：给出平均、p99 与最大（三通道取最大差）。"""
    a = img[y0, x_from:x_to, :3].astype(np.float32)
    b = img[y1, x_from:x_to, :3].astype(np.float32)
    diff = np.abs(a - b).max(axis=1)
    return {'mean': round(float(diff.mean()), 3),
            'p99': round(float(np.percentile(diff, 99)), 3),
            'max': round(float(diff.max()), 3)}


def measure_seam(folder, name, seam, bottom, width):
    """量一次交界：交界线上下相邻两行的色差，与同一块内部相邻两行的色差（噪声底）。

    另外记一个 `vertical_span`：顶部这一整块最上一行与最下一行的色差。它用来判断
    「这一次随机到的主题色**竖直方向上有没有渐变**」——纯色与横向渐变都是 0，
    交界断不断开无从谈起。
    """
    shot = t.shot(folder, name)
    img = np.asarray(Image.open(shot).convert('RGB'))
    # 状态栏里有系统图标（时钟、电量），贴着交界线的那几行才是干净的底色；
    # 左右各留 8% 避开圆角与挖孔。
    x_from, x_to = int(width * .08), int(width * .92)
    entry = {
        'shot': shot,
        'sample_x': [x_from, x_to],
        'seam_step': row_delta(img, seam - 1, seam, x_from, x_to),
        'status_step': row_delta(img, seam - 3, seam - 2, x_from, x_to),
        'toolbar_step': row_delta(img, seam + 2, seam + 3, x_from, x_to),
    }
    entry['noise_floor'] = round(
        max(entry['status_step']['mean'], entry['toolbar_step']['mean']), 3)
    entry['continuous'] = entry['seam_step']['mean'] <= entry['noise_floor'] + 1.0
    entry['vertical_span'] = row_delta(img, 2, bottom - 3, x_from, x_to)['mean']
    # 交界处上下各 24 px 的放大条，便于目视。
    band = Image.fromarray(img[max(0, seam - 24):seam + 24, x_from:x_to])
    band = band.resize((band.width, band.height * 6), Image.NEAREST)
    strip = Path(folder) / (name + '-seam-strip.png')
    band.save(strip)
    entry['seam_strip'] = str(strip)
    picks = np.linspace(x_from, x_to - 1, 9).astype(int)
    entry['seam_rows'] = {
        'above': [[int(v) for v in img[seam - 1, x]] for x in picks],
        'below': [[int(v) for v in img[seam, x]] for x in picks],
        'x': [int(v) for v in picks],
    }
    return entry


def mode_gradient(folder, prefix, result, scale, colors=0, need_gradient=False):
    t.open_page()
    time.sleep(1.2)
    width, height = t.screen_size()
    result['screen'] = [width, height]
    seam, bottom, how = toolbar_top()
    assert seam, '取不到顶栏上沿'
    result['seam_y'] = seam
    result['header_bottom'] = bottom
    result['seam_from'] = how
    entry = measure_seam(folder, prefix + 'header', seam, bottom, width)
    result.update({k: v for k, v in entry.items() if k != 'shot'})
    result['shot_header'] = entry['shot']
    # 「随机到渐变色」那一档：连点换色按钮，每次量一遍。主题色取自用户自己的记事，
    # 竖直分量大的渐变在交界处跳得最明显；纯色与纯横向渐变在这里本来就没有交界可言，
    # 所以 [need_gradient] 打开时一直点到真的抽到一条竖直分量大的渐变为止。
    picks = []
    for i in range(colors):
        _, palette = t.find_rid('iv_particle_tuning_palette')
        d.tap(palette)
        time.sleep(1.6)
        one = measure_seam(folder, '%scolor-%d' % (prefix, i + 1), seam, bottom, width)
        picks.append(one)
        if need_gradient and one['vertical_span'] >= 20:
            break
    if picks:
        result['color_picks'] = picks
        gradients = [p for p in picks if p['vertical_span'] >= 20]
        result['gradient_picks'] = len(gradients)
        worst = max(gradients or picks, key=lambda p: p['seam_step']['mean'])
        result['worst_pick'] = {'seam_step': worst['seam_step'],
                                'noise_floor': worst['noise_floor'],
                                'vertical_span': worst['vertical_span'],
                                'shot': worst['shot'],
                                'seam_strip': worst['seam_strip'],
                                'continuous': worst['continuous']}
    return result


# ---- ② 滑块两端的极限位置 ----


def background_at(img, x, y):
    return img[y, x, :3].astype(np.float32)


def modal_color(img, box):
    """一块区域里出现次数最多的那个颜色。

    底色**不能只取一个像素**：取到抗锯齿边或字形上，整块判定就全反过来
    （三星上实际发生过：底色取在墨迹上，`ink_span` 于是把整块都当成墨迹，
    右缘报到探测框的边上）。
    """
    x1, y1, x2, y2 = box
    patch = img[y1:y2, x1:x2, :3].reshape(-1, 3)
    if patch.size == 0:
        return None, 0.0
    colors, counts = np.unique(patch, axis=0, return_counts=True)
    top = int(counts.argmax())
    return colors[top].astype(np.float32), float(counts[top]) / patch.shape[0]


def ink_span(img, box, bg, threshold=30):
    """控件区域里「不是底色」的像素横向跨度（首尾 x）。"""
    x1, y1, x2, y2 = box
    patch = img[y1:y2, x1:x2, :3].astype(np.float32)
    mask = np.abs(patch - bg).max(axis=2) > threshold
    cols = np.where(mask.any(axis=0))[0]
    if cols.size == 0:
        return None
    return int(x1 + cols[0]), int(x1 + cols[-1])


def set_slider(rid, where):
    """把滑杆推到某个位置（`min` / `mid` / `max`）。

    起手点取滑杆**中段**——横向手势的起点离左右屏幕边缘 140 px 以内会被系统的返回手势
    吃掉（见 .claude/rules/adb.md）；落点不受这条限制，所以直接推到屏幕边上。
    """
    tree, _ = t.find_rid(rid)
    x1, y1, x2, y2 = t.bar_bounds(tree, rid)
    y = (y1 + y2) // 2
    start = (x1 + x2) // 2
    end = {'min': x1 + 4, 'mid': (x1 + x2) // 2, 'max': x2 - 4}[where]
    if where == 'mid':
        # 中段那一格是为了让**两端的轨道**都露出来：先推到一端再拉回中间，
        # 免得「起点就是终点」的手势什么也不做。
        t.adb('shell', 'input', 'swipe', start, y, x1 + 4, y, 420)
        time.sleep(.5)
        t.adb('shell', 'input', 'swipe', x1 + int((x2 - x1) * .12), y, end, y, 520)
    else:
        t.adb('shell', 'input', 'swipe', start, y, end, y, 520)
    time.sleep(.8)


def measure_slider(folder, prefix, result, scale, key, bar_rid, label_rid, value_rid):
    """三个位置各截一张。

    * `min` / `max`：滑块顶到两端，量**滑块外缘**；
    * `mid`：滑块在中间、两端的**轨道**都露着，量轨道的左右两端。

    两者与文本边缘的差之和恒等于滑块半径（`AbsSeekBar` 把滑块中心放在内边距上），
    所以必须分别报，只报一个说明不了对齐到底是什么样。
    """
    out = {}
    for end in ('min', 'max', 'mid'):
        set_slider(bar_rid, end)
        time.sleep(1.2)
        boxes = collect((bar_rid, label_rid, value_rid))
        assert bar_rid in boxes, '取不到滑杆 bounds'
        shot = t.shot(folder, '%s%s-%s' % (prefix, key, end))
        img = np.asarray(Image.open(shot).convert('RGB'))
        bx1, by1, bx2, by2 = boxes[bar_rid]
        # 底色按整条滑杆里**出现次数最多**的颜色取，不取某一个点：滑杆的上下内边距是 0，
        # 「上沿那一行的中点」正好会落在滑块圆顶上（滑块停在正中时必中），底色一旦取到墨迹，
        # 整条就会被判成墨迹，两端读数变成滑杆自己的边（2026-09-18 在流动精细度那根上实际发生）。
        bg, share = modal_color(img, (bx1, by1, bx2, by2))
        assert bg is not None and share >= .5, '滑杆底色判不出来（占比 %.2f）' % share
        span = ink_span(img, (bx1, by1, bx2, by2), bg)
        assert span, '滑杆区域里没量到墨迹'
        entry = {'shot': shot, 'bar_bounds': list(boxes[bar_rid]),
                 'ink': list(span), 'bg': [int(v) for v in bg], 'bg_share': round(share, 3)}
        # 左端两者都取「首个像素」；右端 bounds 的右缘是**开区间**、墨迹的 `span[1]` 是
        # 最后一个像素，要 +1 才是同一个口径（否则恒差 1 px）。
        if end == 'min' and label_rid in boxes:
            entry['what'] = '滑块外缘 左'
            entry['text_edge'] = boxes[label_rid][0]
            entry['edge'] = span[0]
        elif end == 'max' and value_rid in boxes:
            entry['what'] = '滑块外缘 右'
            entry['text_edge'] = boxes[value_rid][2]
            entry['edge'] = span[1] + 1
        elif end == 'mid':
            entry['what'] = '轨道两端'
            if label_rid in boxes:
                entry['track_left_delta_dp'] = round(
                    (span[0] - boxes[label_rid][0]) / scale, 2)
                entry['track_left'] = span[0]
                entry['text_left'] = boxes[label_rid][0]
            if value_rid in boxes:
                entry['track_right_delta_dp'] = round(
                    (span[1] + 1 - boxes[value_rid][2]) / scale, 2)
                entry['track_right'] = span[1] + 1
                entry['text_right'] = boxes[value_rid][2]
        if 'text_edge' in entry:
            entry['delta_dp'] = round((entry['edge'] - entry['text_edge']) / scale, 2)
        out[end] = entry
    result.setdefault('slider', {})[key] = out
    return out


def mode_slider(folder, prefix, result, scale):
    result['prefs_before'] = t.soft(
        'shell', 'run-as', ui.PACKAGE, 'cat', PREFS_PATH
    ).stdout.decode('utf-8', 'replace')
    t.open_page()
    time.sleep(1.0)
    measure_slider(folder, prefix, result, scale, 'cell', 'sb_particle_tuning_cell',
                   'tv_particle_tuning_cell_label', 'tv_particle_tuning_cell_value')
    measure_slider(folder, prefix, result, scale, 'flow', 'sb_particle_tuning_flow',
                   'tv_particle_tuning_flow_label', 'tv_particle_tuning_flow_value')
    return result


def shell_write_text(remote_relative, text):
    """经 `run-as` 把一段原文写进应用私有目录（标准输入喂内容，不经 `/sdcard`）。"""
    command = 'cat > %s.tmp && mv %s.tmp %s' % (
        remote_relative, remote_relative, remote_relative)
    subprocess.run([ui.ADB, '-s', ui.SERIAL, 'shell', 'run-as', ui.PACKAGE,
                    'sh', '-c', "'" + command + "'"],
                   input=text.encode('utf-8'), capture_output=True, check=True)


def restore_prefs(original):
    """把测试前的档位原样写回去——滑杆是用户自己的设置，不得留下测试值。

    **先 `am force-stop`**：进程活着时 SharedPreferences 的内存副本会把改动盖回去
    （见 .claude/rules/adb.md）。写完再 `cat` 读回核对。
    """
    t.soft('shell', 'am', 'force-stop', ui.PACKAGE)
    time.sleep(1.0)
    if original.strip():
        shell_write_text(PREFS_PATH, original)
    else:
        t.soft('shell', 'run-as', ui.PACKAGE, 'rm', '-f', PREFS_PATH)
    return t.soft('shell', 'run-as', ui.PACKAGE, 'cat',
                  PREFS_PATH).stdout.decode('utf-8', 'replace')


# ---- ③ 右缘对齐 ----


EDGE_IDS = ('tv_particle_tuning_cell_value', 'tv_particle_tuning_flow_value',
            'tv_particle_tuning_color_high', 'cb_particle_tuning_auto',
            'tv_particle_tuning_cell_label')


def measure_edge(img, box, width, scale):
    """一个控件的「bounds 右缘」与「墨迹右缘」到屏幕右缘各是多少 dp。"""
    x1, y1, x2, y2 = box
    pad = int(8 * scale)
    right = min(width, x2 + pad)
    # 底色取控件**右侧留白**那一条（各行右侧都还有 16dp 的行内边距），按出现次数最多的
    # 颜色取，不取单点。
    bg, share = modal_color(img, (min(width - 1, x2 + 2), y1, right, y2))
    span = None if bg is None else ink_span(img, (max(0, x1 - 2), y1, right, y2), bg)
    row = {
        'bounds右缘到屏幕右(dp)': round((width - x2) / scale, 2),
        '墨迹右缘到屏幕右(dp)': None if span is None else round((width - span[1] - 1) / scale, 2),
        'bounds': list(box),
        'ink': None if span is None else list(span),
        '底色占比': round(share, 3),
    }
    # 墨迹顶到探测框的右沿 = 底色判错了，这一格的读数不可信。
    row['可信'] = span is not None and span[1] < right - 1 and share >= .4
    return row


def mode_edges(folder, prefix, result, scale):
    """逐屏测量：**每一屏都在同一个状态下先 dump 再截图**。

    此前是「第一屏收一遍 → 缺的滚下去再收 → 最后截一张」，滚过之后第一屏那几个控件的
    y 已经变了，拿旧 bounds 去新截图上量必然量错（2026-09-18 在三星上实际发生：
    流动精细度读数量不到墨迹、标题的墨迹右缘跑到探测框边上）。
    """
    t.open_page()
    time.sleep(1.0)
    width, height = t.screen_size()
    result['screen'] = [width, height]
    t.scroll_params_top()
    time.sleep(.6)
    rows = {}
    shots = []
    for step in range(5):
        want = [i for i in EDGE_IDS if i not in rows]
        if not want:
            break
        boxes = collect(want, tries=14, height=height)
        if boxes:
            shot = t.shot(folder, '%sedges-%d' % (prefix, step))
            shots.append(shot)
            img = np.asarray(Image.open(shot).convert('RGB'))
            # 截图期间页面不许动过：同一批控件再 dump 一次，bounds 必须一模一样。
            again = collect(list(boxes), tries=14, height=height)
            for rid, box in boxes.items():
                if again.get(rid) != box:
                    continue
                row = measure_edge(img, box, width, scale)
                row['控件'] = rid
                row['shot'] = shot
                rows[rid] = row
        if len(rows) == len(EDGE_IDS):
            break
        t.scroll_params(.30)
        time.sleep(.8)
    result['shots_edges'] = shots
    result['shot_edges'] = shots[0] if shots else None
    result['edges'] = [rows.get(rid, {'控件': rid, '缺': True}) for rid in EDGE_IDS]
    inks = [r['墨迹右缘到屏幕右(dp)'] for r in result['edges']
            if r.get('控件') != 'tv_particle_tuning_cell_label' and r.get('可信')]
    if inks:
        result['edges_ink_spread_dp'] = round(max(inks) - min(inks), 2)
        result['edges_ok'] = result['edges_ink_spread_dp'] <= 1.0
    # 读数文本本身（含括号写法）也记下来，用于核对半角括号。
    result['labels'] = t.read_labels(d.dump())
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--mode', required=True,
                        choices=('gradient', 'slider', 'edges', 'all'))
    parser.add_argument('--tag', default='')
    parser.add_argument('--colors', type=int, default=0,
                        help='gradient：额外连点几次换色按钮，每次量一遍交界')
    parser.add_argument('--need-gradient', action='store_true',
                        help='gradient：一直点到抽中一条竖直分量大的渐变为止（抽到就停）')
    parser.add_argument('--serial', default=os.environ.get('THING_ANIMATION_SERIAL'))
    args = parser.parse_args()
    assert re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', args.run)
    assert re.fullmatch(r'[a-zA-Z0-9_-]{0,40}', args.tag)
    if args.serial:
        ui.SERIAL = args.serial
    prefix = (args.tag + '-') if args.tag else ''
    folder = REPO / 'tmp/particle-perf/per-frame' / ui.SERIAL / args.run
    folder.mkdir(parents=True, exist_ok=True)
    scale = density()
    result = {'serial': ui.SERIAL, 'run': args.run, 'tag': args.tag,
              'mode': args.mode, 'density': scale}

    modes = ('gradient', 'slider', 'edges') if args.mode == 'all' else (args.mode,)
    original = None
    try:
        for mode in modes:
            if mode == 'gradient':
                mode_gradient(folder, prefix, result, scale, args.colors, args.need_gradient)
            elif mode == 'slider':
                mode_slider(folder, prefix, result, scale)
                original = result.get('prefs_before')
            else:
                mode_edges(folder, prefix, result, scale)
    finally:
        if original is not None:
            result['prefs_restored'] = restore_prefs(original)

    out = folder / ('batch10-%s-%s.json' % (args.mode, args.tag or 'default'))
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(out)
    if 'seam_step' in result:
        print('交界处相邻两行 平均 %.3f / p99 %.3f / 最大 %.3f；噪声底 %.3f；%s' % (
            result['seam_step']['mean'], result['seam_step']['p99'],
            result['seam_step']['max'], result['noise_floor'],
            '连续' if result['continuous'] else '断开'))
    for i, p in enumerate(result.get('color_picks') or []):
        print('  换色 %d：竖直跨度 %.1f；交界 平均 %.3f / 最大 %.3f；噪声底 %.3f；%s' % (
            i + 1, p['vertical_span'], p['seam_step']['mean'], p['seam_step']['max'],
            p['noise_floor'], '连续' if p['continuous'] else '断开'))
    for key, ends in (result.get('slider') or {}).items():
        for end, e in ends.items():
            if 'delta_dp' in e:
                print('%-5s %-10s 墨迹 %-6s 文本 %-6s 差 %+.2f dp' % (
                    key, e.get('what', end), e['edge'], e['text_edge'], e['delta_dp']))
            if 'track_left_delta_dp' in e:
                print('%-5s %-10s 左 %+.2f dp  右 %+.2f dp' % (
                    key, e.get('what', end), e['track_left_delta_dp'],
                    e['track_right_delta_dp']))
    for row in result.get('edges', []):
        if row.get('缺'):
            print('%-34s 缺' % row['控件'])
        else:
            print('%-34s bounds %-6s 墨迹 %-6s %s' % (
                row['控件'], row['bounds右缘到屏幕右(dp)'], row['墨迹右缘到屏幕右(dp)'],
                '' if row.get('可信') else '(读数不可信)'))
    if 'edges_ink_spread_dp' in result:
        print('右缘墨迹极差 %.2f dp  %s' % (
            result['edges_ink_spread_dp'], 'OK' if result['edges_ok'] else 'NG'))


if __name__ == '__main__':
    main()
