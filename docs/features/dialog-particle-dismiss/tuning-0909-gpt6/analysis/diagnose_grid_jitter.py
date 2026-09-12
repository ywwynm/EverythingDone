"""用设备实测网格偏移隔离三角函数散列的跨 GPU 误差，不修改正式材质。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from unified_model import SHARED,model_fingerprint
from filament_lifetime import filament_energy
OUT=HERE/'analysis/device-filament-origins';OUT.mkdir(exist_ok=True)
ORIGINAL=renderer.VERTEX
START=ORIGINAL.index('uint grid_hash') if 'uint grid_hash' in ORIGINAL else ORIGINAL.index('float hash(vec2')
FUNCTIONS=ORIGINAL[START:ORIGINAL.index('void main()')]
REPLACEMENT='''layout(std430,binding=5) readonly buffer J {vec2 measured_jitter[];};
vec2 jitter(vec2 g){return measured_jitter[int(g.y)*(nx+1)+int(g.x)];}
'''

def main():
    ctx=moderngl.create_standalone_context(require=430)
    program=ctx.program(vertex_shader=renderer.FULLVERT,fragment_shader=(SHARED/'resolve.frag').read_text('utf-8').replace('#version 310 es','#version 430'))
    program['screen']=2;vao=ctx.vertex_array(program,[]);rows=[]
    for scene in ['ironman','color']:
        for serial in ['9018f404','R5CW20BLNKL']:
            folder=HERE/'device-release-distribution'/serial/'generated';jitter=np.fromfile(folder/f'{scene}-grid-jitter.f32','<f4').reshape(-1,2)
            actual=np.array(Image.open(folder/f'{scene}-40.png').convert('RGBA')).astype(float)
            images=[];results=[]
            for measured in [False,True]:
                renderer.VERTEX=ORIGINAL.replace(FUNCTIONS,REPLACEMENT) if measured else ORIGINAL
                r=renderer.Renderer(scene,ctx=ctx,quality=1);t=float(np.float32(40/60));r.seek(t)
                if measured:
                    b=ctx.buffer(jitter.tobytes());b.bind_to_storage_buffer(5)
                    # 材料在两端各自按出生时刻排序，用身份值对齐之后才替换位置。
                    state=np.fromfile(folder/f'{scene}-state040.f32','<f4').reshape(-1,8)
                    material=np.fromfile(folder/f'{scene}-materials.f32','<f4').reshape(-1,12)
                    aligned=np.empty_like(state);aligned[np.argsort(r.base[:,3])]=state[np.argsort(material[:,3])]
                    r.state.write(aligned.tobytes())
                r.fbo.use();r.fbo.clear(0,0,0,0);ctx.enable(moderngl.BLEND);ctx.blend_func=(moderngl.ONE,moderngl.ONE_MINUS_SRC_ALPHA)
                r.program['time']=t;r.program['extrapolate']=0;r.program['diagnostic']=0;r.fg_tex.use(0)
                for p in [0,1]:
                    r.program['material_pass']=p;r.fg_tex.filter=(moderngl.NEAREST,moderngl.NEAREST) if p==0 else (moderngl.LINEAR,moderngl.LINEAR)
                    r.vao.render(moderngl.TRIANGLES,vertices=6,instances=r.n)
                ctx.disable(moderngl.BLEND);r.out_fbo.use();r.tex.use(2);vao.render(moderngl.TRIANGLES,vertices=3)
                rgba=np.frombuffer(r.out_fbo.read(components=4,alignment=1),'uint8').reshape(r.h,r.w,4)[::-1].astype(float)
                bg=np.array(Image.open(HERE/f'assets/{scene}/background.png').convert('RGB')).astype(float)
                bg*=1-r.meta['dim_alpha']*(1-np.clip((t-.18)/.55,0,1)**2)
                predicted=np.clip(rgba[:,:,:3]+bg*(1-rgba[:,:,3:]/255),0,255).astype('uint8')
                got=np.clip(actual[:,:,:3]*actual[:,:,3:]/255+bg*(1-actual[:,:,3:]/255),0,255).astype('uint8')
                result=dict(measured=measured,alpha_mae=float(abs(actual[:,:,3]-rgba[:,:,3]).mean()),rgb_mae=float(abs(got.astype(float)-predicted).mean()))
                if scene=='ironman':result.update(predicted_energy=filament_energy(predicted,bg),device_energy=filament_energy(got,bg))
                if not measured:
                    source=f'''#version 430
layout(local_size_x=64) in;layout(std430,binding=5) buffer O {{vec2 jitter_out[];}};
{FUNCTIONS}
void main(){{uint i=gl_GlobalInvocationID.x;if(i>={len(jitter)}u)return;jitter_out[i]=jitter(vec2(i%{r.nx+1}u,i/{r.nx+1}u));}}'''
                    shader=ctx.compute_shader(source);temp=ctx.buffer(reserve=jitter.nbytes);temp.bind_to_storage_buffer(5)
                    shader.run(group_x=(len(jitter)+63)//64);ctx.memory_barrier()
                    own=np.frombuffer(temp.read(),'<f4').reshape(-1,2)
                    result['jitter_max_cell']=float(abs(jitter-own).max());result['jitter_mae_cell']=float(abs(jitter-own).mean())
                    shader.release();temp.release()
                results.append(result);images.append(Image.fromarray(predicted));r.close()
                if measured:b.release()
            images.append(Image.fromarray(got));height=round(images[0].height/images[0].width*330)
            page=Image.new('RGB',(990,height+28),'#18212a');draw=ImageDraw.Draw(page)
            for col,img in enumerate(images):
                page.paste(img.resize((330,height)),(col*330,28));draw.text((col*330+6,6),['Desktop','Device jitter + state',serial][col],fill='white')
            page.save(OUT/f'grid-jitter-{scene}-{serial}.jpg',quality=95)
            row=dict(scene=scene,device=serial,cases=results);rows.append(row);print(json.dumps(row),flush=True)
    renderer.VERTEX=ORIGINAL;vao.release();program.release();ctx.release()
    suffix='integer' if 'uint grid_hash' in ORIGINAL else 'sin'
    (OUT/f'grid-jitter-diagnosis-{suffix}.json').write_text(json.dumps(dict(model_hash=model_fingerprint(),cases=rows),indent=2),'utf-8')

if __name__=='__main__':main()
