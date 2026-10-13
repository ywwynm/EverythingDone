import json,pathlib,zipfile,sys
import metrics
root=pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path(__file__).parent
out=[]
for folder in sorted(root.glob('*/*')):
    p=folder/'harness/report.json'
    if not p.exists():continue
    if folder.name.startswith('exit-'):
        data=json.loads(p.read_text(encoding='utf8'));ev=data['events']
        first=lambda n:next(e['elapsedMs'] for e in ev if e['event']==n)
        delay=first('diagnostic_parent_first_scroll')-first('doing_probe_finish_requested')
        assert delay<1000,(folder,delay)
        for name in ['doing_scene_capture_resources_released','doing_scene_window_detached']:first(name)
        row={'device':folder.parent.name,'run':folder.name,'scrollAfterExitRequestMs':round(delay,1)}
    elif folder.name.startswith('glscene'):
        row=metrics.audit(p)
        assert not row['bad'],row
        assert row['backends']==['SurfaceView'],row
        if '-doing-' in folder.name:
            assert row['completions']==(1 if 'fastcomplete200' in folder.name else 0),row
            assert row['final']['cardAlpha']==1 and row['final']['containerAlpha']==.84,row
    else:continue
    z=folder/'harness/diagnostics.zip'
    with zipfile.ZipFile(z) as archive:assert archive.testzip() is None
    row['zipCRC']=True;out.append(row)
    print(row['device'],row['run'],'通过')
for folder in sorted(root.glob('*/ripple*-unified-drift-*')):
    assert json.loads((folder/'result.json').read_text())['error'] is None
    data=json.loads((folder/'report.json').read_text());ev=data['events']
    bad=[e for e in ev if any(w in e['event'] for w in ['failed','timeout','aborted'])]
    samples=json.loads((folder/'samples.json').read_text())
    final=samples[-1]
    assert final['particleHolds']==0 and not final['hostFrozen'],final
    out.append({'device':folder.parent.name,'run':folder.name,'scene':'实际添加附件→录音',
                'error':None,'bad':bad,'passed':not bad,'lastSample':final,'recordingStarted':False})
    print(folder.parent.name,'实际添加附件→录音', '通过' if not bad else '未通过：'+','.join(e['event'] for e in bad))
(root/'regression-audit.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf8')
