"""运行实际片元着色器：默认输出对比 Git 基线，容器浓度只在最终合成应用。"""
import json, re, subprocess
from pathlib import Path
import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
SHADERS = ROOT / 'shared/particle-dismiss'
def source(name, old=False):
    def read(n):
        if old:
            return subprocess.check_output(['git','show',f'HEAD:shared/particle-dismiss/{n}'],cwd=ROOT).decode('utf8')
        return (SHADERS/n).read_text('utf8')
    return re.sub(r'#include "([^"/]+)"',lambda m:read(m[1]),read(name)).replace('#version 310 es','#version 430')

def main():
    ctx=moderngl.create_standalone_context(require=430)
    w,h=64,48
    rng=np.random.default_rng(42017)
    rgba=rng.random((h,w,4),dtype=np.float32);rgba[:,:,:3]*=rgba[:,:,3:]
    tex=ctx.texture((w,h),4,rgba.tobytes(),dtype='f4');tex.use(0)
    target=ctx.texture((w,h),4,dtype='f4');fbo=ctx.framebuffer([target]);fbo.use()
    rows=[]
    for name in ('drift-composite.frag','drift-card.frag'):
        def render(old=False,opacity=1.):
            program=ctx.program(vertex_shader=source('drift-quad.vert',old),fragment_shader=source(name,old))
            uniforms={'u_rect':(0.,0.,float(w),float(h)),'u_viewport':(0.,0.,float(w),float(h)),
                      'u_work':0,'u_workSize':(w,h),'u_image':0,'u_full':(w,h),
                      'u_time':.08,'u_latticeW':1,'u_latticeH':1,'u_scaleU':.002,'u_containerFade':1-opacity}
            for k,v in uniforms.items():
                if k in program:program[k].value=v
            if 'u_lattice' in program:program['u_lattice'].write(np.full(64,.3,np.float32).tobytes())
            fbo.clear();ctx.viewport=(0,0,w,h)
            vao=ctx.vertex_array(program,[]);vao.render(moderngl.TRIANGLE_STRIP,vertices=4)
            result=np.frombuffer(fbo.read(components=4,dtype='f4'),np.float32).copy()
            vao.release();program.release();return result
        old=render(True);current=render();faded=render(opacity=.84)
        identity=float(abs(old-current).max());fade=float(abs(faded-current*.84).max())
        assert identity==0,(name,identity)
        assert fade<1e-6,(name,fade)
        assert np.count_nonzero(faded)>0
        rows.append({'shader':name,'defaultVsGitMaxError':identity,'opacity084MaxError':fade})
    out={'gpu':ctx.info['GL_RENDERER'],'checks':rows,'meaning':'实际 GLSL 离屏验证；屏幕交接另用真机录屏验证'}
    dest=ROOT/'docs/features/particle-animation-performance/diagnostics/shadow-doing-2026-10-12'
    dest.mkdir(exist_ok=True,parents=True)
    (dest/'container-alpha.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(out,ensure_ascii=False))
if __name__=='__main__':main()
