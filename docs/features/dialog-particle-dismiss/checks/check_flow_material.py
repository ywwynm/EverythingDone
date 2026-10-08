"""执行正式绘制记录与材质 GLSL，重放真机状态；不重算或拟合运动。"""
import argparse
import json
import subprocess
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[4]
SHARED = ROOT / 'shared/particle-dismiss'
OUT = ROOT / 'tmp/flow-material-20261008'
BASELINE_REVISION = '28e3b1bf6aa96c564380453205a902a04f6b22e9'


def glsl(text, defines=''):
    return text.replace('#version 310 es', '#version 430\n'+defines)


def uniforms(program, values):
    for key, value in values.items():
        if key in program:
            program[key].value = value


def sources(folder):
    return {name: (folder/name).read_text('utf-8')
            for name in ('material.vert', 'material.frag', 'draw-record.comp')}


def program(ctx, src, soft=True):
    flags = '#define DRAW_RECORDS 1\n#define FLAKE_CONSTANTS 1\n#define PARTICLE_COLOR 1\n#define TRUE_CIRCLE 1\n#define ROUND_PARTICLES 1\n#define PARTICLE_AMOUNT 1\n'
    if soft:
        flags += '#define SOFT_PARTICLES 1\n'
    return ctx.program(vertex_shader=glsl(src['material.vert'], flags),
                       fragment_shader=glsl(src['material.frag'], flags)), flags


def draw(ctx, src, records, size=(1080,1080), soft=True):
    p, _ = program(ctx, src, soft)
    b = ctx.buffer(np.array(records, dtype='f4').tobytes())
    vao = ctx.vertex_array(p, [(b, '4f 4f /i', 'record_xform', 'record_place')])
    t = ctx.texture(size, 4, dtype='f4')
    f = ctx.framebuffer([t]); f.use(); f.clear()
    uniforms(p, dict(frame=size, offset=(0,0), material_pass=1, diagnostic=0))
    ctx.enable(moderngl.BLEND); ctx.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA
    vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=len(records))
    result = np.frombuffer(f.read(components=4,dtype='f4'),np.float32).reshape(size[1],size[0],4)[::-1].copy()
    for item in (vao,b,p,f,t): item.release()
    return result


def replay(ctx, src, folder, scene, frame, soft=True):
    materials = np.fromfile(folder/f'render-{scene}-materials.f32','<f4').reshape(-1,12)
    states = np.fromfile(folder/f'render-{scene}-state{frame:03}.f32','<f4')
    pigment = np.fromfile(folder/f'render-{scene}-pigment.f32','<f4')
    ordered = materials[materials[:,3].argsort()]
    cx = float(ordered[1,0]-ordered[0,0]); cy = float(ordered[0,1]*2)
    nx, ny = round(630/cx), round(440/cy)
    source = np.array(Image.open(folder/(scene+'.png')).convert('RGBA'))
    opaque = source[::3,::3,3]>242
    white = source[::3,::3,:3].min(axis=2)>229
    ratio = np.sum(white & opaque)/np.sum(opaque)
    body = np.clip((ratio-.3)/.35,0,1); body = float(body*body*(3-2*body))
    p, flags = program(ctx,src,soft); p.release()
    comp = ctx.compute_shader(glsl(src['draw-record.comp'],flags))
    buffers = [ctx.buffer(materials.tobytes()),ctx.buffer(states.tobytes()),ctx.buffer(pigment.tobytes())]
    for i,b in enumerate(buffers): b.bind_to_storage_buffer(i)
    records = ctx.buffer(reserve=len(materials)*32); records.bind_to_storage_buffer(6)
    tex = ctx.texture((630,440),4,source.tobytes()); tex.filter=(moderngl.LINEAR,moderngl.LINEAR)
    tex.repeat_x=tex.repeat_y=False; tex.use(0)
    report = json.loads((folder/f'render-{scene}.json').read_text('utf-8'))
    uniforms(comp,dict(card=(630,440),cell=(cx,cy),particle_scale=(3.33333333/cx,3.33333333/cy),
        wind=(-.707106781,-.707106781),time=frame/60,extrapolate=0,span=440,
        roll_gain=.85,body_weight=body,panel_weight=report['modelStages']['panelWeight'],
        release_spread=.06+.08*body,nx=nx,grid_count=nx*ny,count=len(materials),foreground=0,light_gain=.85))
    comp.run((len(materials)+255)//256); ctx.memory_barrier()
    data=np.frombuffer(records.read(),np.float32).reshape(-1,8).copy()
    data[:,:2]+=(225,340)
    result=draw(ctx,src,data,soft=soft)
    for item in (*buffers,records,tex,comp): item.release()
    return result,data


def composite(pixels, background):
    bg=np.array(background)/255
    bg=np.where(bg<=.04045,bg/12.92,((bg+.055)/1.055)**2.4)
    linear=pixels[:,:,:3]+bg*(1-pixels[:,:,3:4])
    rgb=np.where(linear<=.0031308,linear*12.92,1.055*np.maximum(linear,0)**(1/2.4)-.055)
    return Image.fromarray(np.uint8(np.clip(rgb,0,1)*255+.5))


def profile(ctx, src):
    # 大点测轮廓，小点的 64 个亚像素位置测覆盖量稳定性。
    img=draw(ctx,src,[[128,128,160,0,1,1,1,1]],(256,256))[:,:,3]
    row=img[127]
    widths={level:int(np.count_nonzero(row>=level)) for level in (.1,.5,.9)}
    mass=[]
    for y in range(8):
        for x in range(8):
            sample=draw(ctx,src,[[16+x/8,16+y/8,2,0,1,1,1,1]],(32,32))
            mass.append(float(sample[:,:,3].sum()))
    return dict(widths={str(k):v for k,v in widths.items()}, energy=float(img.sum()),
                phase_cv=float(np.std(mass)/np.mean(mass)))


def check_culling(ctx, src):
    # 四边外的软边仍可能进入画面；真实可见实例压缩不得误删它。
    data=np.array([[-38,64,64,0,1,1,1,1], [166,64,64,0,1,1,1,1],
                   [64,-38,64,0,1,1,1,1], [64,166,64,0,1,1,1,1],
                   [-90,64,64,0,1,1,1,1]],np.float32)
    text=(ROOT/'app/src/main/assets/particle-playback/compact-circles.comp').read_text('utf-8')
    comp=ctx.compute_shader(glsl(text,'#define SOFT_PARTICLES 1\n'))
    buffers=[ctx.buffer(data.tobytes()),ctx.buffer(reserve=data.nbytes),
             ctx.buffer(reserve=len(data)*4),ctx.buffer(reserve=24)]
    for i,b in enumerate(buffers):b.bind_to_storage_buffer(i)
    uniforms(comp,dict(count=len(data),group_count=1,frame=(128,128),offset=(0,0)))
    for step in range(3):
        comp['pass']=step;comp.run(1);ctx.memory_barrier()
    count=int(np.frombuffer(buffers[3].read(),np.uint32)[1])
    compact=np.frombuffer(buffers[1].read(),np.float32).reshape(-1,8)[:count]
    original=draw(ctx,src,data,(128,128))
    trimmed=draw(ctx,src,compact,(128,128))
    assert count==4 and original[:,:,3].sum()>0
    assert np.array_equal(original,trimmed), '可见实例压缩截断软边'
    for item in (*buffers,comp):item.release()


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--check', action='store_true')
    args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    baseline=OUT/'baseline-shaders'; baseline.mkdir(exist_ok=True)
    # 固定到改动前的版本，避免重新运行时把候选误当基线。
    for name in sources(SHARED):
        src=subprocess.run(['git','show',f'{BASELINE_REVISION}:shared/particle-dismiss/{name}'],
                           cwd=ROOT,capture_output=True,check=True).stdout.decode('utf-8')
        (baseline/name).write_text(src,'utf-8')
    old,new=sources(baseline),sources(SHARED)
    ctx=moderngl.create_standalone_context(require=430)
    results={'before':profile(ctx,old),'after':profile(ctx,new)}
    tiles=[]; labels=[]
    for scene,bg in [('holdout-compact-dialog',(217,221,228)),('holdout-dark',(16,18,24))]:
        for label,src in [('before',old),('edge-only',{**old,'material.vert':new['material.vert'],'material.frag':new['material.frag']}),
                          ('optics-only',{**old,'draw-record.comp':new['draw-record.comp']}),('combined',new)]:
            pixels,records=replay(ctx,src,OUT/'oppo-before',scene,34)
            image=composite(pixels,bg)
            image.save(OUT/f'{scene}-{label}.png')
            tiles.append(image.crop((210,320,890,790)).resize((680,470))); labels.append(label)
    sheet=Image.new('RGB',(2720,1000),'#20242b'); d=ImageDraw.Draw(sheet)
    for i,tile in enumerate(tiles):
        x,y=(i%4)*680,(i//4)*500
        d.text((x+12,y+6),labels[i],fill='white'); sheet.paste(tile,(x,y+30))
    sheet.save(OUT/'material-candidates.png')
    (OUT/'coverage.json').write_text(json.dumps(results,indent=2),'utf-8')
    print(json.dumps(results))
    if args.check:
        check_culling(ctx,new)
        a,b=results['before'],results['after']
        assert abs(b['widths']['0.5']/a['widths']['0.5']-1)<.04, '可见中心大小改变'
        assert b['widths']['0.1']-b['widths']['0.9']>2*(a['widths']['0.1']-a['widths']['0.9']), '软边不足'
        assert .92<b['energy']/a['energy']<1.08, '轮廓总覆盖变化过大'
        assert b['phase_cv']<a['phase_cv']*.7, '小粒子相位变化未改善'


if __name__=='__main__': main()
