"""验证实际 GLSL 的收紧方框、压力提前停止和连续历史插值；不连接设备。"""
import json
import sys
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT/'docs/features/harmony-particle-android-port/checks'))
from check_drift_clarity import source, uniforms


def drift(ctx):
    p = ctx.program(vertex_shader=source('drift-particle.vert'), fragment_shader=source('drift-particle.frag'))
    rng = np.random.default_rng(1008120)
    # 粒子隔开，覆盖 0.001 到 40 工作像素半宽与任意亚像素中心，含屏幕边缘。
    n = 24*24
    values = np.zeros((n, 8), np.float32)
    x, y = np.meshgrid(np.arange(24)*96, np.arange(24)*96)
    values[:, :2] = np.column_stack((x.ravel(), y.ravel())) + rng.uniform(-.5, .5, (n, 2))
    values[:, 2] = np.geomspace(.001, 40, n)
    values[:, 4:] = rng.uniform(.1, 1., (n, 4))
    data = ctx.buffer(values.tobytes())
    vao = ctx.vertex_array(p, [(data, '4f 4f /i', 'a_geometry', 'a_color')])
    tex = ctx.texture((2304, 2304), 4, dtype='f4'); target = ctx.framebuffer([tex])
    ctx.enable(moderngl.BLEND)
    rows = []
    for additive in (False, True):
        ctx.blend_func = moderngl.ONE, moderngl.ONE if additive else moderngl.ONE_MINUS_SRC_ALPHA
        for shift in ((0., 0.), (.125, .375), (.5, .5), (.875, .625)):
            frames = []
            for tight in (0, 1):
                target.use(); ctx.viewport = (0, 0, 2304, 2304); target.clear()
                uniforms(p, dict(u_work=(2304., 2304.), u_workOffset=shift, u_tightQuad=tight))
                vao.render(moderngl.TRIANGLE_STRIP, vertices=4, instances=n)
                frames.append(np.frombuffer(tex.read(), np.float32).copy())
            a, b = frames; delta = np.abs(a-b)
            pixel=np.unravel_index(np.argmax(delta), (2304,2304,4))
            nearest=int(np.argmin(np.linalg.norm(values[:,:2]+shift-(pixel[1],pixel[0]),axis=1)))
            rows.append(dict(additive=additive, shift=shift, max=float(delta.max()), mean=float(delta.mean()),
                             lost=int(np.count_nonzero((a>.001)&(b==0))), worstRadius=float(values[nearest,2])))
    # 三角形端点变化带来光栅化舍入；允许最多两个 8 bit 灰度级，不允许丢失可见边缘。
    assert max(r['max'] for r in rows) < 2/255, rows
    assert all(r['lost'] == 0 for r in rows), rows
    for obj in (vao, data, p, target, tex): obj.release()
    return rows


def history(ctx):
    p = ctx.compute_shader(source('history.comp', ROOT/'app/src/main/assets/particle-playback'))
    # 非整积分步对应的 36 份历史，在整数边界两侧应连续，且正确补足端点时间。
    samples = 36; frame = 7; count = 3
    materials = np.zeros((count, 12), np.float32)
    ranges = np.array([[0,samples,i*(samples+1),0] for i in range(count)], np.int32)
    states = np.zeros((count,samples+1,5), np.float32)
    for i in range(count):
        for j in range(samples+1):
            t = np.floor((j/samples)*240+1e-6)/240
            states[i,j] = [10+i*20+t*30, 50-t*10, 0, 30, -10]
    mb=ctx.buffer(materials.tobytes()); sb=ctx.buffer(reserve=count*32)
    rb=ctx.buffer(ranges.tobytes()); hb=ctx.buffer(states.tobytes())
    for buffer, binding in ((mb,0),(sb,1),(rb,4),(hb,5)): buffer.bind_to_storage_buffer(binding)
    maximum=0.
    for fraction in (0.,.00001,.1,.5,.9,.99999):
        offsets=tuple(j/samples-np.floor(j/samples*240+1e-6)/240 for j in (frame,frame+1))
        uniforms(p,dict(count=count,frame=frame,restore=1,fraction=fraction,sample_extrapolation=offsets))
        p.run(1); ctx.memory_barrier()
        state=np.frombuffer(sb.read(),np.float32).reshape(count,8)
        t=(frame+fraction)/samples
        expected=np.column_stack((10+np.arange(count)*20+t*30, np.full(count,50-t*10)))
        maximum=max(maximum,float(np.abs(state[:,:2]-expected).max()))
    assert maximum<.00002, maximum
    for obj in (p,mb,sb,rb,hb): obj.release()
    return dict(maxPositionError=maximum)


def pressure(ctx, total=100):
    shader=source('pressure-batched.comp', ROOT/'app/src/main/assets/particle-playback')
    base=ctx.compute_shader(shader.replace('#version 430','#version 430\n#define REGISTER_BANDS 1'))
    candidate=ctx.compute_shader(shader.replace('#version 430',
        '#version 430\n#define REGISTER_BANDS 1\n#define ADAPTIVE_PRESSURE 1'))
    n=48; y,x=np.mgrid[:n,:n]; rng=np.random.default_rng(120)
    tiles=np.array([(x,y) for y in range(3) for x in range(3)],np.int32)
    work=ctx.buffer(bytes(64)+tiles.tobytes()); control=ctx.buffer(reserve=136)
    vb=ctx.buffer(reserve=n*n*16); buffers=[ctx.buffer(reserve=n*n*4) for _ in range(2)]
    work.bind_to_storage_buffer(7); control.bind_to_storage_buffer(6); vb.bind_to_storage_buffer(0)
    rows=[]
    for scene in ('empty','smooth','sparse','noisy'):
        v=np.zeros((n,n,4),np.float32)
        shape=np.exp(-((x-24)**2+(y-24)**2)/100).astype(np.float32)
        v[:,:,0]=(24-x)*shape*.01; v[:,:,1]=(24-y)*shape*.01
        v[:,:,2]=(shape>.05).astype(np.float32)
        if scene=='empty':v[:]=0
        if scene=='sparse':v[:,:,2]=(rng.random((n,n))>.7).astype(np.float32)
        if scene=='noisy':v[:,:,:2]=rng.normal(0,.05,(n,n,2))
        vb.write(v.tobytes())
        warm=(shape*.2).astype(np.float32)
        padded=np.pad(v,((1,1),(1,1),(0,0)),mode='edge')
        divergence=(padded[1:-1,2:,0]-padded[1:-1,:-2,0]+
                    padded[2:,1:-1,1]-padded[:-2,1:-1,1])*.5
        expected=warm.copy()
        for step in range(total):
            e=np.pad(expected,1,mode='edge')
            adjacent=e[1:-1,2:]+e[1:-1,:-2]+e[2:,1:-1]+e[:-2,1:-1]
            if step==0:adjacent*=.9
            expected=np.where(v[:,:,2]>.025,np.maximum((adjacent-divergence)*.25,0),0)
        outputs={}
        for tolerance in (-1.,0.,.0001,.001,.01):
            p=base if tolerance<0 else candidate
            buffers[0].write(warm.tobytes()); buffers[1].clear(); control.clear()
            uniforms(p,dict(grid_shape=(n,n),tolerance=tolerance))
            current=0
            for step in range(0,total,8):
                buffers[current].bind_to_storage_buffer(1);buffers[1-current].bind_to_storage_buffer(2)
                uniforms(p,dict(first_iteration=step,iterations=min(8,total-step)))
                p.run(9);ctx.memory_barrier();current=1-current
            output=np.frombuffer(buffers[current].read(),np.float32).reshape(n,n).copy()
            outputs[tolerance]=output
            info=np.frombuffer(control.read(),np.uint32)
            rows.append(dict(scene=scene,total=total,tolerance=tolerance,executed=int(info[33]),
                             pressureMax=float(np.abs(output-expected).max())))
        assert np.max(np.abs(outputs[-1.]-expected))<1e-6, rows[-5:]
        assert np.max(np.abs(outputs[0.]-outputs[-1.]))<1e-6, rows[-5:]
    for obj in (base,candidate,work,control,vb,*buffers):obj.release()
    return rows


def compaction(ctx):
    p=ctx.compute_shader(source('compact-circles.comp', ROOT/'app/src/main/assets/particle-playback'))
    rng=np.random.default_rng(20261008); rows=[]
    for count in (1,255,256,257,10007):
        data=rng.random((count,8),dtype=np.float32)
        data[:,:2]=rng.uniform(-80,1080,(count,2));data[:,2]=rng.uniform(0,40,count)
        data[:,7]=rng.choice(np.array([0.,.0005,.2,.7,1.],np.float32),count)
        groups=(count+255)//256
        src=ctx.buffer(data.tobytes());dst=ctx.buffer(reserve=count*32)
        offsets=ctx.buffer(reserve=count*4); work=ctx.buffer(reserve=16+groups*4)
        for buffer,binding in ((src,0),(dst,1),(offsets,2),(work,3)):buffer.bind_to_storage_buffer(binding)
        uniforms(p,dict(count=count,group_count=groups,frame=(1000.,1000.),offset=(1.,-2.)))
        for phase in range(3):
            p['pass'].value=phase;p.run(1 if phase==1 else groups);ctx.memory_barrier()
        command=np.frombuffer(work.read(size=16),np.uint32)
        center=data[:,:2]+[1.,-2.];radius=np.abs(data[:,2:3])*.5+1
        visible=(data[:,7]>=.001)&np.all(center+radius>=0,axis=1)&np.all(center-radius<=1000,axis=1)
        expected=data[visible]
        assert command.tolist()==[4,len(expected),0,0],command
        actual=np.frombuffer(dst.read(),np.float32).reshape(count,8)[:len(expected)]
        assert np.array_equal(actual,expected),count
        rows.append(dict(count=count,visible=len(expected),identical=True))
        for obj in (src,dst,offsets,work):obj.release()
    p.release();return rows


if __name__ == '__main__':
    out=ROOT/'tmp/particle-120hz-20261008/desktop'; out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430)
    result=dict(drift=drift(ctx),history=history(ctx),
                pressure=[row for total in (8,24,33,100,200) for row in pressure(ctx,total)],
                compaction=compaction(ctx))
    (out/'checks.json').write_text(json.dumps(result,indent=2),'utf-8')
    print(json.dumps(result))
