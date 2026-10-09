"""Perfetto 复核：真实帧截止时间、主线程切片、截图读回和调度。"""
import argparse
import csv
import io
import json
import re
import sqlite3
import subprocess
from collections import Counter,defaultdict
from recheck_20261009 import OUT, DEVICES, save, pct

TP=OUT/'trace_processor_shell.exe'
SQL='''SELECT a.id,a.ts,a.dur,a.ts+a.dur AS actual_end,a.surface_frame_token,a.layer_name,
a.present_type,a.on_time_finish,a.jank_type,a.jank_severity_type,a.prediction_type,
e.ts AS expected_ts,e.dur AS expected_dur,e.ts+e.dur AS expected_end
FROM actual_frame_timeline_slice a JOIN process p USING(upid)
LEFT JOIN expected_frame_timeline_slice e ON e.upid=a.upid AND e.surface_frame_token=a.surface_frame_token AND e.layer_name=a.layer_name
WHERE p.name='com.ywwynm.everythingdone' ORDER BY a.ts;'''

def analyze(d):
    tr=d/'trace.perfetto-trace'; db=d/'trace.db'
    if not db.exists():
        p=subprocess.run([str(TP),'export','sqlite','-o',str(db),str(tr)],capture_output=True)
        if p.returncode: raise RuntimeError(p.stderr.decode())
    fp=d/'timeline.csv'
    if not fp.exists():
        p=subprocess.run([str(TP),'query',str(tr),SQL],capture_output=True)
        if p.returncode: raise RuntimeError(p.stderr.decode())
        save(fp,p.stdout)
    frames=[]
    for r in csv.DictReader(fp.read_text().splitlines()):
        for k in ['id','ts','dur','actual_end','surface_frame_token','expected_ts','expected_dur','expected_end']:
            r[k]=int(r[k]) if r[k]!='[NULL]' else None
        frames.append(r)
    c=sqlite3.connect(db); c.row_factory=sqlite3.Row
    pid=int((d/'pid.txt').read_text())
    upid=c.execute('select upid from process where pid=?',(pid,)).fetchone()[0]
    c.execute("CREATE TEMP VIEW app_slices AS SELECT s.*,t.utid,t.tid,t.name AS thread_name FROM slice s JOIN track tr ON s.track_id=tr.id JOIN args a ON a.arg_set_id=tr.dimension_arg_set_id AND a.key='utid' JOIN thread t ON t.utid=a.int_value WHERE tr.type='thread_execution' AND t.upid="+str(upid))
    out=[]
    for mf in sorted(d.glob('g*-mark.json')):
        g=json.loads(mf.read_text()); down=g['boot_down']; offset=down-g['down']; end=g['up']+offset+800000000
        fs=[f for f in frames if f['ts']>=down-20000000 and f['ts']<=end and f['layer_name'] and 'ThingsActivity' in f['layer_name'] and not 'SurfaceView' in f['layer_name']]
        # 应用切片结束代表 GPU／提交完成，并非实际呈现；上屏时间必须再通过
        # display_frame_token 关联 SurfaceFlinger。这里仅统计应用截止时间与归因。
        janky=[f for f in fs if f['jank_type']!='None']
        appmiss=[f for f in fs if 'App Deadline Missed' in f['jank_type']]
        all_s=[dict(r) for r in c.execute('select * from app_slices where ts>=? and ts<=? and dur>=0',(down-20000000,end))]
        snaps=[{'t':round((s['ts']-down)/1e6,2),'ms':round(s['dur']/1e6,3),'name':s['name'],'thread':s['thread_name']} for s in all_s if 'copyImageInto' in s['name'] or 'readback' in s['name'] or 'particle-material' in s['name']]
        hot=[{'t':round((s['ts']-down)/1e6,2),'ms':round(s['dur']/1e6,3),'name':s['name'],'thread':s['thread_name']} for s in all_s if s['dur']>3000000 and (s['tid']==pid or s['thread_name']=='RenderThread') and not s['name'].startswith('Choreographer#doFrame')]
        hot=sorted(hot,key=lambda s:s['ms'],reverse=True)[:24]
        cpu=defaultdict(float)
        for row in c.execute('select s.ts,s.dur,t.name from sched s join thread t using(utid) where t.upid=? and s.ts<? and s.ts+s.dur>?',(upid,down+300000000,down)):
            cpu[row['name']]+=max(0,min(row['ts']+row['dur'],down+300000000)-max(row['ts'],down))/1e6
        r={'g':g['index'],'hold':g['hold'],'frames':len(fs),'jank':dict(Counter(f['jank_type'] for f in fs)),
           'app_miss':len(appmiss),'app_miss_first300':sum(f['ts']<down+300000000 for f in appmiss),
           'worst_overrun':round(max([f['actual_end']-f['expected_end'] for f in fs if f['expected_end']],default=0)/1e6,2),
           'app_miss_details':[{**f,'t':round((f['ts']-down)/1e6,2),'dur_ms':round(f['dur']/1e6,2),'overrun_ms':round((f['actual_end']-f['expected_end'])/1e6,2) if f['expected_end'] else None} for f in appmiss],
           'snapshots':snaps,'hot':hot,'cpu_first300':dict(sorted(cpu.items(),key=lambda kv:-kv[1]))}
        out.append(r)
    save(d/'trace-review.json',json.dumps(out,ensure_ascii=False,indent=2))
    compact=[{k:r[k] for k in ['g','hold','frames','jank','app_miss_first300','worst_overrun','snapshots']} for r in out]
    print(d.parent.name,d.name,json.dumps(compact,ensure_ascii=False),flush=True)

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--run',default='trace2-drift');ap.add_argument('--device',choices=list(DEVICES));a=ap.parse_args()
    for name in ([a.device] if a.device else list(DEVICES)):
        d=OUT/name/a.run
        if (d/'trace.perfetto-trace').exists(): analyze(d)
