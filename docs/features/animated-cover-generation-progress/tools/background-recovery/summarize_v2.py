import hashlib
import json
import pathlib
import subprocess

root=pathlib.Path(__file__).resolve().parent
rows=[]
for name in ('oppo','samsung','meizu'):
    for mode in ('death-green','final-switch','cancel-finalizing'):
        directory=root/('wm2105-'+mode+'-'+name)
        summary=json.loads((directory/'summary.json').read_text())
        events=json.loads((directory/'events.json').read_text(encoding='utf-8'))
        actions=json.loads((directory/'actions.json').read_text(encoding='utf-8'))
        video=directory/'output.gif'
        probe=json.loads(subprocess.check_output([r'C:\ffmpeg\bin\ffprobe.exe','-v','error','-count_frames','-show_entries',
            'stream=codec_name,width,height,nb_read_frames,duration:format=duration','-of','json',str(video)]))
        stream=probe['streams'][0]
        assert (stream['width'],stream['height'],int(stream['nb_read_frames']))==(720,405,75),probe
        assert float(probe['format']['duration'])==3,probe
        hashes=subprocess.check_output([r'C:\ffmpeg\bin\ffmpeg.exe','-v','error','-i',str(video),'-f','framemd5','-']).decode()
        unique_frames=len({line.rsplit(',',1)[-1].strip() for line in hashes.splitlines() if line and not line.startswith('#')})
        assert unique_frames>1, 'GIF has no changing frames'
        assert not (directory/'crashes.txt').read_text().strip(),directory
        assert not summary['has99'],directory
        ui=next(e['data'] for e in reversed(events) if e['event']=='ui')
        assert ui.get('phase')=='READY' and ui.get('gif') and ui.get('playing'),ui
        if mode!='cancel-finalizing':assert not summary['failures'],summary
        bg_start=next(a['time'] for a in actions if a['action']=='background-start')
        bg_end=next(a['time'] for a in actions if a['action']=='background-end')
        rows.append(dict(device=name,scenario=mode,backgroundSeconds=round(bg_end-bg_start,3),
            summary=summary,finalUi=ui,media=probe,uniqueDecodedFrames=unique_frames,bytes=video.stat().st_size,
            sha256=hashlib.sha256(video.read_bytes()).hexdigest(),newAppCrashes=0))
    result=json.loads((root/('wm2105-regression-'+name)/'cover-generation-checks/result.json').read_text(encoding='utf-8'))
    assert result['ok'],result
    print(name,'regression',len(result['checks']))
(root/'wm2105-verification.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
for row in rows:
    print(row['device'],row['scenario'],'background',row['backgroundSeconds'],
        'ready',row['summary']['timesMs']['ready']/1000,'pids',row['summary']['pids'],'GIF 720x405 / 75 frames / 3s / playing')
