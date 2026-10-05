"""核对仍完整的源纹理与参考帧的空间变换，排除把素材错位当作粒子释放。"""
from pathlib import Path
import sys,json
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from match_refs import RefFrames


def main():
    out=HERE/'analysis/match-cache/surface-geometry';out.mkdir(parents=True,exist_ok=True)
    data={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in ['ironman','kobe','thanos','reference-0926-1','reference-0926-4','reference-0926-5','reference-0926-6','reference-0926-7']:
        ref=RefFrames(name);m=ref.meta;x,y,x1,y1=m['rect'];src=np.asarray(Image.open(HERE/'assets'/name/'source.png').convert('RGB'))
        mask=np.zeros(src.shape[:2],np.uint8);mask[y+3:y1-3,x+3:x1-3]=255
        detector=cv2.SIFT_create(nfeatures=2200,contrastThreshold=.012)
        kp,ds=detector.detectAndCompute(cv2.cvtColor(src,cv2.COLOR_RGB2GRAY),mask)
        matcher=cv2.BFMatcher();rows=[];sheet=Image.new('RGB',(280*5,340),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,p in enumerate([.0,.10,.25,.40,.55]):
            i,fr=ref.nearest(p);k2,d2=detector.detectAndCompute(cv2.cvtColor(fr,cv2.COLOR_RGB2GRAY),mask)
            ms=matcher.knnMatch(ds,d2,k=2);candidates=[a for a,b in ms if a.distance<.67*b.distance]
            # 重复细颗粒可能把许多查询匹配到同一点，不能据此报告仿射变化。
            unique={}
            for q in sorted(candidates,key=lambda q:q.distance):unique.setdefault(q.trainIdx,q)
            good=list(unique.values())
            row=dict(phase=float(ref.progress[i]),matches=len(good))
            if len(good)>5:
                a=np.asarray([kp[q.queryIdx].pt for q in good],np.float32);b=np.asarray([k2[q.trainIdx].pt for q in good],np.float32)
                mat,inliers=cv2.estimateAffine2D(a,b,method=cv2.RANSAC,ransacReprojThreshold=1.5,maxIters=4000,confidence=.995)
                if mat is not None and inliers.sum()>=8 and .7<np.linalg.det(mat[:,:2])<1.3:
                    row.update(matrix=mat.tolist(),inliers=int(inliers.sum()),median_shift=np.median((b-a)[inliers[:,0]>0],0).tolist())
                    fr=fr.copy();corners=np.array([[x,y],[x1,y],[x1,y1],[x,y1]],np.float32)
                    projected=cv2.transform(corners[None],mat)[0]
                    cv2.polylines(fr,[corners.astype(int)],True,(255,100,60),2)
                    cv2.polylines(fr,[projected.astype(int)],True,(0,240,220),2)
            rows.append(row)
            box=(max(x-30,0),max(y-40,0),min(x1+30,src.shape[1]),min(y1+40,src.shape[0]))
            im=Image.fromarray(fr).crop(box);im.thumbnail((280,304));sheet.paste(im,(j*280,32))
            draw.text((j*280+5,4),f'{name} · {p:.2f}',font=font,fill='white')
        data[name]=rows;sheet.save(out/f'{name}.jpg',quality=94)
        print(name,[(r['phase'],r.get('inliers'),r.get('median_shift')) for r in rows],flush=True)
    (out/'audit.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
