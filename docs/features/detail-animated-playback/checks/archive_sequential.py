"""归档逐一播放的三机证据；不保存媒体画面、文件路径或原始逐帧数据。"""
from pathlib import Path
import hashlib
import json
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
DATA = ROOT / 'tmp/detail-gif-flicker'

def collect(name, summary_file='sequential-summary.json'):
    folder = DATA / name
    result = json.loads((folder / 'result.json').read_text())
    row = dict(run=name, ok=result['ok'], originalUnchanged=result['originalUnchanged'],
               apkSha256=result['apkSha256'], samples=result['samples'], events=result['events'])
    summary = folder / summary_file
    if summary.exists():
        values = json.loads(summary.read_text())
        for key in ('scenes', 'positions', 'imeCoverage', 'maxSimultaneouslyRunning', 'gestures', 'decodedGif', 'unmappedRunning'):
            if key in values:
                row[key] = values[key]
    log = (folder / 'process.log').read_text(encoding='utf-8', errors='replace')
    row['runtimeFailures'] = {token: log.count(token) for token in (
        'FATAL EXCEPTION', 'OutOfMemoryError', 'Cannot draw recycled', 'Cannot start a recycled')}
    if any(row['runtimeFailures'].values()):
        row['ok'] = False
    return row

result = dict(
    baseline=[collect(f'sequential-{kind}-red-{device}')
              for kind in ('fast3', 'complex') for device in ('oppo', 'samsung', 'meizu')],
    posterOnly=[collect(f'sequential-complex-poster-{device}') for device in ('oppo', 'meizu')],
    beforeMemoryFix=[collect(f'sequential-{kind}-{device}')
           for kind in ('fast-final', 'complex-fullspan-green') for device in ('oppo', 'samsung', 'meizu')],
    final=[collect(f'sequential-{kind}-mapped-{device}')
           for kind in ('fast', 'complex-fullspan') for device in ('oppo', 'samsung', 'meizu')],
    queue=[collect(f'sequential-queue-{color}-samsung') for color in ('red', 'green')],
    loopBeforeMemoryFix=[collect(f'complex-loop-final-{device}', 'summary.json') for device in ('oppo', 'samsung', 'meizu')],
    loopRegression=[collect(f'complex-loop-verified-{device}', 'summary.json') for device in ('oppo', 'samsung', 'meizu')],
    excluded=['sequential-complex-poster-samsung：ADB daemon 连接超时，未进入测试。'],
    limitations=['复杂夹具的九份真实派生 GIF 是三个内容的独立路径副本。',
                 '未承诺任意媒体、首次解码、缓存淘汰和所有裁切均无等待。',
                 '独立连续循环边界仍未复现用户描述的额外空白。'])
tests = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
for file in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    suite = ET.parse(file).getroot()
    tests['suites'] += 1
    for key in ('tests', 'failures', 'errors', 'skipped'):
        tests[key] += int(suite.get(key, 0))
result['jvm'] = tests
result['productionSha256'] = {
    name: hashlib.sha256((ROOT / 'app/src/main/java/com/ywwynm/everythingdone' / name).read_bytes()).hexdigest()
    for name in ('adapters/ImageAttachmentAdapter.kt', 'helpers/DetailAttachmentPlaybackController.kt')}
if '--publication' in sys.argv:
    result['publication'] = json.loads((DATA / 'publish-verification.json').read_text())
    assert result['publication']['code'] != 202610151408, '不能把首轮发布当作本轮发布'
(Path(__file__).parent / 'sequential-results-2026-10-15.json').write_text(
    json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(jvm=tests, finalSamples=sum(r['samples'] for r in result['final']),
                     loopSamples=sum(r['samples'] for r in result['loopRegression'])), ensure_ascii=False))
