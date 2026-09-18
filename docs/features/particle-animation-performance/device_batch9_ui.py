"""第九批（粒子动画设置页第五轮三条）的真机取证驱动。

只经真实系统输入操作，不发业务 Intent、不改任何系统设置、不碰记事数据。
复用 `device_particle_tuning.py` / `device_batch8_ui.py` 的截图、录屏与定位辅助
（截图一律「设备端 screencap + md5 校验 + pull」，见 .claude/rules/adb.md）。

三个子模式，各自对应用户的三条：

* `--mode bounds`：**度量对照**。先进「空间照片效果」设置页把参照行的 bounds 读出来，
  再进「粒子动画设置」读本页的，全部换算成 dp，逐项写进 `batch9-bounds-<tag>.json`。
  两页各截一张，可以并排看。**尺寸一律按控件 bounds 取**——截图上量墨迹会被字形留白带偏。
* `--mode shadow`：对话框预览录一整轮（消散 → 出现），逐帧量**对话框下缘那条阴影带**的
  平均灰度，做一阶差分。修好之后差分里不应再有孤立的单帧跳变。
* `--mode direction`：对话框静止时打开方向弹层、选一个方向，录屏逐帧量**对话框本体**的
  亮度。改好之后不应再出现「本体先整块恢复成背景色（瞬间消失）再重新出现」那一下。

用法：
    THING_ANIMATION_SERIAL=<序列号> python device_batch9_ui.py --run b9-ui --mode bounds --tag light
    THING_ANIMATION_SERIAL=<序列号> python device_batch9_ui.py --run b9-ui --mode shadow --tag light
    THING_ANIMATION_SERIAL=<序列号> python device_batch9_ui.py --run b9-ui --mode direction --tag light
"""
import argparse
import json
import os
import re
import shutil
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

SPATIAL_KEYS = ('空间照片效果', 'Spatial photo effect')

# 本页要量的控件；参照页对应的那一个写在右边。参照页的说明文本没有 id，
# 单独按它引用的字符串定位（见 reference_note_bounds）。
PAGE_IDS = (
    'rl_particle_tuning_cell_row', 'tv_particle_tuning_cell_label',
    'tv_particle_tuning_cell_value', 'sb_particle_tuning_cell',
    'tv_particle_tuning_cell_detail',
    'rl_particle_tuning_flow_row', 'tv_particle_tuning_flow_label',
    'tv_particle_tuning_flow_value', 'sb_particle_tuning_flow',
    'tv_particle_tuning_flow_detail',
    'll_particle_tuning_color_row', 'tv_particle_tuning_color_label',
    'tv_particle_tuning_color_standard', 'tv_particle_tuning_color_detail',
    'rl_particle_tuning_auto_row', 'tv_particle_tuning_auto_label',
    'tv_particle_tuning_auto_detail', 'cb_particle_tuning_auto',
    'tv_particle_tuning_reset',
)

REFERENCE_IDS = ('rl_spatial_tilt_as_bt', 'sw_spatial_tilt',
                 'btn_clear_spatial_derivatives', 'tv_runtime_status')

# 参照页里要按文案定位的几处（它们没有 id，或者对应的 id 那一块是 gone 的）。
# `row_depth_detail` 只在选中 MoGe 系模型时才可见，两台设备上都没下载模型，所以
# 「56dp 的行 + 16sp 标题」改用同页的「清除全部已生成空间效果」那一行取（同一档取值）。
REFERENCE_TEXTS = {
    'tilt_title': ('通过倾斜设备控制视点', 'Control viewpoint by tilting the device'),
    'tilt_note': ('默认开启。关闭后不会注册姿态传感器，仍可拖动图片控制视点。',
                  'Enabled by default. Turning this off keeps drag control available '
                  'and stops registering the motion sensor.'),
    # 16sp 的行标题：取 `wrap_content` 的那一个。「清除全部已生成空间效果」自己就是那一行
    #（match_parent + 16dp 内边距），它的 bounds 是整行，量不出字高与文本左缘。
    'row_title': ('推理运行环境', 'On-device inference runtime'),
}


def density():
    raw = t.soft('shell', 'wm', 'density').stdout.decode('utf-8', 'replace')
    got = re.findall(r'Override density:\s*(\d+)', raw) or \
        re.findall(r'Physical density:\s*(\d+)', raw)
    assert got, '取不到屏幕密度'
    return int(got[-1]) / 160.0


def bounds_of(node):
    return tuple(map(int, re.findall(r'-?\d+', node.get('bounds'))))


def collect(ids, tries=40, height=None):
    """把这一批控件的 bounds 收齐。

    页面上一直有粒子动画在播，`uiautomator dump` 等不到 idle、可能返回缺了一支的树，
    所以按「收齐为止」轮询，而不是一次 dump 定生死（见 .claude/rules/adb.md）。

    [height] 非空时**丢掉贴住屏幕上下边的节点**：`uiautomator` 把 bounds 裁到可见区域内，
    被折行截断的说明会报出一个偏小的高度，按它折算行数会多数一行（2026-09-18 在三星上
    实际发生：颜色精度的说明报 41.87dp，其实是 67.6dp）。滚过去之后再收一次即可。
    """
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


def node_with_text(keys, tries=20):
    for _ in range(tries):
        for n in d.dump().iter('node'):
            if n.get('text') in keys:
                return n
        time.sleep(.3)
    return None


def open_spatial_settings():
    """抽屉 → 设置 → 空间照片效果。与本页走同一条真实路径。"""
    d.observer(False)
    d.probe(None)
    t.open_settings()
    row = t.scroll_to_text(SPATIAL_KEYS)
    d.tap(row)
    time.sleep(1.4)


def sweep(collect_once, tries=16):
    """参照页很长，一边往下滚一边收：`uiautomator dump` 只输出**可见**的节点。"""
    width, height = t.screen_size()
    for step in range(tries):
        if collect_once():
            return True
        t.adb('shell', 'input', 'swipe', width // 2, int(height * .80),
              width // 2, int(height * .32), 320)
        time.sleep(.45)
    return False


def measure_reference(folder, prefix, result):
    open_spatial_settings()
    result['shot_reference_top'] = t.shot(folder, prefix + 'reference-top')
    boxes, texts = {}, {}
    shot_taken = []

    def once():
        tree = d.dump()
        for n in tree.iter('node'):
            rid = n.get('resource-id') or ''
            for want in REFERENCE_IDS:
                if rid.endswith('/' + want) and want not in boxes:
                    boxes[want] = bounds_of(n)
            label = n.get('text') or ''
            for key, keys in REFERENCE_TEXTS.items():
                if label in keys and key not in texts:
                    texts[key] = bounds_of(n)
        if 'tilt_title' in texts and 'rl_spatial_tilt_as_bt' in boxes and not shot_taken:
            shot_taken.append(t.shot(folder, prefix + 'reference-tilt'))
        return len(boxes) == len(REFERENCE_IDS) and len(texts) == len(REFERENCE_TEXTS)

    result['reference_complete'] = sweep(once)
    result['reference_bounds'] = {k: list(v) for k, v in boxes.items()}
    result['reference_texts'] = {k: list(v) for k, v in texts.items()}
    if shot_taken:
        result['shot_reference_tilt'] = shot_taken[0]
    result['shot_reference_bottom'] = t.shot(folder, prefix + 'reference-bottom')
    t.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1.0)
    return result


def compare(result, scale):
    """把两页的读数换算成 dp 并逐项对照。差值超过 1 dp 就在表里标出来。"""
    page = {k: v for k, v in result.get('page_bounds', {}).items()}
    ref = {k: v for k, v in result.get('reference_bounds', {}).items()}
    ref_texts = result.get('reference_texts', {})
    width = result.get('screen_width') or 0

    def dp(v):
        return round(v / scale, 2)

    def height(box):
        return dp(box[3] - box[1])

    def gap(a, b):
        return dp(b[1] - a[3])

    rows = []

    def add(item, reference, ours):
        delta = None if reference is None or ours is None else round(ours - reference, 2)
        rows.append({'项': item, '参照页': reference, '本页': ours, '差': delta,
                     '合格': None if delta is None else abs(delta) <= 1.0})

    if 'btn_clear_spatial_derivatives' in ref:
        for name, rid in (('粒子大小行高', 'rl_particle_tuning_cell_row'),
                          ('流动精细度行高', 'rl_particle_tuning_flow_row'),
                          ('颜色精度行高', 'll_particle_tuning_color_row')):
            if rid in page:
                add(name, height(ref['btn_clear_spatial_derivatives']), height(page[rid]))
    if 'row_title' in ref_texts:
        for name, rid in (('粒子大小标题字高', 'tv_particle_tuning_cell_label'),
                          ('流动精细度标题字高', 'tv_particle_tuning_flow_label'),
                          ('颜色精度标题字高', 'tv_particle_tuning_color_label')):
            if rid in page:
                add(name, height(ref_texts['row_title']), height(page[rid]))
    if 'tv_runtime_status' in ref:
        # 说明文本会折行，总高没有可比性；参照物取一行的 12sp 文本，本页按行数归一化
        # （行距是均匀的，`总高 / 行数` 就是单行高）。
        single = height(ref['tv_runtime_status'])
        # 三条组说明自带 10dp 的收尾内边距（与参照页每组收尾的说明同一份），
        # 折算单行高之前要先减掉，否则行数会数多一行。
        for name, rid, pad in (('粒子大小说明单行高', 'tv_particle_tuning_cell_detail', 10),
                               ('流动精细度说明单行高', 'tv_particle_tuning_flow_detail', 10),
                               ('颜色精度说明单行高', 'tv_particle_tuning_color_detail', 10),
                               ('自动调节说明单行高', 'tv_particle_tuning_auto_detail', 0)):
            if rid in page:
                total = height(page[rid]) - pad
                lines = max(1, round(total / single))
                add(name, single, round(total / lines, 2))
    if 'rl_spatial_tilt_as_bt' in ref and 'rl_particle_tuning_auto_row' in page:
        add('勾选行高', height(ref['rl_spatial_tilt_as_bt']),
            height(page['rl_particle_tuning_auto_row']))
    if 'tilt_title' in ref_texts and 'tv_particle_tuning_auto_label' in page:
        add('勾选行标题字高', height(ref_texts['tilt_title']),
            height(page['tv_particle_tuning_auto_label']))
    if 'tilt_note' in ref_texts and 'tv_particle_tuning_auto_detail' in page:
        add('勾选行标题底→说明顶',
            gap(ref_texts['tilt_title'], ref_texts['tilt_note']),
            gap(page['tv_particle_tuning_auto_label'],
                page['tv_particle_tuning_auto_detail']))
    # 行的左右缩进：参照页的标题左缘 / 勾选框右缘到屏幕右边。
    if 'row_title' in ref_texts and 'tv_particle_tuning_cell_label' in page:
        add('行标题左缘', dp(ref_texts['row_title'][0]),
            dp(page['tv_particle_tuning_cell_label'][0]))
    if 'tilt_title' in ref_texts and 'tv_particle_tuning_auto_label' in page:
        add('勾选行标题左缘', dp(ref_texts['tilt_title'][0]),
            dp(page['tv_particle_tuning_auto_label'][0]))
    if width and 'sw_spatial_tilt' in ref and 'cb_particle_tuning_auto' in page:
        add('勾选框右缘到屏幕右', dp(width - ref['sw_spatial_tilt'][2]),
            dp(width - page['cb_particle_tuning_auto'][2]))
    # 本页独有的两段间距（参照页没有滑动条，报绝对值供核对）。
    for name, a, b in (('粒子大小 标题行底→滑条顶', 'rl_particle_tuning_cell_row',
                        'sb_particle_tuning_cell'),
                       ('粒子大小 滑条底→说明顶', 'sb_particle_tuning_cell',
                        'tv_particle_tuning_cell_detail'),
                       ('流动精细度 标题行底→滑条顶', 'rl_particle_tuning_flow_row',
                        'sb_particle_tuning_flow'),
                       ('流动精细度 滑条底→说明顶', 'sb_particle_tuning_flow',
                        'tv_particle_tuning_flow_detail')):
        if a in page and b in page:
            rows.append({'项': name, '参照页': None, '本页': gap(page[a], page[b]),
                         '差': None, '合格': None})
    # 左缘：本页各行与滑动条轨道应当都落在同一条线上。
    lefts = {k: dp(v[0]) for k, v in page.items() if k.startswith('tv_') or k.startswith('sb_')}
    result['left_edges_dp'] = lefts
    result['compare'] = rows
    return result


def mode_bounds(folder, prefix, result, scale):
    measure_reference(folder, prefix, result)
    t.open_page()
    time.sleep(1.0)
    width, height = t.screen_size()
    result['screen_width'] = width
    result['shot_page'] = t.shot(folder, prefix + 'page-top')
    result['page_bounds'] = {k: list(v) for k, v in collect(PAGE_IDS, height=height).items()}
    # 参数区下半截要滚下去才可见；滚一次再收一遍，把两次并起来。
    t.scroll_params(.34)
    time.sleep(.6)
    result['shot_page_bottom'] = t.shot(folder, prefix + 'page-bottom')
    more = collect([i for i in PAGE_IDS if i not in result['page_bounds']], height=height)
    # 滚动之后 y 变了，跨屏的相对间距不能混着算：只补那些第一屏没取到的。
    result['page_bounds_scrolled'] = {k: list(v) for k, v in more.items()}
    for k, v in more.items():
        result['page_bounds'].setdefault(k, list(v))
    compare(result, scale)
    return result


# ---- 阴影与方向：录屏 + 逐帧量 ----


def all_frames(video, folder, name):
    """把整段录屏逐帧落盘，并逐帧读时间戳（`screenrecord` 是变帧率）。"""
    probe = subprocess.run(
        ['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
         'frame=best_effort_timestamp_time', '-of', 'csv=p=0', str(video)],
        capture_output=True, text=True)
    stamps = [float(v) for v in probe.stdout.split() if v.replace('.', '', 1).isdigit()]
    out = Path(folder) / name
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', str(video), '-vsync', '0',
                    str(out / 'f-%05d.png')], capture_output=True)
    files = sorted(out.glob('f-*.png'))
    assert len(files) == len(stamps), \
        '解码帧数 %d 与时间戳数 %d 不一致' % (len(files), len(stamps))
    return list(zip(stamps, files))


def band_series(frames, band, body):
    """逐帧量「阴影带」与「对话框本体」的平均灰度。"""
    series = []
    for stamp, path in frames:
        img = np.asarray(Image.open(path).convert('RGB'), dtype=np.float32)
        h, w = img.shape[:2]
        def mean(rect):
            x1, y1, x2, y2 = rect
            x1, x2 = max(0, min(x1, w - 1)), max(1, min(x2, w))
            y1, y2 = max(0, min(y1, h - 1)), max(1, min(y2, h))
            return float(img[y1:y2, x1:x2].mean())
        series.append({'t': round(stamp, 3), 'shadow': round(mean(band), 3),
                       'body': round(mean(body), 3)})
    return series


def darker_runs(series, key='shadow', tolerance=0.8, head=20):
    """比**静止态**还深的那些帧。

    这是「两层半透明投影叠在一起」唯一的、无歧义的信号：阴影只会在消散过程中变浅，
    绝不可能比静止的时候更深；一旦更深，就说明那一刻屏幕上同时画着两份投影。
    """
    sample = sorted(x[key] for x in series[:head])
    base = sample[len(sample) // 2] if sample else 0.0
    runs, cur = [], None
    for x in series:
        if x[key] < base - tolerance:
            if cur is None:
                cur = {'from': x['t'], 'to': x['t'], 'frames': 0, 'min': x[key]}
            cur['to'] = x['t']
            cur['frames'] += 1
            cur['min'] = min(cur['min'], x[key])
        elif cur is not None:
            runs.append(cur)
            cur = None
    if cur is not None:
        runs.append(cur)
    for r in runs:
        r['depth'] = round(base - r['min'], 2)
    return round(base, 3), runs


def spikes(series, key, threshold=1.0):
    """孤立单帧跳变：这一帧与前后两帧都差得多，而前后两帧彼此接近。"""
    out = []
    for i in range(1, len(series) - 1):
        a, b, c = series[i - 1][key], series[i][key], series[i + 1][key]
        if abs(b - a) >= threshold and abs(b - c) >= threshold and \
                abs(c - a) < max(abs(b - a), abs(b - c)) / 2:
            out.append({'t': series[i]['t'], 'value': b, 'prev': a, 'next': c,
                        'delta': round(b - (a + c) / 2, 3)})
    return out


TUNING_PATH = 'files/particle-perf-tuning.json'


def set_handoff(enabled):
    """切换第 9 批那条「含投影的面板走原子交接」的开关，用来在同一个包上做受控对照。

    debug 包只读应用私有目录下的这一个文件，缺文件时取默认（= 发布配置）。
    跑完必须删掉，别把诊断用的开关留在用户设备上。
    """
    t.soft('shell', 'am', 'force-stop', ui.PACKAGE)
    time.sleep(.8)
    if enabled is None:
        t.soft('shell', 'run-as', ui.PACKAGE, 'rm', '-f', TUNING_PATH)
    else:
        d.shell_write(TUNING_PATH, {'panelShadowAtomicHandoff': bool(enabled)})
    raw = t.soft('shell', 'run-as', ui.PACKAGE, 'cat', TUNING_PATH).stdout
    return raw.decode('utf-8', 'replace').strip()


def dialog_rect():
    node = b8.poll_rid('cv_particle_tuning_dialog')
    return bounds_of(node)


def to_dialog_preview():
    """切到对话框预览，等自动那一轮播完再返回，返回时对话框停在可见的静止态。

    第 11 批起切过去**直接显示静止的对话框**，自动播的只有一轮两步（消散 → 出现），
    不再是「空白 + 三步」。等待时间照旧给足，多等几秒不影响这几格的取证。
    """
    d.tap(b8.poll_rid('tv_particle_tuning_mode_dialog'))
    time.sleep(1.5)
    rect = dialog_rect()
    # 一轮两步，每步「动画约 1.2 s + 0.8 s 停顿」，外加起步前的 0.35 s 停顿与收尾；给足 10 s。
    time.sleep(10.0)
    return rect


def mode_shadow(folder, prefix, result, handoff=None):
    if handoff:
        result['handoff'] = handoff
        result['tuning'] = set_handoff({'on': True, 'off': False, 'clear': None}[handoff])
    t.open_page()
    rect = to_dialog_preview()
    x1, y1, x2, y2 = rect
    result['dialog_rect'] = [x1, y1, x2, y2]
    # 阴影带：对话框下缘之外一条窄带（本体之外、还在投影范围内）。
    band = (x1 + (x2 - x1) // 4, y2 + 2, x2 - (x2 - x1) // 4, y2 + 14)
    body = (x1 + 8, y1 + 8, x2 - 8, y2 - 8)
    result['shadow_band'] = list(band)

    def action():
        # 点「稍后」重播一轮：消散 → 出现。两端的交接都在这一段里。
        d.tap(b8.poll_rid('tv_particle_tuning_dialog_cancel'))
        return time.time()

    clip = b8.record(folder, prefix + 'shadow', 14, action, settle=1.5, tail=7.0)
    result['shadow_clip'] = clip
    frames = all_frames(clip['video'], folder, prefix + 'shadow-all')
    window = [(s, f) for s, f in frames if clip['mark_s'] - 0.6 <= s <= clip['mark_s'] + 6.0]
    series = band_series(window, band, body)
    result['shadow_series'] = series
    base, runs = darker_runs(series)
    result['shadow_idle'] = base
    result['shadow_darker_runs'] = runs
    result['shadow_spikes'] = spikes(series, 'shadow')
    result['body_spikes'] = spikes(series, 'body', threshold=2.0)
    shutil.rmtree(Path(folder) / (prefix + 'shadow-all'), ignore_errors=True)
    return result


def mode_shots(folder, prefix, result, reference=True):
    """静止态截图：参照页的倾斜行、本页参数区两屏、对话框静止一张。

    深浅色各跑一次（深色由**设备自己的**系统开关决定，脚本不改应用内的深色设置）。
    """
    if reference:
        measure_reference(folder, prefix, result)
    t.open_page()
    time.sleep(1.2)
    result['shot_page'] = t.shot(folder, prefix + 'page-top')
    t.scroll_params(.34)
    time.sleep(.6)
    result['shot_page_bottom'] = t.shot(folder, prefix + 'page-bottom')
    t.scroll_params_top()
    to_dialog_preview()
    result['shot_dialog_idle'] = t.shot(folder, prefix + 'dialog-idle')
    return result


def mode_direction(folder, prefix, result, cell='iv_particle_direction_b'):
    t.open_page()
    rect = to_dialog_preview()
    x1, y1, x2, y2 = rect
    result['dialog_rect'] = [x1, y1, x2, y2]
    body = (x1 + 8, y1 + 8, x2 - 8, y2 - 8)

    def action():
        d.tap(b8.poll_rid('iv_particle_tuning_direction'))
        time.sleep(.9)
        for _ in range(30):
            for n in d.dump().iter('node'):
                if (n.get('resource-id') or '').endswith('/' + cell):
                    d.tap(n)
                    return time.time()
            time.sleep(.2)
        raise RuntimeError('方向弹层里找不到 ' + cell)

    clip = b8.record(folder, prefix + 'direction', 16, action, settle=1.5, tail=9.0)
    result['direction_clip'] = clip
    frames = all_frames(clip['video'], folder, prefix + 'direction-all')
    window = [(s, f) for s, f in frames if clip['mark_s'] - 0.4 <= s <= clip['mark_s'] + 8.0]
    band = (x1 + (x2 - x1) // 4, y2 + 2, x2 - (x2 - x1) // 4, y2 + 14)
    series = band_series(window, band, body)
    result['direction_series'] = series
    # 「瞬间消失」的判据：本体亮度一帧之内就掉到接近页面底色，且不是渐变过去的。
    result['direction_spikes'] = spikes(series, 'body', threshold=3.0)
    montage = Path(folder) / (prefix + 'direction-montage.png')
    picks = window[::max(1, len(window) // 12)][:12]
    if picks:
        imgs = [Image.open(p).convert('RGB') for _, p in picks]
        w = imgs[0].width // 4
        tiles = [im.crop((max(0, x1 - 40), max(0, y1 - 40),
                          min(im.width, x2 + 40), min(im.height, y2 + 40))).resize(
            (w, int(w * (y2 - y1 + 80) / max(1, x2 - x1 + 80)))) for im in imgs]
        sheet = Image.new('RGB', (w * len(tiles), tiles[0].height), (24, 24, 24))
        for i, tile in enumerate(tiles):
            sheet.paste(tile, (i * w, 0))
        sheet.save(montage)
        result['direction_montage'] = str(montage)
    shutil.rmtree(Path(folder) / (prefix + 'direction-all'), ignore_errors=True)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--mode', required=True,
                        choices=('bounds', 'shadow', 'direction', 'shots'))
    parser.add_argument('--tag', default='')
    parser.add_argument('--cell', default='iv_particle_direction_b',
                        help='方向弹层里要点的那一格')
    parser.add_argument('--no-reference', action='store_true',
                        help='shots：跳过参照页那几张，只取本页的')
    parser.add_argument('--handoff', choices=('on', 'off', 'clear'),
                        help='阴影那一格的受控对照：on = 第 9 批的原子交接，off = 第 8 批那条，'
                             'clear = 删掉诊断配置回到默认')
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

    if args.mode == 'bounds':
        mode_bounds(folder, prefix, result, scale)
    elif args.mode == 'shadow':
        mode_shadow(folder, prefix, result, args.handoff)
    elif args.mode == 'shots':
        mode_shots(folder, prefix, result, reference=not args.no_reference)
    else:
        mode_direction(folder, prefix, result, args.cell)

    out = folder / ('batch9-%s-%s.json' % (args.mode, args.tag or 'default'))
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(out)
    if args.mode == 'bounds':
        for row in result.get('compare', []):
            print('%-24s 参照 %-8s 本页 %-8s 差 %-7s %s' % (
                row['项'], row['参照页'], row['本页'], row['差'],
                '' if row['合格'] is None else ('OK' if row['合格'] else 'NG')))
    elif args.mode == 'shadow':
        print('静止态阴影带 %s' % result.get('shadow_idle'))
        runs = result.get('shadow_darker_runs') or []
        print('比静止还深的帧段：%d 段' % len(runs))
        for r in runs:
            print('  %.3f–%.3f s  %d 帧  最深 %.2f（比静止深 %.2f）' % (
                r['from'], r['to'], r['frames'], r['min'], r['depth']))


if __name__ == '__main__':
    main()
