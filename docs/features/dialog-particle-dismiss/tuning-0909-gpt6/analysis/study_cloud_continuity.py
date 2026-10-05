"""固定释放及输运，检验全部参考共用的颗粒存续改动。"""
from pathlib import Path
import argparse,json,sys,dataclasses
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from cloud_material import CloudRenderer,CloudRules
from surface_transfer import load_transfer
from conditional_material import ConditionalRules
from shared_transport import load_transport
from learned_release import load_weights
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

VARIANTS={
 'before':CloudRules(),
 'longer':CloudRules(life=1.4),
 'fine-dense':CloudRules(size=.75,count=1.8),
 'fine-longer':CloudRules(size=.75,count=1.8,life=1.4),
 'fine-persistent':CloudRules(size=.75,count=1.8,life=1.4,late_size=1.3),
 'persistent':CloudRules(life=1.4,late_size=1.3),
 'tonal':CloudRules(tonal_power=.65),
 'less-chroma':CloudRules(chroma=.5),
 'tonal-dense':CloudRules(tonal_power=.65,chroma=.65,size=.8,count=2.,life=1.2),
 'cohort-half':CloudRules(survival=.5,size=.75,count=1.8),
 'cohort-full':CloudRules(survival=1.,size=.75,count=1.8),
}


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','thanos','kobe','ironman-up-reference','reference-0926-4','reference-0926-7']);ap.add_argument('--phases',nargs='+',type=float,default=[.16,.28,.42,.60,.76]);ap.add_argument('--variants',nargs='+',default=list(VARIANTS));ap.add_argument('--label',default='first');ap.add_argument('--candidate');ap.add_argument('--flow');a=ap.parse_args()
    if a.candidate:VARIANTS['candidate']=CloudRules(**json.loads(Path(a.candidate).read_text('utf-8'))['cloud'])
    out=HERE/'analysis/match-cache/cloud-continuity'/a.label;out.mkdir(parents=True,exist_ok=True)
    baseline=HERE/'analysis/match-cache/cloud-continuity-before'
    common=dict(weights=load_weights(baseline/'conditional-release-weights.json'),transport_weights=load_transport(baseline/'conditional-transport-weights.json'),rules=ConditionalRules(**json.loads((baseline/'conditional-material-rules.json').read_text('utf-8'))['rules']),transfer=load_transfer(baseline/'surface-transfer-rules.json'))
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);summary={}
    for name in a.scenes:
        c=Comparison(name);m=c.scene.meta;mask=c.scene.roi;x,y,x1,y1=m['rect'];pad=round(min(x1-x,y1-y)*.18);fw,fh=m['frame'];box=(max(x-pad,0),max(y-pad,0),min(x1+pad,fw),min(y1+pad,fh))
        tw=340;th=round((box[3]-box[1])*tw/(box[2]-box[0]));dy=th+25
        sheet=Image.new('RGB',(tw*len(a.phases),dy*(len(a.variants)+1)),(14,20,30));d=ImageDraw.Draw(sheet)
        phases=[(float(c.ref.progress[i]),np.asarray(f)) for i,f in [c.ref.nearest(p) for p in a.phases]]
        def paste(frame,row,col,title):
            im=Image.fromarray(frame);im.crop(box).save(out/f'{name}-{row}-{col}.png')
            sheet.paste(im.crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,row*dy+25));d.text((col*tw+4,row*dy+2),title,font=font,fill='white')
        for j,(p,f) in enumerate(phases):paste(f,0,j,f'华为原片 · {p:.3f}')
        summary[name]={}
        for k,key in enumerate(a.variants,1):
            kw=common.copy()
            if key=='candidate' and a.flow:kw['transport_weights']=load_transport(Path(a.flow))
            r=CloudRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],cloud=VARIANTS[key],**kw)
            rows=[]
            for j,(p,f) in enumerate(phases):
                g=r.render(p);paste(g,k,j,key)
                e={f'rgb_{s}':float(np.abs(cv2.GaussianBlur(f.astype('float32'),(0,0),s)-cv2.GaussianBlur(g.astype('float32'),(0,0),s)).mean(2)[mask].mean()) if mask.any() else None for s in [1,4,12]}
                def grain(a):
                    a=a.astype('float32');return cv2.GaussianBlur(np.abs(a-cv2.GaussianBlur(a,(0,0),1.5)),(0,0),8.)
                e['grain']=float(np.abs(grain(f)-grain(g))[mask].mean()) if mask.any() else None
                rows.append(dict(phase=p,**e))
            summary[name][key]=rows;r.close()
            print(name,key,{k:round(np.mean([q[k] for q in rows]),3) for k in ['rgb_4','grain']} if mask.any() else '无完整背景',flush=True)
        sheet.save(out/f'{name}.jpg',quality=95)
        (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
