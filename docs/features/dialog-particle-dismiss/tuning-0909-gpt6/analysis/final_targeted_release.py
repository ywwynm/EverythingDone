"""核对当前触点输运版本的桌面、设备与发布包证据，明确列出未能验证的设备。"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from unified_model import ROOT, SHARED, model_fingerprint
from export_videos import code_hash

OUT = HERE / 'analysis/targeted-release'


def read(path):
    return json.loads(path.read_text('utf-8'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serials', nargs='+', choices=['9018f404', 'R5CW20BLNKL'], required=True)
    parser.add_argument('--unavailable-serials', nargs='*', choices=['9018f404', 'R5CW20BLNKL'], default=[])
    args = parser.parse_args()
    assert set(args.serials).isdisjoint(args.unavailable_serials)
    assert set(args.serials + args.unavailable_serials) == {'9018f404', 'R5CW20BLNKL'}

    fingerprint = model_fingerprint()
    resources = read(SHARED / 'model.json')
    assert resources['model_hash'] == fingerprint
    qa = read(HERE / 'analysis/video-qa.json')
    model = read(HERE / 'analysis/model-qa.json')
    assert qa['count'] == 128 and qa['code_hash'] == code_hash()
    assert model['code_hash'] == code_hash() and len(model['model']) == 18
    assert len(model['direction_checks']) == 30
    regression = read(OUT / 'regression.json')
    assert regression['model_hash'] == fingerprint and len(regression['cases']) == 234
    assert sum(x['stagnant_windows'] for x in regression['cases']) == 0
    assert sum(x['reverse_steps'] for x in regression['cases']) == 0
    distance = read(OUT / 'distances-and-fronts.json')
    assert distance['model_hash'] == fingerprint and len(distance['distance']) == 18
    assert len(distance['fronts']) == 120
    ratios = []
    for name in ['ironman', 'attachment', 'color']:
        for angle in [135, 90]:
            rows = sorted((r for r in distance['distance'] if r['scene'] == name and r['angle'] == angle), key=lambda r: r['gap'])
            assert [r['gap'] for r in rows] == [.15, .65, 1.5]
            for key in ['speed_median', 'displacement_median']:
                assert rows[0][key] < rows[1][key] < rows[2][key]
                assert rows[2][key] > rows[0][key] * 1.65
            assert all(r['moved_closer_fraction'] > .85 for r in rows)
            ratios.append(dict(scene=name, angle=angle,
                far_near_speed=rows[2]['speed_median'] / rows[0]['speed_median'],
                far_near_displacement=rows[2]['displacement_median'] / rows[0]['displacement_median']))
    test_file = ROOT / 'app/build/test-results/testDebugUnitTest/TEST-com.ywwynm.everythingdone.views.particledismiss.ParticleMicroflakeModelTest.xml'
    tests = ET.parse(test_file).getroot()
    assert int(tests.get('tests')) == 12 and int(tests.get('failures')) == int(tests.get('errors')) == 0
    assert test_file.stat().st_mtime > (ROOT / 'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleReleaseTopology.kt').stat().st_mtime

    independent = []
    for folder, count in [('device-comparison', 6), ('device-near-comparison', 2), ('device-far-comparison', 2)]:
        report = read(OUT / folder / 'device-comparison.json')
        assert report['model_hash'] == fingerprint
        assert len(report['cases']) == count * len(args.serials)
        for serial in args.serials:
            assert sum(r['device'] == serial for r in report['cases']) == count
        independent.extend(report['cases'])

    published = read(ROOT / 'app/build/outputs/update-debug-apk/latest.json')
    apk = ROOT / 'app/build/outputs/update-debug-apk' / published['apkUrl'].rsplit('/', 1)[1]
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == published['sha256']
    with zipfile.ZipFile(apk) as archive:
        assert json.loads(archive.read('assets/particle-dismiss/model.json')) == resources
        for name, digest in resources['files'].items():
            assert hashlib.sha256(archive.read('assets/particle-dismiss/' + name)).hexdigest() == digest
    devices = []
    for serial in args.serials:
        ui = read(HERE / 'device-targeted-release' / serial / 'touch-ui-checks.json')
        assert ui['cases'][1]['gap'] > ui['cases'][0]['gap'] * 1.7
        assert ui['cases'][2]['direction'] == 135 and ui['cases'][2]['gap'] == .65
        installed = read(HERE / 'device-targeted-release-published' / serial / 'published-checks.json')
        assert installed['installedSha256'] == published['sha256']
        assert installed['debugUpdateCode'] == published['debugUpdateCode'] and installed['launchAndDismissPassed']
        assert installed['particleLayersAfter'] == installed['dimLayersAfter'] == 0
        assert 'gap=0.65' in installed['render']
        devices.append(installed)

    unavailable = []
    for serial in args.unavailable_serials:
        result = subprocess.run(['E:/AndroidSDK/platform-tools/adb.exe', '-s', serial, 'get-state'], capture_output=True, text=True)
        reason = result.stdout + result.stderr
        assert result.returncode != 0 and 'unauthorized' in reason, (serial, reason)
        unavailable.append(dict(serial=serial, reason='USB 调试未授权，未完成本轮设备验证'))

    request = lambda url: urllib.request.Request(url, headers={'Cache-Control': 'no-cache'})
    url = published['apkUrl'].split('/debug/apk/')[0] + '/debug/latest.json'
    with urllib.request.urlopen(request(url), timeout=30) as response:
        remote = json.load(response)
    assert remote['sha256'] == published['sha256'] and remote['debugUpdateCode'] == published['debugUpdateCode']
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(request(published['apkUrl']), timeout=30) as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    assert digest.hexdigest() == published['sha256'] and size == apk.stat().st_size
    report = dict(model_hash=fingerprint, code_hash=code_hash(), videos=128, scenes=18,
        state_cases=234, release_inputs=120, distance_inputs=18, distance_ratios=ratios,
        long_front_occurrences_before=sum(r['before']['long_transverse'] for r in distance['fronts']),
        long_front_occurrences_after=sum(r['after']['long_transverse'] for r in distance['fronts']),
        independent_device_cases=len(independent), jvm_tests=int(tests.get('tests')),
        shared_resources=list(resources['files']), debugUpdateCode=published['debugUpdateCode'],
        apkUrl=published['apkUrl'], sha256=published['sha256'], remote_bytes=size,
        devices=devices, unavailable_devices=unavailable,
        status='已发布，存在待补设备验证' if unavailable else '已发布，指定设备验证完成',
        scope='状态检查不等同于华为运动真值或用户审美验收；离屏设备渲染不代表系统显示帧率。')
    (OUT / 'acceptance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ['devices', 'distance_ratios']}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
