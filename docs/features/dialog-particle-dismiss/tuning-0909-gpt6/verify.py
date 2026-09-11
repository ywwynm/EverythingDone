"""验证确定性、材料交接、任意方向与视频容器，不把数值检查当视觉验收。"""
import argparse,json,subprocess,hashlib,math
from pathlib import Path
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from export_videos import Reference,single_frame,compare_frame,matrix_frame,PRE,POST,code_hash,VERSION

FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)

def model_checks():
    metas=json.loads((HERE/'assets/scenes.json').read_text(encoding='utf-8'))
    ctx=moderngl.create_standalone_context(require=430);result=[]
    contact=Image.new('RGB',(len(metas)*280,4*360),(14,20,30));draw=ImageDraw.Draw(contact)
    for j,m in enumerate(metas):
        r=Renderer(m['name'],ctx=ctx);a=r.render(0);src=np.array(Image.open(r.directory/'source.png').convert('RGB'))
        fg=np.array(Image.open(r.directory/'foreground.png').convert('RGBA'));x,y,x1,y1=m['rect']
        mask=cv2.erode((fg[:,:,3]>.99*255).astype('uint8'),np.ones((5,5),np.uint8))>0
        start_diff=abs(a[y:y1,x:x1].astype('float32')-src[y:y1,x:x1].astype('float32'))[mask]
        end=r.render(1.);bg=np.array(Image.open(r.directory/'background.png').convert('RGB'))
        end_diff=abs(end.astype('float32')-bg.astype('float32'))
        direct=r.render(.6);r.render(.15);rewind=r.render(.6)
        exact=bool(np.array_equal(direct,rewind))
        r.reset()
        for p in np.linspace(0,.6,73):stepped=r.render(p)
        sampling=bool(np.array_equal(direct,stepped))
        r.render(.25)
        state=np.frombuffer(r.state.read(),dtype='float32').reshape(r.n,8)
        future=r.base[:,2]>.25
        locked=float(np.max(abs(state[future,:2]-r.base[future,:2])))
        item={'scene':m['name'],'start_interior_mae':float(start_diff.mean()),'end_background_mae':float(end_diff.mean()),'end_background_max':float(end_diff.max()),'rewind_pixel_exact':exact,'different_sampling_pixel_exact':sampling,'unreleased_position_max_error':locked,'particles':r.n}
        assert item['start_interior_mae']<1.5,item
        assert item['end_background_mae']<.75,item
        assert exact and sampling and locked<1e-5,item
        result.append(item)
        for row,p in enumerate([0,.32,.64,1.]):
            frame=Image.fromarray(r.render(p));frame.thumbnail((276,325))
            contact.paste(frame,(j*280+(280-frame.width)//2,row*360+30))
            draw.text((j*280+8,row*360+3),f'{m["title"].split(" · ")[0]} {p:.2f}',font=FONT,fill='white')
        r.close();print('模型检查通过',m['name'],flush=True)
    contact.save(HERE/'analysis/all-scenes-final.jpg',quality=94)
    directions=[]
    for name in ['ironman','attachment','color']:
        for angle in [0,17,45,90,135,180,225,270,315,359]:
            r=Renderer(name,direction=angle,ctx=ctx);r.render(.72)
            s=np.frombuffer(r.state.read(),dtype='float32').reshape(r.n,8)
            sel=(r.base[:,2]<.38)&(r.base[:,2]+r.base[:,6]>.72)
            displacement=s[sel,:2]-r.base[sel,:2]
            mean=displacement.mean(axis=0);dot=float(mean@np.array(r.wind))
            assert dot>10,(name,angle,mean,dot)
            directions.append({'scene':name,'angle':angle,'mean_displacement':mean.tolist(),'projection_along_requested_direction':dot})
            r.close()
    ctx.release()
    data={'version':VERSION,'code_hash':code_hash(),'model':result,'direction_checks':directions,'scope':'数值检查只验证渲染性质，不代表与华为视觉一致，也不代表 Android 性能。'}
    (HERE/'analysis/model-qa.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    print('30 组方向检查通过',flush=True)

def video_checks():
    path=HERE/'videos/manifest.json';manifest=json.loads(path.read_text(encoding='utf-8'));items=manifest['videos'];results=[]
    assert manifest['version']==VERSION and manifest['code_hash']==code_hash()
    diagnostic_path=path.parent/'diagnostics.json'
    diagnostics=json.loads(diagnostic_path.read_text('utf-8'))['videos'] if diagnostic_path.exists() else []
    family_path=path.parent/'family-videos.json'
    family=json.loads(family_path.read_text('utf-8'))['videos'] if family_path.exists() else []
    assert len(family)==30
    for item in family:
        assert item['code_hash']==code_hash()
        assert hashlib.sha256((path.parent/item['file']).read_bytes()).hexdigest()==item['sha256']
    for item in diagnostics:
        assert item['file']==Path(item['file']).name
        assert hashlib.sha256((path.parent/item['file']).read_bytes()).hexdigest()==item['sha256']
    assert {p.name for p in path.parent.glob('*.mp4')}=={v['file'] for v in items+diagnostics+family},'磁盘视频与清单不一致'
    metas=json.loads((HERE/'assets/scenes.json').read_text(encoding='utf-8'))
    expected=set()
    for meta in metas:
        kinds=['animation']
        kinds+=['compare-phase','compare-file'] if meta.get('reference') else ['compare-source']
        if not meta.get('holdout'):kinds+=['compare-versions','seed-variants']
        if meta['name']=='ironman':kinds.append('compare-control')
        if meta['name'] in ['ironman','attachment','attachment-image','color']:kinds.append('eight-directions')
        if meta['name'] in ['ironman','attachment','color']:kinds.append('touch-distances')
        expected.update((meta['name'],kind,rate) for kind in kinds for rate in [1.,.5])
    actual={(v['scene'],v['kind'],v['rate']) for v in items}
    assert len(items)==len(actual),'视频清单含重复的场景／类型／速度组合'
    assert actual==expected,{'missing':sorted(expected-actual),'unexpected':sorted(actual-expected)}
    for item in items+family:
        assert item['version']==VERSION and item['code_hash']==code_hash(),item['file']
        p=path.parent/item['file']
        data=json.loads(subprocess.check_output(['C:/ffmpeg/bin/ffprobe.exe','-v','error','-select_streams','v:0','-count_frames','-show_entries','stream=width,height,r_frame_rate,avg_frame_rate,nb_read_frames,duration,codec_name,pix_fmt,color_space','-of','json',str(p)],text=True))['streams'][0]
        assert (int(data['width']),int(data['height']))==(item['width'],item['height']),(item,data)
        assert data['avg_frame_rate']=='60/1' and int(data['nb_read_frames'])==item['frames'],(item,data)
        assert data['codec_name']=='h264' and data['pix_fmt']=='yuv420p',data
        cap=cv2.VideoCapture(str(p));decoded=[]
        for idx in [0,round(item['fps']*(PRE+.48)/item['rate']),item['frames']-1]:
            cap.set(cv2.CAP_PROP_POS_FRAMES,min(idx,item['frames']-1));ok,frame=cap.read()
            assert ok,(p,idx)
            decoded.append({'frame':idx,'mean':float(frame.mean()),'sha256':hashlib.sha256(frame.tobytes()).hexdigest()})
        cap.release()
        assert decoded[0]['sha256']!=decoded[1]['sha256'],p
        assert all(f['mean']>4 for f in decoded),p
        half=next((v for v in items if v['scene']==item['scene'] and v['kind']==item['kind'] and v['rate']==.5),None)
        if item['rate']==1 and half:
            assert half['frames']==2*item['frames'],(item,half)
        results.append({'file':item['file'],'probe':data,'decoded_samples':decoded})
        print('视频检查通过',item['file'],flush=True)
    (HERE/'analysis/video-qa.json').write_text(json.dumps({'version':VERSION,'code_hash':code_hash(),'count':len(items),'family_count':len(family),'expected_final':len(expected),'videos':results},ensure_ascii=False,indent=2),encoding='utf-8')
    print(len(items)+len(family),'个本轮视频检查通过',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--videos',action='store_true');a=p.parse_args()
    video_checks() if a.videos else model_checks()
