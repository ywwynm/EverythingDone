"""用 JVM 输出的正式画布几何与生产 GLSL 检查视口内粒子截断，不连接设备。"""
import argparse
import json
import math
import re
from pathlib import Path

import cv2
import moderngl
import numpy as np
from measure_drift_visible_size import source, uniforms, KOTLIN, legacy_sprite_alpha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--layouts', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--expect-clipped', action='store_true')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    layouts = json.loads(args.layouts.read_text('utf-8'))
    # 高颜色面板、靠上小弹窗、横屏卡片、平板创建页；共用正式布局函数输出。
    selected = [next(c for c in layouts if c['sw'] == 1344 and c['sh'] == 2600)]
    selected += [next(c for c in layouts if c['vw'] == w and c['vh'] == h and
                     c['sw'] == sw and c['sh'] == sh and c['x'] == x and c['y'] == y)
                 for w,h,sw,sh,x,y in [(1080,2400,540,600,24,48),
                                        (3120,1440,3072,180,24,48),
                                        (2560,1600,2560,1600,0,0)]]
    ctx = moderngl.create_standalone_context(require=430)
    compute = ctx.compute_shader(source('drift-update.comp'))
    particle = ctx.program(vertex_shader=source('drift-particle.vert'), fragment_shader=source('drift-particle.frag'))
    composite = ctx.program(vertex_shader=source('drift-quad.vert'), fragment_shader=source('drift-composite.frag'))
    quad = ctx.vertex_array(composite, [])
    text = (KOTLIN/'ParticleDriftRenderer.kt').read_text('utf-8')
    match = re.search(r'SPRITE_ALPHA = intArrayOf\((.*?)\)', text, re.S)
    sprite_rgba = np.full((9,9,4),255,np.uint8)
    sprite_rgba[:,:,3] = (np.array([int(v) for v in re.findall(r'\d+', match.group(1))],np.uint8).reshape(9,9)
                          if match else legacy_sprite_alpha())[::-1]
    sprite = ctx.texture((9,9),4,sprite_rgba.tobytes())
    sprite.filter = moderngl.LINEAR, moderngl.LINEAR
    sprite.repeat_x = sprite.repeat_y = False
    count = 18000
    output = ctx.buffer(reserve=count*32)
    output.bind_to_storage_buffer(0)
    cells = ctx.buffer(np.array([1.],np.float32).tobytes())
    cells.bind_to_storage_buffer(1)
    draw_data = ctx.buffer(reserve=count*32)
    vao = ctx.vertex_array(particle, [(draw_data,'4f 4f /i','a_geometry','a_color')])
    rows = []
    for scene, c in enumerate(selected):
        vw,vh,sw,sh = [int(c[k]) for k in ('vw','vh','sw','sh')]
        snapshot = ctx.texture((sw,sh),4,np.tile(np.array([48,90,145,255],np.uint8),(sh,sw,1)).tobytes())
        screen = ctx.texture((vw,vh),4,dtype='f4')
        target = ctx.framebuffer([screen])
        # 对照画布在整个视口四周另留 256 像素；以更大目标验证正式工作面没有内部截断。
        # 保持相同像素网格相位与模拟数据，只改变绘制范围。
        full = dict(c)
        full['left'] = c['sim_left'] + math.floor((-256-c['sim_left'])/2)*2
        full['top'] = c['sim_top'] + math.floor((-256-c['sim_top'])/2)*2
        full['ww'] = math.ceil((vw+256-full['left'])/2)
        full['wh'] = math.ceil((vh+256-full['top'])/2)
        def render(layout, data):
            ww,wh = int(layout['ww']),int(layout['wh'])
            work = ctx.texture((ww,wh),4,dtype='f2')
            work_fbo = ctx.framebuffer([work])
            shift = ((c['sim_left']-layout['left'])/2,(c['sim_top']-layout['top'])/2)
            # 修复前的着色器没有平移 uniform；对照只平移同一份最终几何，模拟数据保持不变。
            shifted = data.copy()
            if 'u_workOffset' not in particle:
                shifted[:,:2] += shift
            draw_data.write(shifted.tobytes())
            uniforms(particle, dict(u_work=(ww,wh),u_workOffset=shift,u_sprite=1))
            sprite.use(1)
            work_fbo.use(); ctx.viewport = (0,0,ww,wh); work_fbo.clear()
            ctx.enable(moderngl.BLEND); ctx.blend_func = moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA
            vao.render(moderngl.TRIANGLE_STRIP,vertices=4,instances=count)
            target.use(); ctx.viewport = (0,0,vw,vh); target.clear()
            work.use(2)
            uniforms(composite,dict(u_rect=(layout['left'],layout['top'],layout['left']+ww*2,layout['top']+wh*2),
                u_viewport=(0.,0.,float(vw),float(vh)),u_work=2,u_workSize=(ww,wh)))
            quad.render(moderngl.TRIANGLE_STRIP,vertices=4)
            rgba = np.frombuffer(target.read(components=4,alignment=1),np.uint8).reshape(vh,vw,4)[::-1].copy()
            work_fbo.release(); work.release()
            return rgba
        for direction in (-1,1):
            for size in (1.,6.):
                for time in (.35,.55):
                    snapshot.use(0)
                    uniforms(compute,dict(u_image=0,u_count=count,u_baseCount=count,u_grid=(1,1),
                        u_emitPos=((c['sim_w']-sw)/4,(c['sim_h']-sh)/4),u_emitSize=(sw/2,sh/2),
                        u_half=(sw//2,sh//2),u_full=(sw,sh),u_hz=120.,u_wind=(direction*19800.,direction*19800.),
                        u_add=0,u_alphaMul=1.15,u_fadeSizePower=.5,u_fadeAlphaPower=.5,u_sizeScale=size,
                        u_touchGap=-1.,u_turbOffset=(129.,72.),u_turbScale=(1.,1.),u_turbShift=(0.,0.),
                        u_panel=(48/255,90/255,145/255),u_panelWeight=0.,u_total=2.,u_latticeW=1,u_latticeH=1,
                        u_scaleU=.002,u_time=time))
                    compute['u_lattice'].write(np.full(64,.3,np.float32).tobytes())
                    compute.run((count+255)//256); ctx.memory_barrier()
                    data = np.frombuffer(output.read(),np.float32).reshape(count,8)
                    current,reference = render(c,data),render(full,data)
                    # 工作面尺寸不同会改变顶点归一化的浮点舍入；实测少量像素相差至多 6/255。
                    # 允许 8/255 的细微 alpha 差异，同时单独检查明显粒子不能变成透明像素。
                    lost = int(np.count_nonzero(reference[:,:,3].astype(np.int16)-current[:,:,3].astype(np.int16)>8))
                    row = dict(scene=scene,direction=direction,size=size,time=time,lost_pixels=lost,
                               missing_pixels=int(np.count_nonzero((reference[:,:,3]>16)&(current[:,:,3]<=2))),
                               max_alpha_difference=int(np.abs(reference[:,:,3].astype(np.int16)-current[:,:,3].astype(np.int16)).max()),
                               visible_pixels=int(np.count_nonzero(reference[:,:,3]>2)))
                    rows.append(row)
                    if scene==0 and direction==-1 and size==6. and time==.55:
                        for name,rgba in [('current',current),('unclipped',reference)]:
                            rgb = rgba[:,:,:3].astype(np.float32)+255-rgba[:,:,3:4].astype(np.float32)
                            cv2.imwrite(str(args.out/(name+'.png')),np.uint8(np.clip(rgb,0,255))[:,:,::-1])
        snapshot.release(); target.release(); screen.release()
    result = dict(cases=len(rows),clipped_cases=sum(r['lost_pixels']>0 for r in rows),results=rows)
    (args.out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='results'}))
    assert all(r['visible_pixels']>0 for r in rows), '没有生成有效颗粒'
    if args.expect_clipped:
        assert result['clipped_cases']>0, '未复现内部截断'
    else:
        assert result['clipped_cases']==0, '视口内仍有颗粒被截断'
        assert all(r['missing_pixels']==0 for r in rows), '可见颗粒在正式工作面中变成了透明像素'


if __name__=='__main__':
    main()
