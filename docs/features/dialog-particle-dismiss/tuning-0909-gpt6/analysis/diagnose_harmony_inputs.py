"""仅用于离线诊断原生噪声、物理尺度及时间口径；不生成逐像素运行控制图。"""
from pathlib import Path
import argparse,json,sys,time
import numpy as np
import moderngl

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'analysis')]
from harmony_core import RANDOM
from release_integrity import Probe

SHADER='#version 430\n'+RANDOM+r'''
layout(local_size_x=128) in;
layout(std430,binding=0) readonly buffer Samples{vec4 points[];};
layout(std430,binding=1) writeonly buffer Results{vec4 results[];};
uniform uint count,base;
uniform int n;
uniform float frequency;
float lattice(vec2 p,float r){return fract(sin(dot(p+1.,vec2(127.1,311.7)))*43758.5453123*(r+1.));}
float noise(vec2 p,float r){vec2 q=floor(p),f=fract(p);f=f*f*(3.-2.*f);
return mix(mix(lattice(q,r),lattice(q+vec2(1,0),r),f.x),
mix(lattice(q+vec2(0,1),r),lattice(q+1.,r),f.x),f.y);}
void main(){uint k=gl_GlobalInvocationID.x;if(k>=count)return;
float r=randomIndex(k+base),a=0.,b=0.,aa=0.,bb=0.,ab=0.;
for(int i=0;i<n;i++){float x=.2+.8*noise(points[i].xy*frequency,r),y=points[i].z;
a+=x;b+=y;aa+=x*x;bb+=y*y;ab+=x*y;}
float N=float(n),s=clamp((ab-a*b/N)/max(aa-a*a/N,1e-6),.45,1.5);
float c=clamp((b-s*a)/N,-.15,.10);
float e=(bb+s*s*aa+N*c*c-2.*s*ab-2.*c*b+2.*s*c*a)/N;
results[k]=vec4(e,s,c,r);}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--scenes',nargs='+',default=['ironman','kobe','reference-0926-1','reference-0926-5'])
    ap.add_argument('--samples',type=int,default=16384)
    ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);shader=ctx.compute_shader(SHADER)
    result={};t0=time.time()
    for name in args.scenes:
        p=Probe(name);target=p.huawei();v,u=np.meshgrid(p.v,p.u,indexing='ij')
        use=p.valid&np.isfinite(target)&(target>.055)&(target<.84)
        x,y,x1,y1=p.rect;w,h=x1-x,y1-y
        data=np.column_stack([u[use]*w,v[use]*h,target[use],np.ones(use.sum())]).astype('f4')
        take=np.random.RandomState(314).permutation(len(data))[:256];data=data[take]
        buf=ctx.buffer(data.tobytes());buf.bind_to_storage_buffer(0)
        out=ctx.buffer(reserve=args.samples*16);out.bind_to_storage_buffer(1)
        shader['count']=args.samples;shader['n']=len(data);shader['base']=17
        rows=[]
        for scale in np.arange(1.,3.51,.125):
            shader['frequency']=float(scale*(205./(w*h*scale*scale)+.002))
            shader.run(group_x=(args.samples+127)//128);ctx.memory_barrier()
            a=np.frombuffer(out.read(),np.float32).reshape(-1,4)
            for k in np.argsort(a[:,0])[:4]:
                e,s,c,r=map(float,a[k]);rows.append(dict(rmse=e**.5,scale=float(scale),clock_slope=s,
                    clock_intercept=c,random=r,inferred_span=2./(2.1*s)))
        rows.sort(key=lambda r:r['rmse']);result[name]=dict(points=len(data),candidates=rows[:12],
            renderer=ctx.info['GL_RENDERER'],samples_per_scale=args.samples,
            limitation='以原位纹理完整度的观测时刻诊断输入；不等于真实出生时刻。随机数依赖渲染后端；不能直接作为恢复出的原片参数。')
        print(name,rows[:2],flush=True)
        (args.out/'release-inputs.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
        buf.release();out.release()
    shader.release();ctx.release();print('seconds',time.time()-t0)

if __name__=='__main__':main()
