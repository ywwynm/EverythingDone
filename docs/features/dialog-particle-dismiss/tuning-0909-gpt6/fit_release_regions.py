"""用少量连续局部时间修正校准参考形态；留出时刻复核，不写运行素材。"""
import argparse,json
import numpy as np
from scipy.ndimage import gaussian_filter
from scipy.optimize import minimize
from PIL import Image,ImageDraw,ImageFont
from experiment_boundary import module,configure,HERE,crop
from fields import field_grid
from review import reference
from compare_metrics import feature

TRAIN=np.array([.16,.28,.40,.52,.64,.76])
CHECK=np.array([.22,.34,.46,.58,.70,.82,.94])
REGIONS=np.array([[.12,.38,.25,.45],[.85,.74,.28,.30],[.54,.18,.30,.25],[.45,.90,.32,.25]])

def run(name):
    r=configure(module('material12').Renderer(name),'material12');base=r.base.copy();ids=base[:,3].astype(int)
    m=r.meta;profile=json.loads((r.directory/'profile.json').read_text(encoding='utf-8'))
    T0=field_grid(m,r.nx,r.ny,r.direction,profile)
    yy,xx=np.mgrid[:r.ny,:r.nx];xx=(xx+.5)/r.nx;yy=(yy+.5)/r.ny
    B=np.stack([np.exp(-((xx-x)/sx)**2-((yy-y)/sy)**2) for x,y,sx,sy in REGIONS],axis=-1)
    truth={float(t):feature(reference(m,t),m) for t in np.r_[TRAIN,CHECK]}
    fg=np.array(Image.open(r.directory/'foreground.png').convert('RGB'),dtype='float32')/255
    rgb=fg[np.clip(base[:,1].astype(int),0,r.ch-1),np.clip(base[:,0].astype(int),0,r.cw-1)]
    content=np.clip((rgb.max(axis=1)-rgb.min(axis=1)-.30)/.42,0,1);content=content*content*(3-2*content)
    def apply(coeff):
        T=T0+B@coeff;gy,gx=np.gradient(gaussian_filter(T,3),r.cell[1],r.cell[0]);l=np.maximum(np.hypot(gx,gy),1e-6)
        b=base.copy();b[:,2]=np.clip(T.ravel()[ids]+.060*(b[:,11]-.5),.001,.86)
        b[:,4]=gaussian_filter(gx/l,7).ravel()[ids];b[:,5]=gaussian_filter(gy/l,7).ravel()[ids]
        life=(.10+.22*(-np.log(np.maximum(b[:,8],.004)))**.85+.20*b[:,2])*.70
        life=np.maximum(np.minimum(life,.865+.115*b[:,10]-b[:,2]),.11)
        b[:,6]=np.maximum(np.minimum(life*(1.+.30*content),.865+.115*b[:,10]-b[:,2]),.11)
        r.base=b;r.material.write(b.tobytes());r.reset()
    calls=0;history=[]
    def evaluate(coeff,times):
        apply(coeff);return [float(abs(feature(r.render(t),m)-truth[float(t)]).mean()) for t in times]
    def loss(coeff):
        nonlocal calls
        scores=evaluate(coeff,TRAIN);value=float(np.mean(scores)+.06*np.mean((coeff/.05)**2));calls+=1
        if calls%12==0:print(name,calls,round(value,4),flush=True)
        history.append(value);return value
    before=evaluate(np.zeros(4),CHECK)
    result=minimize(loss,np.zeros(4),method='Powell',bounds=[(-.065,.065)]*4,options={'maxiter':3,'maxfev':220,'xtol':.003,'ftol':.002})
    after=evaluate(result.x,CHECK)
    params=[[*reg.tolist(),float(v)] for reg,v in zip(REGIONS,result.x)]
    report={'scene':name,'origin_time_corrections':params,'train_times':TRAIN.tolist(),'check_times':CHECK.tolist(),'baseline_check_mae':before,'candidate_check_mae':after,'evaluations':calls,'objective':float(result.fun),'history':history,'meaning':'低通颜色分布诊断；四个连续局部时间修正，留出时刻不参与拟合。'}
    (HERE/'analysis'/f'{name}-release-regions-candidate.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);W=380;H=370;out=Image.new('RGB',(W*3,H*7),(18,23,31));d=ImageDraw.Draw(out)
    frames=[]
    for coef in [np.zeros(4),result.x]:
        apply(coef);frames.append([r.render(t) for t in CHECK])
    for j,t in enumerate(CHECK):
        for i,(label,a) in enumerate(zip(['华为','边界候选','局部时序候选'],[reference(m,t),frames[0][j],frames[1][j]])):
            im=Image.fromarray(crop(a,m));im.thumbnail((W-8,H-34));out.paste(im,(i*W+(W-im.width)//2,j*H+32));d.text((i*W+8,j*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(HERE/'analysis'/f'{name}-release-regions-candidate.jpg',quality=96)
    print(name,'CHECK',round(float(np.mean(before)),4),'->',round(float(np.mean(after)),4),'coeff',result.x.tolist(),flush=True);r.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('scene');a=p.parse_args();run(a.scene)
