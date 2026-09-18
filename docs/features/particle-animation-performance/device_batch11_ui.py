"""第十一批（切到「示例对话框」直接显示静止对话框）的真机取证驱动。

只经真实系统输入操作，不发业务 Intent、不改任何系统设置、不碰记事数据。
截图一律「设备端 screencap + md5 校验 + pull」，录屏逐帧读时间戳（`screenrecord` 是变帧率），
见 .claude/rules/adb.md。

两个子模式：

* `--mode enter`：**首次切到对话框那一格**。先进一次对话框预览把它的 bounds 量下来，退出页面，
  再重新进页面（默认停在卡片那一格），然后录屏点「示例对话框」。
* `--mode back`：**切走再切回**。从静止的对话框出发，点「记事卡片」，等一会儿再点回「示例对话框」。

两段都按同一套判据逐帧分析：

1. **没有空白帧**——卡片消失的那一帧上，对话框本体就已经在了（按「与静止态底色同色的像素占比」判）；
   从那一帧到消散真正开始之间，不允许出现「整块都是页面底色」的帧；
2. **只播一轮**——本体亮度**穿越中点恰好 2 次**（一次消散 + 一次出现），三步序列会穿越 4 次；
3. **没有单帧跳变**——本体亮度的孤立单帧突变（与前后两帧都差得多、而前后两帧彼此接近）为 0。

底色一律取「区域里出现次数最多的颜色」并断言它的占比，不取单点（第 10 批的教训：
取单点会整块判反）。

用法：
    THING_ANIMATION_SERIAL=<序列号> python device_batch11_ui.py --run b11-ui --mode enter --tag light
    THING_ANIMATION_SERIAL=<序列号> python device_batch11_ui.py --run b11-ui --mode back --tag light
"""
import argparse
import json
import os
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
import device_batch9_ui as b9  # noqa: E402

TOL = 6


def dominant(patch):
    """区域里出现次数最多的颜色与它的占比。

    **不能取单点**：一旦那一点落在字形或抗锯齿边上，整块都会被判反（第 10 批实测）。
    """
    flat = patch.reshape(-1, 3)
    colors, counts = np.unique(flat, axis=0, return_counts=True)
    top = int(counts.argmax())
    return colors[top].astype(np.int16), float(counts[top]) / len(flat)


def crop(img, rect):
    x1, y1, x2, y2 = rect
    h, w = img.shape[:2]
    x1, x2 = max(0, min(x1, w - 1)), max(1, min(x2, w))
    y1, y2 = max(0, min(y1, h - 1)), max(1, min(y2, h))
    return img[y1:y2, x1:x2]


def share_of(patch, color, tol=TOL):
    diff = np.abs(patch.astype(np.int16) - color.reshape(1, 1, 3))
    return float((diff.max(axis=2) <= tol).mean())


def series_of(frames, body, left, card, idle_color, page_color):
    out = []
    for stamp, path in frames:
        img = np.asarray(Image.open(path).convert('RGB'))
        b = crop(img, body)
        out.append({
            't': round(stamp, 3),
            'body': round(float(b.mean()), 3),
            'dialog_share': round(share_of(b, idle_color), 4),
            'page_share': round(share_of(b, page_color), 4),
            # 卡片还在不在：卡片顶部那一条带与页面底色的平均差，卡片撤掉之后趋近 0。
            'card_diff': round(float(np.abs(crop(img, card).astype(np.int16) -
                                            page_color.reshape(1, 1, 3)).mean()), 3),
            'left_diff': round(float(np.abs(crop(img, left).astype(np.int16) -
                                            page_color.reshape(1, 1, 3)).mean()), 3),
        })
    return out


def crossings(series, hi, lo, key='body'):
    """本体亮度穿越「静止值与最低值的中点」的次数。

    一轮「消散 → 出现」恰好穿 2 次；第 10 批那个三步开场序列会穿 4 次。
    """
    mid = (hi + lo) / 2.0
    at, state = [], None
    for x in series:
        now = x[key] >= mid
        if state is None:
            state = now
            continue
        if now != state:
            at.append({'t': x['t'], 'dir': 'up' if now else 'down', 'value': x[key]})
            state = now
    return mid, at


def derive(result, series):
    """从逐帧序列算出全部判据。

    **切换那一帧本身不进亮度类判据**：切之前画面上是示例卡片（另一种内容、另一种亮度），
    把它算进去会让「中点」落在卡片与对话框之间，穿越次数与单帧变化量都失去意义。
    亮度类判据一律只看**切换落地之后**那一段，切换本身由第 1 条单独判。
    """
    idle_dialog = max(x['dialog_share'] for x in series)

    # 1) 卡片撤掉的那一帧上，对话框在不在
    #
    # 基准取整段里的**最大值**，不取前几帧的中位数：`screenrecord` 是变帧率、只在画面变化时
    # 出帧，而卡片那一格是静止的——三星上切换之前**只有一帧**，按「前 4 帧的中位数」算出来的
    # 基准已经是切换之后的值了，判出来的切换时刻会晚两秒（实测踩到）。
    card_base = max(x['card_diff'] for x in series)
    assert card_base >= 20, '整段录屏里没看到卡片（基准只有 %.1f），窗口取错了' % card_base
    gone = next((i for i, x in enumerate(series)
                 if x['card_diff'] < card_base * .25), None)
    result['card_base_diff'] = round(card_base, 3)
    result['switch_index'] = gone
    assert gone is not None, '整段录屏里没看到卡片撤掉，窗口取错了'
    result['switch_t'] = series[gone]['t']
    result['switch_dialog_share'] = series[gone]['dialog_share']
    result['idle_dialog_share'] = round(idle_dialog, 4)
    result['switch_ok'] = series[gone]['dialog_share'] >= idle_dialog * .8

    after = series[gone:]
    idle_body = float(np.median([x['body'] for x in after[-8:]]))
    low_body = min(x['body'] for x in after)
    result['idle_body'] = round(idle_body, 3)
    result['low_body'] = round(low_body, 3)

    # 2) 从切换到消散真正开始之间，不允许有「整块页面底色」的帧
    start = next((i for i in range(gone, len(series))
                  if series[i]['dialog_share'] < idle_dialog * .9), None)
    result['dissolve_index'] = start
    if start is not None:
        result['dissolve_t'] = series[start]['t']
        result['static_frames_before_round'] = start - gone
        result['static_seconds_before_round'] = round(
            series[start]['t'] - series[gone]['t'], 3)
        result['blank_before_round'] = [
            x['t'] for x in series[gone:start] if x['page_share'] >= .9]

    # 3) 只播一轮：穿越中点恰好两次
    mid, at = crossings(after, idle_body, low_body)
    result['mid'] = round(mid, 3)
    result['crossings'] = at
    result['crossing_count'] = len(at)

    # 4) 单帧跳变
    result['body_spikes'] = b9.spikes(after, 'body', threshold=3.0)
    result['max_step'] = round(max(
        abs(after[i]['body'] - after[i - 1]['body'])
        for i in range(1, len(after))), 3)
    return result


def analyse(result, frames, folder, prefix, body, left, card):
    """逐帧判据。参照色从这一段录屏自己身上取，不写死任何颜色。"""
    last = np.asarray(Image.open(frames[-1][1]).convert('RGB'))
    idle_color, idle_share = dominant(last[body[1]:body[3], body[0]:body[2]])
    page_color, page_share = dominant(last[left[1]:left[3], left[0]:left[2]])
    assert idle_share >= .4, '静止态的对话框底色占比只有 %.2f，取样区不对' % idle_share
    assert page_share >= .5, '页面底色占比只有 %.2f，取样区不对' % page_share
    result['idle_color'] = [int(v) for v in idle_color]
    result['idle_color_share'] = round(idle_share, 4)
    result['page_color'] = [int(v) for v in page_color]
    result['page_color_share'] = round(page_share, 4)

    series = series_of(frames, body, left, card, idle_color, page_color)
    result['series'] = series
    derive(result, series)
    settled_vs_idle(result, frames)
    montage(frames, folder, prefix, body, result)
    return result


def settled_vs_idle(result, frames):
    """「直接显示的静止态」与「出现动画的末帧」是不是同一个画面。

    取**消散开始前最后一帧**与**整段末帧**，在含阴影的取景框（对话框外扩 40 px）里逐像素比。
    录屏是 H.264 有损压缩，逐像素绝对差会被编码器的发散淹没，所以判据取**高分位 + 阈值计数**
    （见 memory/video-ab-needs-robust-statistics）：p99 与「差 > 4 的像素占比」。
    """
    start = result.get('dissolve_index')
    if not start or start < 5 or len(frames) < 6:
        return
    x1, y1, x2, y2 = result['dialog_rect']
    probe = np.asarray(Image.open(frames[-1][1]).convert('RGB'))
    h, w = probe.shape[:2]
    box = (max(0, x1 - 40), max(0, y1 - 40), min(w, x2 + 40), min(h, y2 + 40))

    def read(i):
        return crop(np.asarray(Image.open(frames[i][1]).convert('RGB')).astype(np.int16), box)

    def compare(i, j):
        da = np.abs(read(i) - read(j)).max(axis=2)
        return {'a_t': frames[i][0], 'b_t': frames[j][0],
                'mean': round(float(da.mean()), 3), 'p99': int(np.percentile(da, 99)),
                'max': int(da.max()), 'share_over_4': round(float((da > 4).mean()), 5)}

    result['settled_vs_idle'] = {'box': list(box)}
    # **本底噪声**：两帧内容完全相同（都是静止态）时，有损编码本身能差多少。
    # 没有这一条，「差 18 个灰阶」读不出任何意思（见 memory/video-ab-needs-robust-statistics）。
    result['settled_vs_idle']['noise_idle'] = compare(start - 5, start - 1)
    result['settled_vs_idle']['noise_settled'] = compare(len(frames) - 5, len(frames) - 1)
    result['settled_vs_idle']['cross'] = compare(start - 1, len(frames) - 1)
    return result


def montage(frames, folder, prefix, body, result):
    """切换前后各取若干帧并排，肉眼也能核一遍「第一帧就有对话框」。"""
    gone = result.get('switch_index')
    if gone is None:
        return
    picks = frames[max(0, gone - 3):gone + 9]
    if not picks:
        return
    x1, y1, x2, y2 = body
    imgs = [Image.open(p).convert('RGB').crop(
        (max(0, x1 - 40), max(0, y1 - 40), x2 + 40, y2 + 40)) for _, p in picks]
    w = max(1, imgs[0].width // 4)
    tiles = [im.resize((w, max(1, int(w * im.height / im.width)))) for im in imgs]
    sheet = Image.new('RGB', (w * len(tiles), tiles[0].height), (24, 24, 24))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, (i * w, 0))
    out = Path(folder) / ('%s%s-switch-montage.png' % (prefix, result['mode']))
    sheet.save(out)
    result['montage'] = str(out)


def rects(folder, prefix, result):
    """量两个矩形：对话框本体（内缩 8px）与页面底色（预览区最左侧一条）。"""
    x1, y1, x2, y2 = b9.bounds_of(b8.poll_rid('cv_particle_tuning_dialog'))
    result['dialog_rect'] = [x1, y1, x2, y2]
    body = (x1 + 8, y1 + 8, x2 - 8, y2 - 8)
    left = (2, y1, 10, y2)
    result['body_rect'] = list(body)
    result['left_rect'] = list(left)
    return body, left


def card_strip(result, card_rect):
    """判断「卡片还在不在」的取样区：卡片**左侧那一条**——它在对话框的 x 范围之外。

    两次走错路都记在这里：按对话框的位置往上推的带会整条落在卡片上方的页面底色里；
    而「卡片顶部到对话框上缘之间」那一条在三星上只有 **4 px**（卡片上缘 635、对话框 640），
    量到的基准只有 15 个灰阶、判不出卡片什么时候撤掉。卡片比对话框宽（640 dp 对 320 dp），
    左侧这一条**一定**只有卡片、没有对话框，基准因此稳定在几十个灰阶。
    """
    cx1, _, _, _ = card_rect
    _, dy1, _, dy2 = result['dialog_rect']
    dx1 = result['dialog_rect'][0]
    strip = (max(2, cx1 + 4), dy1 + 8, dx1 - 8, dy2 - 8)
    assert strip[2] - strip[0] >= 16, '卡片左侧露出来的那一条太窄：%s' % (strip,)
    result['card_rect'] = list(card_rect)
    result['card_band'] = list(strip)
    return strip


def card_band(result):
    return card_strip(result, t.card_rect())


def to_dialog_idle():
    """切到对话框那一格并等到静止。第 11 批起一共一轮（消散 → 出现），约 3 s 收尾。"""
    d.tap(b8.poll_rid('tv_particle_tuning_mode_dialog'))
    time.sleep(6.0)


def mode_enter(folder, prefix, result):
    # 先进一次量 bounds（第 11 批起对话框在卡片那一格是不可见的，量不到）
    t.open_page()
    to_dialog_idle()
    body, left = rects(folder, prefix, result)
    result['shot_idle'] = t.shot(folder, prefix + 'dialog-idle')
    # 退出页面再重新进来：录的就是**首次**切到对话框那一格
    t.soft('shell', 'input', 'keyevent', '4')
    time.sleep(1.2)
    t.open_page()
    time.sleep(1.0)
    card = card_band(result)
    result['shot_card'] = t.shot(folder, prefix + 'card-idle')

    def action():
        d.tap(b8.poll_rid('tv_particle_tuning_mode_dialog'))
        return time.time()

    clip = b8.record(folder, prefix + 'enter', 14, action, settle=1.5, tail=8.0)
    result['clip'] = clip
    frames = b9.all_frames(clip['video'], folder, prefix + 'enter-all')
    window = [(s, f) for s, f in frames
              if clip['mark_s'] - 0.5 <= s <= clip['mark_s'] + 7.5]
    analyse(result, window, folder, prefix, body, left, card)
    shutil.rmtree(Path(folder) / (prefix + 'enter-all'), ignore_errors=True)
    return result


def mode_back(folder, prefix, result):
    t.open_page()
    to_dialog_idle()
    body, left = rects(folder, prefix, result)
    card_tab = b8.poll_rid('tv_particle_tuning_mode_card')
    dialog_tab = b8.poll_rid('tv_particle_tuning_mode_dialog')
    # 先切回卡片那一格，把卡片自己的 bounds 量下来，再切回对话框，然后才开始录。
    d.tap(card_tab)
    time.sleep(1.5)
    card = card_band(result)
    to_dialog_idle()

    def action():
        d.tap(card_tab)
        time.sleep(1.8)
        d.tap(dialog_tab)
        return time.time()

    clip = b8.record(folder, prefix + 'back', 16, action, settle=1.5, tail=8.0)
    result['clip'] = clip
    frames = b9.all_frames(clip['video'], folder, prefix + 'back-all')
    window = [(s, f) for s, f in frames
              if clip['mark_s'] - 0.5 <= s <= clip['mark_s'] + 7.5]
    analyse(result, window, folder, prefix, body, left, card)
    shutil.rmtree(Path(folder) / (prefix + 'back-all'), ignore_errors=True)
    return result


def mode_still(folder, prefix, result, tries=5):
    """**无损**证据：切过去那一瞬的静止态，与一轮播完之后的静止态逐像素比。

    录屏是 H.264 有损压缩，大场景切换之后编码器要几帧才收敛，「静止态 vs 末帧」的逐像素差
    因此读不出意思（实测本底与信号同量级）。这一格改用设备端截图：`input tap` 与 `screencap`
    合并成**一次** `adb shell`，落在切过去那 0.6 s 的静止窗口里，两张 PNG 逐像素比。
    """
    t.open_page()
    to_dialog_idle()
    body, left = rects(folder, prefix, result)
    settled = t.shot(folder, prefix + 'still-settled')
    x1, y1, x2, y2 = result['dialog_rect']
    card_tab = b8.poll_rid('tv_particle_tuning_mode_card')
    dialog_tab = b8.poll_rid('tv_particle_tuning_mode_dialog')
    dx, dy = d.center(dialog_tab)
    ref = np.asarray(Image.open(settled).convert('RGB')).astype(np.int16)
    h, w = ref.shape[:2]
    box = (max(0, x1 - 40), max(0, y1 - 40), min(w, x2 + 40), min(h, y2 + 40))
    result['still_box'] = list(box)
    attempts = []
    for i in range(tries):
        d.tap(card_tab)
        time.sleep(1.6)
        remote = '/sdcard/particle-b11-still.png'
        t.soft('shell', 'rm', '-f', remote)
        # 一次 adb shell 里点完就截，省掉一个来回；静止窗口约 0.6 s。
        t.soft('shell', 'input tap %d %d; screencap -p %s' % (dx, dy, remote))
        local = Path(folder) / ('%sstill-first-%d.png' % (prefix, i))
        t.soft('pull', remote, str(local))
        t.soft('shell', 'rm', '-f', remote)
        got = np.asarray(Image.open(local).convert('RGB')).astype(np.int16)
        da = np.abs(crop(got, box) - crop(ref, box)).max(axis=2)
        attempts.append({'shot': str(local), 'mean': round(float(da.mean()), 4),
                         'max': int(da.max()),
                         'differing': int((da > 0).sum()), 'pixels': int(da.size)})
        print(attempts[-1], flush=True)
        time.sleep(5.0)
        if da.max() == 0:
            break
    result['still_settled'] = settled
    result['still_attempts'] = attempts
    result['still_identical'] = any(a['max'] == 0 for a in attempts)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--mode', required=True, choices=('enter', 'back', 'still'))
    parser.add_argument('--tag', default='')
    parser.add_argument('--reanalyse', default=None,
                        help='只按已落盘的逐帧序列重算判据，不碰设备')
    parser.add_argument('--recompute', default=None,
                        help='按已录下来的那段视频重新逐帧量一遍（取样区改了才需要），不碰设备')
    parser.add_argument('--serial', default=os.environ.get('THING_ANIMATION_SERIAL'))
    args = parser.parse_args()
    if args.reanalyse or args.recompute:
        path = Path(args.reanalyse or args.recompute)
        saved = json.loads(path.read_text(encoding='utf-8'))
        if args.recompute:
            folder = str(path.parent)
            prefix = (saved.get('tag') + '-') if saved.get('tag') else ''
            clip = saved['clip']
            # 旧产物里没有卡片的原始 bounds，按当时那条带反推左上角即可。
            card_rect = saved.get('card_rect') or [saved['card_band'][0] - 8,
                                                   saved['card_band'][1] - 4,
                                                   saved['card_band'][2] + 8, 0]
            strip = card_strip(saved, card_rect)
            frames = b9.all_frames(clip['video'], folder, prefix + 'recompute-all')
            window = [(s, f) for s, f in frames
                      if clip['mark_s'] - 0.5 <= s <= clip['mark_s'] + 7.5]
            analyse(saved, window, folder, prefix,
                    tuple(saved['body_rect']), tuple(saved['left_rect']), strip)
            shutil.rmtree(Path(folder) / (prefix + 'recompute-all'), ignore_errors=True)
        else:
            derive(saved, saved['series'])
        path.write_text(json.dumps(saved, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({k: v for k, v in saved.items() if k not in ('series', 'clip')},
                         ensure_ascii=False, indent=2)[:2000])
        return
    assert args.serial, '必须指定设备序列号'
    os.environ['THING_ANIMATION_SERIAL'] = args.serial

    folder = REPO / 'tmp/particle-perf/per-frame' / args.serial / args.run
    folder.mkdir(parents=True, exist_ok=True)
    prefix = (args.tag + '-') if args.tag else ''
    result = {'serial': args.serial, 'mode': args.mode, 'tag': args.tag}
    {'enter': mode_enter, 'back': mode_back,
     'still': mode_still}[args.mode](str(folder), prefix, result)
    out = folder / ('batch11-%s-%s.json' % (args.mode, args.tag or 'x'))
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    brief = {k: v for k, v in result.items() if k != 'series'}
    print(json.dumps(brief, ensure_ascii=False, indent=2)[:2400])
    print('写入', out)


if __name__ == '__main__':
    main()
