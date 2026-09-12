"""前沿衔接与局部收束消融；固定整帧、相位与输入，区域只用于诊断。"""
from pathlib import Path
import sys, ast, argparse, json, importlib.util
import numpy as np
from PIL import Image, ImageDraw
import moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import Reference,load_meta,sampled,label,BG
from unified_model import smooth
import unified_model
from scipy.ndimage import gaussian_filter
ORIGINAL_RELEASE=unified_model.release_components
PANEL_WEIGHT=0.
OUT=HERE/'analysis/front-coherence';BASE=HERE/'archive/before-front-coherence'
_spec=importlib.util.spec_from_file_location('front_frozen_model',BASE/'unified_model.py')
unified_model=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(unified_model)
ORIGINAL_RELEASE=unified_model.release_components
TIMES=[.33,.404,.48,.562,.574,.63,.72,.84]

def shaders():
    tree=ast.parse((BASE/'renderer.py').read_text('utf-8'))
    return {n.targets[0].id:ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']}

def candidate(mode):
    if mode.startswith('supply'):
        s=candidate('oriented-arrival-aligned')
        s['COMPUTE']=s['COMPUTE'].replace('peel*span*2.0*','peel*span*2.6*')
        if mode!='supply-curve':s['FRAGMENT']=s['FRAGMENT'].replace('.78*trailing_out','.64*trailing_out')
        if mode=='supply-gentle':s['VERTEX']=s['VERTEX'].replace('surface_out,1.*','surface_out,.9*')
        if mode.startswith('supply-preserve'):
            s['COMPUTE']=s['COMPUTE'].replace('peel*span*2.6*','peel*span*2.2*')
            s['COMPUTE']=s['COMPUTE'].replace('smoothstep(.10,.28,age)*missing','smoothstep(.025,.14,age)*(1.-.65*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18))*(1.-smoothstep(.10,.25,age)))',1)
            s['COMPUTE']=s['COMPUTE'].replace('smoothstep(.10,.28,age)*missing','smoothstep(.04,.16,age)*(1.-.65*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18))*(1.-smoothstep(.10,.25,age)))',1)
            s['VERTEX']=s['VERTEX'].replace('surface_out,1.*','surface_out,.85*')
            if mode.endswith('curl'):s['COMPUTE']=s['COMPUTE'].replace('peel*span*2.2*','peel*span*2.8*')
        return s
    if mode.startswith('oriented-arrival'):
        s=candidate('edge-entrained')
        s['COMPUTE']=s['COMPUTE'].replace('span*1.4*','span*.4*')
        if mode.endswith('soft'):s['COMPUTE']=s['COMPUTE'].replace('peel*span*2.0*','peel*span*1.5*')
        return s
    if mode.startswith('boundary-arrival'):
        s=candidate('natural-edge')
        if mode.endswith('arc'):s['COMPUTE']=s['COMPUTE'].replace('peel*span*1.5*','peel*span*2.1*')
        return s
    if mode.startswith('natural'):
        s=candidate('coherent-dispersion')
        s['COMPUTE']=s['COMPUTE'].replace('span*3.2*','span*.0*' if mode!='natural-edge' else 'span*.4*')
        s['VERTEX']=s['VERTEX'].replace('mix(.70,1.,trailing)','1.')
        if mode=='natural-peel':s['COMPUTE']=s['COMPUTE'].replace('peel*span*1.5*','peel*span*.0*')
        if mode=='natural-stable':s['COMPUTE']=s['COMPUTE'].replace('mix(.018,.44+.06*reach,missing)','mix(.018,.16+.06*reach,missing)')
        return s
    if mode in ['edge-entrained','edge-entrained-wide','edge-entrained-soft']:
        s=candidate('coupled-phase')
        s['COMPUTE']=s['COMPUTE'].replace('target+=peel*span*2.0*', '''vec2 free_edge=min(m.src.xy,card-m.src.xy);
    vec2 free_out=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.)*exp(-free_edge/(span*.13));
    float eject=max(dot(normalize(free_out+vec2(.000001)),normalize(peel+vec2(.000001))),0.);
    peel*=1.-.95*eject*exp(-min(free_edge.x,free_edge.y)/(span*.18));
    target+=peel*span*2.0*''')
        if mode=='edge-entrained-soft':s['COMPUTE']=s['COMPUTE'].replace('peel*span*2.0*','peel*span*1.6*')
        return s
    s=shaders()
    if mode.startswith('coupled'):
        s=candidate('coherent-dispersion')
        s['COMPUTE']=s['COMPUTE'].replace('span*3.2*','span*1.0*')
        s['VERTEX']=s['VERTEX'].replace('mix(.70,1.,trailing)','1.')
        if mode=='coupled-peel':s['COMPUTE']=s['COMPUTE'].replace('peel*span*1.5*','peel*span*2.1*')
        if mode in ['coupled-arc','coupled-balanced','coupled-compact','coupled-phase','coupled-phase-wide']:
            s['COMPUTE']=s['COMPUTE'].replace('span*1.0*','span*1.4*')
            s['COMPUTE']=s['COMPUTE'].replace('peel*span*1.5*','peel*span*2.0*')
        if mode=='coupled-compact':
            s['COMPUTE']=s['COMPUTE'].replace('mix(.12,1.,trailing_edge)','mix(.04,.50,trailing_edge)')
        return s
    if mode=='edge-weak':s['COMPUTE']=s['COMPUTE'].replace('span*3.2*','span*1.0*')
    if mode=='no-dispersion':
        s['COMPUTE']=s['COMPUTE'].replace('1.+.50*(m.random.x-.5)','1.+.0*(m.random.x-.5)').replace('span*.075*smoothstep(.04,.16,age)','span*.0*smoothstep(.04,.16,age)')
    if mode=='coherent-core':
        s['VERTEX']=s['VERTEX'].replace('surface_out=1.-smoothstep', 'source_clock+=.070*smoothstep(.43,.59,source_clock);\n    surface_out=1.-smoothstep')
        s['VERTEX']=s['VERTEX'].replace('mix(.70,1.,trailing)','1.')
    if mode=='clock-global':s['COMPUTE']=s['COMPUTE'].replace('time-m.physical.w','time')
    if mode=='peel-soft':s['COMPUTE']=s['COMPUTE'].replace('peel*span*1.5*','peel*span*.6*')
    if mode=='coherent-dispersion':
        s['COMPUTE']=s['COMPUTE'].replace('1.+.50*(m.random.x-.5)*smoothstep(.025,.14,age)', '1.+.50*(m.random.x-.5)*smoothstep(.10,.28,age)*missing')
        s['COMPUTE']=s['COMPUTE'].replace('span*.075*smoothstep(.04,.16,age)','span*.075*smoothstep(.10,.28,age)*missing')
    return s

def adjusted_release(*args,**kwargs):
    mode=kwargs.pop('mode','coupled')
    core_gain=.06*(1.-.65*PANEL_WEIGHT) if mode=='supply-panel' else .06
    original_mode=mode
    if mode in ['supply-gentle','supply-core-light']:core_gain=.03*(1.-.65*PANEL_WEIGHT)
    if mode=='supply-no-delay':core_gain=0.
    if mode.startswith('supply-preserve'):core_gain=.025*(1.-.65*PANEL_WEIGHT)
    if mode.startswith('supply'):mode='oriented-arrival-aligned'
    if mode.startswith('edge-entrained'):mode='coupled-phase-wide' if mode.endswith('wide') else 'coupled-phase'
    field,offset=ORIGINAL_RELEASE(*args,**kwargs)
    nx,ny,direction,width,height,seed=args
    yy,xx=np.mgrid[:ny,:nx];x=(xx+.5)/nx*width;y=(yy+.5)/ny*height
    span=min(width,height);ex=np.minimum(x,width-x);ey=np.minimum(y,height-y)
    gy,gx=np.gradient(gaussian_filter(field,3),height/ny,width/nx)
    norm=np.maximum(np.hypot(gx,gy),1e-8)
    radius=.17 if mode in ['coupled-arc','coupled-balanced','coupled-compact','coupled-phase','coupled-phase-wide'] else .13
    wx=np.exp(-ex/(span*radius));wy=np.exp(-ey/(span*radius))
    ox=np.where(x<width*.5,-1,1)*wx;oy=np.where(y<height*.5,-1,1)*wy
    outnorm=np.maximum(np.hypot(ox,oy),1e-8)
    terminal=smooth((ox*gx+oy*gy)/(norm*outnorm)/.65)
    corner=wx*wy*terminal
    interior=1-np.exp(-np.minimum(ex,ey)/(span*.10))
    if mode.startswith('oriented-arrival'):
        wind=np.array([np.cos(np.deg2rad(direction)),-np.sin(np.deg2rad(direction))])
        against=np.maximum(smooth(-np.where(x<width*.5,-1,1)*wind[0]/.65)*np.exp(-ex/(span*.14)),smooth(-np.where(y<height*.5,-1,1)*wind[1]/.65)*np.exp(-ey/(span*.14)))
        gain=.17 if mode.endswith('early') else .13
        if original_mode in ['supply-gentle','supply-no-delay']:gain=.08
        if original_mode.startswith('supply-preserve'):gain=.065
        if 'tip' in original_mode:gain=.18
        if mode.endswith('aligned'):
            against*=smooth(((gx*wind[0]+gy*wind[1])/norm-.40)/.50)
        if original_mode.endswith('corner'):against*=np.exp(-np.maximum(ex,ey)/(span*.16))
        if 'tip' in original_mode:against*=np.exp(-np.maximum(ex,ey)/(span*.16))
        delta=core_gain*interior*smooth((field-.40)/.17)-gain*against*smooth((field-.23)/.25)
        return (field+delta).astype('float32'),offset
    if mode.startswith('boundary-arrival'):
        edge=np.exp(-np.minimum(ex,ey)/(span*.16))
        gain=.16 if mode.endswith('early') else .12
        delta=.060*(1-edge)*smooth((field-.40)/.17)-gain*edge*smooth((field-.23)/.25)
        return (field+delta).astype('float32'),offset
    if mode.startswith('natural'):
        delta=.045*interior*smooth((field-.40)/.17)
        return (field+delta).astype('float32'),offset
    corner_gain=.27 if mode=='coupled-arc' else .22 if mode in ['coupled-balanced','coupled-compact','coupled-phase','coupled-phase-wide'] else .13
    core_gain=.045 if mode in ['coupled-arc','coupled-balanced','coupled-compact','coupled-phase','coupled-phase-wide'] else .075
    delta=-corner_gain*corner*smooth((field-.20)/.15)+core_gain*interior*smooth((field-.40)/.17)
    if mode in ['coupled-phase','coupled-phase-wide']:
        if mode=='coupled-phase-wide':delta-=.04*corner*smooth((field-.20)/.15)
        return (field+delta).astype('float32'),offset
    return (field+delta).astype('float32'),(offset+delta).astype('float32')

def render(mode,scene,ctx,direction=None,seed=None):
    global PANEL_WEIGHT
    fg=np.array(Image.open(HERE/'assets'/scene/'foreground.png').convert('RGBA'))
    PANEL_WEIGHT=unified_model.panel_material(fg)[1]
    renderer.materials=unified_model.materials
    for k,v in candidate(mode).items():setattr(renderer,k,v)
    unified_model.release_components=(lambda *args,**kwargs:adjusted_release(*args,mode=mode,**kwargs)) if mode.startswith(('coupled','edge-entrained','natural','boundary-arrival','oriented-arrival','supply')) or mode=='release-geometry' else ORIGINAL_RELEASE
    r=renderer.Renderer(scene,ctx=ctx,direction=direction,seed=seed)
    if mode=='birth-late-core':
        shift=.075*smooth((r.base[:,2]-.43)/.16)
        r.base[:,2]+=shift;r.base[:,7]+=shift
        r.base[:,6]=np.maximum(.11,np.minimum(r.base[:,6],.865+.115*r.base[:,10]-r.base[:,2]));r.material.write(r.base.tobytes())
    frames=[r.render(t) for t in TIMES];r.close();return frames

def contact(rows,file,crop=None):
    for group in range(2):
        indexes=range(group*4,group*4+4);w=300
        frame=rows[0][1][0];l,t,rr,b=crop or (0,0,frame.shape[1],frame.shape[0]);h=round((b-t)*w/(rr-l));rh=h+28
        im=Image.new('RGB',(w*4,rh*len(rows)),BG);draw=ImageDraw.Draw(im)
        for j,(name,images) in enumerate(rows):
            for x,i in enumerate(indexes):
                label(draw,(x*w+5,j*rh+3),f'{name} · {TIMES[i]:.3f}',18)
                im.paste(Image.fromarray(images[i]).crop((l,t,rr,b)).resize((w,h),Image.Resampling.LANCZOS),(x*w,j*rh+28))
        im.save(OUT/f'{file}-{group}.jpg',quality=96)

def main():
    p=argparse.ArgumentParser();p.add_argument('--modes',nargs='+',default=['edge-weak','no-dispersion','coherent-core','clock-global']);p.add_argument('--scenes',nargs='+',default=['ironman']);p.add_argument('--direction',type=float);p.add_argument('--seed',type=int);a=p.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);ctx=moderngl.create_standalone_context(require=430)
    for name in a.scenes:
        meta=load_meta(name)
        if a.direction is not None or a.seed is not None:before=render('baseline',name,ctx,a.direction,a.seed)
        else:
            base=np.load(BASE/(name+'.npy'),mmap_mode='r');before=[sampled(base,t) for t in TIMES]
        if meta.get('reference'):
            ref=Reference(meta);refs=[ref.at(t) for t in TIMES];reference_name='华为参考'
        else:refs=before;reference_name='调整前'
        suffix='' if a.direction is None and a.seed is None else f'-{a.direction}-{a.seed}'
        for mode in a.modes:
            frames=render(mode,name,ctx,a.direction,a.seed);np.save(OUT/f'{name}-{mode}{suffix}.npy',np.stack(frames))
            rows=[(reference_name,refs),('本轮调整前',before),(mode,frames)] if meta.get('reference') else [('本轮调整前',before),(mode,frames)]
            contact(rows,name+'-'+mode+suffix)
            print(name,mode,flush=True)
    ctx.release()

if __name__=='__main__':main()
