"""离线汇总三设备读回、空闲解码 CPU 与实际呈现节奏；不连接设备。"""
import json, sqlite3, csv, subprocess, concurrent.futures
from collections import Counter
from pathlib import Path
from recheck_20261009 import OUT,DEVICES,pct,save
SQL='''WITH display_frames AS (
SELECT a.* FROM actual_frame_timeline_slice a JOIN process p USING(upid)
WHERE p.name='/system/bin/surfaceflinger' AND COALESCE(a.surface_frame_token,0)=0 AND a.dur>0)
SELECT a.id,a.ts,a.dur,a.layer_name,a.present_type,a.jank_type,a.display_frame_token,
d.ts+d.dur AS present_ts FROM actual_frame_timeline_slice a JOIN process p USING(upid)
LEFT JOIN display_frames d USING(display_frame_token)
WHERE p.name='com.ywwynm.everythingdone' ORDER BY a.ts;'''
def export_present(td):
    p=subprocess.run([str(OUT/'trace_processor_shell.exe'),'query',str(td/'trace.perfetto-trace'),SQL],capture_output=True)
    if p.returncode:raise RuntimeError(p.stderr.decode())
    save(td/'presentation.csv',p.stdout)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
    list(ex.map(export_present,[OUT/dev/run for dev in DEVICES for run in ('trace2-drift','trace2-flow','control-drift-slide')]))
result={}
for device in DEVICES:
    d=OUT/device; item={}
    env=(d/'initial-environment.txt').read_text()
    item['thermal_status_lines']=[s.strip() for s in env.splitlines() if 'Thermal Status' in s]
    for run in ('trace2-drift','trace2-flow','control-drift-slide'):
        td=d/run; marks=[json.loads(p.read_text()) for p in sorted(td.glob('g*-mark.json'))]
        reviews=json.loads((td/'trace-review.json').read_text())
        snaps=[s['ms'] for g in reviews for s in g['snapshots'] if 'copyImageInto' in s['name']]
        con=sqlite3.connect(td/'trace.db');con.row_factory=sqlite3.Row
        pid=int((td/'pid.txt').read_text());upid=con.execute('select upid from process where pid=?',(pid,)).fetchone()[0]
        down=marks[0]['boot_down'];lo=down-1500000000;hi=down
        cpu=Counter()
        for r in con.execute('select s.ts,s.dur,t.name from sched s join thread t using(utid) where t.upid=? and s.ts<? and s.ts+s.dur>?',(upid,hi,lo)):
            cpu[r['name']]+=max(0,min(r['ts']+r['dur'],hi)-max(r['ts'],lo))/1e6
        glide=sum(v for k,v in cpu.items() if k.startswith('glide-anim'))
        con.close()
        fs=list(csv.DictReader((td/'presentation.csv').read_text().splitlines()))
        cadence=[];gaps_each=[]
        for g in marks:
            off=g['boot_down']-g['down']
            lo=g['boot_down']+g['hold']*1000000+50000000;hi=g['up']+off+50000000
            ends=sorted(set(int(f['present_ts']) for f in fs if f['present_ts']!='[NULL]' and f['layer_name'] and 'ThingsActivity' in f['layer_name'] and 'SurfaceView' not in f['layer_name'] and 'Dropped' not in f['present_type'] and lo<=int(f['present_ts'])<=hi))
            gaps=[(b-a)/1e6 for a,b in zip(ends,ends[1:])];cadence.extend(gaps)
            gaps_each.append({'n':len(gaps),'median':pct(gaps,.5),'max':round(max(gaps,default=0),2)})
        item[run]={'readback_ms':snaps,'idle_glide_cpu_ms_per_1_5s':round(glide,1),'moving_present_gap_median':pct(cadence,.5),'moving_present_gaps_each':gaps_each}
    result[device]=item
save(OUT/'final-metrics.json',json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))
