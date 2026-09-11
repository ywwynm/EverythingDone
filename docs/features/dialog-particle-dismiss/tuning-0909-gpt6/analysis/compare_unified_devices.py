"""对照同一快照在 Python、Android 独立生成的材料及实际 GPU 输出。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer,FULLVERT
from unified_model import SHARED,model_fingerprint
p=argparse.ArgumentParser();p.add_argument('--device-dir',default='device-unified');p.add_argument('--report-dir',default='analysis/unified-validation');p.add_argument('--scenes',nargs='+');p.add_argument('--direction',type=float);p.add_argument('--touch-gap',type=float);p.add_argument('--seed',type=int);p.add_argument('--serials',nargs='+',choices=['9018f404','R5CW20BLNKL'],default=['9018f404','R5CW20BLNKL']);args=p.parse_args()

ctx=moderngl.create_standalone_context(require=430)
resolve=(SHARED/'resolve.frag').read_text('utf-8').replace('#version 310 es','#version 430')
program=ctx.program(vertex_shader=FULLVERT,fragment_shader=resolve)
vao=ctx.vertex_array(program,[]);program['screen']=2
out=HERE/args.report_dir;out.mkdir(exist_ok=True,parents=True);rows=[]
for meta in json.loads((HERE/'assets/scenes.json').read_text('utf-8')):
    if args.scenes and meta['name'] not in args.scenes:continue
    name=meta['name'];r=Renderer(name,quality=1,ctx=ctx,direction=args.direction,touch_gap=args.touch_gap,seed=args.seed)
    order=np.argsort(r.base[:,3]);expected=r.base[order]
    expected_pigment=np.frombuffer(r.pigment.read(),dtype='<f4')[order]
    source=np.array(Image.open(HERE/'assets'/name/'foreground.png').convert('RGBA'))
    px=np.clip((expected[:,0]/r.cw*source.shape[1]).astype(int),0,source.shape[1]-1)
    py=np.clip((expected[:,1]/r.ch*source.shape[0]).astype(int),0,source.shape[0]-1)
    alpha=source[py,px,3];opaque=alpha==255;covered=alpha>0
    devices={}
    for serial in args.serials:
        folder=HERE/args.device_dir/serial/'generated'
        got=np.fromfile(folder/f'{name}-materials.f32',dtype='<f4').reshape(-1,12)
        assert got.shape==r.base.shape and np.isfinite(got).all(),(serial,name)
        ids=np.argsort(got[:,3]);got=got[ids]
        pigment=np.fromfile(folder/f'{name}-pigment.f32',dtype='<f4')[ids]
        error=np.abs(got-expected)
        assert np.array_equal(got[:,3],expected[:,3]) and np.array_equal(got[:,8:],expected[:,8:]),(serial,name,'身份或随机数')
        info=json.loads((folder/f'{name}.json').read_text('utf-8'))
        assert abs(info['touchStrength']-r.touch_strength)<2e-6,(name,'触点远近计算不一致',info['touchStrength'],r.touch_strength)
        row={'scene':name,'device':serial,'count':r.n,'material_max_by_field':error.max(axis=0).tolist(),
             'pigment_max':float(np.max(np.abs(pigment-expected_pigment))), 'touch_strength':r.touch_strength,
             'model_ms':info['modelMs'],'prepare_ms':info['prepareMs'],
             'offscreen_frame_p90_ms':float(np.quantile(info['frameMs'],.9)),'frames':[]}
        # 纹理解码中的预乘往返会影响半透明像素；几何、释放和随机值单独核对。
        assert error[:,[0,1,2,7]].max()<1e-4 and error[:,4:6].max()<1e-4,(serial,name,row)
        row['opaque_life_max_seconds']=float(error[opaque,6].max(initial=0))
        row['covered_life_max_seconds']=float(error[covered,6].max(initial=0))
        row['opaque_pigment_max']=float(np.abs(pigment-expected_pigment)[opaque].max(initial=0))
        row['covered_pigment_max']=float(np.abs(pigment-expected_pigment)[covered].max(initial=0))
        assert row['opaque_life_max_seconds']<1e-6 and row['opaque_pigment_max']<1e-6,(serial,name,row)
        # 对半透明解码容许一个 120 Hz 样本以内的寿命量化误差。
        assert row['covered_life_max_seconds']<1/120,(serial,name,row)
        devices[serial]=(folder,ids,got,row);rows.append(row)
    for i in [0,10,20,34,48,60]:
        t=float(np.float32(i/60));r.seek(t)
        r.fbo.use();r.fbo.clear(0,0,0,0)
        ctx.enable(moderngl.BLEND);ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
        r.program['time']=t;r.program['extrapolate']=max(0,t-r.step/240);r.program['diagnostic']=0;r.fg_tex.use(0)
        for p in [0,1]:
            r.fg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST) if p==0 else (moderngl.LINEAR,moderngl.LINEAR)
            r.program['material_pass']=p;r.vao.render(mode=moderngl.TRIANGLES,vertices=6,instances=r.n)
        ctx.disable(moderngl.BLEND);r.out_fbo.use();r.tex.use(2);vao.render(mode=moderngl.TRIANGLES,vertices=3)
        rgba=np.frombuffer(r.out_fbo.read(components=4,alignment=1),dtype=np.uint8).reshape(r.h,r.w,4)[::-1].copy().astype(float)
        bg=np.array(Image.open(HERE/'assets'/name/'background.png').convert('RGB')).astype(float)
        bg*=1-r.meta['dim_alpha']*(1-np.clip((t-.18)/.55,0,1)**2)
        predicted=np.clip(rgba[:,:,:3]+bg*(1-rgba[:,:,3:]/255),0,255).astype('uint8')
        imgs=[Image.fromarray(predicted)]
        for serial,(folder,ids,got,row) in devices.items():
            device=np.array(Image.open(folder/f'{name}-{i}.png').convert('RGBA')).astype(float)
            actual=np.clip(device[:,:,:3]*device[:,:,3:]/255+bg*(1-device[:,:,3:]/255),0,255).astype('uint8')
            frame={'frame':i,'alpha_mae':float(np.abs(device[:,:,3]-rgba[:,:,3]).mean()),
                   'composited_rgb_mae':float(np.abs(actual.astype(float)-predicted).mean())}
            if i==60:assert device[:,:,3].max()==0,(name,serial,'末帧残留')
            if i==34:
                state=np.fromfile(folder/f'{name}-state034.f32',dtype='<f4').reshape(-1,8)[ids]
                reference=np.frombuffer(r.state.read(),dtype='<f4').reshape(-1,8)[order]
                visible=(got[:,2]<t)&(got[:,2]+got[:,6]>t)&(expected[:,2]+expected[:,6]>t)
                delta=np.linalg.norm(state[:,:2]-reference[:,:2],axis=1)
                frame.update(position_p99_px=float(np.quantile(delta[visible],.99)),position_max_px=float(delta[visible].max()))
                frame.update(covered_position_p99_px=float(np.quantile(delta[visible&covered],.99)),covered_position_max_px=float(delta[visible&covered].max()))
                assert frame['position_p99_px']<.25 and frame['position_max_px']<1.,(serial,name,frame)
            row['frames'].append(frame);imgs.append(Image.fromarray(actual))
        if i in [20,34]:
            im=Image.new('RGB',(330*len(imgs),round(r.h/r.w*330)+28),'#18212a');draw=ImageDraw.Draw(im)
            for col,img in enumerate(imgs):
                im.paste(img.resize((330,im.height-28)),(col*330,28));draw.text((col*330+6,6),(['Desktop']+args.serials)[col],fill='white')
            im.save(out/f'device-{name}-{i}.jpg',quality=95)
    print(name,'独立材料与 GPU 对照通过',flush=True);r.close()
vao.release();program.release();ctx.release()
(out/'device-comparison.json').write_text(json.dumps({'model_hash':model_fingerprint(),'cases':rows,
 'scope':'每端独立读取 PNG、尺寸、方向和种子构建材料；几何和随机值单独校验。离屏 draw+finish 计时不能代表系统 UI 帧率。'},ensure_ascii=False,indent=2),encoding='utf-8')
print(len(args.serials),'台设备',len(rows),'组独立链路通过')
