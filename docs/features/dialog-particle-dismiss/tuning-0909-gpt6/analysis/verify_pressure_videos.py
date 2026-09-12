"""核对本轮标准与专项视频；历史产物不计入当前验收，也不改写其身份。"""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import subprocess
import sys

import cv2

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from export_videos import PRE, VERSION, code_hash
from unified_model import model_fingerprint


def check_video(item):
    path = HERE / 'videos' / item['file']
    assert path.name == item['file'], '视频必须直接放在共同 videos 目录'
    assert path.stat().st_size == item['bytes'], item['file']
    stream = json.loads(subprocess.check_output([
        'C:/ffmpeg/bin/ffprobe.exe', '-v', 'error', '-select_streams', 'v:0',
        '-count_frames', '-show_entries',
        'stream=width,height,avg_frame_rate,nb_read_frames,duration,codec_name,pix_fmt',
        '-of', 'json', str(path),
    ], text=True))['streams'][0]
    assert (stream['width'], stream['height']) == (item['width'], item['height']), item['file']
    assert stream['avg_frame_rate'] == '60/1', item['file']
    assert int(stream['nb_read_frames']) == item['frames'], item['file']
    assert abs(float(stream['duration']) - item['duration']) < .001, item['file']
    assert stream['codec_name'] == 'h264' and stream['pix_fmt'] == 'yuv420p', item['file']
    capture = cv2.VideoCapture(str(path))
    decoded = []
    try:
        for number in [0, round(60 * (PRE + .48) / item['rate']), item['frames'] - 1]:
            capture.set(cv2.CAP_PROP_POS_FRAMES, number)
            ok, pixels = capture.read()
            assert ok and pixels.mean() > 4, (item['file'], number)
            decoded.append(dict(frame=number, mean=float(pixels.mean()),
                                sha256=hashlib.sha256(pixels.tobytes()).hexdigest()))
    finally:
        capture.release()
    assert len({x['sha256'] for x in decoded}) == 3, item['file']
    return dict(file=item['file'], sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                probe=stream, decoded_samples=decoded)


def main():
    stamp = code_hash()
    standard = json.loads((HERE / 'videos/manifest.json').read_text('utf-8'))
    special = json.loads((HERE / 'videos/pressure-flow-videos.json').read_text('utf-8'))
    assert standard['code_hash'] == special['code_hash'] == stamp
    assert special['model_hash'] == model_fingerprint()
    metas = json.loads((HERE / 'assets/scenes.json').read_text('utf-8'))
    expected = set()
    for meta in metas:
        kinds = ['animation']
        kinds += ['compare-phase', 'compare-file'] if meta.get('reference') else ['compare-source']
        if not meta.get('holdout'):
            kinds += ['compare-versions', 'seed-variants']
        if meta['name'] in {'ironman', 'attachment', 'attachment-image', 'color'}:
            kinds += ['eight-directions']
        if meta['name'] in {'ironman', 'attachment', 'color'}:
            kinds += ['touch-distances']
        expected.update((meta['name'], kind, rate) for kind in kinds for rate in [1., .5])
    actual = {(x['scene'], x['kind'], x['rate']) for x in standard['videos']}
    assert len(actual) == len(standard['videos']) and actual == expected, {
        'missing': sorted(expected - actual), 'unexpected': sorted(actual - expected)}
    assert {(x['case'], x['rate']) for x in special['videos']} == {
        (case, rate) for case in range(1, 13) for rate in [1., .5]}
    items = standard['videos'] + special['videos']
    assert len(items) == len({x['file'] for x in items})
    by_name = {x['file']: x for x in items}
    for item in items:
        assert item['code_hash'] == stamp and item['version'] == VERSION, item['file']
        if item['rate'] == 1:
            slow = by_name[item['file'].replace('-1x.mp4', '-0.5x.mp4')]
            assert slow['frames'] == item['frames'] * 2
            assert slow['duration'] == item['duration'] * 2
    results = []
    # CPU 编解码检查可并行；不并行访问模拟器或同一台实机。
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for result in pool.map(check_video, items):
            results.append(result)
            print('视频检查通过', result['file'], flush=True)
    report = dict(code_hash=stamp, model_hash=model_fingerprint(),
                  standard_count=len(standard['videos']), special_count=len(special['videos']),
                  visual_scope='这里只验证编码与当前身份，细缕及原参考形状须另看完整过程。',
                  videos=results)
    (HERE / 'analysis/edge-flow-support/video-qa.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print('本轮', len(results), '个视频全部通过', flush=True)


if __name__ == '__main__':
    main()
