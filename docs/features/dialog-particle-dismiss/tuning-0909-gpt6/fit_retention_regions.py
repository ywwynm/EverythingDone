"""在时序和输运候选上，验证少量源区域寿命系数能否改善后段残留。"""
import argparse,json
import numpy as np
from scipy.optimize import minimize
from PIL import Image,ImageDraw,ImageFont
from experiment_boundary import module,configure,HERE,crop
from review import reference
from compare_metrics import feature
from fit_flow import texture
from fit_motion_regions import REGIONS,TRAIN,CHECK

def run(name):
    r=configure(module('regional').Renderer(name),'regional');m=r.meta;b=r.base.copy()
    flow=json.loads((r.directory/'flow-profile.json').read_text(encoding='utf-8'));candidate=json.loads((HERE/'analysis'/f'{name}-motion-regions-candidate.json').read_text(encoding='utf-8'))
    yy,xx=np.mgrid[:36,:36];xx=-.45+(xx+.5)/36*1.9;yy=-.45+(yy+.5)/36*1.9;delta=np.zeros((36,36,2))
    for x,y,sx,sy,vx,vy in candidate['motion_regions']:delta+=np.exp(-((xx-x)/sx)**2-((yy-y)/sy)**2)[...,None]*[vx,vy]
    ts=np.linspace(0,1,32);a=np.clip((ts-.08)/.17,0,1);a=a*a*(3-2*a);z=np.clip((ts-.72)/.22,0,1);z=z*z*(3-2*z)
    r.flow_tex.write((texture(flow)+(a*(1-z))[:,None,None,None]*delta[None]).astype('float32').tobytes())
    qx=b[:,0]/r.cw;qy=b[:,1]/r.ch;B=np.stack([np.exp(-((qx-x)/sx)**2-((qy-y)/sy)**2) for x,y,sx,sy in REGIONS],axis=-1)
    truth={float(t):feature(reference(m,t),m) for t in np.r_[TRAIN,CHECK]}
    def apply(coef):
        r.base=b.copy();r.base[:,6]=np.maximum(np.minimum(b[:,6]*(1+B@coef),.865+.115*b[:,10]-b[:,2]),.11);r.material.write(r.base.tobytes());r.reset()
    def evaluate(coef,times):
        apply(coef);return [float(abs(feature(r.render(t),m)-truth[float(t)]).mean()) for t in times]
    calls=0
    def loss(coef):
        nonlocal calls
        val=float(np.mean(evaluate(coef,TRAIN))+.06*np.mean((coef/.5)**2));calls+=1
        if calls%20==0:print(name,'retention',calls,round(val,4),flush=True)
        return val
    before=evaluate(np.zeros(4),CHECK)
    result=minimize(loss,np.zeros(4),method='Powell',bounds=[(-.40,.90)]*4,options={'maxiter':3,'maxfev':160,'xtol':.01,'ftol':.002})
    after=evaluate(result.x,CHECK)
    report={'scene':name,'retention_regions':[[*p.tolist(),float(v)] for p,v in zip(REGIONS,result.x)],'train_times':TRAIN.tolist(),'check_times':CHECK.tolist(),'baseline_check_mae':before,'candidate_check_mae':after,'evaluations':calls,'meaning':'源坐标中的连续寿命修正；无额外力，原结束上限不变。'}
    (HERE/'analysis'/f'{name}-retention-candidate.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    W=380;H=370;font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);out=Image.new('RGB',(W*3,H*7),(18,23,31));d=ImageDraw.Draw(out);frames=[]
    for coef in [np.zeros(4),result.x]:apply(coef);frames.append([r.render(t) for t in CHECK])
    for j,t in enumerate(CHECK):
        for i,(label,img) in enumerate(zip(['华为','输运候选','寿命候选'],[reference(m,t),frames[0][j],frames[1][j]])):
            im=Image.fromarray(crop(img,m));im.thumbnail((W-8,H-34));out.paste(im,(i*W+(W-im.width)//2,j*H+32));d.text((i*W+8,j*H+5),f'{label} {t:.2f}',font=font,fill='white')
    out.save(HERE/'analysis'/f'{name}-retention-candidate.jpg',quality=96)
    print(name,'RETENTION CHECK',round(float(np.mean(before)),4),'->',round(float(np.mean(after)),4),result.x.tolist(),flush=True);r.close()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('scene');run(p.parse_args().scene)
