"""从正式片元输出验证可见粒径与软边；不是用四边形尺寸代替可见大小。"""
import json
from pathlib import Path
import sys
import moderngl
import numpy as np
from check_drift_clarity import source, uniforms, old_sprite, OUT

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'particle-animation-performance/checks'))
from measure_drift_visible_size import profile_width


def main():
    ctx=moderngl.create_standalone_context(require=430)
    program=ctx.program(vertex_shader=source('drift-particle.vert'),fragment_shader=source('drift-particle.frag'))
    data=ctx.buffer(reserve=32)
    vao=ctx.vertex_array(program,[(data,'4f 4f /i','a_geometry','a_color')])
    sprite=old_sprite(ctx); sprite.use(0)
    tex=ctx.texture((1024,1024),4,dtype='f4'); fbo=ctx.framebuffer([tex])
    uniforms(program,dict(u_work=(16.,16.),u_workOffset=(0.,0.),u_sprite=0))
    fbo.use()
    rows=[]
    for factor in (.5,1.,2.,6.):
        # 单颗粒子的生产半宽缩放到当前视野；6x 用更大视野避免测量被目标边缘截断。
        extent=2.45*factor; span=max(16.,extent*4)
        uniforms(program,dict(u_work=(span,span)))
        data.write(np.array([span/2,span/2,extent,1,1,1,1,1],np.float32).tobytes())
        fbo.clear(); vao.render(moderngl.TRIANGLE_STRIP,vertices=4,instances=1)
        alpha=np.frombuffer(fbo.read(components=4,dtype='f4'),np.float32).reshape(1024,1024,4)[:,:,3]
        w50=profile_width(alpha,.5,span/1024); w10=profile_width(alpha,.1,span/1024)
        rows.append(dict(multiplier=factor,visible_fraction=w50/(2*extent),edge_ratio=w10/w50))
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'coverage.json').write_text(json.dumps(rows,indent=2),'utf-8')
    print(json.dumps(rows))
    assert all(.49<r['visible_fraction']<.51 for r in rows), '可见中心仍过小，未达到轮廓外框的一半'
    assert all(r['edge_ratio']<1.30 for r in rows), '透明软边仍过宽'


if __name__=='__main__': main()
