"""历史低速补偿试验，判据已被视觉复核否决；不作为当前修复验收。

当前使用 measure_marked_motion.py 和 summarize_flow_regression.py，结合重点慢放。
本文件依赖当时的着色器结构，保留用于追溯首个候选的错误判断。
"""
from pathlib import Path
import argparse, json, sys, shutil
import numpy as np
import moderngl
from PIL import Image

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import renderer
from unified_model import model_fingerprint

ROOT = HERE / 'analysis/motion-continuity'
CASES = [('ironman',0,122),('attachment',1,65),('color',0,90),
         ('kobe',1,180),('thanos',7,270),('language',23,315)]

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('tag');ap.add_argument('--assert-fixed',action='store_true')
    ap.add_argument('--projection-off',action='store_true')
    ap.add_argument('--baseline-shader',action='store_true')
    ap.add_argument('--limit',type=int,default=6)
    ap.add_argument('--reuse-traces',action='store_true')
    a=ap.parse_args();out=ROOT/a.tag;out.mkdir(parents=True,exist_ok=True)
    if a.baseline_shader:
        begin=renderer.COMPUTE.index('    // 观测场的静止背景不是固体边界。')
        end=renderer.COMPUTE.index('    float response=',begin)
        renderer.COMPUTE=renderer.COMPUTE[:begin]+renderer.COMPUTE[end:]
    # 固定身份记录截至当前步曾遇到的最低目标速度，排除出生瞬间和死后混淆。
    renderer.COMPUTE=renderer.COMPUTE.replace('    target+=wind*max(0.-dot(target,wind),0.);',
        '    target+=wind*max(0.-dot(target,wind),0.);\n    state[i].pos.w=min(age<=dt?1e6:state[i].pos.w,length(target)/span);')
    renderer.COMPUTE=renderer.COMPUTE.replace('    float response=',
        '    state[i].vel.w=length(guide)/span;\n    float response=')
    if a.projection_off:
        renderer.COMPUTE=renderer.COMPUTE.replace('target+=wind*max(0.-dot(target,wind),0.);','')
    ctx=moderngl.create_standalone_context(require=430);reports=[]
    for name,seed,angle in CASES[:a.limit]:
        key=f'{name}-s{seed}-d{angle}';r=renderer.Renderer(name,direction=angle,seed=seed,ctx=ctx,quality=1)
        file=out/f'{key}.npy'
        if a.reuse_traces:
            prior=json.loads((out/'report.json').read_text('utf-8'))
            assert prior['model_hash']==model_fingerprint()
            trace=np.load(file,mmap_mode='r')
        else:
            trace=np.lib.format.open_memmap(file,mode='w+',dtype='float32',shape=(121,r.n,8))
            for i in range(121):
                r.seek(i/120);trace[i]=np.frombuffer(r.state.read(),np.float32).reshape(r.n,8)
            trace.flush()
        times=np.arange(121,dtype='float32')/120
        age=times[:,None]-r.base[None,:,2];life=r.base[None,:,6]
        fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
        xy=np.clip(r.base[:,:2].astype(int),[0,0],[r.cw-1,r.ch-1]);alpha=fg[xy[:,1],xy[:,0],3]/255
        fade_start=np.maximum(life-.075,life*.55)
        fade_t=np.clip((age-fade_start)/(life-fade_start),0,1)
        fade=(1-fade_t*fade_t*(3-2*fade_t))*alpha[None,:]
        visible=(age>.12)&(fade>.15)
        speed=np.linalg.norm(trace[:,:,4:6],axis=2)/r.span
        displacement=np.cumsum(np.linalg.norm(np.diff(trace[:,:,:2],axis=0,prepend=trace[0:1,:,:2]),axis=2),axis=0)/r.span
        stalled=visible&(speed<.015)
        tiny=visible&(age>.18)&(displacement<.012)
        world=trace[:,:,:2]+np.array(r.meta['rect'][:2])
        outside=visible&((world[:,:,0]<0)|(world[:,:,0]>=r.w)|(world[:,:,1]<0)|(world[:,:,1]>=r.h))
        guide_speed=trace[:,:,7]
        normal=visible&(speed>.16)
        info=dict(scene=name,seed=seed,angle=angle,n=r.n,visible_pairs=int(visible.sum()),
                  stalled_pairs=int(stalled.sum()),stalled_fraction=float(stalled.sum()/max(visible.sum(),1)),
                  tiny_travel_pairs=int(tiny.sum()),outside_visible_pairs=int(outside.sum()),
                  guide_speed_at_stalls_quantiles=np.quantile(guide_speed[stalled],[0,.5,.9,.99]).tolist() if stalled.any() else [],
                  minimum_wind_velocity=float((trace[:,:,4:6]@np.array(r.wind))[visible].min()))
        if a.tag!='baseline' and (ROOT/'baseline'/f'{key}.npy').exists():
            old=np.load(ROOT/'baseline'/f'{key}.npy',mmap_mode='r')
            oldspeed=np.linalg.norm(old[:,:,4:6],axis=2)/r.span
            # 所有已释放阶段均保持正常速度的材料，修复应完全不触碰。
            healthy=visible&(old[:,:,3]>=.105)
            info['healthy_material_frame_pairs']=int(healthy.sum())
            info['healthy_position_max_error_px']=float(np.max(np.abs(trace[:,:,:2]-old[:,:,:2])[healthy])) if healthy.any() else None
            info['normal_pair_fraction_unchanged']=float(np.mean(np.max(np.abs(trace[:,:,:2]-old[:,:,:2]),axis=2)[normal]<.001))
        # 保留少量完整画面，便于相同相位并排审查。
        if not a.reuse_traces:
            for t in [.35,.55,.70,.84]:
                Image.fromarray(r.render(t)).save(out/f'{key}-{round(t*100):02d}.png')
        end=r.render(1.);background=np.asarray(Image.open(r.directory/'background.png').convert('RGB'))
        info['final_background_mae']=float(np.abs(end.astype(float)-background).mean())
        reports.append(info);print(json.dumps(info,ensure_ascii=False),flush=True)
        r.close();del trace
    ctx.release()
    data=dict(model_hash=model_fingerprint(),variant='baseline_without_continuation' if a.baseline_shader else 'production',cases=reports,scope='六组固定输入，真实 GPU 状态；包含淡出末段，历史 226 组标注不重跑')
    (out/'report.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    if a.assert_fixed:
        for c in reports:
            assert c['stalled_fraction']<.001,c
            assert c['tiny_travel_pairs']==0,c
            assert c['minimum_wind_velocity']>-.02,c
            assert c['healthy_position_max_error_px'] in [None,0.],c
            assert c['final_background_mae']<.75,c

if __name__=='__main__':main()
