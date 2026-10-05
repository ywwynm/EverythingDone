"""核对候选成片身份、双速度完整性与可解码性；不代替视觉验收。"""
from pathlib import Path
import concurrent.futures,hashlib,json,subprocess,urllib.request

HERE=Path(__file__).resolve().parents[1]


def main():
    out=HERE/'videos';m=json.loads((out/'shared-field-manifest.json').read_text('utf-8'))
    from export_shared_candidate import signature
    identity=signature()
    if m.get('previous'):
        root=Path(m['previous'])
        if not root.is_absolute():root=HERE/root
        h=hashlib.sha256(identity.encode())
        for n in ['conditional-release-weights.json','conditional-transport-weights.json','conditional-material-rules.json','surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json','surface-coherence-weights.json','particle-scatter-weights.json']:
            path=root/n
            if path.is_file():h.update(path.read_bytes())
        identity=h.hexdigest()
    assert m['hash']==identity,'代码或默认参数已变化，需要重新导出当前视频'
    files=m['videos'];assert len(files)==68,(len(files),'导出尚未完成')
    assert len({v['file'] for v in files})==68
    groups={}
    def inspect(v):
        path=out/v['file'];assert path.is_file() and path.stat().st_size==v['bytes'],v['file']
        assert v['code_hash']==m['hash'],v['file']
        assert (out/v['poster']).is_file(),v['poster']
        r=subprocess.run(['C:/ffmpeg/bin/ffprobe.exe','-v','error','-show_entries','stream=codec_name,width,height,r_frame_rate,nb_frames:format=duration','-of','json',str(path)],capture_output=True,text=True,check=True)
        q=json.loads(r.stdout);s=q['streams'][0]
        assert s['codec_name']=='h264' and s['r_frame_rate']=='60/1',v['file']
        assert (s['width'],s['height'])==(v['width'],v['height']),v['file']
        assert int(s['nb_frames'])==v['frames'],v['file']
        assert abs(float(q['format']['duration'])-v['duration'])<.025,v['file']
        return v['file']
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        checked=list(pool.map(inspect,files))
    for v in files:groups.setdefault((v['scene'],v['kind']),set()).add(v['rate'])
    assert all(v=={.5,1.} for v in groups.values())
    decoded=[]
    for name in ['ironman','reference-0926-2','ironman-up-reference']:
        file=f'shared-field-{name}-comparison-0.5x.mp4'
        subprocess.run(['C:/ffmpeg/bin/ffmpeg.exe','-v','error','-i',str(out/file),'-f','null','-'],capture_output=True,check=True);decoded.append(file)
    http={}
    for file in ['shared-field.html','shared-field-report.html','shared-field-manifest.json']:
        with urllib.request.urlopen('http://127.0.0.1:13070/'+file,timeout=10) as r:
            http[file]=r.status;assert r.status==200
    result=dict(hash=m['hash'],videos=len(checked),pairs=len(groups),decoded=decoded,http=http,visual_acceptance=False,note='只确认成片身份与播放文件有效；未达到参考视觉验收')
    target=HERE/'analysis/match-cache/shared-checks/export-checks.json';target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
