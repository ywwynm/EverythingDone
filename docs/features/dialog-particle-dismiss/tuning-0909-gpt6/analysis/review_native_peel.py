"""按录屏真实时间戳定位运动区；系统录屏可能是变帧率，不能用帧号除以平均帧率。"""
from pathlib import Path
import cv2,numpy as np,xml.etree.ElementTree as ET,re,json
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
p=HERE/'analysis/recorded-filament-trace/real-ui/R5CW20BLNKL'
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);report=[]
for label in ['near','far','back']:
    cap=cv2.VideoCapture(str(p/f'touch-{label}.mp4'));vw=int(cap.get(3));vh=int(cap.get(4))
    nodes=ET.fromstring((p/f'touch-{label}-before.xml').read_bytes()).iter('node')
    node=next(n for n in nodes if n.get('resource-id')=='android:id/content')
    x,y,x1,y1=map(int,re.findall(r'\d+',node.get('bounds')))
    sx=vw/1440;sy=vh/3088;x,x1=round(x*sx),round(x1*sx);y,y1=round(y*sy),round(y1*sy)
    counts=[];pts=[]
    while True:
        ok,im=cap.read()
        if not ok:break
        roi=im[y:y1:6,x:x1:6];counts.append(float(np.mean(roi.min(2)>230)))
        pts.append(cap.get(cv2.CAP_PROP_POS_MSEC)/1000)
    baseline=np.median(counts[:min(10,len(counts))]);hits=np.flatnonzero(np.array(counts)<baseline*.90)
    assert len(hits)>0,(label,'没有定位到面板释放')
    begin=pts[int(hits[0])];times=[max(0,begin-.15),begin+.05,begin+.2,begin+.35,begin+.5,begin+.7]
    # OpenCV 对部分系统变帧率文件的按时间跳转不准确；第二遍顺序解码到真实帧号。
    indices=[int(np.argmin(abs(np.asarray(pts)-t))) for t in times]
    cap.release();cap=cv2.VideoCapture(str(p/f'touch-{label}.mp4'));selected={}
    for index in range(max(indices)+1):
        ok,im=cap.read();assert ok
        if index in indices:selected[index]=cv2.cvtColor(im,cv2.COLOR_BGR2RGB)
    w=320;h=round(vh/vw*w);sheet=Image.new('RGB',(w*6,h+30),'#0e141e');d=ImageDraw.Draw(sheet)
    for j,t in enumerate(times):
        image=Image.fromarray(selected[indices[j]]);sheet.paste(image.resize((w,h)),(j*w,30))
        d.text((j*w+4,3),f'{label} · {t:.2f}s',font=font,fill='white')
        if j==3:image.save(p/f'native-{label}-middle.png')
    sheet.save(p/f'native-{label}-contact.jpg',quality=97);cap.release()
    report.append(dict(case=label,first_release_observed=begin,timestamps=times,frames=len(pts)))
(p/'native-review.json').write_text(json.dumps(report,indent=2),'utf-8');print(json.dumps(report),flush=True)
