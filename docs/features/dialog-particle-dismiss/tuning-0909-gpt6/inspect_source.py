from prepare import HERE,REF,FONT
import cv2
from PIL import Image,ImageDraw,ImageFont

def main():
    file=next(REF.glob('7-*.mp4'));cap=cv2.VideoCapture(str(file));fps=cap.get(cv2.CAP_PROP_FPS)
    ts=[0,.5,1,1.5,2,2.5,3,3.5,4,4.5,4.9,5.0]
    lookup={round(t*fps):i for i,t in enumerate(ts)}
    out=Image.new('RGB',(6*240,2*360),(18,23,31));draw=ImageDraw.Draw(out);font=ImageFont.truetype(FONT,18)
    i=0
    while True:
        ok,fr=cap.read()
        if not ok:break
        if i in lookup:
            j=lookup[i];im=Image.fromarray(cv2.cvtColor(fr,cv2.COLOR_BGR2RGB))
            im.save(HERE/'analysis'/f'thanos-clean-candidate-{ts[j]:.1f}.png')
            # 只显示卡片所在范围，以核实确认弹窗之前的完整图块。
            im=im.crop((10,110,710,960));im.thumbnail((232,310));out.paste(im,((j%6)*240,(j//6)*360+34));draw.text(((j%6)*240+8,(j//6)*360+7),f'{ts[j]:.2f} s',font=font,fill='white')
        i+=1
        if i>max(lookup):break
    out.save(HERE/'analysis/thanos-earlier-source.jpg',quality=95)
if __name__=='__main__':main()
