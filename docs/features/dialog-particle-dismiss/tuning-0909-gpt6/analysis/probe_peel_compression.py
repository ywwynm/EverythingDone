"""诊断候选：依据局部剥离速度导数限制过度汇聚，保留原法向和材质。"""
from pathlib import Path
import sys,json,argparse
import numpy as np,moderngl,cv2
from scipy.ndimage import gaussian_filter
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from probe_filament_layers import OUT,CASES
from ablate_filament_layers import replace,ORIGINAL
def compression(r):
    base=r.base;ids=base[:,3].astype('int64');order=np.argsort(ids);grid=base[order[ids[order]<r.nx*r.ny],4:6].reshape(r.ny,r.nx,2)
    wind=np.array(r.wind);peel=-grid;axial=peel@wind;peel-=np.minimum(axial,0)[...,None]*wind;peel-=np.maximum(axial,0)[...,None]*wind*.82
    dx=np.gradient(peel,r.cell[0],axis=1);dy=np.gradient(peel,r.cell[1],axis=0)
    a=dx[:,:,0];b=(dx[:,:,1]+dy[:,:,0])*.5;d=dy[:,:,1]
    lowest=(a+d)*.5-np.sqrt(((a-d)*.5)**2+b*b)
    # 只测压缩方向，不削减局部平移、转动与展开；尺度随控件短边变化。
    strain=gaussian_filter(np.maximum(-lowest,0),1)*r.span*.16*1.2141309
    return strain.ravel()[ids%(r.nx*r.ny)].astype('float32')
def configure(strength):
    c,v,f=ORIGINAL
    if strength:
        c=replace(c,'uniform int count;','layout(std430,binding=5) readonly buffer PeelCompression {float compression[];};\nuniform int count;')
        c=replace(c,'target+=peel*span*1.2141309*(.10+1.80*peel_response)',f'target+=peel*span*1.2141309*(.10+1.80*peel_response)/(1.+{strength:.6f}*compression[i]*(.10+1.80*peel_response))')
    renderer.COMPUTE,renderer.VERTEX,renderer.FRAGMENT=c,v,f
def make(name,angle,seed,strength,ctx):
    configure(strength);r=renderer.Renderer(name,ctx=ctx,direction=angle,seed=seed)
    b=None
    if strength:b=ctx.buffer(compression(r).tobytes());b.bind_to_storage_buffer(5)
    return r,b
def main():
    p=argparse.ArgumentParser();p.add_argument('--cases',default='0,1,2,3,4,5,6,7,8,9,10,11');a=p.parse_args()
    ctx=moderngl.create_standalone_context(require=430);phases=[.20,.30,.40,.50,.60];strengths=[0.,1.,2.,4.];font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    for j in map(int,a.cases.split(',')):
        name,angle,seed=CASES[j];dest=OUT/f'case-{j:02d}-{name}-{angle}-{seed}'
        meta=json.loads((dest/'input.json').read_text('utf-8'))['meta'];x,y,x1,y1=meta['rect'];w=400;h=round((y1-y+60)/(x1-x+30)*w)
        sheet=Image.new('RGB',(w*4,(h+28)*2),'#101620');d=ImageDraw.Draw(sheet)
        for k,s in enumerate(strengths):
            r,b=make(name,angle,seed,s,ctx);images=np.asarray([r.render(t) for t in phases]);np.save(dest/f'compression-{s:g}.npy',images)
            for row,idx in enumerate([1,3]):
                im=Image.fromarray(images[idx]).crop((x-15,y-30,x1+15,y1+30)).resize((w,h));sheet.paste(im,(k*w,row*(h+28)+28));d.text((k*w+3,row*(h+28)+3),f'压缩反馈 {s:g} · {phases[idx]:.2f}',font=font,fill='white')
            r.close()
            if b:b.release()
        sheet.save(dest/'compression-candidates.jpg',quality=96);print(dest.name,flush=True)
    ctx.release()
if __name__=='__main__':main()
