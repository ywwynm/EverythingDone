"""核对随机起始区域模型的现有证据、发布资源及设备安装身份，不重跑渲染或调参。"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from unified_model import ROOT, SHARED, model_fingerprint
from export_videos import code_hash

parser = argparse.ArgumentParser()
parser.add_argument('--publication-only', action='store_true')
parser.add_argument('--report-dir', default='analysis/random-variation')
args = parser.parse_args()
out = HERE / args.report_dir
out.mkdir(parents=True, exist_ok=True)


def read(path):
    return json.loads(path.read_text('utf-8'))


fingerprint = model_fingerprint()
render_hash = code_hash()
meta = read(ROOT / 'app/build/outputs/update-debug-apk/latest.json')
apk = ROOT / 'app/build/outputs/update-debug-apk' / meta['apkUrl'].rsplit('/', 1)[1]
assert hashlib.sha256(apk.read_bytes()).hexdigest() == meta['sha256']
resources = read(SHARED / 'model.json')
assert resources['model_hash'] == fingerprint and len(resources['files']) == 8
with zipfile.ZipFile(apk) as archive:
    assert resources == json.loads(archive.read('assets/particle-dismiss/model.json'))
    for name, digest in resources['files'].items():
        assert hashlib.sha256((SHARED / name).read_bytes()).hexdigest() == digest
        assert hashlib.sha256(archive.read('assets/particle-dismiss/' + name)).hexdigest() == digest

if args.publication_only:
    url = meta['apkUrl'].split('/debug/apk/')[0] + '/debug/latest.json'
    with urllib.request.urlopen(urllib.request.Request(url, headers={'Cache-Control': 'no-cache'}), timeout=30) as response:
        remote = json.load(response)
    assert remote == meta, '远端与本地发布元数据不一致'
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(meta['apkUrl'], timeout=30) as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    assert digest.hexdigest() == meta['sha256'] and size == apk.stat().st_size
    report = dict(remote, model_hash=fingerprint, remoteMetadataExact=True,
                  remoteApkExact=True, verifiedApkBytes=size, resources_verified=list(resources['files']))
    (out / 'remote-publication.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print('远端元数据、APK 字节与八份模型资源一致，更新', meta['debugUpdateCode'])
    sys.exit(0)

scenes = read(HERE / 'assets/scenes.json')
assert len(scenes) == 17 and sum(not item.get('holdout') for item in scenes) == 7
assert read(out / 'freeze.json')['model_hash'] == fingerprint
variation = read(out / 'variation-review.json')
assert variation['model_hash'] == fingerprint and len(variation['transforms']) == 256
assert min(item['minimum_abs_determinant'] for item in variation['transforms']) > .6
assert min(item['minimum_clock_rate'] for item in variation['transforms']) > .6
contract = read(out / 'contract.json')
assert contract['frozen_model_hash'] == fingerprint and contract['renamed_and_poisoned_profiles_pixel_identical']
assert len(contract['cases']) == 17 and all(item['channel_permutation_identical_material'] for item in contract['cases'])
qa = read(HERE / 'analysis/model-qa.json')
assert qa['code_hash'] == render_hash and len(qa['model']) == 17
trace = read(HERE / 'analysis/trajectory-qa.json')
assert trace['model_hash'] == fingerprint and trace['code_hash'] == render_hash and len(trace['cases']) == 170
assert all(item['reverse_steps_over_002px'] == 0 for item in trace['cases'])
seed_trace = read(out / 'seed-trajectory-qa.json')
assert seed_trace['model_hash'] == fingerprint and seed_trace['code_hash'] == render_hash
assert len(seed_trace['cases']) == 64 and all(item['reverse_steps_over_002px'] == 0 for item in seed_trace['cases'])
videos = read(HERE / 'analysis/video-qa.json')
assert videos['code_hash'] == render_hash and videos['count'] == videos['expected_final'] == 110
manifest = read(HERE / 'videos/manifest.json')
assert manifest['code_hash'] == render_hash and len(manifest['videos']) == 110
assert all((HERE / 'videos' / item['file']).stat().st_size == item['bytes'] for item in manifest['videos'])
gpu = read(out / 'device-comparison.json')
assert gpu['model_hash'] == fingerprint and len(gpu['cases']) == 34
positions = [frame for item in gpu['cases'] for frame in item['frames'] if 'covered_position_max_px' in frame]
assert max(item['covered_position_p99_px'] for item in positions) < .25
assert max(item['covered_position_max_px'] for item in positions) < 1
units = []
for path in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*Particle*.xml'):
    suite = ET.fromstring(path.read_bytes())
    item = {key: suite.get(key) for key in ['name', 'tests', 'failures', 'errors', 'skipped']}
    assert all(item[key] == '0' for key in ['failures', 'errors', 'skipped'])
    units.append(item)
assert sum(int(item['tests']) for item in units) == 14
remote = read(out / 'remote-publication.json')
assert remote['model_hash'] == fingerprint and remote['sha256'] == meta['sha256']
assert remote['debugUpdateCode'] == meta['debugUpdateCode'] and remote['remoteMetadataExact'] and remote['remoteApkExact']
devices = []
for serial in ['9018f404', 'R5CW20BLNKL']:
    directory = HERE / 'device-variation' / serial
    published = read(directory / 'published-checks.json')
    assert published['installedSha256'] == meta['sha256'] and published['debugUpdateCode'] == meta['debugUpdateCode']
    assert published['launchAndDismissPassed'] and published['particleLayersAfter'] == published['dimLayersAfter'] == 0
    assert float(re.search(r'direction=([-\d.]+)', published['render']).group(1)) == 135
    ui = read(directory / 'ui-checks.json')
    assert len(ui) == 4 and all(item['error'] < .001 for item in ui)
    title = next(item for item in ui if item['case'] == 'language-back-after-title-touch')
    assert title['actualDirection'] == 135 and title['touchToBackMs'] < 300
    directions = read(directory / 'direction-checks.json')
    assert len(directions) == 8 and max(item['error'] for item in directions) < .06
    rapid = read(directory / 'rapid-checks.json')
    assert len(rapid['iterations']) == 10 and rapid['dialogWindowsAfter'] == rapid['particleLayersAfter'] == rapid['dimLayersAfter'] == 0
    repeated = read(directory / 'variation-ui.json')
    assert repeated['model_hash'] == fingerprint and repeated['unique_seeds'] == 6
    assert len(repeated['cases']) == 6 and all(item['direction'] == 135 for item in repeated['cases'])
    devices.append(dict(published=published, ui=ui, directions=directions, rapid=rapid, repeated=repeated))
recordings = read(HERE / 'device-variation/videos/manifest.json')
assert len(recordings['videos']) == 4
assert all((HERE / 'device-variation/videos' / item['file']).is_file() for item in recordings['videos'])
url = 'http://127.0.0.1:13070/'
with urllib.request.urlopen(url + 'index.html', timeout=10) as response:
    assert response.status == 200
    html = response.read().decode('utf-8')
    assert '每次不同的起始区域' in html and 'ironman-seed-variants-0.5x.mp4' in html
with urllib.request.urlopen(urllib.request.Request(url + 'ironman-seed-variants-0.5x.mp4', headers={'Range': 'bytes=0-1023'}), timeout=10) as response:
    assert response.status == 206 and len(response.read()) == 1024
assert '桌面预览已离屏渲染验证' in (out / 'viewer-validation.log').read_text('utf-8-sig')
report = dict(model_hash=fingerprint, code_hash=render_hash, development_scenes=7, regression_scenes=10,
              new_untuned_scenes=0, scenes=17, unit_tests=units, trajectory_cases=170,
              visible_material_frame_pairs=trace['total_visible_pairs'], seed_trajectory_cases=64, seed_visible_material_frame_pairs=seed_trace['total_visible_pairs'], variation=variation,
              videos=110, video_mib=sum(item['bytes'] for item in manifest['videos']) / 1024**2,
              independent_device_cases=34, resources_verified=list(resources['files']),
              gpu_max_case_p99_px=max(item['covered_position_p99_px'] for item in positions),
              gpu_max_position_px=max(item['covered_position_max_px'] for item in positions),
              published=meta, remote=remote, devices=devices, real_recording_videos=4,
              gallery_http=200, gallery_video_range_http=206, qt_capture_passed=True,
              limits=['共同场从认可的观测效果学习，保留形态但不等于华为内部物理模型；局部侵蚀时序仍有差异。',
                      '触点当前确定方向；相同方向、不同触点距离不会独立改变释放原点。',
                      '真实返回键已经测试，预测性返回手势未单独注入。',
                      '输入法恢复可能遮挡尾段；离屏和提交耗时不代表实际显示帧率。',
                      'OPD2515 使用真实阶段截图，三星另有真实系统录屏。'])
(out / 'acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
print('17 场景、234 组轨迹、34 组跨端对照、110 视频、同向随机关闭和发布包双设备复测全部通过。')
print('调试更新', meta['debugUpdateCode'], '模型', fingerprint)
