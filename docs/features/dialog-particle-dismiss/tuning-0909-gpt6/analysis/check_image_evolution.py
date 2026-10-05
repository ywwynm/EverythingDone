"""在真实绘制中直接比较原片的画面变化；不从亮度变化推断粒子对应或速度。"""
from pathlib import Path
import argparse, json, pickle, sys
import cv2, moderngl, numpy as np

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from prepare_uncertain_grain_cache import observed_mask, high_frequency
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--candidate',type=Path,required=True)
    ap.add_argument('--baseline',type=Path,default=HERE)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--training-cache',type=Path)
    ap.add_argument('--maximum-gap',type=float,default=.16,help='剔除训练帧后允许的最大相邻进度差，报告实际间距，不插值原片')
    ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES))
    a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    train={}
    if a.training_cache:
        data=pickle.loads(a.training_cache.read_bytes())
        for name in a.scenes:train[name]=np.array([d['progress'] for d in data if d['name']==name])
        del data
    ctx=moderngl.create_standalone_context(require=430);report={}
    for name in a.scenes:
        c=Comparison(name);mask=c.scene.roi.astype(np.float32)
        known=bool(mask.any());grain_mask=mask if known else observed_mask(c.scene.meta,c.ref.frames.shape[1:])
        ids=[i for i in c.index if .08<=c.ref.progress[i]<=.90
             and (not len(train.get(name,[])) or np.min(abs(train[name]-c.ref.progress[i]))>1e-6)]
        pairs=[]
        for left in ids:
            choices=[right for right in ids if .025<=c.ref.progress[right]-c.ref.progress[left]<=a.maximum_gap]
            if choices:pairs.append((left,min(choices,key=lambda j:abs(c.ref.progress[j]-c.ref.progress[left]-.04))))
        ids=sorted(set(i for pair in pairs for i in pair));assert pairs,name
        def observe(frame):
            value=np.asarray(frame,np.float32)/255.
            result={'grain':high_frequency(frame)}
            if known:
                for sigma in [4,12]:result[f'rgb_{sigma}']=cv2.GaussianBlur(value,(0,0),sigma)
            return result
        target={i:observe(c.ref.frames[i]) for i in ids};row={'known_background':known,'independent_pairs':len(pairs),
            'phase_gap_quantiles':np.quantile([c.ref.progress[r]-c.ref.progress[l] for l,r in pairs],[0,.5,1]).tolist()}
        def score(current):
            errors={};absolute={}
            for key in target[ids[0]]:
                weight=grain_mask if key=='grain' else mask
                norm=max(float(weight.sum())*3,1.)
                errors[key]=float(np.mean([((abs((current[r][key]-current[l][key])-(target[r][key]-target[l][key]))*weight[...,None]).sum()/norm)*255 for l,r in pairs]))
                absolute[key]=float(np.mean([(abs(current[i][key]-target[i][key])*weight[...,None]).sum()/norm*255 for i in ids]))
            return dict(change_error=errors,frame_error=absolute)
        for stage,root in [('before',a.baseline),('after',a.candidate)]:
            cls,kw=configuration(root)
            renderer=cls(name,ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
            current={i:observe(renderer.render(float(c.ref.progress[i]))) for i in ids}
            row[stage]=score(current);del current
            renderer.close()
        row['phases']=[[float(c.ref.progress[l]),float(c.ref.progress[r])] for l,r in pairs]
        report[name]=row
        print(name,{key:row[key] for key in ['independent_pairs','before','after']},flush=True)
        (a.out/'report.json').write_text(json.dumps(dict(scope='真实连续画面变化误差，不是粒子速度；剔除训练观测，两段未知背景仅高频',scenes=report),ensure_ascii=False,indent=2),'utf-8')
        del target
    ctx.release()


if __name__=='__main__':main()
