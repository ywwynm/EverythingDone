"""围绕用户三个反例独立消融，所有候选都保留同一原材料及显示规则。"""
from pathlib import Path
import sys,json,ast,argparse
import numpy as np,moderngl
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_edge_support import OUT,BASE,sheet
TREE=ast.parse((BASE/'renderer.py').read_text('utf-8'))
SHADERS={n.targets[0].id:ast.literal_eval(n.value) for n in TREE.body if isinstance(n,ast.Assign) and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']}
KINDS=['baseline','no-peel','no-guide','no-local-clock','no-inertia','no-curl','no-optics','no-compression-feedback']
def replace(text,before,after):
    assert text.count(before)==1,(before,text.count(before));return text.replace(before,after)
def configure(kind):
    c,v,f=[SHADERS[k] for k in ['COMPUTE','VERTEX','FRAGMENT']]
    enabled=set(kind.split('+'))
    if 'no-peel' in enabled:c=replace(c,'target+=peel*span*','target+=peel*.000001*span*')
    if 'no-guide' in enabled:c=replace(c,'sample0.xy*(guide_gain/.9)','sample0.xy*.000001*(guide_gain/.9)')
    if 'no-local-clock' in enabled:c=replace(c,'clamp(time-m.physical.w,0.,1.)','clamp(time,0.,1.)')
    if 'no-inertia' in enabled:c=replace(c,'vec2 v=mix(old_v,target,response);','vec2 v=mix(old_v,target,1.-.000001*(1.-response));')
    if 'no-curl' in enabled:
        c=replace(c,'target+=flow*','target+=flow*.000001*')
        c=replace(c,'*span*.075*smoothstep','*span*.000000075*smoothstep')
    if 'no-optics' in enabled:f=replace(f,'if(diagnostic==1)color=vec3(1.,.20,.07);','color=vec3(.85)+color*.000001; if(diagnostic==1)color=vec3(1.,.20,.07);')
    if 'no-compression-feedback' in enabled:c=replace(c,'peel/=1.+peel_pressure*peel_pressure/(1.+peel_pressure);','peel/=1.+.000001*peel_pressure*peel_pressure/(1.+peel_pressure);')
    renderer.COMPUTE,renderer.VERTEX,renderer.FRAGMENT=c,v,f

def main():
    p=argparse.ArgumentParser();p.add_argument('--kinds',default=','.join(KINDS));args=p.parse_args()
    inputs=json.loads((OUT/'matched-inputs.json').read_text('utf-8'));ctx=moderngl.create_standalone_context(require=430)
    for item in inputs+[dict(name='ironman',angle=122,seed=909602,phase=.56)]:
        folder=OUT/item['name'];folder.mkdir(exist_ok=True);rows=[]
        for kind in args.kinds.split(','):
            configure(kind);r=renderer.Renderer('ironman' if item['name']=='ironman' else 'attachment',direction=item['angle'],seed=item['seed'],ctx=ctx)
            phases=np.array([-.06,0,.06])+item['phase'];frames=[(float(t),r.render(t)) for t in phases]
            np.save(folder/f'ablate-{kind}.npy',np.asarray([f for t,f in frames]));rows.append((kind,frames))
            r.render(item['phase']);np.save(folder/f'state-{kind}.npy',np.frombuffer(r.state.read(),'float32').reshape(-1,8))
            r.close()
        rect=(60,410,680,1140) if item['name']!='ironman' else (80,180,645,880)
        sheet([(title,[frames[1]]) for title,frames in rows],folder/'ablation-matched.png',rect=rect,width=620)
        sheet(rows,folder/'ablations.png',rect=rect,width=360)
        print(item['name'],'单因素消融完成',flush=True)
    ctx.release()
if __name__=='__main__':main()
