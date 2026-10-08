"""正式 GLSL 的固定输入画面对照；从真机基线读取输入，不连接设备、不改用户设置。"""
import argparse
import json
import math
import re
import subprocess
from pathlib import Path

import moderngl
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[4]
SHADERS = ROOT / 'shared/particle-dismiss'
BASE = ROOT / 'tmp/harmony-particle-android-port/parity/clarity-20261008-before'
OUT = ROOT / 'tmp/drift-clarity-20261008'
BASELINE_REVISION = '3b9b3d59018d72f74d94173a65e4055bfeb9ce44'


def uniforms(program, values):
    for key, value in values.items():
        if key in program:
            program[key].value = value


def source(name, directory=SHADERS):
    text=(directory/name).read_text('utf-8').replace('#version 310 es', '#version 430')
    return re.sub(r'#include "([^"/]+)"',lambda m:(directory/m[1]).read_text('utf-8'),text)


def old_sprite(ctx):
    text = (ROOT/'app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/ParticleDriftRenderer.kt').read_text('utf-8')
    match = re.search(r'SPRITE_ALPHA = intArrayOf\((.*?)\)', text, re.S)
    # 保留历史软点供单变量对照；生产去掉纹理后也能运行旧基线。
    alpha = [0,0,1,5,7,4,1,0,0,0,3,14,29,36,29,14,3,0,2,14,46,83,100,83,46,14,1,
             4,30,82,149,183,149,82,29,5,6,37,100,184,255,184,100,36,6,
             4,29,83,149,184,148,83,30,4,1,14,46,83,100,82,45,14,0,
             0,3,15,30,37,29,15,2,0,0,0,1,5,6,3,0,0,0]
    if match:
        alpha = [int(v) for v in re.findall(r'\d+', match.group(1))]
    rgba = np.full((9,9,4), 255, np.uint8)
    rgba[:,:,3] = np.array(alpha,np.uint8).reshape(9,9)[::-1]
    tex = ctx.texture((9,9),4,rgba.tobytes())
    tex.filter = moderngl.LINEAR, moderngl.LINEAR
    tex.repeat_x = tex.repeat_y = False
    return tex


class Renderer:
    def __init__(self, ctx, folder, directory=SHADERS, compute=None, fragment=None):
        self.ctx = ctx
        self.spec = s = json.loads((folder/'spec.json').read_text('utf-8'))
        self.result = r = json.loads((folder/'device/result.json').read_text('utf-8'))
        self.sw,self.sh = r['width'],r['height']
        self.vw,self.vh = s['output']
        self.ox,self.oy = s['origin']
        self.ew,self.eh = map(int,s['expansion'])
        self.ex,self.ey = (self.ew-self.sw)/4,(self.eh-self.sh)/4
        sim_left,sim_top = self.ox-(self.ew-self.sw)/2,self.oy-(self.eh-self.sh)/2
        self.left = sim_left+math.floor(-sim_left/2)*2
        self.top = sim_top+math.floor(-sim_top/2)*2
        self.ww,self.wh = math.ceil((self.vw-self.left)/2),math.ceil((self.vh-self.top)/2)
        self.shift = ((sim_left-self.left)/2,(sim_top-self.top)/2)
        self.compute = ctx.compute_shader(compute or source('drift-update.comp',directory))
        self.particle = ctx.program(vertex_shader=source('drift-particle.vert',directory),
                                    fragment_shader=fragment or source('drift-particle.frag',directory))
        self.composite = ctx.program(vertex_shader=source('drift-quad.vert',directory),
                                     fragment_shader=source('drift-composite.frag',directory))
        self.card = ctx.program(vertex_shader=source('drift-quad.vert',directory),
                                fragment_shader=source('drift-card.frag',directory))
        self.data = ctx.buffer(reserve=r['total']*32)
        self.cells = ctx.buffer(np.array(r['content']['cumulative'] or [1.],np.float32).tobytes())
        self.vao = ctx.vertex_array(self.particle,[(self.data,'4f 4f /i','a_geometry','a_color')])
        self.quad = ctx.vertex_array(self.composite,[])
        self.cardvao = ctx.vertex_array(self.card,[])
        self.image = ctx.texture((self.sw,self.sh),4,(folder/'device/snapshot.rgba').read_bytes())
        self.image.filter = moderngl.NEAREST,moderngl.NEAREST
        self.image.repeat_x = self.image.repeat_y = False
        self.sprite = old_sprite(ctx)
        self.work = ctx.texture((self.ww,self.wh),4,dtype='f2')
        self.workfbo = ctx.framebuffer([self.work])
        self.screen = ctx.texture((self.vw,self.vh),4)
        self.target = ctx.framebuffer([self.screen])
        self.resources = [self.compute,self.particle,self.composite,self.card,self.data,self.cells,self.vao,
                          self.quad,self.cardvao,self.image,self.sprite,self.workfbo,self.work,self.target,self.screen]
        self.base_size = r.get('sizeScale',s.get('sizeScale',1.))
        c = r['content']
        tf = s.get('turbulenceFrame')
        scale = (tf[2]/(self.sw/2),tf[3]/(self.sh/2)) if tf else (1.,1.)
        shift = (tf[0]-self.ex*scale[0],tf[1]-self.ey*scale[1]) if tf else (0.,0.)
        self.values = dict(u_image=0,u_count=r['total'],u_baseCount=r['count'],u_grid=c['grid'],
            u_emitPos=(self.ex,self.ey),u_emitSize=(self.sw/2,self.sh/2),u_half=(self.sw//2,self.sh//2),
            u_full=(self.sw,self.sh),u_hz=120.,u_wind=s['wind'],u_add=int(s['additive']),
            u_alphaMul=s['overAlphaMul'],u_fadeSizePower=s['fadeSizePower'],u_fadeAlphaPower=s['fadeAlphaPower'],
            u_sizeScale=self.base_size,u_touchGap=s.get('touchGap',-1.),u_turbOffset=s.get('turbulenceOffset',(0.,0.)),
            u_turbScale=scale,u_turbShift=shift,u_panel=c['panel'],u_panelWeight=c['panelWeight'] if s['content'] else 0.,
            u_total=r['totalSeconds'],u_latticeW=s['lattice']['width'],u_latticeH=s['lattice']['height'],u_scaleU=r['scaleU'])
        self.lattice = np.zeros(64,np.float32)
        self.lattice[:len(s['lattice']['values'])] = s['lattice']['values']

    def render(self, time, size=1., data_only=False):
        ctx,s = self.ctx,self.spec
        self.data.bind_to_storage_buffer(0); self.cells.bind_to_storage_buffer(1); self.image.use(0)
        uniforms(self.compute,{**self.values,'u_time':time,'u_sizeScale':self.base_size*size})
        if 'u_lattice' in self.compute:
            self.compute['u_lattice'].write(self.lattice.tobytes())
        self.compute.run((self.result['total']+255)//256); ctx.memory_barrier()
        if data_only:
            return np.frombuffer(self.data.read(),np.float32).reshape(-1,8).copy()
        self.workfbo.use(); ctx.viewport=(0,0,self.ww,self.wh); self.workfbo.clear()
        self.sprite.use(1)
        uniforms(self.particle,dict(u_work=(self.ww,self.wh),u_workOffset=self.shift,u_sprite=1))
        ctx.enable(moderngl.BLEND)
        ctx.blend_func=(moderngl.ONE,moderngl.ONE if s['additive'] else moderngl.ONE_MINUS_SRC_ALPHA)
        self.vao.render(moderngl.TRIANGLE_STRIP,vertices=4,instances=self.result['total'])
        self.target.use(); ctx.viewport=(0,0,self.vw,self.vh)
        self.target.clear(*[v/255 for v in s['background']],1.)
        ctx.blend_func=moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA
        self.work.use(2)
        uniforms(self.composite,dict(u_rect=(self.left,self.top,self.left+self.ww*2,self.top+self.wh*2),
            u_viewport=(0.,0.,float(self.vw),float(self.vh)),u_work=2,u_workSize=(self.ww,self.wh)))
        self.quad.render(moderngl.TRIANGLE_STRIP,vertices=4)
        self.image.use(0)
        uniforms(self.card,dict(u_rect=(self.ox,self.oy,self.ox+self.sw,self.oy+self.sh),
            u_viewport=(0.,0.,float(self.vw),float(self.vh)),u_image=0,u_full=(self.sw,self.sh),
            u_time=math.floor(time*120+1e-3)/120,u_latticeW=s['lattice']['width'],
            u_latticeH=s['lattice']['height'],u_scaleU=self.result['scaleU']))
        self.card['u_lattice'].write(self.lattice.tobytes())
        self.cardvao.render(moderngl.TRIANGLE_STRIP,vertices=4)
        return np.frombuffer(self.target.read(components=3,alignment=1),np.uint8).reshape(self.vh,self.vw,3)[::-1].copy()

    def close(self):
        for resource in self.resources:
            resource.release()


def montage(images, path, crop=None, width=390):
    tiles=[]
    for name,frame in images:
        im=Image.fromarray(frame)
        if crop: im=im.crop(crop)
        im=im.resize((width,round(im.height*width/im.width)),Image.Resampling.LANCZOS)
        tile=Image.new('RGB',(width,im.height+28),'#ddd')
        tile.paste(im,(0,28)); ImageDraw.Draw(tile).text((6,6),name,fill='black')
        tiles.append(tile)
    result=Image.new('RGB',(sum(t.width for t in tiles),max(t.height for t in tiles)),'#ddd')
    x=0
    for tile in tiles: result.paste(tile,(x,0)); x+=tile.width
    result.save(path)


def main():
    p=argparse.ArgumentParser(); p.add_argument('--mode',choices=['experiments','verify'],default='experiments')
    args=p.parse_args(); OUT.mkdir(parents=True,exist_ok=True)
    baseline=OUT/'baseline'; baseline.mkdir(exist_ok=True)
    # 固定到改动前版本；清空 tmp 后重跑也不能把当前实现误当旧基线。
    for name in ('drift-update.comp','drift-particle.vert','drift-particle.frag',
                 'drift-quad.vert','drift-composite.frag','drift-card.frag','drift-common.glsl'):
        original = subprocess.run(['git','show',f'{BASELINE_REVISION}:shared/particle-dismiss/{name}'],
                                  cwd=ROOT,capture_output=True,check=True).stdout
        (baseline/name).write_bytes(original)
    (baseline/'revision.txt').write_text(BASELINE_REVISION+'\n','utf-8')
    ctx=moderngl.create_standalone_context(require=430)
    old_frag=source('drift-particle.frag',baseline)
    old_comp=source('drift-update.comp',baseline)
    compact=old_frag.replace('texture(u_sprite, v_q * 0.5 + 0.5).a',
                             '1.0 - smoothstep(0.36, 0.64, length(v_q))')
    variants={'before':(old_comp,old_frag,1.),'size-only':(old_comp,old_frag,1.4),
              'compact':(old_comp,compact,1.),'body-only':(old_comp.replace('BODY_SIZE_CUT = 0.26','BODY_SIZE_CUT = 0.0').replace('BODY_ALPHA_CUT = 0.35','BODY_ALPHA_CUT = 0.0'),old_frag,1.),
              'compact-density':(old_comp,compact,1.25)}
    if args.mode=='verify': variants={'before':(old_comp,old_frag,1.),'after':(source('drift-update.comp'),source('drift-particle.frag'),1.)}
    rows=[]
    for name in ('ironman-ref','dialog-light','dialog-dark'):
        folder=BASE/name
        for t in (.45,.6,.75,.9,1.05,1.2):
            images=[]
            for variant,(comp,frag,size) in variants.items():
                r=Renderer(ctx,folder,compute=comp,fragment=frag)
                frame=r.render(t,size=size); r.close()
                dest=OUT/args.mode/name/variant; dest.mkdir(parents=True,exist_ok=True)
                Image.fromarray(frame).save(dest/f'{t:.2f}.png')
                images.append((variant,frame))
                if variant=='before':
                    idx=int(round(t/.05))-1
                    dev=np.asarray(Image.open(folder/'device'/f'frame-{idx:03d}.png').convert('RGB'))
                    diff=np.abs(frame.astype(np.int16)-dev.astype(np.int16))
                    rows.append(dict(scene=name,time=t,mean=float(diff.mean()),p999=float(np.percentile(diff,99.9))))
            montage(images,OUT/args.mode/name/f'{t:.2f}-sheet.png')
            if t==.75:
                crop=(100,330,650,880) if name=='ironman-ref' else (70,560,670,1160)
                montage(images,OUT/args.mode/name/'detail.png',crop,width=450)
        print(name,flush=True)
    (OUT/'baseline-parity.json').write_text(json.dumps(rows,indent=2),'utf-8')
    print(json.dumps(rows),flush=True)


if __name__=='__main__': main()
