"""真实列表滚动、粒子回拖及媒体批量恢复。只能对已授权的独立测试包执行。"""
from pathlib import Path
import json
import subprocess
import sys

root = next(p for p in Path(__file__).resolve().parents if (p / 'gradlew.bat').is_file())
runner = root / 'docs/features/selection-batch-actions/checks/run_case.py'
serial, label = sys.argv[1:3]
assert serial in ['R5CW20BLNKL', '9018f404', '192.168.100.67:42641']
cases = [
    dict(scroll_warm='true', rounds='36', scope='root', appearance='day', style='DRIFT'),
    dict(scroll_warm='true', rounds='36', scope='folder', appearance='night', style='FLOW'),
    dict(scroll_warm='true', rounds='12', gesture='horizontal', press_ms='90', scope='root', appearance='day', style='DRIFT'),
    dict(scroll_warm='true', rounds='12', gesture='horizontal', press_ms='90', scope='folder', appearance='day', style='FLOW'),
    dict(audit_appearance='true', complex='true', action='finish', scope='root', scroll='middle', selection='offscreen', style='DRIFT', select_doing='true'),
    dict(audit_appearance='true', complex='true', action='delete', scope='folder', scroll='bottom', selection='mixed', style='FLOW'),
    dict(scroll_warm='true', rounds='18', scope='root', appearance='day', particles='false'),
]
for index, case in enumerate(cases):
    run = f'scroll-final-{label}-{index:02d}'
    options = dict(record_video='false', probe_timeout='220') | case
    subprocess.run([sys.executable, '-X', 'utf8', str(runner), serial, run,
                    *[f'{k}={v}' for k, v in options.items()]], check=True, timeout=300)
    folder = root / 'tmp/selection-transition-20261013' / run
    (folder / 'case.json').write_text(json.dumps(options), encoding='utf-8')
    result = json.loads((folder / 'result.json').read_text())
    if not result.get('ok'):
        raise SystemExit(f'{run} 未通过，保留失败记录')
