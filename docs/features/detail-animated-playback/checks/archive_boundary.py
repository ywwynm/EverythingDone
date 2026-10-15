"""归档大 GIF 循环交界专项的计数证据，不保存用户媒体和原始窗口像素。"""
from pathlib import Path
import json
import statistics
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[4]
checks = Path(__file__).resolve().parent
tmp = root / 'tmp/detail-gif-flicker'
runs = {}
for group in ('boundary-window', 'boundary-fullspan-ime', 'boundary-fullspan-fixed', 'boundary-fullspan-cold'):
    for device in ('oppo', 'samsung', 'meizu'):
        name = group + '-' + device
        folder = tmp / name
        summary = json.loads((folder / 'boundary-summary.json').read_text(encoding='utf-8'))
        gestures = json.loads((folder / 'gestures.json').read_text(encoding='utf-8'))
        summary['gestures'] = dict(count=len(gestures), ime=sum(g['ime'] for g in gestures),
            settling=sum(g['scrollState'] == 2 for g in gestures),
            medianMs=statistics.median(g['durationMs'] for g in gestures) if gestures else None)
        runs[name] = summary
tests = [ET.parse(p).getroot().attrib for p in (root / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
result = dict(
    caveat='未独立复现持续循环交界露底；已复现并修复切换播放模式后重新进入的跨静动占位缺口。窗口采样非物理屏幕每次刷新。',
    source='12 个动态附件，其中 3 个约 32–35 MB 派生 GIF 与 9 个约 10–16 MB 独立文件副本；4 张静态图穿插，另有 17 张静态图与 80 段正文。',
    positiveControl='首次加载阶段确实捕获到背景色和加载指示器；原有模式切换空白的实际窗口截图同样保留在 tmp。',
    runs=runs,
    unitTests=dict(suites=len(tests), **{k: sum(int(t.get(k, 0)) for t in tests) for k in ('tests', 'failures', 'errors', 'skipped')}))
if '--publication' in sys.argv:
    result['publication'] = json.loads((tmp / 'publish-verification.json').read_text())
(checks / 'boundary-results-2026-10-15.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(runs=len(runs), failed=[name for name, run in runs.items() if not run['passed']], unitTests=result['unitTests']), ensure_ascii=False))
