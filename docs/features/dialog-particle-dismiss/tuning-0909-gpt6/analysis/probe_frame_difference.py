"""共享参数消融和完整画面误差；候选全部离线，不修改正式版本。"""
from pathlib import Path
import sys,ast,json,argparse,importlib.util
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from frame_difference import OUT,BASE,PHASES,metrics,sheet
from export_videos import Reference,load_meta
BASE_RELEASE=np.fromfile(BASE/'shared/common-release.f32','<f4').reshape(96,96)
S={n.targets[0].id:ast.literal_eval(n.value) for n in ast.parse((BASE/'renderer.py').read_text('utf-8')).body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']}
ORIGINAL_VARIATION=model.variation
ORIGINAL_LOCALITY=model.release_locality
ORIGINAL_GUIDANCE=lambda:np.fromfile(BASE/'shared/common-flow.f16','<f2').astype('float32').reshape(48,64,64,2)
ORIGINAL_CONFIDENCE=lambda:np.fromfile(BASE/'shared/flow-confidence.u8','uint8').reshape(48,64,64)
BASE_RULES={line.split('=',1)[0]:float(line.split('=',1)[1]) for line in (BASE/'shared/rules.properties').read_text('utf-8').splitlines() if '=' in line and not line.startswith('#')}

def configure(config):
    model.RULES.update(BASE_RULES)
    delta=np.load(OUT/'release-calibration.npz')['delta']
    model.RELEASE=np.clip(BASE_RELEASE+delta*config.get('release',0),.001,.80).astype('float32')
    if 'release_grid' in config:
        yy,xx=np.mgrid[:96,:96];u=-.45+(xx+.5)/96*1.9;v=-.45+(yy+.5)/96*1.9
        correction=np.zeros_like(u)
        for iy,cy in enumerate(np.linspace(-.12,1.12,5)):
            for ix,cx in enumerate(np.linspace(-.12,1.12,5)):
                correction+=config['release_grid'][iy*5+ix]*np.exp(-((u-cx)**2+(v-cy)**2)/(.20**2))
        model.RELEASE=np.clip(model.RELEASE+correction,.001,.84).astype('float32')
    model.variation=ORIGINAL_VARIATION;model.release_locality=ORIGINAL_LOCALITY
    renderer.guidance=ORIGINAL_GUIDANCE;renderer.flow_confidence=ORIGINAL_CONFIDENCE
    if 'measured_flow' in config:
        data=np.load(OUT/'remeasured-flow.npz');weight=config['measured_flow']
        field=(ORIGINAL_GUIDANCE()*(1-weight)+data['flow']*weight).astype('float32')
        confidence=np.rint(ORIGINAL_CONFIDENCE().astype(float)*(1-weight)+data['confidence']*255*weight).astype('uint8')
        renderer.guidance=lambda:field;renderer.flow_confidence=lambda:confidence
    if 'flow_grid' in config:
        field=renderer.guidance().copy();tt,yy,xx=np.mgrid[:48,:64,:64]
        u=-.45+(xx+.5)/64*1.9;v=-.45+(yy+.5)/64*1.9;t=(tt+.5)/48
        envelope=model.smooth((t-.08)/.25)*(1-model.smooth((t-.78)/.22))
        coefficients=np.asarray(config['flow_grid']).reshape(25,2)
        slopes=np.asarray(config.get('flow_slope',[0.]*50)).reshape(25,2)
        for iy,cy in enumerate(np.linspace(-.12,1.12,5)):
            for ix,cx in enumerate(np.linspace(-.12,1.12,5)):
                g=np.exp(-((u-cx)**2+(v-cy)**2)/(.22**2))*envelope
                field+=g[...,None]*(coefficients[iy*5+ix]+slopes[iy*5+ix]*(2*t[...,None]-1))
        renderer.guidance=lambda:field
    if 'coordinate' in config:
        def varied(seed):
            v=ORIGINAL_VARIATION(seed).copy();strength=config['coordinate'];mirror=-1 if v[0]<0 else 1
            v[:2]=[mirror*(1+(abs(v[0])-1)*strength),1+(v[1]-1)*strength]
            v[2:8]*=strength;v[12:]*=strength;return v
        model.variation=varied
    if 'locality' in config:model.release_locality=lambda w,h,d,s:ORIGINAL_LOCALITY(w,h,d,s)*config['locality']
    s=dict(S)
    # 全部系数作用于共同材质与输运，不包含人物、截图区域或固定相位选择。
    motion=config.get('motion',{})
    numeric={
        'peel':('peel*span*2.2*',f"peel*span*{motion.get('peel',2.2):.7f}*"),
        'ambient':('*(.12+.50*reach)*carried*wind_gain;',f"*(.12+.50*reach)*carried*wind_gain*{motion.get('ambient',1.):.7f};"),
        'guide':('*(1.08+.12*reach);',f"*(1.08+.12*reach)*{motion.get('guide',1.):.7f};"),
        'independent':('1.+.50*(m.random.x-.5)',f"1.+{motion.get('independent',.50):.7f}*(m.random.x-.5)"),
    }
    for k,(old,new) in numeric.items():
        if k in motion:s['COMPUTE']=s['COMPUTE'].replace(old,new)
    optical=config.get('optical',{})
    if 'surface_delay' in config:s['VERTEX']=s['VERTEX'].replace('surface_out=1.-smoothstep',f"source_clock+={config['surface_delay']:.7f}*(1.-exp(-min(edge_d.x,edge_d.y)/(span*.10)));\n    surface_out=1.-smoothstep")
    if 'size' in optical:s['VERTEX']=s['VERTEX'].replace('vec2 vertex=move+',f"scale*=mix(1.,{optical['size']:.7f},smoothstep(.0,.06,age));\n    vec2 vertex=move+")
    if 'glint' in optical:s['FRAGMENT']=s['FRAGMENT'].replace('.22*pow(facing,12.)',f"{optical['glint']:.7f}*pow(facing,12.)")
    if 'diffuse' in optical:s['FRAGMENT']=s['FRAGMENT'].replace('vec3(.012+glint)',f"vec3({optical['diffuse']:.7f}+glint)")
    if 'base_light' in optical:s['FRAGMENT']=s['FRAGMENT'].replace('.70*optical_loosen*light_gain',f"{optical['base_light']:.7f}*optical_loosen*light_gain")
    if 'young_size' in optical:s['VERTEX']=s['VERTEX'].replace('vec2 vertex=move+',f"scale*=1.+({optical['young_size']:.7f}-1.)*smoothstep(.002,.025,age)*(1.-smoothstep(.10,.25,age));\n    vec2 vertex=move+")
    if 'young_light' in optical:s['FRAGMENT']=s['FRAGMENT'].replace('.32*light_gain*smoothstep',f"{optical['young_light']:.7f}*light_gain*smoothstep")
    if 'old_size' in optical:s['VERTEX']=s['VERTEX'].replace('1.-.36*smoothstep(.12,.36,age)',f"1.-{optical['old_size']:.7f}*smoothstep(.12,.36,age)")
    model.RULES['life_gain']=float(config.get('life',.70))
    model.RULES['cell']=float(config.get('cell',1.85))
    for key,replacements in config.get('shader',{}).items():
        for old,new in replacements:
            assert old in s[key],old
            s[key]=s[key].replace(old,new)
    for key,value in s.items():setattr(renderer,key,value)

def run(tag,config,name='ironman',angle=None,seed=None,full=True):
    configure(config);ctx=moderngl.create_standalone_context(require=430)
    r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
    frames=np.lib.format.open_memmap(OUT/f'{tag}-{name}.npy',mode='w+',dtype='uint8',shape=(61,r.h,r.w,3))
    for i,t in enumerate(PHASES):frames[i]=r.render(t)
    frames.flush();meta=r.meta;r.close();ctx.release()
    ref=Reference(meta);refs=np.stack([ref.at(t) for t in PHASES])
    y,x=np.mgrid[:meta['frame'][1],:meta['frame'][0]];l,t,rr,b=meta['rect'];span=min(rr-l,b-t)
    mask=(x>l-span*.15)&(x<rr+span*.09)&(y>t-span*.10)&(y<b+span*.24)
    errors=metrics(frames,refs,mask);means={k:float(np.mean([e[k] for e in errors][12:49])) for k in errors[0] if k!='phase'}
    result=dict(config=config,errors=errors,means=means)
    (OUT/f'{tag}-{name}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    sheet(frames,refs,f'{tag}-{name}',reference_title='华为参考' if meta['reference'] else '原始截图（无参考动画）')
    print(tag,name,means,flush=True);return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--tag');p.add_argument('--release',type=float,default=1);p.add_argument('--config',type=Path);p.add_argument('--scene',default='ironman');p.add_argument('--angle',type=float);p.add_argument('--seed',type=int);a=p.parse_args()
    config=json.loads(a.config.read_text('utf-8')) if a.config else dict(release=a.release)
    run(a.tag or 'release-'+str(a.release),config,a.scene,a.angle,a.seed)

if __name__=='__main__':main()
