"""提交版与单因素候选对照；所有候选只在本进程替换着色器。"""
from pathlib import Path
import ast, json, sys, shutil, argparse, hashlib
import numpy as np
import moderngl
from PIL import Image, ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from export_videos import Reference, load_meta, label, BG, code_hash
from unified_model import SHARED, model_fingerprint
OUT=HERE/'analysis/surface-transfer';ARCHIVE=HERE/'archive/before-surface-transfer'
TIMES=[.33,.39,.44,.48,.52,.56,.63]

def freeze():
    if ARCHIVE.exists():return
    ARCHIVE.mkdir(parents=True);(ARCHIVE/'shared').mkdir()
    for name in ['renderer.py','unified_model.py','export_videos.py','touch_geometry.py','viewer.py']:
        shutil.copy2(HERE/name,ARCHIVE/name)
    for p in SHARED.iterdir():
        if p.is_file():shutil.copy2(p,ARCHIVE/'shared'/p.name)
    for scene in ['ironman','ironman-up-reference','thanos','kobe','language','color','attachment','attachment-image']:
        for suffix in ['.npy','.json']:
            path=HERE/'cache'/f'{scene}{suffix}'
            if path.exists():shutil.copy2(path,ARCHIVE/path.name)
    (ARCHIVE/'identity.json').write_text(json.dumps(dict(commit='3c2c18b6',model_hash=model_fingerprint(),code_hash=code_hash()),indent=2),'utf-8')

def original():
    tree=ast.parse((ARCHIVE/'renderer.py').read_text('utf-8'))
    return {t.id:ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign)
            for t in n.targets if isinstance(t,ast.Name) and t.id in ['COMPUTE','VERTEX','FRAGMENT']}

def shaders(mode):
    if mode in ['soft-density','soft-density-light']:
        s=shaders('soft-scatter');v,f=s['VERTEX'],s['FRAGMENT']
        v=v.replace('vec2 vertex=move+', '''scale*=1.+.20*(1.-panel_weight)*smoothstep(.010,.050,age)*(1.-smoothstep(.10,.24,age));
    vec2 vertex=move+''')
        if mode=='soft-density-light':
            f=f.replace('float lighting=mix(1.,light_out,light_gain);', '''float lighting=mix(1.,light_out,light_gain);
    lighting*=1.+.32*smoothstep(.006,.045,age)*(1.-smoothstep(.11,.28,age));''')
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode in ['soft-transfer','soft-scatter','soft-facets']:
        # 取消被否定的相干窄窗加色；先检查整帧，不以局部白线作为目标。
        s=shaders('conservative');v,f=s['VERTEX'],s['FRAGMENT']
        start=f.index('float rim=');end=f.index('if(diagnostic==1)',start)
        f=f[:start]+f[end:]
        v=v.replace('mix(.32,.90,trailing)', 'mix(.20,.82,trailing)')
        v=v.replace('mix(.80,.98,panel_weight)', 'mix(.84,.98,panel_weight)')
        v=v.replace('smoothstep(0.,.030,age)', 'smoothstep(.004,.060,age)')
        if mode in ['soft-scatter','soft-facets']:
            f=f.replace('optical_loosen=smoothstep(.018,.18,age)', 'optical_loosen=smoothstep(.005,.095,age)')
            f=f.replace('vec3(.006+glint)', 'vec3(.012+glint)')
        if mode=='soft-facets':
            f=f.replace('float glint=.22*pow(facing,12.);', '''float glint=.42*pow(facing,18.);
    glint*=mix(.45,1.,smoothstep(.25,.8,random_out.y));''')
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode in ['cohort','cohort-bright']:
        s=shaders('conservative');v,f=s['VERTEX'],s['FRAGMENT']
        v=v.replace('flat out float trailing_out;','flat out float trailing_out;\nflat out float cohort_age_out;')
        v=v.replace('source_clock-=.065*trailing;', 'cohort_age_out=time-source_clock;source_clock-=.065*trailing;')
        f=f.replace('flat in float trailing_out;','flat in float trailing_out;\nflat in float cohort_age_out;')
        f=f.replace('(age-.060)/.025','(cohort_age_out-.075)/.024')
        f=f.replace('rim_tint*.55*rim','rim_tint*'+('1.4' if mode=='cohort-bright' else '.85')+'*rim')
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode=='conservative':
        s=shaders('material-aware');v,f=s['VERTEX'],s['FRAGMENT']
        v=v.replace('1.-.78*panel_weight','mix(.32,.90,trailing)*(1.-.60*panel_weight)')
        v=v.replace('mix(1.,.80,smoothstep(.0,.018,age))','mix(1.,mix(.80,.98,panel_weight),smoothstep(.0,.018,age))')
        v=v.replace('shape_out=smoothstep(0.,.030,age);','shape_out=mix(smoothstep(0.,.030,age),loosen,.85*panel_weight);')
        f=f.replace('float glint=.38*pow(facing,24.);','float glint=.22*pow(facing,12.);')
        f=f.replace('mix(1.,.72,smoothstep(.018,.12,age))','mix(1.,.92,smoothstep(.018,.12,age))')
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode=='material-aware':
        s=shaders('balanced');v,f=s['VERTEX'],s['FRAGMENT']
        v=v.replace('float loosen=smoothstep', '''surface_out=mix(time<=m.src.z?1.:0.,surface_out,1.-.78*panel_weight);
    float loosen=smoothstep''')
        start=f.index('vec3 color=linear(src.rgb);');end=f.index('if(diagnostic==1)color=')
        optics=f[start:end].replace('shape_out','optical_loosen')
        optics='float optical_loosen=smoothstep(.018,.18,age);\n    '+optics
        optics=optics.replace('float glint=1.2*pow(facing,24.);','float glint=.38*pow(facing,24.);')
        optics=optics.replace('(age-.060)/.032','(age-.060)/.025')
        optics=optics.replace('color+=vec3(.85)*rim', '''vec3 rim_tint=mix(vec3(1.),src.rgb/max(max(src.r,src.g),max(src.b,.001)),.70*smoothstep(.12,.45,max(max(src.r,src.g),src.b)-min(min(src.r,src.g),src.b)));
    color+=rim_tint*.55*rim''')
        f=f[:start]+optics+f[end:]
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode=='balanced':
        s=shaders('porous')
        s['VERTEX']=s['VERTEX'].replace('source_clock-=.110*trailing','source_clock-=.065*trailing')
        s['FRAGMENT']=s['FRAGMENT'].replace('vec3(.65)*rim','vec3(.85)*rim').replace('(age-.060)/.038','(age-.060)/.032')
        return s
    if mode in ['porous','porous-rim']:
        s=shaders('facet-edge')
        s['VERTEX']=s['VERTEX'].replace('source_clock-=.065*trailing','source_clock-=.110*trailing')
        s['FRAGMENT']=s['FRAGMENT'].replace('alpha*=1.-.65*trailing_out;', '''if(fract(random_out.y*13.731+random_out.z*17.371)<.78*trailing_out)discard;''')
        if mode=='porous-rim':
            s['FRAGMENT']=s['FRAGMENT'].replace('vec3(.65)*rim','vec3(1.1)*rim').replace('(age-.060)/.038','(age-.060)/.025')
        return s
    if mode in ['facet','facet-edge']:
        s=shaders('dense-bright');v,f=s['VERTEX'],s['FRAGMENT']
        f=f.replace('pow(color,vec3(.70))','pow(color,vec3(.86))')
        f=f.replace('float glint=.22*pow(facing,12.);','float glint=1.2*pow(facing,24.);')
        f=f.replace('vec3(.026+glint)','vec3(.006+glint)')
        f=f.replace('mix(1.,.83,smoothstep(.018,.12,age))','mix(1.,.72,smoothstep(.018,.12,age))')
        if mode=='facet-edge':v=v.replace('span*.075','span*.15')
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode in ['dense-fine','dense-bright']:
        s=shaders('selective')
        s['VERTEX']=s['VERTEX'].replace('mix(1.,.68,smoothstep(.0,.018,age))','mix(1.,.80,smoothstep(.0,.018,age))')
        s['FRAGMENT']=s['FRAGMENT'].replace('1.-.38*trailing_out','1.-.65*trailing_out')
        if mode=='dense-bright':
            s['FRAGMENT']=s['FRAGMENT'].replace('vec3(.36)*rim','vec3(.65)*rim')
        return s
    if mode in ['grains','grains-soft']:
        s=shaders('selective');v=s['VERTEX'];f=s['FRAGMENT']
        v=v.replace('vec2 c=corners[gl_VertexID];','''vec2 c=corners[gl_VertexID%6];
    vec2 grain_uv=c;
    int piece=gl_VertexID/6;
    if(material_pass==1)c=(c+vec2(piece%2,piece/2))*.5;''')
        v=v.replace('local_uv=c;','local_uv=grain_uv;')
        v=v.replace('mix(1.,.68,smoothstep(.0,.018,age))','mix(1.,.84,smoothstep(.0,.018,age))')
        v=v.replace('if(material_pass==0)vertex=source;', '''if(material_pass==1){
        vec2 grain_jitter=vec2(hash(m.src.xy+float(piece)*13.1),hash(m.src.yx+float(piece)*27.3))-.5;
        vertex+=grain_jitter*cell*.65*smoothstep(.002,.05,age);
    }
    if(material_pass==0)vertex=source;''')
        f=f.replace('vec3(.36)*rim','vec3(.54)*rim')
        if mode=='grains-soft':f=f.replace('1.-.38*trailing_out','1.-.62*trailing_out')
        return dict(COMPUTE=s['COMPUTE'],VERTEX=v,FRAGMENT=f)
    if mode in ['edge-transfer','selective']:
        s=shaders('fine');c,v,f=s['COMPUTE'],s['VERTEX'],s['FRAGMENT']
        c=c.replace('float spread_age=smoothstep(.012,.09,age)*exp(-age/.32);', '''float trailing_edge=smoothstep(0.,.6,dot(normalize(outward_spread+vec2(.000001)),m.physical.xy));
    float spread_age=smoothstep(.002,.014,age)*exp(-age/.080)*mix(.12,1.,trailing_edge);''')
        c=c.replace('span*.055','span*.075').replace('span*.48*(.2+1.8*m.random.z)','span*3.2*(.05+2.4*m.random.z*m.random.z)')
        c=c.replace('float depth_target=', '''float forward_front=smoothstep(.20,.85,dot(-m.physical.xy,wind));
    target*=1.-.45*forward_front*(1.-smoothstep(.10,.25,age));
    float depth_target=''')
        v=v.replace('surface_out=1.-smoothstep', '''vec2 edge_d=min(m.src.xy,card-m.src.xy);
    vec2 edge_n=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.)*exp(-edge_d/(span*.075));
    float trailing=smoothstep(0.,.6,dot(normalize(edge_n+vec2(.000001)),m.physical.xy))*max(exp(-edge_d.x/(span*.075)),exp(-edge_d.y/(span*.075)));
    source_clock-=.065*trailing;
    surface_out=1.-smoothstep''')
        if mode=='selective':
            # 新片亮带保留，开放边的最后一批材料降低覆盖和亮度。
            v=v.replace('flat out float surface_out;','flat out float surface_out;\nflat out float trailing_out;')
            v=v.replace('source_clock-=.065*trailing;', 'trailing_out=trailing;source_clock-=.065*trailing;')
            f=f.replace('flat in float surface_out;','flat in float surface_out;\nflat in float trailing_out;')
            f=f.replace('color+=vec3(.36)*rim*(1.-.5*body);','color+=vec3(.36)*rim*(1.-.5*body)*(1.-.85*trailing_out);')
            f=f.replace('if(alpha<.001)discard;','alpha*=1.-.38*trailing_out;\n    if(alpha<.001)discard;')
        return dict(COMPUTE=c,VERTEX=v,FRAGMENT=f)
    s=original();c,v,f=s['COMPUTE'],s['VERTEX'],s['FRAGMENT']
    if mode in ['departure','combined','fine','open']:
        c=c.replace('peel-=wind*min(dot(peel,wind),0.);','''peel-=wind*min(dot(peel,wind),0.);
    peel-=wind*max(dot(peel,wind),0.)*.82;''')
    if mode in ['dispersion','combined','fine','open']:
        c=c.replace('float depth_target=', '''// 原表面边缘解除后向开放侧分散，不保留有限支撑的锐利外轮廓。
    vec2 edge_dist=min(m.src.xy,card-m.src.xy);
    vec2 outward=vec2(m.src.x<card.x*.5?-1.:1.,m.src.y<card.y*.5?-1.:1.);
    vec2 edge_weight=exp(-edge_dist/(span*.055));
    vec2 outward_spread=outward*edge_weight;
    float spread_age=smoothstep(.012,.09,age)*exp(-age/.32);
    target+=outward_spread*span*.48*(.2+1.8*m.random.z)*spread_age;
    target+=vec2(-wind.y,wind.x)*span*.12*(m.random.w-.5)*length(edge_weight)*spread_age;
    float depth_target=''')
    if mode in ['transfer','combined','fine','open']:
        v=v.replace('uniform int nx,grid_count;','uniform int nx,grid_count;\nuniform int material_pass;')
        v=v.replace('out float age_out,life_out,light_out,shape_out;','out float age_out,life_out,light_out,shape_out;\nflat out float surface_out;')
        v=v.replace('age_out=age;', '''age_out=time-m.src.z;
    float source_clock=m.src.z-(.060+.080*body_weight)*(m.random.w-.5);
    surface_out=1.-smoothstep(max(.001,source_clock-.050),max(.018,source_clock+.028),time);
    ''')
        v=v.replace('vec2 world=offset+vertex;', '''if(material_pass==0)vertex=source;
    vec2 world=offset+vertex;''')
        f=f.replace('in float age_out,life_out,light_out,shape_out;','in float age_out,life_out,light_out,shape_out;\nflat in float surface_out;')
        f=f.replace('float age=age_out;', '''if(material_pass==0){
        float a=src.a*surface_out;
        if(a<.001)discard;
        frag=vec4(linear(src.rgb)*a,a);return;
    }
    float age=age_out;''')
        f=f.replace('float alpha=src.a*fade*shape;','float alpha=src.a*fade*shape*smoothstep(.001,.022,age);')
    if mode in ['optics','combined','fine','open']:
        f=f.replace('float alpha=src.a*fade*shape;', 'float alpha=src.a*fade*shape;')
        f=f.replace('if(alpha<.001)discard;', '''alpha*=mix(1.,.66,smoothstep(.018,.12,age));
    if(alpha<.001)discard;''')
        f=f.replace('if(diagnostic==1)color=', '''float rim=exp(-pow((age-.045)/.030,2.))*smoothstep(.001,.012,age);
    color+=vec3(.85)*rim*(1.-.5*body);
    if(diagnostic==1)color=''')
    if mode in ['fine','open']:
        v=v.replace('vec2 vertex=move+', '''scale*=mix(1.,.68,smoothstep(.0,.018,age));
    vec2 vertex=move+''')
        v=v.replace('shape_out=loosen;', 'shape_out=smoothstep(0.,.030,age);')
        f=f.replace('vec3(.85)*rim','vec3(.36)*rim')
        f=f.replace('(age-.045)/.030','(age-.060)/.038')
        f=f.replace('mix(1.,.66,smoothstep(.018,.12,age))','mix(1.,.83,smoothstep(.018,.12,age))')
    if mode=='open':
        c=c.replace('span*.055','span*.11').replace('span*.48*(.2+1.8*m.random.z)','span*1.2*(.05+2.4*m.random.z*m.random.z)')
    return dict(COMPUTE=c,VERTEX=v,FRAGMENT=f)

def render_mode(mode,ctx,scene='ironman',direction=None,seed=None):
    for key,value in shaders(mode).items():setattr(renderer,key,value)
    fine=mode.startswith(('dense-','facet','porous','balanced','material-aware','conservative','cohort'))
    r=renderer.Renderer(scene,ctx=ctx,direction=direction,seed=seed,cell_px=1.85 if mode.startswith('soft-') else 1.65 if fine else 2.35)
    if mode.startswith('grains'):
        class SplitVao:
            def __init__(self,inner):self.inner=inner
            def render(self,**kw):
                if r.program['material_pass'].value==1:kw['vertices']=24
                self.inner.render(**kw)
            def release(self):self.inner.release()
        r.vao=SplitVao(r.vao)
    frames=[]
    for t in TIMES:frames.append(r.render(t))
    r.close();return frames

def sheet(mode,frames,base,refs,crop,key):
    left,top,right,bottom=crop;w=336;h=round((bottom-top)*w/(right-left));row=h+32
    for group,indices in enumerate([range(4),range(4,7)]):
        im=Image.new('RGB',(w*len(indices),row*3),BG);d=ImageDraw.Draw(im)
        for col,i in enumerate(indices):
            for j,(title,arr) in enumerate([('华为参考',refs[i]),('已提交基线',base[i]),(mode,frames[i])]):
                label(d,(col*w+8,j*row+3),f'{title} · {TIMES[i]:.2f}',20)
                im.paste(Image.fromarray(arr).crop(crop).resize((w,h),Image.Resampling.LANCZOS),(col*w,j*row+32))
        im.save(OUT/f'{mode}-{key}-{group}.jpg',quality=96)

def main():
    p=argparse.ArgumentParser();p.add_argument('--modes',nargs='+',default=['transfer','departure','dispersion','optics','combined']);a=p.parse_args()
    OUT.mkdir(parents=True,exist_ok=True);freeze()
    ctx=moderngl.create_standalone_context(require=430)
    base=render_mode('baseline',ctx);ref=Reference(load_meta('ironman'));refs=[ref.at(t) for t in TIMES]
    np.save(OUT/'baseline-stills.npy',np.stack(base));np.save(OUT/'reference-stills.npy',np.stack(refs))
    for mode in a.modes:
        frames=render_mode(mode,ctx);np.save(OUT/f'{mode}-stills.npy',np.stack(frames))
        for key,crop in [('whole',(0,0,720,1280)),('rim',(80,440,410,760)),('upper',(50,145,350,430)),('full',(55,135,650,800))]:
            sheet(mode,frames,base,refs,crop,key)
        print(mode,flush=True)
    for key,value in original().items():setattr(renderer,key,value)
    ctx.release()

if __name__=='__main__':main()
