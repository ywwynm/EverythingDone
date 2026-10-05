"""等权联合校准一份全局规则；留出视频 2、3 和所有验收相位。"""
from pathlib import Path
import argparse, dataclasses, json, sys, time
import cv2
import numpy as np
import moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from front_transport import FrontRenderer,FrontRules,ReleaseSite
from export_videos import Reference,load_meta
from reference_observations_0926 import OBSERVED_SITES
OUT=HERE/'analysis/new-references-0926'
TRAIN=[f'reference-0926-{i}' for i in [1,4,5,6,7]]+['spiderman-original','ironman','thanos','kobe']
PHASES=[.18,.36,.54,.70]


def main():
    p=argparse.ArgumentParser();p.add_argument('--trials',type=int,default=32)
    p.add_argument('--stage',choices=['front','motion','optics','deformation','edge','compositing'],default='front');p.add_argument('--rules-json');a=p.parse_args()
    initial_rules=json.loads(Path(a.rules_json).read_text('utf-8')) if a.rules_json else dataclasses.asdict(FrontRules())
    ctx=moderngl.create_standalone_context(require=430);cases=[]
    for name in TRAIN:
        m=load_meta(name);ref=Reference(m);x,y,x1,y1=m['rect'];span=min(x1-x,y1-y)
        margin=round(span*.08);rect=(max(0,x-margin),max(0,y-margin),min(m['frame'][0],x1+margin),min(m['frame'][1],y1+margin))
        def down(frame,rect=rect):
            xx,yy,xx1,yy1=rect
            return cv2.resize(frame[yy:yy1,xx:xx1],(128,128),interpolation=cv2.INTER_AREA).astype('float32')
        truth=[down(ref.at(t)) for t in PHASES]
        sites=[ReleaseSite(*q) for q in OBSERVED_SITES[name]] if name in OBSERVED_SITES else None
        r=FrontRenderer(name,sites=sites,ctx=ctx,quality=1,cell_px=2.1,rules=FrontRules(**initial_rules))
        cases.append((name,r,down,truth))
    if a.stage=='compositing':
        keys=['coverage','lighting','optical_thickness','life']
        limits=np.array([[1.,1.7],[.25,1.5],[.65,1.8],[.9,1.5]])
    elif a.stage=='edge':
        keys=['duration','exponent','interior_exponent','rate_variation','edge_transport']
        limits=np.array([[.7,2.2],[.6,1.8],[.7,2.],[.30,1.0],[0,3.]])
    elif a.stage=='front':
        keys=['duration','exponent','interior_exponent','rate_variation','anisotropy','interior']
        limits=np.array([[.65,1.30],[.40,1.3],[.70,1.6],[.20,.70],[0,.75],[0,.5]])
    elif a.stage=='deformation':
        keys=['deformation_clock','coverage','lighting'];limits=np.array([[0,1.],[.9,1.4],[.4,1.1]])
    elif a.stage=='optics':
        keys=['dissipation','dissipation_speed','coverage','lighting'];limits=np.array([[0,.85],[.25,.8],[.9,1.55],[.3,1.2]])
    else:
        keys=['curl','carry','template','life','coverage'];limits=np.array([[-.75,.75],[-.30,.55],[0,.70],[.75,1.85],[.9,1.55]])
    output=OUT/f'{a.stage}-joint-fit.json'
    history=[];best=None
    def evaluate(v):
        nonlocal best
        rules=FrontRules(**(initial_rules|dict(zip(keys,map(float,v)))));losses=[];start=time.perf_counter()
        for name,r,down,truth in cases:
            r.rules=rules
            r.compute['front_curl']=rules.curl;r.compute['front_carry']=rules.carry;r.compute['front_template']=rules.template
            r.compute['deformation_clock']=rules.deformation_clock
            r.program['cloud_coverage']=rules.coverage
            r.program['cloud_thickness']=rules.optical_thickness
            r.program['light_gain']=r.settings['light_gain']*rules.lighting
            for program in [r.program,r.pressure_grid.splat]:
                program['cloud_dissipation']=rules.dissipation;program['cloud_speed']=rules.dissipation_speed
            # 原纹理、几何、粒子 ID 不变，只更新唯一的全局规则所产生的材料状态。
            from PIL import Image
            fg=np.array(Image.open(r.directory/'foreground.png').convert('RGBA'))
            built=r.material_builder(r.cw,r.ch,fg,r.direction,r.meta['seed'],2.1)
            assert len(built['base'])==r.n
            r.base=built['base'];r.material.write(r.base.tobytes());r.peel_compression.write(built['peel_compression'].tobytes());r.reset()
            values=[]
            for t,target in zip(PHASES,truth):
                im=down(r.render(t));error=np.abs(im-target)
                structure=np.abs(cv2.GaussianBlur(im,(0,0),1.25)-cv2.GaussianBlur(target,(0,0),1.25))
                values.append(float(.35*np.minimum(error,90).mean()+.65*np.minimum(structure,90).mean()))
            losses.append(float(np.mean(values)))
        score=float(np.mean(losses))
        if history:
            baseline=history[0]['losses']
            score+=sum(max(0.,losses[TRAIN.index(n)]-baseline[n]*1.01)*3 for n in ['ironman','thanos','kobe'])
        record=dict(index=len(history),score=score,rules=dataclasses.asdict(rules),losses=dict(zip(TRAIN,losses)))
        history.append(record)
        if best is None or score<best['score']:
            best=record;print('改善',record['index'],round(score,4),{k:round(getattr(rules,k),4) for k in keys},flush=True)
            output.write_text(json.dumps(dict(training_scenes=TRAIN,training_phases=PHASES,
                withheld_scenes=['reference-0926-2','reference-0926-3'],best=best,history=history),ensure_ascii=False,indent=2),'utf-8')
        elif len(history)%5==0:print('已比较',len(history),'组全局规则；当前最小误差',round(best['score'],4),flush=True)
        return score
    evaluate([initial_rules[k] for k in keys])
    rng=np.random.default_rng(926)
    for _ in range(a.trials):evaluate(limits[:,0]+rng.random(len(keys))*(limits[:,1]-limits[:,0]))
    for step in [.15,.07,.03]:
        for j in range(len(keys)):
            base=np.array([best['rules'][k] for k in keys])
            for sign in [-1,1]:
                v=base.copy();v[j]=np.clip(v[j]+sign*step*(limits[j,1]-limits[j,0]),*limits[j]);evaluate(v)
    output.write_text(json.dumps(dict(training_scenes=TRAIN,training_phases=PHASES,
        withheld_scenes=['reference-0926-2','reference-0926-3'],best=best,history=history),ensure_ascii=False,indent=2),'utf-8')
    for _,r,_,_ in cases:r.close()
    print('联合校准完成',best['score'],flush=True)


if __name__=='__main__':main()
