"""从源纹理变化代理量拟合 18 个传播参数；原片遮罩仅供诊断。"""
from pathlib import Path
import json
import numpy as np
import cv2
from PIL import Image,ImageDraw,ImageFont
from scipy.optimize import least_squares
from scipy.ndimage import gaussian_filter
from fields import propagation,initial_params,field_grid,residual_basis

HERE=Path(__file__).resolve().parent

def measure(name):
    p=HERE/'assets'/name
    m=json.loads((p/'scene.json').read_text(encoding='utf-8'))
    frames=np.load(p/'reference.npy',mmap_mode='r');times=np.load(p/'reference-times.npy')
    fg=np.array(Image.open(p/'foreground.png'))[:,:,:3]
    x,y,x1,y1=m['rect']
    bg=np.array(Image.open(p/'background.png'))[y:y1,x:x1]
    N=80
    src=cv2.resize(fg,(N,N),interpolation=cv2.INTER_AREA).astype('float32')
    back=cv2.resize(bg,(N,N),interpolation=cv2.INTER_AREA).astype('float32')
    contrast=np.sqrt(np.mean((src-back)**2,axis=-1))
    changes=[]
    for frame in frames:
        a=cv2.resize(frame[y:y1,x:x1],(N,N),interpolation=cv2.INTER_AREA).astype('float32')
        changes.append(np.sqrt(np.mean((a-src)**2,axis=-1))/(contrast+30))
    change=np.array(changes)
    if name=='thanos':
        # 确认框残影覆盖下部材料，不能当成粒子化证据。
        for i,t in enumerate(times):
            if t<5.57:change[i,round((662-y)/(y1-y)*N):,:]=0
    norm=(times-m['reference']['start'])/(m['reference']['end']-m['reference']['start'])
    target=np.ones((N,N),dtype='float32')
    # 连续两帧明显偏离，避免压缩噪声和单帧运动重合。
    for i in range(len(norm)-2,-1,-1):
        target[(change[i]>.17)&(change[i+1]>.17)]=max(norm[i],0)
    valid=(contrast>27)&(target<.88)&(target>.018)
    valid[:2]=False;valid[-2:]=False;valid[:,:2]=False;valid[:,-2:]=False
    yy,xx=np.mgrid[:N,:N].astype(float);xx=(xx+.5)/N;yy=(yy+.5)/N
    v=valid.ravel();tx=xx.ravel()[v];ty=yy.ravel()[v];target_fit=target.ravel()[v]
    seed=initial_params(m)
    lo=[];hi=[]
    for ox,oy,d,sx,sy,a in seed.reshape(-1,6):
        lo += [ox-.14,oy-.14,max(-.015,d-.18),.25,.25,-1.1]
        hi += [ox+.14,oy+.14,d+.20,1.25,1.25,1.1]
    result=least_squares(lambda q:propagation(tx,ty,q)-target_fit,seed,bounds=(lo,hi),loss='soft_l1',f_scale=.038,max_nfev=160)
    fitted=propagation(xx,yy,result.x)
    # 少量余弦系数表达非径向分界线；高频二阶惩罚避免跟随人物纹理。
    order=6
    B=residual_basis(tx,ty,order)
    weight=np.array([1+(i*i+j*j)**2 for i in range(order) for j in range(order)])
    residual=target_fit-propagation(tx,ty,result.x)
    coefficients=np.linalg.solve(B.T@B+np.diag(weight)*len(tx)*.00009,B.T@residual)
    coefficients=np.clip(coefficients,-.16,.16)
    fitted+=residual_basis(xx,yy,order)@coefficients
    err=np.abs(fitted[valid]-target[valid])
    profile={'params':result.x.tolist(),'residual_coefficients':coefficients.tolist(),'residual_order':order,'proxy':'连续两帧源纹理归一化偏差 > 0.17；不是精确释放真值','normalized_median_abs_error':float(np.median(err)),'normalized_p90_abs_error':float(np.quantile(err,.9)),'samples':int(valid.sum()),'parameter_count':18+order**2,'direction':m['direction']}
    if name=='ironman':
        profile['origin_time_corrections']=[[.03,.03,.23,.23,.055]]
        profile['visual_calibration']='左上起始区在纹理变化后延迟脱离，中心与范围为归一化源坐标；0.055 为视觉标定值。'
    (p/'profile.json').write_text(json.dumps(profile,ensure_ascii=False,indent=2),encoding='utf-8')
    np.save(HERE/'analysis'/f'{name}-measured-arrival.npy',target)
    np.save(HERE/'analysis'/f'{name}-fitted-arrival.npy',fitted)
    print(name,'样本',valid.sum(),'时间代理拟合中位误差',round(np.median(err),4),'P90',round(np.quantile(err,.9),4),flush=True)
    return m,target,fitted,valid

def main():
    rows=[measure(n) for n in ['ironman','thanos','kobe']]
    out=Image.new('RGB',(1020,3*340),(18,23,31));d=ImageDraw.Draw(out);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
    for j,(m,target,fitted,valid) in enumerate(rows):
        for i,(a,title) in enumerate([(target,'原片纹理变化代理'),(fitted,'54 参数传播拟合'),(abs(target-fitted),'绝对误差（0–0.3）')]):
            f=a/.8 if i<2 else a/.3
            im=cv2.applyColorMap(np.uint8(np.clip(f,0,1)*255),cv2.COLORMAP_TURBO)[:,:,::-1]
            if i==2:im[~valid]=[32,37,43]
            out.paste(Image.fromarray(im).resize((300,280)),(i*340+20,j*340+44))
            d.text((i*340+12,j*340+12),m['title']+' · '+title,font=font,fill='white')
    out.save(HERE/'analysis/arrival-fit.jpg',quality=95)
if __name__=='__main__':main()
