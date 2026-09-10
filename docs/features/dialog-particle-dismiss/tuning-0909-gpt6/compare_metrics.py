"""参考对照诊断：低频颜色分布和尾部残留，不作为主观相似度评分。"""
import argparse,json
import numpy as np,cv2
from PIL import Image
from renderer import Renderer,HERE
from review import reference

TIMES=[.12,.24,.40,.56,.72,.88]

def feature(a,m):
    x,y,x1,y1=m['rect'];margin=round(min(x1-x,y1-y)*.26)
    a=a[max(100,y-margin):y1+margin,max(0,x-margin):min(m['frame'][0],x1+margin)]
    # 低通后比较整体颜色密度，避免把参考压缩纹理和随机粒子逐像素对应。
    a=cv2.resize(a.astype('float32'),(128,128),interpolation=cv2.INTER_AREA)
    return cv2.GaussianBlur(a,(0,0),2.2)

def run(life,tag,baseline='r30'):
    results=[]
    for name in ['ironman','thanos','kobe']:
        r=Renderer(name,settings={'life_gain':life})
        old=np.load(HERE/'archive'/baseline/f'{name}.npy',mmap_mode='r')
        bg=feature(np.array(Image.open(r.directory/'background.png')),r.meta)
        rows=[]
        for t in TIMES:
            # 归档只有 120 Hz 时刻，新模型与参考也取同一时刻，避免半帧偏差混入版本差异。
            frame_index=round(t*120);sample_t=frame_index/120
            truth=feature(reference(r.meta,sample_t),r.meta)
            before=feature(old[frame_index],r.meta)
            after=feature(r.render(sample_t),r.meta)
            rows.append({'t':sample_t,'requested_t':t,'sample_frame':frame_index,'baseline_lowpass_rgb_mae':float(abs(before-truth).mean()),'candidate_lowpass_rgb_mae':float(abs(after-truth).mean()),'reference_residual_energy':float(abs(truth-bg).mean()),'baseline_residual_energy':float(abs(before-bg).mean()),'candidate_residual_energy':float(abs(after-bg).mean())})
        row={'scene':name,'phases':rows,'baseline_mean_mae':float(np.mean([x['baseline_lowpass_rgb_mae'] for x in rows])),'candidate_mean_mae':float(np.mean([x['candidate_lowpass_rgb_mae'] for x in rows]))}
        results.append(row);r.close();print(name,round(row['baseline_mean_mae'],3),round(row['candidate_mean_mae'],3),flush=True)
    data={'tag':tag,'baseline':baseline,'life_gain':life,'meaning':'128×128 低通颜色分布的 RGB MAE（0–255）。仅检查空间分布和残留变化，不等于人类感知相似度；不是独立模型盲测。','scenes':results}
    (HERE/'analysis'/f'{tag}-reference-metrics.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    return data

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--life',type=float,default=.7);p.add_argument('--tag',default='r31');p.add_argument('--baseline',default='r30');a=p.parse_args();run(a.life,a.tag,a.baseline)
