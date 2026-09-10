from pathlib import Path
import sys,json
import numpy as np
from PIL import Image,ImageDraw
import moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import Renderer
from renderer import FULLVERT
REPO=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
resolve=(REPO/'shared/particle-dismiss/resolve.frag').read_text('utf-8').replace('#version 310 es','#version 430')
ctx=moderngl.create_standalone_context(require=430)
report=[]
for name in ['ironman','thanos','kobe','language','color','attachment','attachment-image']:
    r=Renderer(name,quality=1,ctx=ctx)
    half=np.fromfile(HERE/'android-fixtures'/f'{name}.flow.f16',dtype='<f2').astype('float32')
    r.flow_tex.write(half.tobytes())
    program=ctx.program(vertex_shader=FULLVERT,fragment_shader=resolve);vao=ctx.vertex_array(program,[]);program['screen']=2
    comparisons=[]
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
        a=rgba[:,:,3:]/255
        bg=np.array(Image.open(HERE/'assets'/name/'background.png').convert('RGB')).astype(float)
        dim=r.meta['dim_alpha']*(1-np.clip((t-.18)/.55,0,1)**2);bg*=1-dim
        expected=np.clip(rgba[:,:,:3]+bg*(1-a),0,255).astype('uint8')
        imgs=[Image.fromarray(expected)]
        for serial in ['9018f404','R5CW20BLNKL']:
            d=HERE/'device-r33'/serial/'fixed'
            if not (d/f'{name}-{i}.png').exists():continue
            device=np.array(Image.open(d/f'{name}-{i}.png').convert('RGBA')).astype(float);da=device[:,:,3:]/255
            actual=np.clip(device[:,:,:3]*da+bg*(1-da),0,255).astype('uint8');imgs.append(Image.fromarray(actual))
            row={'scene':name,'frame':i,'device':serial,'alpha_mae':float(np.abs(device[:,:,3]-rgba[:,:,3]).mean()),'rgb_mae':float(np.abs(actual.astype(float)-expected).mean())}
            if i==34:
                got=np.fromfile(d/f'{name}-state034.f32',dtype='<f4').reshape(-1,8)
                ref=np.frombuffer(r.state.read(),dtype='<f4').reshape(-1,8)
                vis=(r.base[:,2]<t)&(r.base[:,2]+r.base[:,6]>t)
                error=np.linalg.norm(got[:,:2]-ref[:,:2],axis=1)
                row.update(position_p99=float(np.quantile(error[vis],.99)),position_max=float(error[vis].max()))
            report.append(row)
        # 每个场景保留两个主阶段三列同背景比较。
        if i in [20,34]:
            out=Image.new('RGB',(330*len(imgs),round(r.h/r.w*330)+28),'#18212a');draw=ImageDraw.Draw(out)
            for col,img in enumerate(imgs):
                out.paste(img.resize((330,out.height-28)),(col*330,28));draw.text((col*330+6,6),['Desktop transparent','OPD2515','SM-S9180'][col],fill='white')
            out.save(HERE/'analysis'/f'android-{name}-{i}.jpg',quality=95)
    r.close();vao.release();program.release()
(HERE/'analysis/android-gpu-comparison.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps([v for v in report if v['frame']==34],indent=2))
for serial in ['9018f404','R5CW20BLNKL']:
    for path in (HERE/'device-r33'/serial/'fixed').glob('*.json'):
        data=json.loads(path.read_text('utf-8'))
        if 'frameMs' in data:print(serial,path.stem,'prepare',round(data['prepareMs'],1),'p90',round(float(np.quantile(data['frameMs'],.9)),1),'max',round(max(data['frameMs']),1))
