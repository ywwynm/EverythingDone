"""带真实媒体／状态控件的恢复回归；沿用隔离包和设备白名单。"""
import json
from pathlib import Path
import subprocess
import sys

here = Path(__file__).resolve().parent
root = next(p for p in here.parents if (p / 'gradlew.bat').exists()) / 'tmp/selection-transition-20261013'
serial, name = sys.argv[1:3]
start = int(sys.argv[3]) if len(sys.argv) > 3 else 0
cases = [
    dict(action='finish', scope='root', scroll='middle', selection='offscreen', style='DRIFT', select_doing='true'),
    dict(action='delete', scope='folder', scroll='top', selection='visible', style='FLOW'),
    dict(action='restore', scope='folder', scroll='bottom', selection='mixed', style='DRIFT'),
    dict(action='forever', scope='root', scroll='middle', selection='offscreen', style='FLOW'),
    dict(action='untrash', scope='folder', scroll='middle', selection='visible', style='DRIFT'),
    dict(action='finish', scope='root', scroll='bottom', selection='mixed', style='FLOW', particles='false', checklist_taps='false'),
    dict(action='finish', scope='folder', cache_exit='true'),
    dict(action='delete', scope='root', scroll='middle', selection='visible', style='FLOW', interrupt='back'),
    dict(action='finish', scope='folder', scroll='top', selection='all', style='DRIFT', audit_appearance='false'),
]
for index, case in enumerate(cases[start:], start):
    run = f'appearance-complete-{name}-{index:02d}'
    options = dict(audit_appearance='true', complex='true', record_video='false') | case
    command = [sys.executable, '-X', 'utf8', str(here / 'run_case.py'), serial, run]
    command += [f'{key}={value}' for key, value in options.items()]
    subprocess.run(command, check=True, timeout=150)
    result_file = root / run / 'result.json'
    data = json.loads(result_file.read_text()) if result_file.exists() else dict(ok=False)
    (root / run / 'case.json').write_text(json.dumps(options), encoding='utf-8')
    if not data.get('ok'):
        raise SystemExit(f'{run} 未通过')
