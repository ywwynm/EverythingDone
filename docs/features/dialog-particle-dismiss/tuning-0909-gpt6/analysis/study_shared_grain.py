"""颗粒粗细与平均覆盖分开检验：放大颗粒时按平方反比减少数目。"""
from pathlib import Path
import argparse,json,dataclasses,sys
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from conditional_material import ConditionalRenderer,ConditionalRules
from learned_release import load_weights
from v2_inputs import OBSERVED_SITES
from dissolve_v2 import Site
from match_compare import Comparison


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--weights',required=True);a=ap.parse_args()
    out=HERE/'analysis/match-cache/shared-grain';out.mkdir(parents=True,exist_ok=True)
    weights=load_weights(Path(a.weights));rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    for name in ['kobe','thanos','spiderman-original','ironman-up-reference']+[f'reference-0926-{i}' for i in [1,4,5,6,7]]:
        comp=Comparison(name);m=comp.scene.meta;x,y,x1,y1=m['rect'];box=(max(0,x-20),max(0,y-20),min(m['frame'][0],x1+20),min(m['frame'][1],y1+20))
        tw=300;th=round((box[3]-box[1])*tw/(box[2]-box[0]));phases=[comp.ref.nearest(t) for t in [.25,.4,.56,.72]]
        sheet=Image.new('RGB',(tw*4,(th+28)*5),(14,20,30));draw=ImageDraw.Draw(sheet)
        def paste(f,row,col,text):
            sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,row*(th+28)+28));draw.text((col*tw+4,row*(th+28)+2),text,font=font,fill='white')
        for j,(idx,f) in enumerate(phases):paste(f,0,j,f'华为原片 {comp.ref.progress[idx]:.3f}')
        mask=comp.scene.roi;report[name]={}
        for k,grain in enumerate([1.,1.25,1.5,1.75],1):
            r=ConditionalRenderer(name,ctx=ctx,quality=2,sites=[Site(*s) for s in OBSERVED_SITES[name]],weights=weights,rules=dataclasses.replace(rules,grain=grain));rows=[]
            for j,(idx,target) in enumerate(phases):
                f=r.render(float(comp.ref.progress[idx]));paste(f,k,j,f'颗粒尺度 ×{grain:g}')
                def measure(img):
                    img=img.astype('float32');low=cv2.GaussianBlur(img,(0,0),4.)
                    high=cv2.GaussianBlur(np.abs(img-cv2.GaussianBlur(img,(0,0),1.5)),(0,0),8.)
                    return low,high
                ml,mh=measure(f);rl,rh=measure(target)
                rows.append(dict(low=float(np.abs(ml-rl)[mask].mean()),grain=float(np.abs(mh-rh)[mask].mean())))
            r.close();report[name][str(grain)]={k:float(np.mean([row[k] for row in rows])) for k in ['low','grain']}
        sheet.save(out/f'{name}.jpg',quality=95);print(name,report[name],flush=True)
    ctx.release();(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')


if __name__=='__main__':main()
