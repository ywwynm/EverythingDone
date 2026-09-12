"""从录像提取输入；搜索具有相近释放布局的确定性反例，不声称恢复未知原种子。"""
from pathlib import Path
import sys,json
import cv2,numpy as np
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import unified_model as m
from probe_device_filaments import OUT

def main():
    result=[]
    for j,frame in [(1,44),(2,48),(3,48)]:
        frames=np.load(OUT/f'device-{j}.npy',mmap_mode='r');src=frames[20];h,w=src.shape[:2]
        white=(src.min(axis=2)>230).astype('uint8')
        _,_,stats,_=cv2.connectedComponentsWithStats(white,8)
        x,y,cw,ch,area=stats[1:][np.argmax(stats[1:,4])];rect=[int(x),int(y),int(x+cw),int(y+ch)]
        fg=src[y:y+ch,x:x+cw];alpha=Image.new('L',(cw,ch),0);ImageDraw.Draw(alpha).rounded_rectangle((0,0,cw-1,ch-1),radius=28*w/592,fill=255)
        bg=frames[-8]
        image=Image.fromarray(fg).convert('RGBA');image.putalpha(alpha)
        dest=OUT/f'input-{j}';dest.mkdir(exist_ok=True);image.save(dest/'foreground.png');Image.fromarray(bg).save(dest/'background.png');Image.fromarray(src).save(dest/'source.png')
        # 只在文字之外比较仍然接近白色的面板覆盖。
        observed=cv2.resize((frames[frame][y:y+ch,x:x+cw].min(axis=2)>225).astype('float32'),(64,64),interpolation=cv2.INTER_AREA)
        valid=cv2.resize((fg.min(axis=2)>240).astype('float32'),(64,64),interpolation=cv2.INTER_AREA)>.98
        best=(1e9,None,None)
        direction=136.
        for seed in [909602]+list(range(768)):
            field,_=m.release_components(64,64,direction,cw,ch,seed)
            field=m.refine_release(field,cw,ch,direction,1.)
            for t in [.24,.28,.32,.36,.40,.44,.48]:
                pred=m.smooth((field-t+.045)/.09)
                error=float(np.mean((pred[valid]-observed[valid])**2))
                if error<best[0]:best=(error,int(seed),float(t))
        meta=dict(name=f'user-device-{j}',title=f'真机录像 {j} 的输入',frame=[w,h],rect=rect,direction=direction,seed=best[1],radius=28,dim_alpha=.60,reference=None,duration=1.)
        (dest/'scene.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),'utf-8')
        result.append(dict(video=j,recorded_frame=frame,rect=rect,seed=best[1],phase=best[2],release_mask_error=best[0],note='相近布局反例；原录像种子未知，不是逐粒子重放'))
        print(result[-1],flush=True)
    (OUT/'reproduction-inputs.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')

if __name__=='__main__':main()
