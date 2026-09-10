"""校准连续投影流场的四个空间区域，留出时刻验证；不改写运行素材。"""
import argparse,json
import numpy as np
from scipy.optimize import minimize
from PIL import Image,ImageDraw,ImageFont
from experiment_boundary import module,configure,HERE,crop
from review import reference
from compare_metrics import feature
from fit_flow import texture

REGIONS=np.array([[.14,.53,.26,.40],[.78,.78,.33,.32],[.74,.37,.35,.26],[.42,.10,.40,.25]])
TRAIN=np.array([.16,.28,.40,.52,.64,.76])
CHECK=np.array([.22,.34,.46,.58,.70,.82,.94])

def run(name):
    r=configure(module('regional').Renderer(name),'regional');m=r.meta
    profile=json.loads((r.directory/'flow-profile.json').read_text(encoding='utf-8'));original=texture(profile)
    yy,xx=np.mgrid[:36,:36];xx=-.45+(xx+.5)/36*1.9;yy=-.45+(yy+.5)/36*1.9
    B=np.stack([np.exp(-((xx-x)/sx)**2-((yy-y)/sy)**2) for x,y,sx,sy in REGIONS],axis=-1)
    ts=np.linspace(0,1,32);attack=np.clip((ts-.08)/.17,0,1);attack=attack*attack*(3-2*attack)
    end=np.clip((ts-.72)/.22,0,1);end=end*end*(3-2*end);env=attack*(1-end)
    truth={float(t):feature(reference(m,t),m) for t in np.r_[TRAIN,CHECK]}
    def apply(coeff):
        delta=B@coeff.reshape(4,2)
        r.flow_tex.write((original+env[:,None,None,None]*delta[None]).astype('float32').tobytes());r.reset()
    def evaluate(coeff,times):
        apply(coeff);return [float(abs(feature(r.render(t),m)-truth[float(t)]).mean()) for t in times]
    calls=0
    def loss(coeff):
        nonlocal calls
        value=float(np.mean(evaluate(coeff,TRAIN))+.12*np.mean((coeff/.5)**2));calls+=1
        if calls%20==0:print(name,'motion',calls,round(value,4),flush=True)
        return value
    before=evaluate(np.zeros(8),CHECK)
    result=minimize(loss,np.zeros(8),method='Powell',bounds=[(-.60,.60)]*8,options={'maxiter':3,'maxfev':300,'xtol':.012,'ftol':.002})
    after=evaluate(result.x,CHECK)
    regions=[[*p.tolist(),*v.tolist()] for p,v in zip(REGIONS,result.x.reshape(4,2))]
    report={'scene':name,'motion_regions':regions,'envelope':[.08,.25,.72,.94],'train_times':TRAIN.tolist(),'check_times':CHECK.tolist(),'baseline_check_mae':before,'candidate_check_mae':after,'evaluations':calls,'objective':float(result.fun),'meaning':'四个低频连续空间速度修正，每区两个投影分量；不是逐帧流图，留出时刻不参与拟合。'}
    (HERE/'analysis'/f'{name}-motion-regions-candidate.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);W=380;H=370;out=Image.new('RGB',(W*3,H*7),(18,23,31));d=ImageDraw.Draw(out)
    frames=[]
    for coef in [np.zeros(8),result.x]:
        apply(coef);frames.append([r.render(t) for t in CHECK])
    for j,t in enumerate(CHECK):
        for i,(label,a) in enumerate(zip(['华为','时序候选','输运候选'],[reference(m,t),frames[0][j],frames[1][j]])):
            im=Image.fromarray(crop(a,m));im.thumbnail((W-8,H-34));out.paste(im,(i*W+(W-im.width)//2,j*H+32));d.text((i*W+8,j*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(HERE/'analysis'/f'{name}-motion-regions-candidate.jpg',quality=96)
    print(name,'MOTION CHECK',round(float(np.mean(before)),4),'->',round(float(np.mean(after)),4),flush=True);r.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('scene');a=p.parse_args();run(a.scene)
