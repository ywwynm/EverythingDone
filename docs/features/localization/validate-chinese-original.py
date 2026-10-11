"""核对本轮全部既有中文资源；只允许用户确认的术语及繁体缺项修复。"""
import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASELINE = '51b71df3c6898fcddb7160ccc3b0171b5b03fc26'


def check():
    errors, locales = [], {}
    for locale in ['zh-rCN', 'zh-rTW', 'zh-rHK']:
        relative = f'app/src/main/res/values-{locale}/strings.xml'
        old = ET.fromstring(subprocess.check_output(['git', 'show', f'{BASELINE}:{relative}'], cwd=ROOT))
        new = ET.parse(ROOT / relative).getroot()
        old_entries = {node.get('name'): node for node in old}
        new_entries = {node.get('name'): node for node in new}
        permitted = {'home_empty_note': '明确统一记录／記錄术语'}
        if locale != 'zh-rCN':
            for key in ['dissolve_thing_folder_confirm', 'delete_thing_folder_confirm', 'delete_thing_folder_forever_confirm']:
                permitted[key] = '补齐已确认的繁体参数和功能含义'
        retained = []
        for key, before in old_entries.items():
            after = new_entries.get(key)
            if after is None:
                errors.append(f'{locale}/{key}: 原资源被删除')
            elif ET.tostring(before, encoding='unicode').strip() != ET.tostring(after, encoding='unicode').strip():
                if key not in permitted:
                    errors.append(f'{locale}/{key}: 存在未经要求的原文改动')
                retained.append({'key': key, 'reason': permitted.get(key), 'before': ''.join(before.itertext()), 'after': ''.join(after.itertext())})
        locales[locale] = {'original_entries_checked': len(old_entries),
            'new_entries_kept': len(new_entries.keys() - old_entries.keys()), 'retained_changes': retained}
    return {'baseline_commit': BASELINE, 'locales': locales, 'errors': errors}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output')
    args = parser.parse_args()
    report = check()
    if args.output:
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(bool(report['errors']))
