"""仅在实验中让附加剥离跟随当前释放边缘，检验出生法向的长期独立作用。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from scipy.ndimage import gaussian_filter
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from ablate_edge_support import SHADERS,replace
from probe_edge_support import OUT,sheet

def configure(kind):
    c=SHADERS['COMPUTE'];c=replace(c,'uniform sampler3D guide_field;','uniform sampler3D guide_field;\nuniform sampler2D release_edge;')
    insert='''vec2 edge_uv=p/card;
    vec4 edge_sample=texture(release_edge,edge_uv);
    float edge_gap=max(time-edge_sample.z,0.)/max(edge_sample.w,.10);
    vec2 outside=max(max(-edge_uv,edge_uv-1.),vec2(0));
    float current_support=exp(-pow(edge_gap/.09,2.))*exp(-dot(outside,outside)/.0064);
    vec2 peel=-m.physical.xy;
'''
    if kind in ['current-normal','current-supported']:insert=insert.replace('vec2 peel=-m.physical.xy;','vec2 peel=-edge_sample.xy;')
    if kind in ['source-supported','current-supported']:insert+='    peel*=current_support;\n'
    else:insert+='    peel*=1.+.000001*current_support;\n'
    c=replace(c,'vec2 peel=-m.physical.xy;',insert)
    if kind=='short-memory':c=replace(c,'exp(-age/.16)','exp(-age/.045)')
    renderer.COMPUTE=c;renderer.VERTEX=SHADERS['VERTEX'];renderer.FRAGMENT=SHADERS['FRAGMENT']

def edge_texture(r,seed):
    field,_=model.release_components(r.nx,r.ny,r.direction,r.cw,r.ch,seed)
    field=model.refine_release(field,r.cw,r.ch,r.direction,r.material_info['panel_weight'])
    ids=r.base[:,3].astype(int);normals=np.zeros((r.nx*r.ny,2),'float32');keep=ids<r.nx*r.ny;normals[ids[keep]]=r.base[keep,4:6]
    gy,gx=np.gradient(gaussian_filter(field,3),r.cell[1],r.cell[0]);length=np.hypot(gx,gy)*r.span
    data=np.concatenate([normals.reshape(r.ny,r.nx,2),field[:,:,None],length[:,:,None]],axis=-1).astype('float32')
    tex=r.ctx.texture((r.nx,r.ny),4,data.tobytes(),dtype='f4');tex.filter=(moderngl.LINEAR,moderngl.LINEAR);tex.repeat_x=False;tex.repeat_y=False
    r.compute['release_edge']=5;tex.use(5);return tex

def main():
    ctx=moderngl.create_standalone_context(require=430);inputs=json.loads((OUT/'matched-inputs.json').read_text('utf-8'))
    inputs.append(dict(name='ironman',angle=122,seed=909602,phase=.56))
    for item in inputs:
        rows=[]
        for kind in ['current-normal','source-supported','current-supported','short-memory']:
            configure(kind);r=renderer.Renderer('ironman' if item['name']=='ironman' else 'attachment',direction=item['angle'],seed=item['seed'],ctx=ctx)
            tex=edge_texture(r,item['seed']);phases=np.array([-.06,0,.06])+item['phase'];frames=[(float(t),r.render(t)) for t in phases]
            np.save(OUT/item['name']/f'ablate-{kind}.npy',np.array([f for t,f in frames]));rows.append((kind,frames));tex.release();r.close()
        rect=(60,490,680,1130) if item['name']!='ironman' else (65,180,645,820)
        sheet(rows,OUT/item['name']/'edge-candidates.png',rect=rect,width=360);print(item['name'],flush=True)
    ctx.release()
if __name__=='__main__':main()
