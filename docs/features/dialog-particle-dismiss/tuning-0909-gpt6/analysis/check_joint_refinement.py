"""共同输运与材质联合候选的全程复核；两份配置使用同一修正后的渲染代码。"""
from pathlib import Path
import argparse, json, math, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from check_cloud_evolution import configuration
from fit_particle_optics import NAMES,measure
from match_compare import Comparison
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--before',type=Path,required=True);ap.add_argument('--after',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True);ap.add_argument('--stride',type=int,default=1)
    ap.add_argument('--allow-flow-change',action='store_true',help='候选明确修改积分流场时允许状态变化；仍检查材料、初末帧、回放及有限值')
    ap.add_argument('--scenes',nargs='+',default=list(OBSERVED_SITES));a=ap.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);variants={'before':configuration(a.before),'candidate':configuration(a.after)}
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
    for name in a.scenes:
        c=Comparison(name);sites=[Site(*v) for v in OBSERVED_SITES[name]]
        rs={key:cls(name,ctx=ctx,quality=2,sites=sites,**kw) for key,(cls,kw) in variants.items()}
        checks={};before,after=rs.values()
        checks['initial_equal']=bool(np.array_equal(before.render(0),after.render(0)))
        before.render(.5);after.render(.5);checks['simulation_equal']=before.state.read()==after.state.read()
        checks['material_equal']=bool(np.array_equal(before.base,after.base))
        first=after.render(.35);after.render(.8);checks['seek_equal']=bool(np.array_equal(first,after.render(.35)))
        checks['end_max_difference']=int(abs(before.render(1).astype(np.int16)-after.render(1)).max())
        checks['finite_state']=bool(np.isfinite(np.frombuffer(after.state.read(),np.float32)).all())
        assert checks['initial_equal'] and (a.allow_flow_change or checks['simulation_equal']) and checks['material_equal'] and checks['seek_equal'] and checks['finite_state'] and checks['end_max_difference']<=1,(name,checks)
        angle=math.radians(c.scene.meta['direction']);wind=np.array([math.cos(angle),-math.sin(angle)]);cross=np.array([-wind[1],wind[0]])
        def observe(frame):
            particle=c.scene.observe(frame)['particle']&c.scene.roi
            cloud,valid=c.scene.blocks(particle);y,x=np.nonzero(particle);geo=None
            if len(x)>=200:
                xy=np.stack([x,y],-1)/c.scene.span;along=xy@wind;across=xy@cross
                geo=[xy.mean(0),np.quantile(along,.95),np.quantile(along,.05),np.quantile(across,.95)-np.quantile(across,.05)]
            f=frame.astype(np.float32)/255;luma=f@np.float32([.2126,.7152,.0722]);hp=luma-cv2.GaussianBlur(luma,(0,0),1.5)
            tails=[]
            for sign in [1.,-1.]:
                for threshold in [.025,.06,.12,.24]:
                    soft=1./(1.+np.exp(np.clip(-(sign*hp-threshold)/.015,-30,30)))
                    tails.append(cv2.GaussianBlur(soft,(0,0),8.)[c.scene.roi])
            return cloud,valid>.5,geo,np.asarray(tails)
        rows=[]
        if name in NAMES:
            for i in c.index[::a.stride]:
                p=float(c.ref.progress[i]);ref=np.asarray(c.ref.frames[i]);target=measure(ref,c.scene.roi);cloud,valid,geo,tails=observe(ref)
                row=dict(index=int(i),phase=p)
                for key,r in rs.items():
                    f=r.render(p);q,_,g,tail=observe(f)
                    assert np.isfinite(np.frombuffer(r.state.read(),np.float32)).all(),(name,key,p)
                    metrics={k:float(abs(u-v).mean()) for k,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(f,c.scene.roi),target)}
                    metrics.update(cloud_map=float(abs(q-cloud)[valid].mean()),signed_grain=float(abs(tail-tails).mean()))
                    if geo is not None and g is not None:
                        metrics.update(centroid=float(np.linalg.norm(g[0]-geo[0])),front=float(abs(g[1]-geo[1])),back=float(abs(g[2]-geo[2])),width=float(abs(g[3]-geo[3])))
                    row[key]=metrics
                rows.append(row)
        tw=320;th=round(c.scene.H*tw/c.scene.W);sheet=Image.new('RGB',(tw*4,(th+25)*3),(14,20,30));draw=ImageDraw.Draw(sheet)
        for col,p in enumerate([.2,.4,.6,.8]):
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);frames=[ref,*[r.render(p) for r in rs.values()]]
            for k,(label,f) in enumerate(zip(['华为原片','本轮之前','共同模型候选'],frames)):
                sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,k*(th+25)+25));draw.text((col*tw,k*(th+25)),f'{label} {p:.3f}',font=font,fill='white')
        sheet.save(a.out/f'{name}.jpg',quality=95)
        for r in rs.values():r.close()
        checks['gl_error']=ctx.error;assert checks['gl_error']=='GL_NO_ERROR',checks
        keys=['rgb_1','rgb_4','rgb_12','grain','cloud_map','signed_grain','centroid','front','back','width']
        summary={stage:{k:float(np.mean([row[stage][k] for row in rows if k in row[stage]])) for k in keys} for stage in rs} if rows else None
        report[name]=dict(frames=len(rows),checks=checks,summary=summary,rows=rows)
        (a.out/'report.json').write_text(json.dumps(dict(before=str(a.before),after=str(a.after),flow_change_allowed=a.allow_flow_change,scope='新 2／3 的背景不可靠，仅作视觉和运行检查；同参考的其他帧不是未知素材',scenes=report),ensure_ascii=False,indent=2),'utf-8')
        print(name,'帧',len(rows),'检查',checks,'结果',summary,flush=True)
    ctx.release()
if __name__=='__main__':main()
