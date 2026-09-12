"""逐项去掉力、时钟及光学变化，替换必须恰好命中一次；不写正式模型。"""
from pathlib import Path
import sys,json,argparse,ast
import numpy as np,moderngl
from PIL import Image
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer,unified_model as model
from probe_filament_layers import OUT,BASE,CASES,montage
# 消融固定在发现问题时的版本，后续正式修复不能悄悄改变对照。
tree=ast.parse((BASE/'renderer.py').read_text('utf-8'))
shaders={n.targets[0].id:ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign)
         and isinstance(n.targets[0],ast.Name) and n.targets[0].id in ['COMPUTE','VERTEX','FRAGMENT']}
ORIGINAL=tuple(shaders[k] for k in ['COMPUTE','VERTEX','FRAGMENT'])
def replace(code,old,new):
    assert code.count(old)==1,(old,code.count(old))
    return code.replace(old,new)
def configure(kind):
    renderer.COMPUTE,renderer.VERTEX,renderer.FRAGMENT=ORIGINAL
    c,v,f=ORIGINAL
    if kind=='no-peel':c=replace(c,'target+=peel*span*','target+=peel*.000001*span*')
    if kind=='no-guide':c=replace(c,'sample0.xy*(guide_gain/.9)','sample0.xy*.000001*(guide_gain/.9)')
    if kind=='no-local-clock':c=replace(c,'clamp(time-m.physical.w,0.,1.)','clamp(time,0.,1.)')
    if kind=='no-floor':c=replace(c,'wind*span*.5*(deficit+sqrt(deficit*deficit+.0064))','wind*span*.000001*(deficit+sqrt(deficit*deficit+.0064))')
    if kind=='no-optics':
        f=replace(f,'if(diagnostic==1)color=vec3(1.,.20,.07);','color=vec3(.85)+color*.000001; if(diagnostic==1)color=vec3(1.,.20,.07);')
    if kind=='no-shear-random':
        c=replace(c,'target*=1.+0.6496880*','target*=1.+.000001*')
    renderer.COMPUTE,renderer.VERTEX,renderer.FRAGMENT=c,v,f
    assert kind=='current' or (c,v,f)!=ORIGINAL
def main():
    p=argparse.ArgumentParser();p.add_argument('--cases',default='0,1,2,3,4,8,10');a=p.parse_args();ctx=moderngl.create_standalone_context(require=430)
    kinds=['current','no-peel','no-guide','no-local-clock','no-floor','no-optics','no-shear-random'];phases=[.2,.3,.4,.5]
    for j in map(int,a.cases.split(',')):
        name,angle,seed=CASES[j];dest=OUT/f'case-{j:02d}-{name}-{angle}-{seed}';rows=[]
        for kind in kinds:
            configure(kind);r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
            frames=[(t,r.render(t,diagnostic=2)) for t in phases];rows.append((kind,frames));np.save(dest/f'ablate-{kind}.npy',np.asarray([im for t,im in frames]));r.close()
        x,y,x1,y1=r.meta['rect'];rect=[max(0,x-25),max(0,y-50),min(r.w,x1+25),min(r.h,y1+50)]
        montage(rows,dest/'ablations.jpg',rect)
        # 当前与各消融在同一时刻的大图，细线不能被整页缩放掩盖。
        montage([(title,[ims[1]]) for title,ims in rows],dest/'ablations-030.jpg',rect)
        print(dest.name,flush=True)
    ctx.release()
if __name__=='__main__':main()
