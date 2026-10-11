"""核对审查清单的处理覆盖率和明确的术语约束；不把文本变化等同于语言质量。"""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def read(locale):
    folder = 'values' if locale == 'en' else 'values-' + locale.replace('zh-', 'zh-r')
    entries = {}
    for file in (ROOT / 'app/src/main/res' / folder).glob('*.xml'):
        for node in ET.parse(file).getroot():
            if node.tag == 'string':
                entries[node.get('name')] = ''.join(node.itertext())
            elif node.tag in ('string-array', 'plurals'):
                for index, child in enumerate(node):
                    suffix = child.get('quantity') if node.tag == 'plurals' else str(index)
                    entries[f'{node.get("name")}[{suffix}]'] = ''.join(child.itertext())
    return entries


def check():
    audit = json.loads((HERE / 'wording-audit-evidence-2026-10-11.json').read_text(encoding='utf8'))
    locales = ['en', 'zh-CN', 'zh-TW', 'zh-HK', 'de', 'es', 'fr', 'hi', 'it', 'ja', 'ko', 'pt', 'ru']
    current = {locale: read(locale) for locale in locales}
    errors, groups, entries = [], [], {}
    # W04 将正确的旧总称与错误的新操作标题一并取证；该旧总称应保留。
    retained = {('de', 'all_things'): '原文 Alle Dinge 已符合 Ding / Dinge 术语，保留。'}
    restored = json.loads((HERE / 'validation/2026-10-11-chinese-original/xml-dispositions.json').read_text(encoding='utf8'))
    for entry in restored:
        if entry['action'] == '恢复原文':
            retained[(entry['locale'].replace('zh-r', 'zh-'), entry['key'])] = '按用户最新要求恢复已有中文原文，撤销本轮非必要润色；另由中文基线检查验证。'
    for group in audit['groups']:
        resolved = []
        for evidence in group['evidence']:
            identity = evidence['locale'], evidence['entry']
            text = current[identity[0]].get(identity[1])
            changed = text is not None and text != evidence['text']
            if not changed and identity not in retained:
                errors.append(f'{group["id"]}: 未处理或缺失 {identity}')
            entries[identity] = {'locale': identity[0], 'entry': identity[1],
                'status': '已修订' if changed else '复核后保留', 'current_text': text,
                'note': retained.get(identity, '')}
            resolved.append({'locale': identity[0], 'entry': identity[1]})
        groups.append({'id': group['id'], 'title': group['title'], 'entries': resolved})

    canonical = {
        'en': ('Thing', 'Note'), 'de': ('Ding', 'Notiz'), 'es': ('Cosa', 'Nota'),
        'fr': ('Élément', 'Note'), 'hi': ('चीज़', 'नोट'), 'it': ('Elemento', 'Nota'),
        'ja': ('メモ', 'ノート'), 'ko': ('항목', '메모'), 'pt': ('Item', 'Nota'),
        'ru': ('Запись', 'Заметка'), 'zh-CN': ('记事', '记录'),
        'zh-TW': ('記事', '記錄'), 'zh-HK': ('記事', '記錄'),
    }
    for locale, (thing, note) in canonical.items():
        for key, expected in [('thing', thing), ('note', note)]:
            if current[locale][key].casefold() != expected.casefold():
                errors.append(f'{locale}/{key}: 未使用约定术语 {expected}')
        if current[locale]['title_activity_things_all'] not in ('@string/all_things', current[locale]['all_things']):
            errors.append(f'{locale}: 全部记事标题没有引用统一术语')
        if current[locale]['statistic_habit_completion_rate'] not in ('@string/habit_detail_completion_rate', current[locale]['habit_detail_completion_rate']):
            errors.append(f'{locale}: 统计页和习惯详情的完成率术语未统一')
        for key, text in current[locale].items():
            if '__ईडीएनएल__' in text or (locale == 'es' and key.startswith('start_doing_') and '__' in text):
                errors.append(f'{locale}/{key}: 损坏占位残留')
        for key in ('l10n_recurrence_at_time', 'l10n_recurrence_at_one'):
            if '%1$s' not in current[locale][key]: errors.append(f'{locale}/{key}: 缺少时刻参数')
    for key, text in current['en'].items():
        if key.startswith(('l10n_habit_', 'cd_checklist_', 'act_finish')) and re.search(r'\bcomplete(?:d|s)?\b', text, re.I):
            errors.append(f'en/{key}: 记事完成未使用 finish / finished')
    if current['ru']['habit_detail_completion_rate'] != 'Процент выполнения':
        errors.append('ru: 完成率必须表达百分比，不能译为完成速度')
    return {'说明': '逐条译文已由代理按中文与调用语境修订。此脚本只检查覆盖率与明确约束，语法边界另由设备探针核验。',
        'group_count': len(groups), 'entry_count': len(entries),
        'changed_count': sum(e['status'] == '已修订' for e in entries.values()),
        'retained_count': sum(e['status'] == '复核后保留' for e in entries.values()),
        'groups': groups, 'entries': list(entries.values()), 'errors': errors}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output')
    args = parser.parse_args()
    report = check()
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('groups', 'entries')}, ensure_ascii=False, indent=2))
    raise SystemExit(bool(report['errors']))
