"""复核原始逐帧数据；不把渲染管线延迟当作屏幕相邻帧间隔。"""
import json
from pathlib import Path
from collections import Counter
from recheck_20261009 import OUT, DEVICES, summary, parse_dump, stages, pct, save

def review(d):
    results=[]
    for mark in sorted(d.glob('g*-mark.json')):
        g=json.loads(mark.read_text()); paths=sorted(d.glob(mark.stem.replace('-mark','')+'-*-framestats.txt'))
        r=summary(paths,g)
        # reset 后首帧可晚于 DOWN。这里只报告时间差与样本数，不能以“首帧晚于
        # DOWN”直接断定环形缓冲覆盖；静止窗口也可能暂时没有帧。
        raw={}
        for p in paths:
            for w,v in parse_dump(p).items():
                if 'ThingsActivity' in w:
                    for f in v['frames']:
                        if 'IntendedVsync' in f: raw[f['IntendedVsync']]=f
        r['first_frame_after_down_ms']=round((min(raw)-g['down'])/1e6,3) if raw else None
        r['raw_frame_count']=len(raw)
        in_window=[f for f in raw.values() if f.get('Flags')==0 and g['down']<=f['IntendedVsync']<=g['up']+800000000]
        actual=[f for f in in_window if 0<f.get('FrameCompleted',0)-f['IntendedVsync']<1000000000]
        r['invalid_completion_count']=len(in_window)-len(actual)
        # 魅族的呈现字段部分缺失，OPPO 全为 0；不据此计算呈现间隔与掉帧率。
        r['valid_present_timestamp_count']=sum(f.get('DisplayPresentTime',0)>=f['IntendedVsync'] for f in actual)
        r['overrun_max']=max([f['FrameCompleted']/1e6-f['FrameDeadline']/1e6 for f in actual],default=0)
        r['startup_miss']=sum(x['overdue'] and x['t']<=300 for x in r['rows'])
        r['later_miss']=sum(x['overdue'] and x['t']>300 for x in r['rows'])
        r['gesture']=g
        results.append(r)
    save(d/'review.json',json.dumps(results,ensure_ascii=False,indent=2))
    compact=[]
    for r in results:
        compact.append({'g':r['gesture']['index'],'first_frame_after_down_ms':r['first_frame_after_down_ms'],'n':r['frames'],'invalid_completion_count':r['invalid_completion_count'],'max':r['max'],'overrun':round(r['overrun_max'],1)})
    return compact

if __name__=='__main__':
    for name in DEVICES:
        for d in sorted((OUT/name).glob('*')):
            if d.is_dir() and list(d.glob('g*-mark.json')):
                print(name,d.name,json.dumps(review(d),ensure_ascii=False))
