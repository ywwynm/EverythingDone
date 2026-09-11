"""只读复现已释放微片停滞：保存实际位置与可见性，再为确认视频标注边缘。"""
from pathlib import Path
import argparse,json,sys,math
import numpy as np
import moderngl,cv2
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from unified_model import model_fingerprint
from export_videos import code_hash

OUT=HERE/'analysis/stalled-edges';OUT.mkdir(exist_ok=True,parents=True)
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)

def slow_mask(pos,base,t,index,span,alpha):
    past=max(0,index-8);age=t-base[:,2];old_age=past/60-base[:,2]
    life=base[:,6];fade_start=np.maximum(life-.075,life*.55)
    f=np.clip((age-fade_start)/np.maximum(life-fade_start,1e-6),0,1)
    opacity=1-f*f*(3-2*f)
    movement=np.linalg.norm(pos[index]-pos[past],axis=1)
    # 133 ms 内中心移动小于控件短边的 0.15%，排除尚未释放及已经不可见的材料。
    visible=(age>.06)&(old_age>.06)&(age<life)&(opacity>.15)&(alpha>200)
    return visible&(movement<span*.0015),visible,movement

def scan(names,seeds):
    ctx=moderngl.create_standalone_context(require=430);rows=[]
    for name in names:
        sheet=Image.new('RGB',(len(seeds)*360,3*520),'#101722');draw=ImageDraw.Draw(sheet)
        for col,seed in enumerate(seeds):
            r=Renderer(name,seed=seed,ctx=ctx,quality=1);base=r.base.copy();offset=np.array(r.meta['rect'][:2])
            fg=np.array(Image.open(r.directory/'foreground.png').convert('RGBA'))
            px=np.clip(base[:,0].astype(int),0,r.cw-1);py=np.clip(base[:,1].astype(int),0,r.ch-1);alpha=fg[py,px,3]
            pos=np.empty((61,r.n,2),dtype='float32')
            for i in range(61):
                r.seek(i/60);pos[i]=np.frombuffer(r.state.read(),dtype='float32').reshape(-1,8)[:,:2]+offset
            stats=[];selected=np.zeros(r.n,dtype=bool)
            for i in range(15,58):
                slow,vis,move=slow_mask(pos,base,i/60,i,r.span,alpha);selected|=slow
                x,y,x1,y1=r.meta['rect'];points=pos[i]
                near=(np.minimum(abs(points[:,0]-x),abs(points[:,0]-x1))<5)|(np.minimum(abs(points[:,1]-y),abs(points[:,1]-y1))<5)
                moving=np.linalg.norm(points-base[:,:2]-offset,axis=1)>3
                stats.append(dict(time=i/60,visible=int(vis.sum()),slow=int(slow.sum()),near_original_edges=int((slow&near).sum()),previously_displaced=int((slow&moving).sum())))
            for row,t in enumerate([.45,.65,.85]):
                i=round(t*60);slow,_,_=slow_mask(pos,base,t,i,r.span,alpha)
                frame=r.render(t);points=pos[i,slow].round().astype(int)
                for x,y in points[::max(1,len(points)//6000)]:
                    if 0<=x<r.w and 0<=y<r.h:cv2.circle(frame,(int(x),int(y)),1,(255,90,75),-1)
                x,y,x1,y1=r.meta['rect'];lo=max(0,y-round(r.span*.35));hi=min(r.h,y1+round(r.span*.35))
                im=Image.fromarray(frame[lo:hi]);im.thumbnail((356,470));sheet.paste(im,(col*360+(360-im.width)//2,row*520+42))
                draw.text((col*360+8,row*520+7),f'{name} {seed} · {t:.2f} · {slow.sum()}',font=FONT,fill='white')
            # 仅归档出现过停滞的材料，不保存全部画面或改动正式积分器。
            np.savez_compressed(OUT/f'{name}-seed-{seed}-slow.npz',positions=pos[:,selected],base=base[selected],alpha=alpha[selected],ids=np.flatnonzero(selected))
            row=dict(scene=name,seed=seed,direction=r.direction,rect=r.meta['rect'],frame=r.meta['frame'],span=r.span,particles=r.n,stats=stats)
            rows.append(row);r.close();print(name,seed,'峰值停滞',max(s['slow'] for s in stats),'峰值原边缘',max(s['near_original_edges'] for s in stats),flush=True)
        sheet.save(OUT/f'{name}-scan.jpg',quality=95)
    ctx.release()
    (OUT/'scan.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),code_hash=code_hash(),cases=rows),ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--scenes',nargs='+',default=['ironman','attachment']);p.add_argument('--seeds',nargs='+',type=int,default=list(range(6)));a=p.parse_args()
    scan(a.scenes,a.seeds)
