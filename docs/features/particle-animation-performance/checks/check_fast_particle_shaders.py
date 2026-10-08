"""直接执行共享 GLSL，核对缓存、近似轨迹与逐粒子颜色；不连接设备。"""
import argparse
import json
import sys
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT/'docs/features/harmony-particle-android-port/checks'))
from check_drift_clarity import Renderer, source as drift_source
from check_round_particles import source, uniforms, attributes


def defines(text, macros):
    return text.replace('#version 430', '#version 430\n'+macros)


def drift(ctx, out):
    inputs = ROOT/'tmp/harmony-particle-android-port/parity/clarity-20261008-after'
    rows=[]
    for folder in sorted(inputs.iterdir()):
        if not (folder/'device/result.json').exists():
            continue
        baseline=Renderer(ctx, folder)
        for curve in (False, True):
            macros='#define DRIFT_CACHE 1\n'+('#define DRIFT_CURVE 1\n' if curve else '')
            variant=Renderer(ctx, folder, compute=defines(drift_source('drift-update.comp'),macros))
            init=ctx.compute_shader(defines(drift_source('drift-update.comp'),macros+'#define DRIFT_INITIALIZE 1\n'))
            cache=ctx.buffer(reserve=variant.result['total']*96)
            cache.bind_to_storage_buffer(2)
            variant.cells.bind_to_storage_buffer(1); variant.image.use(0)
            uniforms(init, variant.values)
            init['u_lattice'].write(variant.lattice.tobytes())
            init.run((variant.result['total']+255)//256); ctx.memory_barrier()
            static=np.frombuffer(cache.read(),np.float32).reshape(-1,24)
            valid=static[:,3]>0
            errors=[]; color_max=0.; radius_max=0.; mask_errors=0; worst=[]
            total=variant.result['totalSeconds']
            # 覆盖每一个 120 Hz 模拟帧，包括节点之间、出生／死亡和任意时刻回退。
            for frame in range(int(np.ceil(total*120))+1):
                t=frame/120
                a=baseline.render(t,data_only=True)
                cache.bind_to_storage_buffer(2)
                b=variant.render(t,data_only=True)
                mask_errors+=int(np.count_nonzero((a[:,2]>0)!=(b[:,2]>0)))
                alive=a[:,2]>0
                if not alive.any():continue
                delta=np.linalg.norm(a[alive,:2]-b[alive,:2],axis=1)*2
                errors.append(delta)
                color_max=max(color_max,float(np.max(np.abs(a[:,4:]-b[:,4:]))))
                radius_max=max(radius_max,float(np.max(np.abs(a[:,2]-b[:,2])))*2)
                worst.append((float(delta.max()),frame))
            e=np.concatenate(errors)
            # 倒序／重复请求必须完全一致，避免引入依赖前一帧的状态。
            for t in (.25,.75,.4,.75):
                cache.bind_to_storage_buffer(2)
                state=variant.render(t,data_only=True)
                if t==.75:
                    if 'repeat' in locals():assert np.array_equal(repeat,state)
                    repeat=state
            del repeat
            row=dict(case=folder.name,curve=curve,count=len(static),valid=int(valid.sum()),
                     fitted=float(np.mean(static[valid,15]>.5)),maskErrors=mask_errors,
                     positionPx=np.quantile(e,[.5,.95,.99,.999,1]).tolist(),
                     colorMax=color_max,radiusMaxPx=radius_max,worst=sorted(worst,reverse=True)[:3])
            rows.append(row)
            print(json.dumps(row,ensure_ascii=False),flush=True)
            cache.release();init.release();variant.close()
        baseline.close()
    (out/'drift.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),'utf-8')
    assert all(r['maskErrors']==0 and r['colorMax']<1e-5 and r['radiusMaxPx']<.002 for r in rows)
    # 静态缓存的重结合会影响极少数噪声分区边界；以 0.1 px 验收，不要求浮点逐位相等。
    assert all(r['positionPx'][-1] < (.1 if not r['curve'] else .75) for r in rows)


def flow(ctx, out):
    base='#define PARTICLE_AMOUNT 1\n#define ROUND_PARTICLES 1\n#define FLAKE_CONSTANTS 1\n'
    programs={}; computes={}
    modes={'baseline':('',64),'color':('#define PARTICLE_COLOR 1\n',48),
           'circle':('#define PARTICLE_COLOR 1\n#define TRUE_CIRCLE 1\n',32)}
    for name,(extra,_) in modes.items():
        computes[name]=ctx.compute_shader(source('draw-record.comp',base+extra))
        programs[name]=ctx.program(vertex_shader=source('material.vert',base+extra+'#define DRAW_RECORDS 1\n'),
                                   fragment_shader=source('material.frag',base+extra+'#define DRAW_RECORDS 1\n'))
    rng=np.random.default_rng(129)
    count=144
    m=np.zeros((count,12),np.float32)
    xy=np.stack(np.meshgrid(np.arange(12)*40+20,np.arange(12)*40+20),axis=-1).reshape(-1,2)
    m[:,:2]=xy;m[:,2]=.1;m[:,3]=np.arange(count);m[:,4]=1;m[:,6]=.8;m[:,8:]=rng.random((count,4))
    s=np.zeros((count,8),np.float32);s[:,:2]=xy;s[:,2]=rng.random(count)*20
    mb=ctx.buffer(m.tobytes());sb=ctx.buffer(s.tobytes());pb=ctx.buffer(np.zeros(count,np.float32).tobytes())
    rb=ctx.buffer(reserve=count*64)
    image=rng.random((32,32,4)).astype(np.float32);image[:,:,3]=np.clip(image[:,:,3]+.3,0,1)
    # 既有黑白本体，也有彩色和半透明中心采样。
    image[:8,:,:3]=.96;image[8:12,:,:3]=.02
    texture=ctx.texture((32,32),4,image.tobytes(),dtype='f4');texture.filter=(moderngl.LINEAR,moderngl.LINEAR)
    texture.repeat_x=texture.repeat_y=False;texture.use(0)
    target=ctx.texture((960,960),4,dtype='f4');fbo=ctx.framebuffer([target])
    rows=[]
    for size in (.5,1.,3.,6.):
        for age in (.002,.012,.035,.10,.25,.55,.79,.81):
            values=dict(frame=(480.,480.),card=(480.,480.),offset=(0.,0.),cell=(40.,40.),wind=(1.,0.),
                particle_scale=(size*.18,size*.18),time=.1+age,extrapolate=0.,span=480.,roll_gain=1.,body_weight=1.,
                panel_weight=1.,release_spread=.1,nx=12,grid_count=count,count=count,material_pass=1,diagnostic=0,
                foreground=0,light_gain=1.)
            outputs={}
            for name,(_,stride) in modes.items():
                mb.bind_to_storage_buffer(0);sb.bind_to_storage_buffer(1);pb.bind_to_storage_buffer(2);rb.bind_to_storage_buffer(6)
                texture.use(0);uniforms(computes[name],values);computes[name].run(1);ctx.memory_barrier()
                program=programs[name]
                specs=[]
                for i,attr in enumerate(('record_xform','record_place','record_shade','record_mask')[:stride//16]):
                    fmt=(f'{i*16}x ' if i else '')+'4f'+(f' {stride-(i+1)*16}x' if stride>(i+1)*16 else '')+' /i'
                    specs.append((rb,fmt,attr))
                specs.append((mb,'4f 32x /i','material_src'))
                vao=ctx.vertex_array(program,attributes(program,specs));uniforms(program,values)
                fbo.use();ctx.viewport=(0,0,960,960);fbo.clear();ctx.disable(moderngl.BLEND)
                vao.render(moderngl.TRIANGLE_STRIP,vertices=4,instances=count)
                outputs[name]=np.frombuffer(target.read(),np.float32).reshape(960,960,4).copy();vao.release()
            a=outputs['baseline'];b=outputs['color'];c=outputs['circle']
            delta=np.abs(a-b)
            row=dict(size=size,age=age,colorMax=float(delta.max()),colorMean=float(delta.mean()),
                     circleAlphaAreaRatio=float(c[:,:,3].sum()/max(a[:,:,3].sum(),1e-8)),
                     circleMean=float(np.abs(c-a).mean()))
            rows.append(row)
    (out/'flow.json').write_text(json.dumps(rows,indent=2),'utf-8')
    print(json.dumps(dict(flowCases=len(rows),colorMax=max(r['colorMax'] for r in rows),
                         circleMaxMean=max(r['circleMean'] for r in rows))),flush=True)
    assert max(r['colorMax'] for r in rows)<.003
    for resource in [*programs.values(),*computes.values(),mb,sb,pb,rb,texture,target,fbo]:resource.release()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--mode',choices=['drift','flow','all'],default='all')
    p.add_argument('--out',type=Path,default=ROOT/'tmp/particle-fast-20261008/desktop')
    args=p.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430)
    if args.mode in ('flow','all'):flow(ctx,args.out)
    if args.mode in ('drift','all'):drift(ctx,args.out)
