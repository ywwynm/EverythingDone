"""普通工具栏范围操作的合并回归；仅操作隔离测试包。"""
from pathlib import Path
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
serial, prefix = sys.argv[1:3]
flow_only = '--flow-only' in sys.argv[3:]
script = Path(__file__).with_name('run_case.py')
root = next(p for p in script.resolve().parents if (p / 'gradlew.bat').is_file())
cases = [
    ('finish_root', 'action=finish scope=root selection=all style=DRIFT record_video=true'),
    ('finish_folder', 'action=finish scope=folder selection=mixed scroll=middle style=FLOW protect_doing=true'),
    ('restore_folder', 'action=restore scope=folder selection=mixed scroll=bottom style=DRIFT'),
    ('delete_root', 'action=delete scope=root selection=mixed style=FLOW'),
    ('untrash_root', 'action=untrash scope=root selection=mixed scroll=middle style=DRIFT'),
    ('forever_folder', 'action=forever scope=folder selection=mixed scroll=bottom style=FLOW record_video=true'),
    ('fade', 'action=finish scope=root selection=all particles=false'),
    ('selection_control', 'entry=selection action=finish scope=folder selection=mixed style=DRIFT'),
]
summary = []
if flow_only:
    cases = [(name, parameters) for name, parameters in cases if 'style=FLOW' in parameters]
for name, parameters in cases:
    run = f'{prefix}_{name}'
    options = dict(x.split('=', 1) for x in ('entry=toolbar record_video=false ' + parameters).split())
    command = [sys.executable, '-X', 'utf8', str(script), serial, run]
    command.extend(f'{key}={value}' for key, value in options.items())
    result = subprocess.run(command, check=False)
    data = json.loads((root / 'tmp/selection-transition-20261013' / run / 'result.json').read_text(encoding='utf-8'))
    summary.append({k: v for k, v in data.items() if k != 'frames'})
    (root / 'tmp/selection-transition-20261013' / f'{prefix}-summary.json').write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    if result.returncode or not data.get('ok'):
        raise SystemExit(f'场景失败，停止后续操作：{run}')
print(f'通过：{serial}，{len(cases)} 个场景', flush=True)
