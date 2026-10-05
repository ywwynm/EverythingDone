"""参考帧之间的观测光流，仅作方向／卷动诊断，不生成运行期查找表。"""
from pathlib import Path
import sys,json
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from export_videos import Reference,load_meta
OUT=HERE/'analysis/new-references-0926'


def main():
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17);reports=[]
    for name in [f'reference-0926-{i}' for i in range(1,8)]+['spiderman-original','ironman']:
        m=load_meta(name);ref=Reference(m);x,y,x1,y1=m['rect'];span=min(x1-x,y1-y)
        margin=round(span*.2);rect=(max(0,x-margin),max(0,y-margin),min(m['frame'][0],x1+margin),min(m['frame'][1],y1+margin))
        left,top,right,bottom=rect;size=(384,round(384*(bottom-top)/(right-left)));scale=384/(right-left)
        sheet=Image.new('RGB',(384*3,size[1]+32),'#0e141e');d=ImageDraw.Draw(sheet)
        fields=[]
        for col,p in enumerate([.20,.40,.60]):
            a=cv2.resize(ref.at(p)[top:bottom,left:right],size);b=cv2.resize(ref.at(p+.04)[top:bottom,left:right],size)
            flow=cv2.calcOpticalFlowFarneback(cv2.cvtColor(a,cv2.COLOR_RGB2GRAY),cv2.cvtColor(b,cv2.COLOR_RGB2GRAY),None,.5,4,21,5,7,1.5,0)
            # 像素级光流在遮挡与稀疏区不可靠；反向一致性用于标记，不用于扭曲输出。
            reverse=cv2.calcOpticalFlowFarneback(cv2.cvtColor(b,cv2.COLOR_RGB2GRAY),cv2.cvtColor(a,cv2.COLOR_RGB2GRAY),None,.5,4,21,5,7,1.5,0)
            gy,gx=np.mgrid[:size[1],:size[0]].astype('float32')
            error=np.linalg.norm(flow+cv2.remap(reverse,gx+flow[:,:,0],gy+flow[:,:,1],cv2.INTER_LINEAR),axis=2)
            fields.append(dict(phase=p,flow=flow,fb_error=error))
            for yy in range(15,size[1]-15,16):
                for xx in range(15,size[0]-15,16):
                    dx,dy=flow[yy,xx]
                    if error[yy,xx]>1.2 or dx*dx+dy*dy<.10:continue
                    cv2.arrowedLine(a,(xx,yy),(round(xx+dx*2.5),round(yy+dy*2.5)),(20,255,220),1,cv2.LINE_AA,tipLength=.3)
            sheet.paste(Image.fromarray(a),(col*384,32));d.text((col*384+5,5),f'参考运动 {p:.2f} → {p+.04:.2f}',fill='white',font=font)
        sheet.save(OUT/f'{name}-flow-observed.jpg',quality=95)
        np.savez_compressed(OUT/f'{name}-flow-observed.npz',phases=[q['phase'] for q in fields],flow=[q['flow'] for q in fields],error=[q['fb_error'] for q in fields],rect=rect,scale=scale)
        samples=[]
        for f in fields[1:]:
            norm=np.linalg.norm(f['flow'],axis=2)
            valid=(f['fb_error']<.6)&(norm>1)&(norm<12)
            valid&=(gx>(x-left)*scale)&(gx<(x1-left)*scale)&(gy>(y-top)*scale)&(gy<(y1-top)*scale)
            samples.extend(f['flow'][valid].tolist())
        vector=np.median(samples,axis=0);angle=float(np.degrees(np.arctan2(-vector[1],vector[0]))%360)
        reports.append(dict(scene=name,direction=angle,median_vector=vector.tolist(),samples=len(samples)))
        print(name,round(angle,2),flush=True)
    (OUT/'observed-directions.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
