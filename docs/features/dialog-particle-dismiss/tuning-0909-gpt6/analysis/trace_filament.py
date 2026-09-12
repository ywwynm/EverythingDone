"""追踪截图对应细缕的源材料、寿命和速度随机量；空间框只用于诊断。"""
from pathlib import Path
import sys,json
import numpy as np,moderngl
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from filament_probe import OUT

def main():
    ctx=moderngl.create_standalone_context(require=430);r=renderer.Renderer('ironman',ctx=ctx)
    phases=np.arange(9,49)/60;states=[]
    for t in phases:
        r.seek(t);states.append(np.frombuffer(r.state.read(),'float32').reshape(-1,8).copy())
    i=int(np.argmin(abs(phases-2/3)));t=phases[i];base=r.base;offset=np.array(r.meta['rect'][:2]);points=states[i][:,:2]+offset
    age=t-base[:,2];life=base[:,6];fade=1-np.clip((age-np.maximum(life-.075,life*.55))/(life-np.maximum(life-.075,life*.55)),0,1)
    alive=(age>0)&(fade>.05)
    # 仅取用户标出的孤立弧形薄带，排除主体和静态背景中的星点。
    centre=490-40*np.clip((points[:,1]-420)/200,0,1)
    mask=(abs(points[:,0]-centre)<16)&(points[:,1]>350)&(points[:,1]<620)&alive
    source=base[:,:2]+offset
    current=r.render(t);draw=ImageDraw.Draw(im:=Image.fromarray(current));draw.rectangle((432,345,512,624),outline=(255,205,80),width=2)
    for point in points[mask][::4]:draw.ellipse((point[0]-1,point[1]-1,point[0]+1,point[1]+1),fill=(255,100,80))
    im.save(OUT/'traced-location.png')
    source_image=Image.open(r.directory/'source.png').convert('RGB');d=ImageDraw.Draw(source_image)
    for point in source[mask][::4]:d.ellipse((point[0]-1,point[1]-1,point[0]+1,point[1]+1),fill=(255,60,40))
    source_image.save(OUT/'traced-source.png')
    def stats(m):
        return {name:np.percentile(values[m],[5,25,50,75,95]).tolist() for name,values in dict(birth=base[:,2],life=life,age=age,speed=np.linalg.norm(states[i][:,4:6],axis=1),random0=base[:,8],source_x=base[:,0]/r.cw,source_y=base[:,1]/r.ch).items()}
    result=dict(count=int(mask.sum()),filament=stats(mask),visible=stats(alive))
    np.savez_compressed(OUT/'traced-cohort.npz',mask=mask,base=base,phases=phases,states=states)
    (OUT/'trace.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(result),flush=True)
    r.close();ctx.release()

if __name__=='__main__':main()
