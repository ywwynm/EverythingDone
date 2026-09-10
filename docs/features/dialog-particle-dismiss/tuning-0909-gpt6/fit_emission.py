"""固定 r29 释放场，拟合三段释放阶段的输运／寿命校准；不读取逐帧位置贴图。"""
import argparse,json,types,time
import numpy as np,cv2
from PIL import Image,ImageDraw,ImageFont
from scipy.optimize import minimize
from renderer import HERE
from review import reference,crop

BASE=types.ModuleType('emission_baseline');BASE.__file__=str(HERE/'renderer.py')
exec(compile((HERE/'archive/r29/renderer.py').read_text(encoding='utf-8'),'archive/r29/renderer.py','exec'),BASE.__dict__)
COMPUTE=BASE.COMPUTE
INJECTION='''
    vec3 emission_weight=exp(-pow((vec3(m.src.z)-vec3(.15,.40,.65))/.18,vec3(2.)));
    emission_weight/=max(dot(emission_weight,vec3(1.)),.00001);
    float emission_envelope=exp(-age/.12)*smoothstep(.003,.025,age);
    target+=(wind*dot(emission_weight,emission_forward)+vec2(-wind.y,wind.x)*dot(emission_weight,emission_lateral))*span*emission_envelope;
'''
BASE.COMPUTE=COMPUTE.replace('uniform vec2 wind;','uniform vec2 wind;\nuniform vec3 emission_forward,emission_lateral;').replace('target+=wind*max(u*.20-dot(target,wind),0.);',INJECTION+'\n    target+=wind*max(u*.20-dot(target,wind),0.);')
TRAIN=[.20,.32,.44,.56,.68,.80]
CHECK=[.16,.28,.40,.52,.64,.76,.88]
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)

def weights(t):
    b=np.exp(-((t[:,None]-np.array([.15,.40,.65]))/.18)**2)
    return b/np.maximum(b.sum(axis=1,keepdims=True),1e-9)

def features(a,m):
    x,y,x1,y1=m['rect'];margin=round(min(x1-x,y1-y)*.34)
    a=a[max(100,y-margin):y1+margin,max(0,x-margin):min(m['frame'][0],x1+margin)]
    a=cv2.resize(a.astype('float32'),(96,96),interpolation=cv2.INTER_AREA)
    return cv2.GaussianBlur(a,(0,0),1.35)

class Fit:
    def __init__(self,name):
        self.name=name;self.r=BASE.Renderer(name);self.base=self.r.base.copy();self.w=weights(self.base[:,2]);self.m=self.r.meta
        self.targets=[features(reference(self.m,t),self.m) for t in TRAIN]
        self.background=features(np.array(Image.open(self.r.directory/'background.png')),self.m)
        self.count=0;self.best=1e9;self.bestx=None;self.history=[]

    def configure(self,q):
        self.r.compute['emission_lateral']=tuple(q[:3]);self.r.compute['emission_forward']=tuple(q[3:6])
        b=self.base.copy();b[:,6]=np.maximum(np.minimum(b[:,6]*(self.w@q[6:9]),.865+.115*b[:,10]-b[:,2]),.11)
        self.r.base=b;self.r.material.write(b.tobytes());self.r.reset()

    def evaluate(self,q):
        self.configure(q);errors=[]
        for t,target in zip(TRAIN,self.targets):
            frame=features(self.r.render(t),self.m)
            # 既比较颜色位置，也惩罚过早失去材料；二者都只是校准损失。
            spatial=float(np.mean(np.sqrt((frame-target)**2+4.)))
            e0=float(abs(target-self.background).mean());e1=float(abs(frame-self.background).mean())
            errors.append(spatial+.24*abs(e0-e1))
        regularizer=.018*np.square(q[:6]).mean()+.10*np.square(q[6:9]-1).mean()
        score=float(np.mean(errors)+regularizer);self.count+=1
        if score<self.best:
            self.best=score;self.bestx=q.copy();self.history.append({'evaluation':self.count,'loss':score,'parameters':q.tolist()})
            print(self.name,self.count,round(score,4),np.round(q,3).tolist(),flush=True)
        return score

    def fit(self,iterations):
        initial=np.array([0.]*6+[1.]*3);baseline=self.evaluate(initial)
        result=minimize(self.evaluate,initial,method='Powell',bounds=[(-2.,2.)]*3+[(-1.2,1.2)]*3+[(.72,1.8)]*3,options={'maxiter':iterations,'maxfev':260,'xtol':.09,'ftol':.002})
        q=self.bestx;self.configure(q)
        profile={'source_baseline':'r29','release_centers':[.15,.40,.65],'release_width':.18,'envelope_decay':.12,'lateral':q[:3].tolist(),'forward':q[3:6].tolist(),'life':q[6:9].tolist(),'parameter_count':9,'baseline_loss':baseline,'candidate_loss':self.best,'evaluations':self.count,'fit_progress':TRAIN,'check_progress':CHECK,'description':'固定来源与 r29 释放场，三段归一化释放时间的平滑混合；有界画面校准参数，不等于参考内部物理真值。'}
        path=HERE/'analysis'/f'{self.name}-emission-candidate.json';path.write_text(json.dumps(profile,ensure_ascii=False,indent=2),encoding='utf-8')
        (HERE/'analysis'/f'{self.name}-emission-fit-log.json').write_text(json.dumps(self.history,ensure_ascii=False,indent=2),encoding='utf-8')
        self.review(q,profile)
        self.r.close()

    def review(self,q,profile):
        old=np.load(HERE/'archive/r29'/f'{self.name}.npy',mmap_mode='r')
        W=440;H=505;out=Image.new('RGB',(W*3,H*len(CHECK)),(18,23,31));d=ImageDraw.Draw(out)
        rows=[];self.configure(q)
        for j,t in enumerate(CHECK):
            ref=reference(self.m,t);base=np.asarray(old[round(t*120)]);new=self.r.render(t)
            rows.append({'t':t,'baseline_rgb_mae':float(abs(features(base,self.m)-features(ref,self.m)).mean()),'candidate_rgb_mae':float(abs(features(new,self.m)-features(ref,self.m)).mean())})
            for i,(im,title) in enumerate([(ref,'华为'),(base,'r29'),(new,'释放阶段校准')]):
                img=Image.fromarray(crop(im,self.m));img.thumbnail((W-8,H-36));out.paste(img,(i*W+(W-img.width)//2,j*H+34));d.text((i*W+12,j*H+7),f'{title} {t:.2f}',font=FONT,fill='white')
        out.save(HERE/'analysis'/f'{self.name}-emission-candidate.jpg',quality=96)
        (HERE/'analysis'/f'{self.name}-emission-check.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
        print('检查时刻',self.name,'r29',np.mean([x['baseline_rgb_mae'] for x in rows]),'候选',np.mean([x['candidate_rgb_mae'] for x in rows]),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['kobe','thanos']);p.add_argument('--iterations',type=int,default=2);a=p.parse_args()
    for name in a.scenes:Fit(name).fit(a.iterations)
