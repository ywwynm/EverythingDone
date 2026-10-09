"""离线区分按住准备、实际拖动与尾段，避免把静止重帧说成滚动停顿。"""
import json
from recheck_20261009 import OUT as out
result={}
for device in ('samsung','meizu','oppo'):
    result[device]={}
    for run in ('clean2-drift','clean2-flow','clean-control-drift-slide'):
        data=json.loads((out/device/run/'summary.json').read_text());rows=[]
        for s in data:
            g=s['gesture'];hold=g['hold'];up=(g['up']-g['down'])/1e6
            valid=[r for r in s['rows'] if 0<r.get('total',0)<1000]
            moving=[r for r in valid if hold-8.4<=r['t']<=up]
            still=[r for r in valid if r['t']<hold-8.4] if hold else []
            rows.append({'g':g['index'],'moving_max':round(max((r['total'] for r in moving),default=0),2),'hold_max':round(max((r['total'] for r in still),default=0),2),'whole_window_max':round(s['max'],2)})
        result[device][run]=rows
(out/'phase-metrics.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
for dev,tests in result.items():
    for run,rows in tests.items():
        print(dev,run,'moving',[r['moving_max'] for r in rows],'hold',[r['hold_max'] for r in rows[-2:]])
