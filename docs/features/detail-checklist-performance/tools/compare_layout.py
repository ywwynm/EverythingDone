"""对照旧/新详情真实截图和可见控件属性；输出差异，不自动把差异判为通过。"""
import argparse
import collections
import json
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
OUTPUT = ROOT / 'tmp/detail-checklist-diagnosis/migration'
EXCLUDED = {'sv_detail', 'rv_check_list', 'rv_audio_attachment', 'rv_image_attachment'}


def indexed(nodes):
    counts = collections.Counter()
    result = {}
    for node in nodes:
        if node['id'] in EXCLUDED:
            continue
        key = (node['id'], node.get('text', ''))
        counts[key] += 1
        result[(key, counts[key])] = node
    return result


def compare(device, candidate, baseline):
    directory = OUTPUT / device.replace(':', '_')
    report = []
    for path in sorted((directory / baseline).glob('*.json')):
        if path.name == 'result.json':
            continue
        new_path = directory / candidate / path.name
        if not new_path.exists():
            report.append({'capture': path.stem, 'missing': True})
            continue
        old = indexed(json.loads(path.read_text(encoding='utf-8')))
        new = indexed(json.loads(new_path.read_text(encoding='utf-8')))
        differences = []
        for key in old.keys() & new.keys():
            changes = {field: [value, new[key].get(field)] for field, value in old[key].items()
                       if new[key].get(field) != value}
            if changes:
                differences.append({'id': key[0][0], 'text': key[0][1][:70], 'changes': changes})
        a = np.asarray(Image.open(path.with_suffix('.png')).convert('RGB'), dtype=np.int16)
        b = np.asarray(Image.open(new_path.with_suffix('.png')).convert('RGB'), dtype=np.int16)
        pixel = None if a.shape != b.shape else round(float((np.abs(a-b).max(axis=2)>8).mean())*100, 3)
        item = {'capture': path.stem, 'pixel_changed_percent': pixel,
                'old_only': [k[0] for k in old.keys()-new.keys()],
                'new_only': [k[0] for k in new.keys()-old.keys()], 'differences': differences}
        report.append(item)
        print(f"{device} {path.stem}: 像素差异={pixel}%, 属性差异={len(differences)}, 可见节点变化={len(item['old_only'])}/{len(item['new_only'])}")
    (directory / f'{candidate}-comparison.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('candidate')
    parser.add_argument('--device', action='append', required=True)
    parser.add_argument('--baseline', default='baseline')
    args = parser.parse_args()
    for device in args.device:
        compare(device, args.candidate, args.baseline)
