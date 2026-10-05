"""把独立候选与原片、当前默认放在同一整机视频中，不修改默认权重和画廊。"""
from pathlib import Path
import argparse, hashlib, json, sys
import moderngl, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from export_v2_suite import Truth
from export_shared_candidate import compose, signature
import export_videos as video


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--root',type=Path,required=True)
    ap.add_argument('--label',required=True)
    ap.add_argument('--scenes',nargs='+',default=['reference-0926-2','ironman-up-reference','ironman'])
    ap.add_argument('--carried',type=Path,help='静态速度积分研究权重，不进入标准画廊')
    a=ap.parse_args()
    assert a.label.replace('-','').isalnum()
    configs=['conditional-release-weights.json','conditional-material-rules.json','conditional-transport-weights.json',
             'surface-transfer-rules.json','particle-coverage-rules.json','particle-cohort-weights.json',
             'surface-coherence-weights.json','particle-scatter-weights.json']
    identity=hashlib.sha256(signature().encode()+Path(__file__).read_bytes()
                            +b''.join((a.root/name).read_bytes() for name in configs)
                            +(a.carried.read_bytes()+(HERE/'analysis/carried_velocity.py').read_bytes() if a.carried else b'')).hexdigest()
    video.code_hash=lambda:identity
    video.VERSION='连续运动研究候选'
    out=HERE/'videos';items=[]
    ctx=moderngl.create_standalone_context(require=430)
    for name in a.scenes:
        meta=json.loads((HERE/'assets'/name/'scene.json').read_text('utf-8'))
        truth=Truth(name);frames=[]
        for variant,root in enumerate([HERE,a.root]):
            cls,kw=configuration(root)
            if variant==1 and a.carried:
                from carried_velocity import CarriedVelocityRenderer,load
                cls=CarriedVelocityRenderer;kw['carried_weights']=load(a.carried)
            r=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
            frames.append([r.render(i/120) for i in range(121)])
            r.close()
        for rate in [1.,.5]:
            path=out/f'{a.label}-{name}-{rate:g}x.mp4'
            def frame(t):
                p=float(np.clip(t-video.PRE,0,1));i=round(p*120)
                return compose([truth.at(p),frames[0][i],frames[1][i]],
                               ['华为原片','当前默认','连续运动候选'],p,rate,meta['title'])
            row=video.encode(path,video.PRE+1+video.POST,rate,frame)
            row.update(scene=name,title=meta['title'],rate=rate)
            items.append(row)
        print('整机对照已导出',name,flush=True)
    ctx.release()
    manifest=dict(hash=identity,candidate=str(a.root),visual_acceptance=False,videos=items,
                  note='独立研究候选；按独立画面顺序对齐，两段背景不完整的参考不计算全图像素误差。')
    (out/f'{a.label}-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    (out/f'{a.label}.html').write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>连续运动 · 原片对照</title><style>body{margin:20px;background:#0e141e;color:#eaf0fa;font:18px system-ui}button,select{font:inherit;background:#223246;color:inherit;border:1px solid #52667e;border-radius:6px;padding:9px;margin:5px}video{display:block;width:100%;max-height:85vh;margin-top:12px}p{color:#b6c6d8}</style>
<select id="scene"></select><button onclick="speed(1)">1 倍速</button><button onclick="speed(.5)">0.5 倍速</button>
<button onclick="v.paused?v.play():v.pause()">播放／暂停</button><button onclick="step(-1)">上一帧</button><button onclick="step(1)">下一帧</button>
<p>左：华为原片；中：当前默认；右：独立研究候选。完整画面对照，尚未通过视觉验收。</p><video id="v" controls loop playsinline></video>
<script>let data,rate=.5,hash;const v=document.querySelector('#v'),s=document.querySelector('#scene');
function load(){let q=data.find(q=>q.scene===s.value&&q.rate===rate);v.src=q.file+'?hash='+hash;location.hash=q.file;}
function speed(x){let p=v.currentTime*rate;rate=x;load();v.addEventListener('loadedmetadata',()=>v.currentTime=p/rate,{once:true});}
function step(x){v.pause();v.currentTime+=x/60;}
fetch('MANIFEST').then(r=>r.json()).then(m=>{data=m.videos;hash=m.hash;for(let q of data.filter(q=>q.rate===.5)){let o=new Option(q.title,q.scene);s.add(o);}let chosen=data.find(q=>q.file===location.hash.slice(1));if(chosen){s.value=chosen.scene;rate=chosen.rate;}s.onchange=load;load();});</script></html>'''.replace('MANIFEST',f'{a.label}-manifest.json'),'utf-8')
    print(out/f'{a.label}.html',flush=True)


if __name__=='__main__':main()
