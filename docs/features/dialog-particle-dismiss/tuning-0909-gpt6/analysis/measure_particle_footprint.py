"""测量真实 GPU 足迹与原片可分离亮点，避免把高频能量误读为颗粒大小。"""
from pathlib import Path
import json,sys,argparse
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES


def geometry_reader(r):
    copies=10 if 'fission_strength' in r.program else 1
    count=r.n*copies
    source=r.vertex_source;varyings=['gl_Position','age_out','surface_out','density_out','trailing_out','random_out'];stride=12
    if copies>1:
        source=source.replace('void main(){','flat out vec4 proxy_fission;\nout vec2 proxy_scaled_offset;\nvoid main(){',1)
        source=source.replace('float projected=scale*sqrt(cell.x*cell.y)/span;','float projected=scale*sqrt(cell.x*cell.y)/span;\n    proxy_fission=vec4(projected,float(child),move);\n    proxy_scaled_offset=vec2(0.);',1)
        source=source.replace('move+=(rot*(location*cell))*scale+','proxy_scaled_offset=(rot*(location*cell))*scale;\n        move+=(rot*(location*cell))*scale+',1)
        source=source.replace('if(material_pass==0)vertex=source;','proxy_scaled_offset+=vertex-move;\n    if(material_pass==0)vertex=source;',1)
        varyings+=['proxy_fission','proxy_scaled_offset'];stride+=6
    program=r.ctx.program(vertex_shader=source,varyings=varyings)
    vao=r.ctx.vertex_array(program,[]);buffer=r.ctx.buffer(reserve=count*6*stride*4)
    def read():
        for name in program:
            if isinstance(program[name],moderngl.Uniform) and name in r.program:program[name].value=r.program[name].value
        # 直接读真实十份实例，包含母片与九个细分片；不经过绘制包装再乘十。
        program['material_pass']=1
        vao.transform(buffer,mode=moderngl.POINTS,vertices=6,instances=count)
        a=np.frombuffer(buffer.read(),np.float32).reshape(count,6,stride).copy()
        pos=a[:,:,:2]*np.array([r.w*.5,-r.h*.5])+np.array([r.w*.5,r.h*.5])
        # 六顶点的两个三角形相加，单位为最终场景像素平方。
        def tri(i,j,k):
            u=pos[:,j]-pos[:,i];v=pos[:,k]-pos[:,i]
            return abs(u[:,0]*v[:,1]-u[:,1]*v[:,0])*.5
        area=tri(0,1,2)+tri(3,4,5)
        result=dict(pos=pos.mean(1),area=area,age=a[:,0,4],surface=a[:,0,5],density=a[:,0,6],trailing=a[:,0,7],
                    random=a[:,0,8:12],parent=np.arange(count)//copies)
        if copies>1:result.update(projected=a[:,0,12],child=a[:,0,13],carrier=a[:,0,14:16],scaled_offset=a[:,:,16:18].mean(1))
        return result
    return read,lambda:(program.release(),vao.release(),buffer.release())


def geometry_stats(r,g,scene):
    age=g['age'];density=g['density'];keep0=r.program['conditional_keep'].value
    shift=np.minimum(density*r.coverage_rules.area,max(0.,-np.log(keep0)))*r.coverage_rules.count_share
    keep=np.clip(keep0*np.exp(shift),0,1)
    retain=np.mod(r.base[:,8]*47.123+r.base[:,10]*19.817,1)<keep
    retain&=np.mod(r.base[:,9]*13.731+r.base[:,10]*17.371,1)>=.64*g['trailing']
    live=(age>.05)&(age<r.base[:,6]-.05)&(g['surface']<.15)&retain
    x,y=np.rint(g['pos']).astype(int).T;inside=(x>=0)&(x<scene.W)&(y>=0)&(y<scene.H)
    live&=inside&scene.roi[np.clip(y,0,scene.H-1),np.clip(x,0,scene.W-1)]
    return dict(live_count=int(live.sum()),footprint_diameter=np.quantile(np.sqrt(g['area'][live]*4/np.pi),[.1,.5,.9,.99]).tolist() if live.any() else [],density_quantiles=np.quantile(density[live],[.1,.5,.9]).tolist() if live.any() else [],keep_mean=float(keep[live].mean()) if live.any() else 0.)


def patch_stats(f,bg,region):
    # 独立亮点只能描述明亮且不重叠的子集；阈值分别报告，不能等同全部粒子数。
    lum=f.astype(np.float32)@np.float32([.299,.587,.114]);back=bg@np.float32([.299,.587,.114])
    residual=lum-cv2.GaussianBlur(lum,(0,0),3)
    result={}
    for threshold in [8,16,24]:
        mask=(residual>threshold)&(lum-back>12)&region
        n,lab,st,cen=cv2.connectedComponentsWithStats(mask.astype(np.uint8),8)
        good=(st[1:,cv2.CC_STAT_AREA]>=1)&(st[1:,cv2.CC_STAT_AREA]<=64)&(st[1:,cv2.CC_STAT_WIDTH]<=12)&(st[1:,cv2.CC_STAT_HEIGHT]<=12)
        ar=st[1:,cv2.CC_STAT_AREA][good]
        result[str(threshold)]=dict(count=int(len(ar)),diameter=np.quantile(np.sqrt(ar*4/np.pi),[.1,.5,.9]).tolist() if len(ar) else [])
    # 同一位置的完整 RGB 高通相关长度，按局部标准差归一化。
    hp=f.astype(np.float32)-cv2.GaussianBlur(f.astype(np.float32),(0,0),5)
    norm=np.sqrt(cv2.GaussianBlur((hp*hp).mean(2),(0,0),8)+4)
    hp=hp/norm[:,:,None];autocorr=[]
    for k in range(1,7):
        mx=region[:,:-k]&region[:,k:];my=region[:-k]&region[k:]
        u=hp[:,:-k][mx];v=hp[:,k:][mx];a=hp[:-k][my];b=hp[k:][my]
        value=(np.sum(u*v)+np.sum(a*b))/max(np.sum(u*u)+np.sum(a*a),1e-6)
        autocorr.append(float(value))
    return dict(bright_components=result,highpass_autocorrelation=autocorr,region_pixels=int(region.sum()))


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','ironman-up-reference','reference-0926-4','reference-0926-7']);a=ap.parse_args()
    out=HERE/'analysis/match-cache/particle-footprint';out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(HERE,scatter=False);ctx=moderngl.create_standalone_context(require=430);report={}
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
    for name in a.scenes:
        c=Comparison(name);r=cls(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],**kw);read,close=geometry_reader(r)
        rows=[];sheet=Image.new('RGB',(1200,660),(14,20,30));draw=ImageDraw.Draw(sheet)
        for j,t in enumerate([.29,.43,.57]):
            i,ref=c.ref.nearest(t);p=float(c.ref.progress[i]);f=r.render(p);g=read();s=c.scene
            # 原片与模型的表面都已经解除，且两者均可观察粒子、背景纹理较平滑。
            o=s.observe(ref);m=s.observe(f)
            cloud=cv2.GaussianBlur(o['particle'].astype(np.float32),(0,0),8)>.06
            cloud&=cv2.GaussianBlur(m['particle'].astype(np.float32),(0,0),8)>.06
            surface=cv2.dilate((o['surface']|m['surface']).astype(np.uint8),np.ones((13,13),np.uint8)).astype(bool)
            back_detail=abs(s.bg-cv2.GaussianBlur(s.bg,(0,0),2)).max(2)
            region=cloud&~surface&s.roi&(back_detail<5)
            row=dict(phase=p,gpu=geometry_stats(r,g,s),reference=patch_stats(ref,s.bg,region),model=patch_stats(f,s.bg,region))
            rows.append(row)
            # 固定共享位置，原片/模型均为 2 倍最近邻放大；禁止按各自亮点重定位。
            score=cv2.boxFilter(region.astype(np.float32),-1,(160,140),normalize=False)
            yy,xx=np.unravel_index(score.argmax(),score.shape);xx=int(np.clip(xx,80,s.W-80));yy=int(np.clip(yy,70,s.H-70));box=(xx-80,yy-70,xx+80,yy+70)
            for k,(label,im) in enumerate([('华为原片',ref),('当前共同模型',f)]):
                x=j*400;y=k*330;sheet.paste(Image.fromarray(im).crop(box).resize((400,300),Image.Resampling.NEAREST),(x,y+28));draw.text((x+4,y+2),f'{label} t={p:.3f}',font=font,fill='white')
            Image.fromarray(ref).save(out/f'{name}-{j}-ref.png');Image.fromarray(f).save(out/f'{name}-{j}-model.png')
        report[name]=dict(layout_change=r.layout_change,base_cell=r.cell,particle_count=r.n,conditional_size=r.program['conditional_size'].value,conditional_keep=r.program['conditional_keep'].value,rows=rows)
        sheet.save(out/f'{name}.png');print(name,json.dumps(report[name],ensure_ascii=False),flush=True)
        close();r.close();(out/'measurement.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
