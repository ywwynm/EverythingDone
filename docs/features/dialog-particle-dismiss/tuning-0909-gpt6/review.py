"""逐阶段并排图与短视频，供调优过程使用。"""
import argparse,json,subprocess,time
from pathlib import Path
import cv2,numpy as np
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE

FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)

def reference(meta,p):
    d=HERE/'assets'/meta['name'];frames=np.load(d/'reference.npy',mmap_mode='r');times=np.load(d/'reference-times.npy')
    time=meta['reference']['start']+np.clip(p,0,1)*(meta['reference']['end']-meta['reference']['start'])
    return np.array(frames[np.argmin(abs(times-time))])

def crop(image,meta):
    x,y,x1,y1=meta['rect'];margin=round(min(x1-x,y1-y)*.30)
    y0=max(0,y-margin);y2=min(meta['frame'][1],y1+margin)
    return image[y0:y2]

def main():
    p=argparse.ArgumentParser();p.add_argument('--tag',default='r28');p.add_argument('--scenes',nargs='+',default=['ironman','thanos','kobe']);p.add_argument('--wind',type=float,default=1.05);p.add_argument('--roll',type=float,default=.85);p.add_argument('--curl',type=float,default=1.);p.add_argument('--life',type=float,default=.70);a=p.parse_args()
    for name in a.scenes:
        r=Renderer(name,settings={'wind_gain':a.wind,'roll_gain':a.roll,'curl_gain':a.curl,'life_gain':a.life})
        ts=[.16,.32,.48,.64,.80,.94];W=380;H=450
        out=Image.new('RGB',(W*4,H*3),(18,23,31));d=ImageDraw.Draw(out)
        for j,t in enumerate(ts):
            for k,im in enumerate([reference(r.meta,t),r.render(t)]):
                im=Image.fromarray(crop(im,r.meta));im.thumbnail((W-8,H-38))
                x=(j%2)*W*2+k*W;y=(j//2)*H
                out.paste(im,(x+(W-im.width)//2,y+34));d.text((x+12,y+6),f'{"华为" if k==0 else "本轮"}  {t:.2f}',font=FONT,fill='white')
        out.save(HERE/'analysis'/f'{a.tag}-{name}.jpg',quality=96)
        print(a.tag,name,'完成',flush=True);r.close()
if __name__=='__main__':main()
