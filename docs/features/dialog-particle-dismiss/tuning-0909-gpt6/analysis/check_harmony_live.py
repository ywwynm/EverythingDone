"""十二段在线／离线画面、立即切换与随机交互的集成核对。"""
from pathlib import Path
import argparse,json,math
import sys
import time
from urllib.request import Request,urlopen
import cv2
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'analysis')]
from desktop_inputs import REFS,META,GAP,animation_inputs
from match_refs import RefFrames

OUT=ROOT/'analysis/match-cache/takeover-20261004/harmony-core'
BASE='http://127.0.0.1:8653'
MODEL='harmony-core'


def get(path):
    with urlopen(BASE+path,timeout=10) as response:
        return response.read(),dict(response.headers)


def command(body):
    body={'model':MODEL,'speed':.5,**body}
    request=Request(BASE+'/api/cmd',data=json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urlopen(request,timeout=10) as response:
        ticket=json.load(response)['request_id']
    begin=time.perf_counter()
    while time.perf_counter()-begin<10:
        info=json.loads(get('/api/status')[0])
        if info.get('request_id')==ticket:
            assert not info.get('status','').startswith('错误'),info
            data,headers=get('/api/frame?after=-1')
            assert int(headers['X-Request-Id'])==ticket
            assert headers['X-Scene']==body['scene']
            return info,data,time.perf_counter()-begin
        time.sleep(.02)
    raise AssertionError('预览请求超时：'+str(body))


def main():
    global MODEL,OUT
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model',choices=['harmony-core','harmony-origins'],default=MODEL)
    MODEL=parser.parse_args().model
    OUT=ROOT/'analysis/match-cache/takeover-20261004'/MODEL
    report=json.loads((OUT/'report.json').read_text('utf-8'))
    results=[]
    for row in report['scenes']:
        name=row['name'];p=row['parity_progress']
        still_info,still_jpg,latency=command(dict(kind='scene',scene=name))
        assert still_info['progress']==0 and not still_info['playing']
        info,jpg,_=command(dict(kind='seek',scene=name,progress=p,seed=META[name]['seed']))
        if MODEL=='harmony-origins':
            expected_inputs=animation_inputs(name,'ref',META[name]['seed'])
            assert info['origins']==expected_inputs['origins'],(name,'实时起点不等于参考输入')
            wind=info['core']['wind'];angle=math.degrees(math.atan2(wind[1],wind[0]))%360
            assert abs((angle-expected_inputs['direction']+180)%360-180)<1e-5,(name,'实时风向未生效')
        ours=np.load(OUT/f'{name}-core-mid.npy')
        ref=np.asarray(RefFrames(name).nearest(p)[1]);h,w=ours.shape[:2]
        expected=np.full((h,w*2+GAP,3),16,np.uint8)
        expected[:,:w]=ref;expected[:,w+GAP:]=ours
        ok,encoded=cv2.imencode('.jpg',cv2.cvtColor(expected,cv2.COLOR_RGB2BGR),[cv2.IMWRITE_JPEG_QUALITY,94])
        assert ok
        decoded=cv2.imdecode(np.frombuffer(jpg,np.uint8),cv2.IMREAD_COLOR)
        compare=cv2.imdecode(encoded,cv2.IMREAD_COLOR)
        error=np.abs(decoded.astype(float)-compare).mean()
        assert jpg==encoded.tobytes(),(name,error)
        results.append(dict(scene=name,immediate_switch_ms=round(latency*1000,1),
                            jpeg_identical=True,mean_error=error,active=info['active_count']))
        print(name,'线上与成片完全一致；立即切换',round(latency*1000,1),'ms',flush=True)
    # 固定种子重复可重放；随机种子在同一进度形成不同画面。
    name='ironman'
    command(dict(kind='back',scene=name,seed=129))
    _,a,_=command(dict(kind='seek',scene=name,progress=.5))
    command(dict(kind='back',scene=name,seed=196))
    _,b,_=command(dict(kind='seek',scene=name,progress=.5))
    assert a!=b
    command(dict(kind='back',scene=name,seed=129))
    _,c,_=command(dict(kind='seek',scene=name,progress=.5))
    assert a==c
    taps=[]
    for x,y in [(90,400),(620,400),(360,240),(360,800)]:
        command(dict(kind='tap',scene=name,seed=129,x=x,y=y))
        info,image,_=command(dict(kind='seek',scene=name,progress=.5))
        assert info['finite']
        taps.append(dict(point=[x,y],direction=info['direction'],wind=info['core']['wind']))
    assert len({tuple(t['wind']) for t in taps})==4
    command(dict(kind='scene',scene='ironman'))
    output=dict(scenes=results,seed_replay=True,seed_variation=True,taps=taps)
    (OUT/'live-check.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),'utf-8')
    print('随机种子、重放、四个方向：通过')


if __name__=='__main__':main()
