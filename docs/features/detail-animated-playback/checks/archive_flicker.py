"""归档不含媒体内容和路径的三机红绿证据；原始逐帧数据留在 tmp。"""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[4]
DATA=ROOT/'tmp/detail-gif-flicker'
rows=[]
for label, before, after in [('oppo','red4-oppo','bounded-oppo'), ('samsung','red2-samsung','bounded-samsung'), ('meizu','red2-meizu','bounded2-meizu')]:
    row=dict(device=label)
    for key,name in [('before',before),('after',after)]:
        summary=json.loads((DATA/name/'summary.json').read_text())
        result=json.loads((DATA/name/'result.json').read_text())
        scenes=summary['scenes']
        row[key]=dict(run=name,samples=result['samples'],apkSha256=result['apkSha256'],
            originalUnchanged=result['originalUnchanged'],events=result.get('events',[]),
            recycle_blank_by_position=[s['blank'] for s in scenes if s['stage']=='recycle-return'],
            warm_blank_by_position=[s['blank'] for s in scenes if s['stage']=='warm-return'],
            edge_static_frames=sum(s['types'].get('BitmapDrawable',0) for s in scenes if s['stage'].startswith('edge-')),
            loop_blank=sum(s['blank'] for s in scenes if s['stage']=='loop'),
            loop_wraps=sum(s['wraps'] for s in scenes if s['stage']=='loop'),
            existing_loop_blank=sum(s['blank'] for s in scenes if s['stage']=='real-loop'),
            existing_loop_wraps=sum(s['wraps'] for s in scenes if s['stage']=='real-loop'),
            decodedGif=summary['decodedGif'])
    rows.append(row)

extra=[]
for name in ('warm-samsung', 'large-red-samsung', 'bounded-large-oppo', 'bounded-large-samsung', 'bounded-large-meizu'):
    summary=json.loads((DATA/name/'summary.json').read_text())
    result=json.loads((DATA/name/'result.json').read_text())
    extra.append(dict(run=name,samples=result['samples'],apkSha256=result['apkSha256'],
        originalUnchanged=result['originalUnchanged'],events=result.get('events',[]),
        returns=[dict(stage=s['stage'],position=s['pos'],samples=s['samples'],blank=s['blank'])
            for s in summary['scenes'] if s['stage'] in ('warm-return','large-return','large-warm-return')]))
tests={'tests':0,'failures':0,'errors':0,'skipped':0,'suites':0}
for file in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    element=ET.parse(file).getroot()
    tests['suites']+=1
    for key in ('tests','failures','errors','skipped'): tests[key]+=int(element.get(key,0))
output=dict(devices=rows,cacheAndSizeChecks=extra,jvm=tests,
    existingGifFiles=json.loads((DATA/'existing/summary.json').read_text()))
publication=DATA/'publish-verification.json'
if publication.exists(): output['publication']=json.loads(publication.read_text())
(Path(__file__).parent/'flicker-results-2026-10-15.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(tests)
