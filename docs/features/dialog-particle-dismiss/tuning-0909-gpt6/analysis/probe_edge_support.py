"""配准本轮三个确定反例，冻结当前模型并输出完整画面及分层证据。"""
from pathlib import Path
import sys,json,shutil,argparse
import cv2,numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
OUT=HERE/'analysis/edge-flow-support'
BASE=HERE/'archive/before-edge-flow-support'
SOURCES=[
    ('down','e660ac3a-7a91-4f85-9621-4eaf9c71ba2b',1,270,909602,(531,198,962,1123)),
    ('up','22523ee9-7241-41b6-9923-7ff5de9d024f',2,90,909602,(528,198,959,1123)),
    ('right','2490ebd8-05d7-486a-98d0-1aa2df370be1',4,0,42,(526,203,957,1128)),
]

def archive():
    OUT.mkdir(parents=True,exist_ok=True)
    if not (BASE/'identity.json').exists():
        (BASE/'shared').mkdir(parents=True,exist_ok=True)
        for name in ['renderer.py','unified_model.py','android_shaders.py','export_videos.py','touch_geometry.py']:
            shutil.copy2(HERE/name,BASE/name)
        for p in model.SHARED.iterdir():
            if p.is_file():shutil.copy2(p,BASE/'shared'/p.name)
        (BASE/'identity.json').write_text(json.dumps({'model_hash':model.model_fingerprint()},indent=2),'utf-8')

def match():
    result=[]
    for name,uid,case,angle,seed,box in SOURCES:
        shot=Image.open('C:/Users/ywwynm/AppData/Local/Temp/codex-clipboard-'+uid+'.png').convert('RGB')
        target=np.asarray(shot.crop(box).resize((144,309),Image.Resampling.BILINEAR)).astype(float)
        cap=cv2.VideoCapture(str(HERE/f'videos/peel-compression-{case:02d}-attachment-0.5x.mp4'))
        scores=[];i=0
        while True:
            ok,im=cap.read()
            if not ok:break
            panel=cv2.cvtColor(im[80:1110,:480],cv2.COLOR_BGR2RGB)
            panel=np.asarray(Image.fromarray(panel).resize((144,309),Image.Resampling.BILINEAR)).astype(float)
            scores.append(float(np.mean(np.abs(panel[85:225,10:135]-target[85:225,10:135]))));i+=1
        cap.release();frame=int(np.argmin(scores));phase=frame/60*.5-.35
        result.append(dict(name=name,angle=angle,seed=seed,video_frame=frame,phase=phase,mae=scores[frame],screenshot=uid))
        print(result[-1],flush=True)
    (OUT/'matched-inputs.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    return result

def sheet(rows,path,rect=None,width=420):
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    first=rows[0][1][0][1];rect=rect or (0,0,first.shape[1],first.shape[0]);height=round((rect[3]-rect[1])/(rect[2]-rect[0])*width)
    result=Image.new('RGB',(len(rows[0][1])*width,len(rows)*(height+30)),'#101620');d=ImageDraw.Draw(result)
    for j,(name,frames) in enumerate(rows):
        for k,(phase,frame) in enumerate(frames):
            x=k*width;y=j*(height+30)
            result.paste(Image.fromarray(frame).crop(rect).resize((width,height)),(x,y+30))
            d.text((x+4,y+3),f'{name} · {phase:.3f}',font=font,fill='white')
    result.save(path)

def main():
    archive();inputs=match();ctx=moderngl.create_standalone_context(require=430)
    for item in inputs:
        p=OUT/item['name'];p.mkdir(exist_ok=True)
        r=renderer.Renderer('attachment',direction=item['angle'],seed=item['seed'],ctx=ctx)
        phases=np.clip(np.array([-.10,-.05,0,.05,.10])+item['phase'],0,1);rows=[]
        for mode,title in [(0,'完整'),(2,'运动层'),(3,'原表面')]:
            frames=[(float(t),r.render(t,diagnostic=mode)) for t in phases]
            np.save(p/f'layer-{mode}.npy',np.asarray([f for t,f in frames]));rows.append((title,frames))
        sheet([rows[0]],p/'whole.png',width=320)
        sheet(rows,p/'layers.png',rect=(60,410,680,1140),width=420)
        r.seek(item['phase']);np.save(p/'state.npy',np.frombuffer(r.state.read(),'float32').reshape(-1,8));np.save(p/'base.npy',r.base)
        r.close()
    ctx.release()
if __name__=='__main__':main()
