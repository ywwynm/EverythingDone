"""固定输入复现孤立尘缕，并逐项消融上次发布的共同模型改动。"""
from pathlib import Path
import sys,json,shutil,hashlib,argparse,copy
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from export_videos import Reference,load_meta
from probe_frame_difference import configure
OUT=HERE/'analysis/filament-continuity';FROZEN=HERE/'archive/before-filament-continuity'
OUT.mkdir(parents=True,exist_ok=True)

def freeze():
    if (FROZEN/'identity.json').exists():return
    (FROZEN/'shared').mkdir(parents=True,exist_ok=True)
    for name in ['renderer.py','unified_model.py','android_shaders.py','export_videos.py','touch_geometry.py']:
        shutil.copy2(HERE/name,FROZEN/name)
    for path in model.SHARED.iterdir():
        if path.is_file():shutil.copy2(path,FROZEN/'shared'/path.name)
    for name in ['ironman','thanos','kobe','attachment','attachment-image','language','color','ironman-up-reference']:
        for ext in ['.json','.npy']:shutil.copy2(HERE/'cache'/(name+ext),FROZEN/(name+ext))
    (FROZEN/'identity.json').write_text(json.dumps(dict(model_hash=model.model_fingerprint(),description='用户指出孤立尘缕的发布版本'),ensure_ascii=False,indent=2),'utf-8')

def sheet(rows,name,phases):
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);w,h,head=360,640,30
    for start in range(1,len(rows),2):
        group=rows[:1]+rows[start:start+2]
        im=Image.new('RGB',(len(phases)*w,(h+head)*len(group)),(14,20,30));d=ImageDraw.Draw(im)
        for row,(title,frames) in enumerate(group):
            for col,(t,frame) in enumerate(zip(phases,frames)):
                x,y=col*w,row*(h+head);d.text((x+5,y+4),f'{title} · {t:.3f}',font=font,fill='white')
                im.paste(Image.fromarray(frame).resize((w,h)),(x,y+head))
        im.save(OUT/f'{name}-{start}.jpg',quality=96)

def main():
    p=argparse.ArgumentParser();p.add_argument('--ablate',action='store_true');a=p.parse_args();freeze()
    phases=[.55,2/3,.78];ref=Reference(load_meta('ironman'));rows=[('华为参考',[ref.at(t) for t in phases])]
    ctx=moderngl.create_standalone_context(require=430);r=renderer.Renderer('ironman',ctx=ctx)
    images=[r.render(t) for t in phases];r.close();rows.append(('当前发布',images))
    np.save(OUT/'reproduction.npy',np.stack(images))
    old=np.load(HERE/'archive/before-frame-difference/ironman.npy',mmap_mode='r')
    rows.append(('再前一版',[old[round(t*120)] for t in phases]));sheet(rows,'reproduction',phases)
    if a.ablate:
        selected=json.loads((HERE/'analysis/common-shape-calibration.json').read_text('utf-8'))['config'];rows=rows[:2]
        changes=[('恢复旧寿命',dict(life=.70)),('恢复旧流场',dict(flow_grid=[0.]*50,flow_slope=[0.]*50)),
            ('恢复旧释放',dict(release_grid=[0.]*25)),('恢复旧剥离',dict(motion=dict(selected['motion'],peel=2.2))),
            ('恢复旧流场强度',dict(motion=dict(selected['motion'],guide=1.))),('恢复旧采样',dict(cell=1.85))]
        for name,change in changes:
            config=copy.deepcopy(selected);config.update(change);configure(config);r=renderer.Renderer('ironman',ctx=ctx)
            images=[r.render(t) for t in phases];r.close();rows.append((name,images));np.save(OUT/(name+'.npy'),images)
            print(name,'完成',flush=True)
        sheet(rows,'ablation',phases)
    ctx.release();print('复现完成',flush=True)

if __name__=='__main__':main()
