"""跟踪真实 GPU 的固定材料编号，区分基础积分与最终位置修正的连续运动。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl,cv2
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison


def trajectory_reader(r):
    source=r.vertex_source.replace('int parent_instance=material_pass==1?gl_InstanceID/10:gl_InstanceID;', 'int parent_instance=gl_InstanceID;')
    source=source.replace('int child=material_pass==1?gl_InstanceID%10:0;', 'int child=0;')
    source=source.replace('void main(){','out vec4 trace_positions;\nout vec4 trace_life;\nvoid main(){',1)
    source=source.replace('float projected=scale*','trace_positions=vec4(s.pos.xy+s.vel.xy*extrapolate,move);\n    trace_life=vec4(age_out,life_out,surface_out,density_out);\n    float projected=scale*',1)
    program=r.ctx.program(vertex_shader=source,varyings=['trace_positions','trace_life'])
    vao=r.ctx.vertex_array(program,[]);buffer=r.ctx.buffer(reserve=r.n*8*4)
    def read():
        for name in program:
            if isinstance(program[name],moderngl.Uniform) and name in r.program:program[name].value=r.program[name].value
        program['material_pass']=1
        vao.transform(buffer,mode=moderngl.POINTS,vertices=1,instances=r.n)
        return np.frombuffer(buffer.read(),np.float32).reshape(r.n,8).copy()
    return read,lambda:(vao.release(),buffer.release(),program.release())


def fine_trajectory_reader(r,maximum_parents=2048):
    """固定抽取母片及其九份子片，读取细分和尺寸变换之后的实际中心。

    母片路径检查不覆盖子片因尺寸变化产生的额外位移；两类结果分开记录。
    末列是实际细分权重，供可见区间判断，不能当作完整片元透明度。
    """
    stride=max(1,int(np.ceil(r.n/maximum_parents)))
    parents=np.arange(0,r.n,stride,dtype=np.int32)
    source=r.vertex_source.replace('int parent_instance=material_pass==1?gl_InstanceID/10:gl_InstanceID;',f'int parent_instance=(gl_InstanceID/10)*{stride};')
    source=source.replace('void main(){','out vec4 trace_positions;\nout vec4 trace_life;\nvoid main(){',1)
    source=source.replace('vec2 vertex=move+','trace_positions=vec4(s.pos.xy+s.vel.xy*extrapolate,move);\n    trace_life=vec4(age_out,life_out,surface_out,fission_weight);\n    vec2 vertex=move+',1)
    program=r.ctx.program(vertex_shader=source,varyings=['trace_positions','trace_life'])
    vao=r.ctx.vertex_array(program,[]);buffer=r.ctx.buffer(reserve=len(parents)*10*8*4)
    def read():
        for name in program:
            if isinstance(program[name],moderngl.Uniform) and name in r.program:program[name].value=r.program[name].value
        program['material_pass']=1
        vao.transform(buffer,mode=moderngl.POINTS,vertices=1,instances=len(parents)*10)
        return np.frombuffer(buffer.read(),np.float32).reshape(-1,8).copy()
    return read,lambda:(vao.release(),buffer.release(),program.release()),np.repeat(parents,10)


def metrics(trace,phases,wind,span,mask,split_weight=False):
    pos=trace[:,:,:4].reshape(len(phases),-1,2,2)/span
    visible=(trace[:,:,4]>.04)&(trace[:,:,4]<trace[:,:,5]-.055)&(trace[:,:,6]<.6)&mask
    if split_weight:visible&=trace[:,:,7]>.05
    valid=visible[1:]&visible[:-1]
    dt=np.diff(phases)[:,None,None,None];v=np.diff(pos,axis=0)/dt
    a=np.diff(v,axis=0)/dt[:-1]
    common=valid[1:]&valid[:-1]
    result={}
    for k,label in enumerate(['integrated','displayed']):
        speed=np.linalg.norm(v[:,:,k],axis=-1);axial=v[:,:,k]@wind
        reverse=axial<-.05
        turns=(np.sum(v[1:,:,k]*v[:-1,:,k],-1)<0)&(speed[1:]>.1)&(speed[:-1]>.1)
        result[label]=dict(visible_samples=int(valid.sum()),reverse_fraction=float(reverse[valid].mean()),
            speed_quantiles=np.quantile(speed[valid],[.5,.9,.99]).tolist(),
            acceleration_quantiles=np.quantile(np.linalg.norm(a[:,:,k],axis=-1)[common],[.5,.9,.99]).tolist(),
            reversal_events=int((turns&common).sum()))
    # 固定同一批材料，不把新生和渐隐造成的质心变化误认为往返运动。
    from scipy.ndimage import gaussian_filter1d
    interval=(phases>=.30-1e-6)&(phases<=.70+1e-6)
    stable=visible[interval].all(0)
    cross=np.array([-wind[1],wind[0]])
    for k,label in enumerate(['integrated','displayed']):
        paths=pos[interval][:,stable,k]@cross
        if not paths.size:continue
        velocity=gaussian_filter1d(np.diff(paths,axis=0)/np.diff(phases[interval])[:,None],2.,axis=0)
        travel=(np.abs(velocity)*np.diff(phases[interval])[:,None]).sum(0)
        net=np.abs((velocity*np.diff(phases[interval])[:,None]).sum(0))
        signs=np.sign(velocity);signs[np.abs(velocity)<.02]=0
        for i in range(1,len(signs)):signs[i]=np.where(signs[i]==0,signs[i-1],signs[i])
        switches=signs[1:]*signs[:-1]<0
        # 约 50 ms 窗口内发生换向的材料占比，仅诊断同步程度，不作为美感分数。
        window=max(1,round(.05/np.diff(phases).mean()))
        sync=max(float(switches[i:i+window].any(0).mean()) for i in range(len(switches)))
        result[label].update(stable_materials=int(stable.sum()),
            lateral_excess_quantiles=np.quantile(np.maximum(travel-net,0),[.5,.9]).tolist(),
            lateral_turns_mean=float(switches.sum(0).mean()),maximum_synchronized_turn_fraction=sync)
    if split_weight:
        # 完整 0.30～0.70 期间始终可见的子片可能很少；补充较短、互相重叠的区间。
        # 小于 32 个固定材料时只报告样本数，不给出不稳定的比例或分位数。
        for k,label in enumerate(['integrated','displayed']):
            result[label]['short_windows']=[]
            for start,end in [(.20,.45),(.35,.60),(.50,.75)]:
                section=(phases>=start-1e-6)&(phases<=end+1e-6);kept=visible[section].all(0)
                row=dict(start=start,end=end,stable_materials=int(kept.sum()))
                if kept.sum()>=32:
                    lateral=pos[section][:,kept,k]@cross
                    velocity=gaussian_filter1d(np.diff(lateral,axis=0)/np.diff(phases[section])[:,None],2.,axis=0)
                    distance=abs(velocity)*np.diff(phases[section])[:,None]
                    excess=distance.sum(0)-abs((velocity*np.diff(phases[section])[:,None]).sum(0))
                    row['lateral_excess_quantiles']=np.quantile(np.maximum(excess,0),[.5,.9]).tolist()
                result[label]['short_windows'].append(row)
    return result,visible,pos


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','ironman','reference-0926-4','reference-0926-7'])
    ap.add_argument('--label',default='current');ap.add_argument('--root',type=Path,default=HERE)
    ap.add_argument('--weights',type=Path,help='只替换待验收的共同输运权重，其余读取指定配置目录')
    ap.add_argument('--capacity',type=Path,help='仅诊断离线控制，不作为共同模型验收')
    ap.add_argument('--impulse',type=Path,help='共同材料年龄校准实验')
    ap.add_argument('--causal',type=Path,help='按出生位置和材料年龄积分的共同速度实验')
    ap.add_argument('--hz',type=int,default=120);ap.add_argument('--kinematic',action='store_true')
    ap.add_argument('--response',type=float,default=.032);ap.add_argument('--acceleration',type=float,default=18.)
    ap.add_argument('--probe');ap.add_argument('--guide',type=float)
    ap.add_argument('--relative',choices=['birth','steady']);ap.add_argument('--fine',action='store_true')
    ap.add_argument('--carried',type=Path,help='沿基准材料位置积分的静态共同速度场实验')
    ap.add_argument('--spec',help='用 handoff_variants 的研究变体（例如圆点外观候选）代替默认完整链')
    a=ap.parse_args()
    out=HERE/'analysis/match-cache/motion-continuity'/a.label;out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(a.root)
    if a.spec:
        from handoff_variants import build
        cls,kw=build(a.root,a.spec)
    ctx=moderngl.create_standalone_context(require=430);report=json.loads((out/'report.json').read_text('utf-8')) if (out/'report.json').is_file() else {};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    if a.weights:
        from shared_transport import load_transport
        kw['transport_weights']=load_transport(a.weights)
    if a.capacity:
        if a.weights or a.impulse or a.causal or a.kinematic or a.relative or a.probe or a.guide is not None:raise ValueError('离线容量诊断必须固定当前默认模型')
        from continuous_capacity_renderer import renderer
        cls=renderer(a.capacity)
    if a.impulse:
        if a.weights or a.causal or a.kinematic or a.relative or a.probe or a.guide is not None:raise ValueError('材料年龄诊断必须固定当前默认模型')
        from source_impulse import SourceImpulseRenderer,load_impulse
        cls=SourceImpulseRenderer;kw['impulse']=load_impulse(a.impulse)
    if a.causal:
        if a.weights or a.kinematic or a.relative or a.probe or a.guide is not None:raise ValueError('共同速度诊断必须固定当前默认模型')
        from causal_velocity import CausalVelocityRenderer,load
        cls=CausalVelocityRenderer;kw['causal']=load(a.causal)
    if a.kinematic:
        from kinematic_candidate import KinematicRenderer
        cls=KinematicRenderer;kw.update(response=a.response,acceleration=a.acceleration)
    if a.relative:
        from relative_transport_candidate import RelativeRenderer,SteadyRelativeRenderer
        cls=RelativeRenderer if a.relative=='birth' else SteadyRelativeRenderer
    if a.carried:
        if a.capacity or a.impulse or a.causal or a.kinematic or a.relative or a.probe:raise ValueError('静态速度积分诊断不能混用其它研究渲染器')
        from carried_velocity import CarriedVelocityRenderer,load
        cls=CarriedVelocityRenderer;kw['carried_weights']=load(a.carried)
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw)
        if a.probe:
            from probe_motion_material import configure
            configure(r,a.probe)
        if a.guide is not None:r.compute['conditional_motion']=1.+r.layout_change*(a.guide-1.)
        if a.fine:read,close,parents=fine_trajectory_reader(r)
        else:read,close=trajectory_reader(r);parents=np.arange(r.n)
        phases=np.arange(0,.901,1/a.hz);traces=[];frames=[]
        for i,t in enumerate(phases):
            f=r.render(float(t));traces.append(read())
            if i%(a.hz//30)==0:frames.append(f)
        tr=np.array(traces);wind=np.array(r.program['wind'].value)
        fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'));sx=np.clip(r.base[:,0].astype(int),0,r.cw-1);sy=np.clip(r.base[:,1].astype(int),0,r.ch-1)
        mask=(fg[sy,sx,3]>.5)[parents]
        result,visible,pos=metrics(tr,phases,wind,r.span,mask,split_weight=a.fine)
        report[name]=result;print(name,json.dumps(result),flush=True)
        np.savez_compressed(out/f'{name}.npz',phases=phases,trace=tr,source=r.base[parents,:2],span=r.span,wind=wind,mask=mask,rect=r.meta['rect'],fine=a.fine,parents=parents)
        tw=300;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(tw*5,(th+28)*2),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,t in enumerate([.30,.40,.50,.60,.70]):
            _,ref=c.ref.nearest(t);f=frames[round(t*30)]
            for row,(im,lab) in enumerate([(ref,'华为原片'),(f,'当前共同模型')]):
                sheet.paste(Image.fromarray(im).resize((tw,th),Image.Resampling.LANCZOS),(tw*j,(th+28)*row+28));draw.text((tw*j+3,(th+28)*row+3),f'{lab} {t:.2f}',font=font,fill='white')
        sheet.save(out/f'{name}.jpg',quality=94)
        close();r.close();(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
