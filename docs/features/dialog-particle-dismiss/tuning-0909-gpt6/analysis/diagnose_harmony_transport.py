"""用已认可基准的真实粒子路径检验华为噪声运动族的表达能力。

这是离线诊断：基准路径不是华为真值，结果必须再次对照原片。
只保存少量路径样本和一组共同标量，不产生逐素材运动表或逐帧状态缓存。
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import sys
import time
from dataclasses import replace

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.optimize import differential_evolution, minimize

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from desktop_candidate import SparseRenderer
from harmony_core import (SIMPLEX,HarmonyCoreRenderer,DEFAULT,COMPUTE,
                          MASK_FRAGMENT,COMPOSITE_FRAGMENT)
from harmony_adapted import DECODE,ENCODE
from match_refs import RefFrames
from unified_model import model_fingerprint


# 归一化到短边和风向坐标；两层 simplex、年龄位移及二次风仍保留。
# 新增的空间旋转、共同时间项和线性风，属于能力诊断，非恢复出的宿主参数。
MOTION = r'''
float noiseSum(vec3 q){return snoise(q)+.5*snoise(q*1.5);}
vec2 turn(vec2 q,float a){return vec2(cos(a)*q.x-sin(a)*q.y,sin(a)*q.x+cos(a)*q.y);}
vec2 motion(vec2 origin,float age,float progress,vec4 a,vec4 b,vec4 c){
    vec2 xy=turn(origin,c.z)*a.x+a.yz;
    vec3 q=vec3(xy,a.w+age*b.x+progress*b.y);
    vec2 f=vec2(noiseSum(q),noiseSum(q+vec3(100,100,0)));
    return turn(f,b.w)*b.z*age+vec2(0.,-c.x*age-c.y*age*age);
}
'''

FIT_SHADER = '#version 430\n' + SIMPLEX + MOTION + r'''
layout(local_size_x=128) in;
struct Sample{vec4 source;vec4 target;};
layout(std430,binding=0) readonly buffer Samples{Sample samples[];};
layout(std430,binding=1) readonly buffer Params{vec4 params[];};
layout(std430,binding=2) writeonly buffer Output{vec4 results[];};
uniform uint count;
void main(){
    uint i=gl_GlobalInvocationID.x,j=gl_WorkGroupID.y;if(i>=count)return;
    Sample s=samples[i];
    vec2 d=motion(s.source.xy,s.source.z,s.source.w,params[j*3u],params[j*3u+1u],params[j*3u+2u]);
    vec2 error=d-s.target.xy;
    results[j*count+i]=vec4(d,dot(error,error),s.target.z);
}
'''

PARAMETER_NAMES = ['frequency','offset_x','offset_y','offset_z','age_evolution',
                   'global_evolution','amplitude','vector_rotation','linear_wind',
                   'quadratic_wind','coordinate_rotation']
BOUNDS = [(.4,3.5),(-2.,2.),(-2.,2.),(-2.,2.),(-1.,4.),(-1.,4.),
          (.05,2.5),(-math.pi,math.pi),(0.,1.3),(0.,3.),(-math.pi,math.pi)]


def basis(direction):
    a=math.radians(direction)
    return np.array([[math.sin(a),-math.cos(a)],
                     [math.cos(a),math.sin(a)]],np.float32)


def capture(ctx, out):
    r=SparseRenderer('ironman',ctx=ctx,quality=1)
    source=r.base
    fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
    px=np.clip(source[:,0].astype(int),0,r.cw-1)
    py=np.clip(source[:,1].astype(int),0,r.ch-1)
    rgba=fg[py,px]
    rotation=basis(r.direction)
    origin=((source[:,:2]-[r.cw*.5,r.ch*.5])/r.span)@rotation
    rng=np.random.default_rng(104)
    parts=[]
    for p in np.arange(.12,.91,.04):
        r.seek(float(p))
        state=np.frombuffer(r.state.read(),np.float32).reshape(-1,8)
        age=r.step/240.-source[:,2]
        valid=(age>.02)&(age<source[:,6]*.90)&(rgba[:,3]==255)
        candidates=np.flatnonzero(valid)
        ids=rng.choice(candidates,min(300,len(candidates)),replace=False)
        data=np.zeros((len(ids),8),np.float32)
        data[:,:2]=origin[ids];data[:,2]=age[ids];data[:,3]=r.step/240.
        data[:,4:6]=((state[ids,:2]-source[ids,:2])/r.span)@rotation
        # 让纯底色与人物都有权重，避免只拟合极少数强色区域。
        data[:,6]=.5+.5*np.ptp(rgba[ids,:3].astype(float),axis=1)/255.
        data[:,7]=ids
        parts.append(data)
    samples=np.concatenate(parts)
    np.savez_compressed(out/'baseline-path-samples.npz',samples=samples)
    info=dict(scene='ironman',seed=r.meta['seed'],span=r.span,particles=r.n,
              samples=len(samples),renderer=ctx.info['GL_RENDERER'],
              baseline_fingerprint=model_fingerprint(),
              note='旧基准可见粒子位置，仅用于能力诊断，不是华为真值')
    (out/'sample-source.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),'utf-8')
    r.close()
    return samples,info


class Objective:
    def __init__(self,ctx,samples):
        self.ctx=ctx;self.samples=samples;self.n=len(samples)
        self.shader=ctx.compute_shader(FIT_SHADER)
        self.source=ctx.buffer(samples.astype('float32').tobytes())
        self.params=ctx.buffer(reserve=512*48)
        self.output=ctx.buffer(reserve=512*self.n*16)
        self.shader['count']=self.n

    def predict(self,x):
        values=np.atleast_2d(x).astype('float32')
        packed=np.zeros((len(values),12),np.float32);packed[:,:values.shape[1]]=values
        self.params.write(packed.tobytes())
        self.source.bind_to_storage_buffer(0);self.params.bind_to_storage_buffer(1)
        self.output.bind_to_storage_buffer(2)
        self.shader.run(group_x=(self.n+127)//128,group_y=len(values))
        self.ctx.memory_barrier()
        return np.frombuffer(self.output.read(size=len(values)*self.n*16),np.float32).reshape(len(values),self.n,4)

    def __call__(self,x):
        values=np.asarray(x)
        many=values.ndim>1
        out=self.predict(values.T if many else values)
        loss=np.sum(out[:,:,2]*out[:,:,3],axis=1)/np.sum(out[:,:,3],axis=1)
        return loss if many else float(loss[0])

    def close(self):
        for obj in [self.shader,self.source,self.params,self.output]:obj.release()


class CoreTransportProbe(HarmonyCoreRenderer):
    """原生释放配合同一组共同标量；不读取旧运动场或路径样本。"""
    compute_source=COMPUTE.replace('void main(){',MOTION+DECODE+r'''
uniform vec4 motion_a,motion_b,motion_c;
uniform float motion_progress,motion_period,transport_gain,life_gain;
void main(){''',1).replace(
        'p.radius_scale.x=mix',
        'p.color.rgb=decode(p.color.rgb);p.radius_scale.x=mix').replace(
        'p.origin_life.z=max(mix(lifetime.x,lifetime.y,randomIndex(i+8u)),.001);',r'''
        float born=clamp(motion_progress,0.,1.);
        float cap=.865+.115*randomIndex(i+9u)-born;
        float lifeScale=mix(.680886527,.968657944,smoothstep(.12,.374711007,born));
        float borrowed=(.10+.22*pow(-log(max(randomIndex(i+8u),.004)),.85)+.20*born)*lifeScale;
        borrowed=max(min(borrowed,cap),.11);
        p.origin_life.z=mix(max(mix(lifetime.x,lifetime.y,randomIndex(i+8u)),.001),
                           borrowed*motion_period,life_gain);''').replace(
        'p.position_age.xy=p.origin_life.xy+shift+flow*turbulence.z*t;',r'''
        vec2 forward=normalize(wind),across=vec2(-forward.y,forward.x);
        float span=min(emission_size.x,emission_size.y);
        vec2 src=(p.origin_life.xy-emission_origin-emission_size*.5)/span;
        vec2 origin=vec2(dot(src,across),-dot(src,forward));
        vec2 delta=motion(origin,t/motion_period,motion_progress,motion_a,motion_b,motion_c);
        vec2 analytic=(across*delta.x-forward*delta.y)*span;
        p.position_age.xy=p.origin_life.xy+mix(shift+flow*turbulence.z*t,analytic,transport_gain);''')
    mask_source=MASK_FRAGMENT.replace('void main(){',DECODE+'void main(){',1).replace(
        'c.rgb*=c.a;','c.rgb=decode(c.rgb)*c.a;')
    composite_source=COMPOSITE_FRAGMENT.replace('void main(){',DECODE+'void main(){',1).replace(
        'texture(background,uv).rgb*(1.-dim)','decode(texture(background,uv).rgb)*(1.-dim)').replace('result=c;',ENCODE)

    def __init__(self,*args,parameters,period,start,transport_gain=1.,life_gain=0.,**kwargs):
        self.period=period;self.start=start
        super().__init__(*args,**kwargs)
        packed=np.zeros(12,np.float32);packed[:len(parameters)]=parameters
        for key,value in zip(['motion_a','motion_b','motion_c'],packed.reshape(3,4)):
            self.compute[key]=tuple(map(float,value))
        self.compute['motion_period']=period
        self.compute['transport_gain']=transport_gain;self.compute['life_gain']=life_gain

    def draw_mask(self,seconds):
        self.compute['motion_progress']=(seconds-self.start)/self.period
        super().draw_mask(seconds)


def render_core_comparison(ctx,parameters,out,scenes,inputs):
    fitted=json.loads(inputs.read_text('utf-8'))
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    report=dict(visual_acceptance=False,renderer=ctx.info['GL_RENDERER'],scenes={})
    for name in scenes:
        ref=RefFrames(name);m=ref.meta;fit=fitted[name]['candidates'][0]
        period=2./(DEFAULT.rate*fit['clock_slope'])
        start=-fit['clock_intercept']*period-2./DEFAULT.rate*.075
        stop=max(period+start,period)+.05
        config=replace(DEFAULT,count=40000,native_scale=fit['scale'],stop_time=stop)
        variants=[]
        for label,gain,life in [('原生运动／寿命',0.,0.),('共同解析运动',1.,0.),('共同运动／独立寿命',1.,1.)]:
            r=CoreTransportProbe(name,ctx=ctx,config=config,parameters=parameters,period=period,start=start,
                                 random_value=fit['random'],wind_x=DEFAULT.wind_y/math.tan(math.radians(m['direction'])),
                                 transport_gain=gain,life_gain=life)
            variants.append((label,r))
        x,y,x1,y1=m['rect'];fw,fh=m['frame']
        box=(max(0,x-55),max(0,y-110),min(fw,x1+55),min(fh,y1+45))
        rows=[];checks=[]
        for p in [.18,.30,.42,.54,.66,.78]:
            frames=[ref.nearest(p)[1]]
            for label,r in variants:
                frames.append(r.render(max(0.,period*p+start)/stop))
                state=r.diagnostics();assert state['finite'],(name,label,p)
                checks.append(dict(variant=label,progress=p,**state))
            tiles=[]
            for label,frame in zip(['华为原片']+[v[0] for v in variants],frames):
                im=Image.fromarray(frame).crop(box);im.thumbnail((300,375))
                tile=Image.new('RGB',(300,im.height+28),'#10151d');tile.paste(im,(0,28))
                ImageDraw.Draw(tile).text((3,2),f'{label} · {p:.2f}',font=font,fill='white')
                if p>m['reference'].get('valid_progress',1.):
                    ImageDraw.Draw(tile).text((4,32),'原片截断，不参与验收',font=font,fill='#ff9090')
                tiles.append(tile)
            row=Image.new('RGB',(1200,max(t.height for t in tiles)),'#10151d')
            for col,tile in enumerate(tiles):row.paste(tile,(col*300,0))
            rows.append(row)
        for half,selected in [('early',rows[:3]),('late',rows[3:])]:
            sheet=Image.new('RGB',(1200,sum(row.height for row in selected)),'#10151d');yy=0
            for row in selected:sheet.paste(row,(0,yy));yy+=row.height
            sheet.save(out/f'core-{name}-{half}.jpg',quality=94)
        report['scenes'][name]=dict(input=fit,period=period,start=start,states=checks)
        (out/'core-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        for _,r in variants:r.close()
        print(name,'原生消蚀和共同运动对照完成',flush=True)


def render_comparison(ctx,parameters,out,scenes):
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    report={}
    for name in scenes:
        r=SparseRenderer(name,ctx=ctx,quality=1)
        ref=RefFrames(name);rot=basis(r.direction)
        rows=[];metrics=[]
        x,y,x1,y1=r.meta['rect']
        box=(max(0,x-55),max(0,y-110),min(r.w,x1+55),min(r.h,y1+45))
        for p in [.18,.30,.42,.54,.66,.78]:
            original=r.render(p)
            state=np.frombuffer(r.state.read(),np.float32).reshape(-1,8).copy()
            data=np.zeros((r.n,8),np.float32)
            data[:,:2]=((r.base[:,:2]-[r.cw*.5,r.ch*.5])/r.span)@rot
            data[:,2]=np.maximum(0.,r.step/240.-r.base[:,2]);data[:,3]=r.step/240.;data[:,6]=1
            objective=Objective(ctx,data)
            prediction=objective.predict(parameters)[0,:,:2]
            objective.close()
            modified=state.copy()
            modified[:,:2]=r.base[:,:2]+prediction@rot.T*r.span
            modified[:,2:]=0
            r.state.write(modified.tobytes())
            candidate=r.render(r.step/240.)
            r.state.write(state.tobytes())
            frames=[ref.nearest(p)[1],original,candidate]
            tiles=[]
            for label,frame in zip(['华为原片','旧版同一材质／释放','仅替换共同解析运动'],frames):
                im=Image.fromarray(frame).crop(box);im.thumbnail((320,385))
                tile=Image.new('RGB',(320,im.height+28),'#10151d');tile.paste(im,(0,28))
                ImageDraw.Draw(tile).text((4,2),f'{label} · {p:.2f}',font=font,fill='white')
                tiles.append(tile)
            row=Image.new('RGB',(960,max(t.height for t in tiles)),'#10151d')
            for col,tile in enumerate(tiles):row.paste(tile,(col*320,0))
            rows.append(row)
            visible=(data[:,2]>.02)&(data[:,2]<r.base[:,6]*.9)
            error=np.linalg.norm(modified[visible,:2]-state[visible,:2],axis=1)
            metrics.append(dict(progress=p,position_rmse=float(np.sqrt(np.mean(error**2))),
                                position_median=float(np.median(error))))
        for half,selected in [('early',rows[:3]),('late',rows[3:])]:
            sheet=Image.new('RGB',(960,sum(row.height for row in selected)),'#10151d');yy=0
            for row in selected:sheet.paste(row,(0,yy));yy+=row.height
            sheet.save(out/f'{name}-{half}.jpg',quality=94)
        report[name]=metrics
        r.close();print(name,'路径替换对照完成',flush=True)
    (out/'render-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--iterations',type=int,default=180)
    ap.add_argument('--fit-seed',type=int,default=104)
    ap.add_argument('--review',action='store_true')
    ap.add_argument('--core-inputs',type=Path,help='以已有噪声输入诊断原生释放；仍不发布这些输入')
    ap.add_argument('--scenes',nargs='+',default=['ironman','kobe','reference-0926-5','reference-0926-7'])
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430)
    if args.review:
        fitted=json.loads((args.out/'fit.json').read_text('utf-8'))
        if args.core_inputs:render_core_comparison(ctx,fitted['parameters'],args.out,args.scenes,args.core_inputs)
        else:render_comparison(ctx,fitted['parameters'],args.out,args.scenes)
        ctx.release();return
    cache=args.out/'baseline-path-samples.npz'
    if cache.exists():
        samples=np.load(cache)['samples'];info=json.loads((args.out/'sample-source.json').read_text('utf-8'))
        if info['baseline_fingerprint']!=model_fingerprint():raise ValueError('基准已变化，不能复用路径')
    else:samples,info=capture(ctx,args.out)
    # 按粒子身份分组，保留独立粒子而非同一路径的相邻帧用于验证。
    train=samples[(samples[:,7].astype(int)%3)!=0][::2]
    holdout=samples[(samples[:,7].astype(int)%3)==0]
    objective=Objective(ctx,train)
    initial=[1.56,0.,0.,0.,.117,0.,.50,0.,0.,1.462,0.]
    initial_loss=objective(initial)
    count=0;start=time.perf_counter()
    def callback(x,convergence):
        nonlocal count
        count+=1
        if count%20==0:print('代数',count,'训练位置 RMSE',round(math.sqrt(objective(x))*info['span'],2),'像素',flush=True)
    result=differential_evolution(objective,BOUNDS,maxiter=args.iterations,popsize=12,
                                  seed=args.fit_seed,vectorized=True,updating='deferred',
                                  polish=False,callback=callback,tol=1e-5,x0=initial)
    # 单精度 GLSL 不适合有限差分梯度；用显式步长的无导数局部收敛。
    local=minimize(objective,result.x,method='Nelder-Mead',bounds=BOUNDS,
                   options=dict(maxiter=900,xatol=1e-5,fatol=1e-8))
    x=local.x if local.fun<result.fun else result.x
    train_loss=objective(x);objective.close()
    validation=Objective(ctx,holdout)
    validation_loss=validation(x);validation.close()
    record=dict(parameters=list(map(float,x)),named=dict(zip(PARAMETER_NAMES,map(float,x))),
                initial_rmse=math.sqrt(initial_loss)*info['span'],
                train_rmse=math.sqrt(train_loss)*info['span'],
                holdout_rmse=math.sqrt(validation_loss)*info['span'],
                train_samples=len(train),holdout_samples=len(holdout),
                elapsed_seconds=time.perf_counter()-start,source=info,
                visual_acceptance=False,
                limitations='只衡量对旧基准的表达能力；原片、不同种子和其余场景仍需视觉验收')
    (args.out/'fit.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(record,ensure_ascii=False),flush=True)
    ctx.release()


if __name__=='__main__':main()
