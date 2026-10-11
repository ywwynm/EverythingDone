"""对照简体中文检查全部应用语言的资源、参数、数组与复数结构。只读，不修改译文。"""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / 'app/src/main/res'
LOCALES = ['values', 'values-zh-rCN', 'values-zh-rTW', 'values-zh-rHK'] + [
    'values-' + x for x in ['de', 'es', 'fr', 'hi', 'it', 'ja', 'ko', 'pt', 'ru']]
PATTERN = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])')

def parameters(text):
    result = {}; position = 0
    for match in PATTERN.finditer(text):
        index, kind = match.groups()
        if kind in ('%', 'n'): continue
        position += 1
        result[int(index) if index else position] = kind
    return result

def read(locale):
    entries = {}; errors = []
    for file in sorted((RES / locale).glob('*.xml')):
        for node in ET.parse(file).getroot():
            if node.tag not in ('string', 'string-array', 'plurals'): continue
            key = (node.tag, node.attrib['name'])
            if key in entries: errors.append(f'{locale}: 重复资源 {key}')
            entries[key] = node
    return entries, errors

def check():
    loaded = {locale: read(locale) for locale in LOCALES}
    baseline = loaded['values-zh-rCN'][0]
    baseline = {k:v for k,v in baseline.items() if v.get('translatable') != 'false'}
    errors = [error for _, problems in loaded.values() for error in problems]
    counts = {}
    for locale, (entries, _) in loaded.items():
        missing = sorted(set(baseline) - set(entries))
        errors.extend(f'{locale}: 缺少 {key}' for key in missing)
        counts[locale] = {'resources': len(entries), 'missing': len(missing)}
        for key, source in baseline.items():
            if key not in entries: continue
            target = entries[key]
            source_items = [source] if key[0] == 'string' else list(source)
            target_items = [target] if key[0] == 'string' else list(target)
            if key[0] == 'string-array' and len(source_items) != len(target_items):
                errors.append(f'{locale}: 数组长度不同 {key[1]}')
            if key[0] == 'plurals':
                if not any(item.get('quantity') == 'other' for item in target_items):
                    errors.append(f'{locale}: 复数缺少 other {key[1]}')
                source_items = [next(item for item in source_items if item.get('quantity') == 'other')] * len(target_items)
            for index, (base_item, item) in enumerate(zip(source_items, target_items)):
                text = ''.join(item.itertext())
                if not text.strip(): errors.append(f'{locale}: 空文案 {key[1]}[{index}]')
                expected = {} if source.get('formatted') == 'false' else parameters(''.join(base_item.itertext()))
                actual = {} if target.get('formatted') == 'false' else parameters(text)
                # 中文帮助保留原有字面日期例子；外语按已授权的语言日期顺序填入示例。
                if key[1] == 'create_daily_todo_help_info' and not locale.startswith('values-zh-'):
                    expected = {1: 's'}
                if expected != actual:
                    errors.append(f'{locale}: 参数不同 {key[1]}[{index}]: {expected} != {actual}')
    return {'baseline': len(baseline), 'locales': counts, 'errors': errors}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output'); args = parser.parse_args()
    report = check()
    if args.output:
        output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(bool(report['errors']))
