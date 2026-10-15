"""汇总真实绘制采样：空白、主图跳回、循环边界、Drawable 重建。"""
from pathlib import Path
from collections import Counter
import json
import sys
from PIL import Image, ImageSequence
import numpy as np

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[4] / 'tmp/detail-gif-flicker'
verify = '--verify' in sys.argv
failed = False
for name in [arg for arg in sys.argv[1:] if arg != '--verify']:
    folder = ROOT / name
    data = json.loads((folder / 'samples.json').read_text())
    result = json.loads((folder / 'result.json').read_text())
    summary = dict(run=name, originalUnchanged=result.get('originalUnchanged'), scenes=[])
    for stage in dict.fromkeys(row['stage'] for row in data):
        for pos in sorted({r['pos'] for r in data}):
            rows = [r for r in data if r['stage'] == stage and r['pos'] == pos]
            if not rows:
                continue
            summary['scenes'].append(dict(stage=stage, pos=pos, samples=len(rows),
                blank=sum(r['type']=='null' or r['opaque']==0 for r in rows),
                black=sum(r['black']>550 for r in rows),
                poster=sum(r['yellow']>550 for r in rows),
                types=dict(Counter(r['type'] for r in rows)),
                instances=len(set(r['drawable'] for r in rows)),
                wraps=sum(a['frame'] > b['frame'] >= 0 and a['drawable']==b['drawable'] for a,b in zip(rows,rows[1:])),
                uniqueFrames=len(set(r['frame'] for r in rows if r['frame']>=0))))
    gif=Image.open(folder/'video.gif')
    decoded=[np.asarray(frame.convert('RGBA')) for frame in ImageSequence.Iterator(gif)]
    summary['decodedGif']=dict(frames=len(decoded), blackFrames=int(sum(np.mean(a[:,:,:3])<10 for a in decoded)),
        transparentFrames=int(sum(np.min(a[:,:,3])==0 for a in decoded)))
    (folder/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    violations = [s for s in summary['scenes'] if
        (s['pos']<3 and s['stage'].startswith('edge-') and (s['types'] != {'GifDrawable': s['samples']} or s['poster'])) or
        (s['stage'] in ('recycle-return', 'warm-return', 'large-return', 'large-warm-return') and s['blank']) or
        (s['stage'] in ('loop', 'real-loop') and (s['blank'] or
            (s['pos']<3 and (s['instances'] != 1 or s['wraps'] < 2))))]
    bad_events = [event for event in result.get('events', []) if not all(
        value for key, value in event.items() if key in ('same', 'stopped', 'passed'))]
    log=(folder/'process.log').read_text(encoding='utf-8',errors='replace')
    runtime_failures={token:log.count(token) for token in (
        'FATAL EXCEPTION','OutOfMemoryError','Cannot draw recycled','Cannot start a recycled')}
    print(name, 'samples=',len(data), 'violations=',len(violations), 'gif=',summary['decodedGif'])
    for scene in violations:
        print(scene)
    if verify and (violations or bad_events or any(runtime_failures.values()) or not result.get('ok') or not result.get('originalUnchanged')):
        failed = True
sys.exit(1 if failed else 0)
