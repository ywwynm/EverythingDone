"""在修正剥离分布后微调共同场，完整画面损失与新反例共同约束。"""
from pathlib import Path
import ast,sys,json,copy,time,argparse
import numpy as np,moderngl
from scipy.optimize import minimize
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from export_videos import Reference,load_meta
from frame_difference import blur
from probe_device_filaments import OUT
from verify_release_filaments import ridge_contrast
from filament_lifetime import filament_energy
from PIL import Image
BASE=HERE/'archive/before-device-filament-origins'
S={n.targets[0].id:ast.literal_eval(n.value) for n in ast.parse((BASE/'renderer.py').read_text('utf-8')).body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']}
BASE_RELEASE=np.fromfile(BASE/'shared/common-release.f32','<f4').reshape(96,96)
BASE_FLOW=np.fromfile(BASE/'shared/common-flow.f16','<f2').astype('float32').reshape(48,64,64,2)
BASE_RULES={line.split('=',1)[0]:float(line.split('=',1)[1]) for line in (BASE/'shared/rules.properties').read_text('utf-8').splitlines() if '=' in line and not line.startswith('#')}
INITIAL=dict(release_delta=[0.]*25,flow_delta=[0.]*50,guide=1.,peel=1.2615089,life_early=BASE_RULES['life_early_gain'],life_late=BASE_RULES['life_gain'],size=1.,light=.2567657)

def configure(config):
    model.RULES.update(BASE_RULES)
    model.RULES['guide_gain']=BASE_RULES['guide_gain']*config['guide']
    model.RULES['life_early_gain']=config['life_early'];model.RULES['life_gain']=config['life_late']
    for name in S:setattr(renderer,name,S[name])
    renderer.COMPUTE=renderer.COMPUTE.replace('vec2 peel=-m.physical.xy;', '''// 剥离响应与出生时间独立；保持平均强度，避免同步材料形成窄密度峰。
    uint peel_seed=floatBitsToUint(m.random.x)^floatBitsToUint(m.random.y)^0xa54ff53au;
    float peel_response=transport_unit(peel_seed);
    vec2 peel=-m.physical.xy;''')
    renderer.COMPUTE=renderer.COMPUTE.replace('(.88+.24*m.random.w)','(.15+1.70*peel_response)').replace('peel*span*1.2615089',f"peel*span*{config['peel']:.7f}")
    renderer.VERTEX=renderer.VERTEX.replace('vec2 vertex=move+',f"scale*=mix(1.,{config['size']:.7f},smoothstep(.0,.06,age));\n    vec2 vertex=move+")
    renderer.FRAGMENT=renderer.FRAGMENT.replace('0.2567657*optical_loosen',f"{config['light']:.7f}*optical_loosen")
    yy,xx=np.mgrid[:96,:96];u=-.45+(xx+.5)/96*1.9;v=-.45+(yy+.5)/96*1.9
    release=BASE_RELEASE.copy()
    tt,fy,fx=np.mgrid[:48,:64,:64];fu=-.45+(fx+.5)/64*1.9;fv=-.45+(fy+.5)/64*1.9;t=(tt+.5)/48
    env=model.smooth((t-.08)/.25)*(1-model.smooth((t-.78)/.22));flow=BASE_FLOW.copy()
    for iy,cy in enumerate(np.linspace(-.12,1.12,5)):
        for ix,cx in enumerate(np.linspace(-.12,1.12,5)):
            k=iy*5+ix;release+=config['release_delta'][k]*np.exp(-((u-cx)**2+(v-cy)**2)/.20**2)
            flow+=np.exp(-((fu-cx)**2+(fv-cy)**2)/.22**2)[...,None]*env[...,None]*np.array(config['flow_delta'][2*k:2*k+2])
    model.RELEASE=np.clip(release,.001,.84).astype('float32');renderer.guidance=lambda:flow

def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',choices=['global','release','flow'],default='global');a=p.parse_args()
    source=json.loads((OUT/'candidate-config.json').read_text('utf-8')) if (OUT/'candidate-config.json').exists() else copy.deepcopy(INITIAL)
    times=list(np.arange(8,53,4)/60);ref=Reference(load_meta('ironman'))
    targets=[[blur(ref.at(t),s)[150:930,35:685] for s in [2,6,12]] for t in times]
    background=np.array(Image.open(HERE/'assets/ironman/background.png').convert('RGB'))
    ctx=moderngl.create_standalone_context(require=430);history=[];started=time.monotonic();best=[1e9,None]
    def evaluate(config):
        configure(config);r=renderer.Renderer('ironman',ctx=ctx);errors=[];old_energy=0.
        for t,target in zip(times,targets):
            im=r.render(t);errors.append(sum(w*abs(blur(im,s)[150:930,35:685]-v).mean() for s,w,v in zip([2,6,12],[.3,.5,.2],target)))
            if abs(t-2/3)<1e-5:old_energy=filament_energy(im,background)
        r.close()
        # 独立白底反例作为约束，不能用更低照片误差换回细缕。
        r=renderer.Renderer(str(OUT/'input-3'),ctx=ctx);ridge=ridge_contrast(r.render(.24,diagnostic=2),3);r.close()
        loss=float(np.mean(errors)+.30*max(ridge-2.5,0)+.80*max(old_energy/9000.-1.,0));history.append(dict(loss=loss,ridge=ridge,old_energy=old_energy,config=copy.deepcopy(config)))
        if loss<best[0]:
            best[:]=[loss,copy.deepcopy(config)]
            (OUT/'candidate-config.json').write_text(json.dumps(config,indent=2),'utf-8')
            print(a.stage,len(history),round(loss,5),'细线',round(ridge,3),'旧反例',round(old_energy),'秒',round(time.monotonic()-started),flush=True)
        return loss
    evaluate(source)
    if a.stage=='global':
        keys=['guide','peel','life_early','life_late','size','light']
        def f(x):
            c=copy.deepcopy(source);c.update(dict(zip(keys,map(float,x))));return evaluate(c)
        minimize(f,[source[k] for k in keys],method='Powell',bounds=[(.93,1.07),(1.14,1.42),(.64,.72),(.92,1.02),(.95,1.12),(.24,.50)],options=dict(maxiter=2,maxfev=105,xtol=.012,ftol=.0008))
    else:
        key='release_delta' if a.stage=='release' else 'flow_delta';step=.018 if a.stage=='release' else .10
        values=source[key].copy();current=best[0]
        for i in range(len(values)):
            selected=values.copy();lowest=current
            for sign in [-1,1]:
                test=values.copy();test[i]+=sign*step;c=copy.deepcopy(source);c[key]=test;loss=evaluate(c)
                if loss<lowest-.0004:lowest=loss;selected=test
            values=selected;current=lowest
    (OUT/f'{a.stage}-search.json').write_text(json.dumps(history,indent=2),'utf-8');ctx.release();print('阶段完成',a.stage,best[0],flush=True)
if __name__=='__main__':main()
