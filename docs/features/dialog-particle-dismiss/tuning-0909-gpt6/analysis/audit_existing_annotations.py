"""汇总全部既有标注和扫描数据，忽略短小标线，不重新渲染视频。"""
from pathlib import Path
import json,collections
import numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
OUT=HERE/'analysis/motion-field-extension'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    cases=json.loads((HERE/'analysis/stalled-edges-expanded/review-manifest.json').read_text('utf-8'))['cases']
    assert len(cases)==226
    rows=[];by_scene=collections.defaultdict(list)
    for c in cases:
        c.update(json.loads((HERE/f'analysis/stalled-edges-expanded/{c["key"]}.json').read_text('utf-8')))
        x,y,x1,y1=c['rect'];span=min(x1-x,y1-y)
        long=[]
        for a in c['annotations']:
            length=float(np.linalg.norm(np.diff(np.array(a['points']),axis=0),axis=1).sum())
            if length>=span*.12:
                long.append(dict(id=a['id'],kind=a['kind'],length_px=length,length_over_span=length/span,phase=a['phase']))
        stats=c['stats']
        row=dict(id=c['id'],scene=c['scene'],seed=c['seed'],angle=c['angle'],long_annotations=long,
                 peak_slow=c['peak_slow'],peak_visible=max(s['visible'] for s in stats),
                 late_slow=max(s['slow'] for s in stats if s['time']>=.7))
        rows.append(row);by_scene[c['scene']].append(row)
    report=dict(cases=rows,case_count=len(rows),long_mark_count=sum(len(r['long_annotations']) for r in rows),
                scope='全部 226 组既有标注；短于控件短边 12% 的标线从重点列表中排除，原始证据完整保留。',
                summary={k:dict(cases=len(v),with_long_marks=sum(bool(r['long_annotations']) for r in v),
                    original_marks=sum(a['kind']=='original' for r in v for a in r['long_annotations']),
                    other_marks=sum(a['kind']!='original' for r in v for a in r['long_annotations']),
                    peak_slow=max(r['peak_slow'] for r in v),late_slow=max(r['late_slow'] for r in v)) for k,v in by_scene.items()})
    (OUT/'all-annotations.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'},ensure_ascii=False,indent=2))
    # 每种素材挑原边界和其它轮廓各一例，仅复用已存在的检查图。
    selected=[]
    for name,values in by_scene.items():
        for kind in ['original','other']:
            matches=lambda a: a['kind']=='original' if kind=='original' else a['kind']!='original'
            candidates=[r for r in values if any(matches(a) for a in r['long_annotations'])]
            if candidates:selected.append(max(candidates,key=lambda r:sum(a['length_px'] for a in r['long_annotations'] if matches(a))))
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for group in range(2):
        im=Image.new('RGB',(1540,1440),'#101722');d=ImageDraw.Draw(im)
        for i,row in enumerate(selected[group*7:(group+1)*7]):
            c=next(c for c in cases if c['id']==row['id'])
            src=Image.open(HERE/f'analysis/stalled-edges-expanded/{c["key"]}-review.jpg')
            src.thumbnail((760,430));x=(i%2)*770;y=(i//2)*360
            d.text((x+10,y+5),f'{row["id"]} {row["scene"]} 种子 {row["seed"]} · {row["angle"]}°',font=font,fill='white')
            im.paste(src,(x,y+30))
        im.save(OUT/f'existing-long-marks-{group}.jpg',quality=94)

if __name__=='__main__':main()
