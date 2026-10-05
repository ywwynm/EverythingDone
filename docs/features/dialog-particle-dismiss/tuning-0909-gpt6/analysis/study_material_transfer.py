"""完整 GPU 交接消融；共同参数跨参考复用，同时保留原尺寸与整体对照。"""
from pathlib import Path
import argparse,dataclasses,json,sys,time
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from surface_transfer import TransferRenderer,TransferRules,load_transfer
from conditional_material import ConditionalRules
from shared_transport import SharedTransportRenderer,load_transport
from learned_release import load_weights
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

VARIANTS={
    'current':None,
    'wider':TransferRules(stochastic=0.,broaden=1.,pairing=0.),
    'paired-small':TransferRules(spatial=.025,temporal=.025),
    'paired-wide':TransferRules(spatial=.05,temporal=.04),
    'paired-medium':TransferRules(spatial=.035,temporal=.03),
    'overlap':TransferRules(spatial=.035,temporal=.015,stochastic=.25,pairing=.0,broaden=1.,coverage=.75),
    'overlap-mixed':TransferRules(spatial=.035,temporal=.015,stochastic=.25,pairing=.2,broaden=1.,coverage=.75),
    'overlap-small':TransferRules(spatial=.018,temporal=.015,stochastic=.25,pairing=.0,broaden=1.,coverage=.75),
    'overlap-early':TransferRules(spatial=.035,temporal=.015,stochastic=.6,pairing=.0,broaden=1.,coverage=1.),
}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','thanos','kobe','spiderman-original','ironman-up-reference','reference-0926-1','reference-0926-4','reference-0926-5','reference-0926-6','reference-0926-7']);ap.add_argument('--phases',nargs='+',type=float,default=[.16,.24,.38,.56,.72]);ap.add_argument('--variants',nargs='+',default=list(VARIANTS));ap.add_argument('--label',default='initial');ap.add_argument('--candidate');ap.add_argument('--weights');ap.add_argument('--flow');ap.add_argument('--baseline',default=str(HERE));args=ap.parse_args()
    out=HERE/'analysis/match-cache/material-transfer'/args.label;out.mkdir(parents=True,exist_ok=True)
    # 冻结的前轮参数用于复查；避免默认参数更新后悄悄改变“调整前”含义。
    baseline=Path(args.baseline)
    weights=load_weights(baseline/'conditional-release-weights.json');flow=load_transport(baseline/'conditional-transport-weights.json');rules=ConditionalRules(**json.loads((baseline/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    if (baseline/'surface-transfer-rules.json').is_file():VARIANTS['current']=load_transfer(baseline/'surface-transfer-rules.json')
    if args.candidate:
        candidate=json.loads(Path(args.candidate).read_text('utf-8'));VARIANTS['candidate']=TransferRules(**candidate['transfer'])
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);summary={}
    for name in args.scenes:
        c=Comparison(name);m=c.scene.meta;mask=c.scene.roi;x,y,x1,y1=m['rect'];pad=round(min(x1-x,y1-y)*.18);fw,fh=m['frame'];box=(max(x-pad,0),max(y-pad,0),min(x1+pad,fw),min(y1+pad,fh))
        tw=340;th=round((box[3]-box[1])*tw/(box[2]-box[0]));dy=th+25
        sheet=Image.new('RGB',(tw*len(args.phases),dy*(len(args.variants)+1)),(14,20,30));d=ImageDraw.Draw(sheet)
        phases=[(float(c.ref.progress[i]),np.asarray(f)) for i,f in [c.ref.nearest(p) for p in args.phases]]
        def paste(frame,row,col,title):
            im=Image.fromarray(frame);im.crop(box).save(out/f'{name}-{row}-{col}.png')
            sheet.paste(im.crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,row*dy+25));d.text((col*tw+4,row*dy+2),title,font=font,fill='white')
        for j,(p,f) in enumerate(phases):paste(f,0,j,f'华为原片 · {p:.3f}')
        summary[name]={}
        for k,key in enumerate(args.variants,1):
            cfg=VARIANTS[key];kw=dict(ctx=ctx,quality=2,weights=weights,transport_weights=flow,rules=rules,sites=[Site(*s) for s in OBSERVED_SITES[name]])
            if key=='candidate':
                kw['rules']=ConditionalRules(**candidate['material'])
                if args.weights:kw['weights']=load_weights(Path(args.weights))
                if args.flow:kw['transport_weights']=load_transport(Path(args.flow))
            r=SharedTransportRenderer(name,**kw) if cfg is None else TransferRenderer(name,transfer=cfg,**kw)
            rows=[]
            for j,(p,f) in enumerate(phases):
                g=r.render(p);paste(g,k,j,key)
                e={f'rgb_{s}':float(np.abs(cv2.GaussianBlur(f.astype('float32'),(0,0),s)-cv2.GaussianBlur(g.astype('float32'),(0,0),s)).mean(2)[mask].mean()) if mask.any() else None for s in [1,4,12]}
                rows.append(dict(phase=p,**e))
            summary[name][key]=rows;r.close()
            print(name,key,round(np.mean([q['rgb_4'] for q in rows]),3) if mask.any() else '无完整背景',flush=True)
        sheet.save(out/f'{name}.jpg',quality=95)
        (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()

if __name__=='__main__':main()
