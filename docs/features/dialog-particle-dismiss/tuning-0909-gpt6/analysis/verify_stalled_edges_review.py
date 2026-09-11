"""核验诊断产物完整性、解码、标注范围和正式模型未变；不判定视觉根因。"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import hashlib,json,subprocess,sys,urllib.request
import numpy as np

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from unified_model import model_fingerprint
from export_videos import code_hash

REPORT=HERE/'analysis/stalled-edges-expanded';VIDEOS=HERE/'videos'
data=json.loads((REPORT/'review-manifest.json').read_text('utf-8'))
previous_path=REPORT/'verified.json'
previous=json.loads(previous_path.read_text('utf-8')) if previous_path.exists() else {}
checked={v['id']:v for v in previous.get('videos',[])}
assert len(data['cases'])==226
assert model_fingerprint()==data['model_hash'] and code_hash()==data['code_hash']
problems=[]
for c in data['cases']:
    x,y,x1,y1=c['crop']
    assert c['annotations'],c['id']
    for a in c['annotations']:
        p=np.array(a['points'])
        assert np.isfinite(p).all() and len(p)>1
        if not ((p[:,0]>=x-1)&(p[:,0]<=x1+1)&(p[:,1]>=y-1)&(p[:,1]<=y1+1)).all():
            problems.append([c['id'],a['id'],'候选超出当前显示裁切'])

def check(c):
    v=c['video'];path=VIDEOS/v['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==v['sha256'],c['id']
    old=checked.get(c['id'])
    if old and old['sha256']==v['sha256']:
        return old
    cmd=['C:/ffmpeg/bin/ffprobe.exe','-v','error','-threads','2','-select_streams','v:0','-count_frames',
         '-show_entries','stream=width,height,avg_frame_rate,nb_read_frames,duration','-of','json',str(path)]
    r=subprocess.run(cmd,capture_output=True,text=True,check=True)
    assert not r.stderr.strip(),(c['id'],r.stderr)
    s=json.loads(r.stdout)['streams'][0]
    assert (s['width'],s['height'])==(1600,1000) and s['avg_frame_rate']=='60/1' and int(s['nb_read_frames'])==151,(c['id'],s)
    return dict(id=c['id'],frames=int(s['nb_read_frames']),seconds=float(s['duration']),sha256=v['sha256'])

with ThreadPoolExecutor(max_workers=6) as pool:videos=list(pool.map(check,data['cases']))
print('所有视频解码通过',len(videos),'标注范围问题',problems,flush=True)
normal=json.loads((VIDEOS/'manifest.json').read_text('utf-8'))
diag=json.loads((VIDEOS/'diagnostics.json').read_text('utf-8'))
actual={p.name for p in VIDEOS.glob('*.mp4')}
assert actual=={v['file'] for v in normal['videos']}|{v['file'] for v in diag['videos']}
assert len(normal['videos'])==110 and len(diag['videos'])==227
url='http://127.0.0.1:13070/'
local=urllib.request.build_opener(urllib.request.ProxyHandler({}))
with local.open(url+'stalled-edges.html',timeout=10) as r:
    assert r.status==200 and '226'.encode() in r.read()
req=urllib.request.Request(url+data['cases'][-1]['video']['file'],headers={'Range':'bytes=0-4095'})
with local.open(req,timeout=10) as r:assert r.status==206 and len(r.read())==4096
result=dict(model_hash=data['model_hash'],code_hash=data['code_hash'],model_unchanged=True,
  new_videos=len(videos),original_videos=110,diagnostic_videos=227,frames=sum(v['frames'] for v in videos),
  video_range_status=206,html_status=200,annotation_crop_problems=problems,videos=videos)
(REPORT/'verified.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
assert not problems
print('226 个视频、完整组合、原 110 视频清单、HTTP 范围读取和正式模型身份核验通过。',flush=True)
