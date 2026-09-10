"""参考图像的投影流速诊断。只取显著变化且仍区别于背景的像素。"""
from pathlib import Path
import json,numpy as np,cv2
from PIL import Image,ImageDraw,ImageFont
from review import reference,crop
from renderer import HERE

def main():
    measurements=[]
    for name in ['ironman','thanos','kobe']:
        p=HERE/'assets'/name;m=json.loads((p/'scene.json').read_text(encoding='utf-8'))
        bg=np.array(Image.open(p/'background.png')).astype('float32')
        src=np.array(Image.open(p/'source.png')).astype('float32')
        panels=[]
        for t in [.20,.35,.50,.65]:
            a=reference(m,t);b=reference(m,t+.06)
            ag=cv2.cvtColor(a,cv2.COLOR_RGB2GRAY);bg2=cv2.cvtColor(b,cv2.COLOR_RGB2GRAY)
            flow=cv2.calcOpticalFlowFarneback(ag,bg2,None,.5,4,23,4,7,1.5,cv2.OPTFLOW_FARNEBACK_GAUSSIAN)
            activity=(np.sqrt(np.mean((a.astype('float32')-bg)**2,axis=-1))>15)&(np.sqrt(np.mean((a.astype('float32')-src)**2,axis=-1))>19)
            x,y,x1,y1=m['rect'];roi=np.zeros(activity.shape,bool);roi[max(y-130,80):y1+100,max(x-130,0):min(x1+130,720)]=True
            activity&=roi
            v=flow[activity]/.06
            sel=np.linalg.norm(v,axis=1)>12;v=v[sel]
            row=dict(scene=name,p=t,samples=len(v),median_velocity=np.median(v,axis=0).tolist(),speed_p80=float(np.quantile(np.linalg.norm(v,axis=1),.8)))
            measurements.append(row);print(row,flush=True)
            im=a.copy()
            for py in range(max(80,y-100),min(y1+80,m['frame'][1]),22):
                for px in range(max(x-100,0),min(x1+100,720),22):
                    if not activity[py,px]:continue
                    dx,dy=flow[py,px]*2.6
                    if np.hypot(dx,dy)<2:continue
                    cv2.arrowedLine(im,(px,py),(round(px+dx),round(py+dy)),(100,255,155),1,cv2.LINE_AA,tipLength=.25)
            panels.append(crop(im,m))
        h=panels[0].shape[0];sheet=np.concatenate(panels,axis=1)
        sheet=cv2.resize(sheet,(1440,round(h*.5)),interpolation=cv2.INTER_AREA)
        Image.fromarray(sheet).save(HERE/'analysis'/f'{name}-reference-flow.jpg',quality=95)
    (HERE/'analysis/reference-flow.json').write_text(json.dumps(measurements,ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':main()
