"""共同规则的边缘外扩消融；原片、当前模型、取消抑制和浅层隆起同时检查。"""
from pathlib import Path
import argparse,json,sys,time,dataclasses
import numpy as np,cv2,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from shared_transport import SharedTransportRenderer,load_transport
from origin_volume import OriginVolumeRenderer,OriginVolumeRules
from conditional_material import ConditionalRules
from learned_release import load_weights
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison

CASES={
    'current':('调整前',OriginVolumeRules()),
    'candidate':('共同模型 · 起点外扩约束',OriginVolumeRules()),
    'open':('取消边缘剥离抑制',OriginVolumeRules(edge_attenuation=0.)),
    'half-open':('减半边缘剥离抑制',OriginVolumeRules(edge_attenuation=.5)),
    'depth':('起点浅层隆起',OriginVolumeRules(depth=.4)),
    'depth-open':('浅层隆起与开放剥离',OriginVolumeRules(edge_attenuation=.5,depth=.3)),
    'cross-depth':('横向隆起',OriginVolumeRules(depth=.4,transverse=1.)),
    'cross-wide':('宽起点横向隆起',OriginVolumeRules(depth=.55,transverse=1.,radius=.32)),
}

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman','spiderman-original','reference-0926-5','reference-0926-6','reference-0926-7'])
    ap.add_argument('--variants',nargs='+',default=list(CASES));ap.add_argument('--phases',type=float,nargs='+',default=[.12,.25,.38,.50,.62]);ap.add_argument('--label',default='initial');ap.add_argument('--candidate-transport');a=ap.parse_args()
    out=HERE/'analysis/match-cache/origin-volume'/a.label;out.mkdir(parents=True,exist_ok=True)
    weights=load_weights(HERE/'conditional-release-weights.json');flow=load_transport(HERE/'conditional-transport-weights.json')
    rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
    ctx=moderngl.create_standalone_context(require=430);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16);report={}
    for name in a.scenes:
        begin=time.monotonic();c=Comparison(name);m=c.scene.meta;x,y,x1,y1=m['rect'];span=min(x1-x,y1-y);pad=round(span*.22)
        box=(max(0,x-pad),max(0,y-pad),min(m['frame'][0],x1+pad),min(m['frame'][1],y1+pad));tw=310;th=round((box[3]-box[1])*tw/(box[2]-box[0]));dy=th+28
        sheet=Image.new('RGB',(tw*len(a.phases),dy*(len(a.variants)+1)),(14,20,30));d=ImageDraw.Draw(sheet)
        def paste(f,row,col,label):
            sheet.paste(Image.fromarray(f).crop(box).resize((tw,th),Image.Resampling.LANCZOS),(col*tw,row*dy+27));d.text((col*tw+5,row*dy+3),label,font=font,fill='white')
        refs=[(float(c.ref.progress[i]),np.asarray(f)) for i,f in [c.ref.nearest(p) for p in a.phases]]
        for j,(p,f) in enumerate(refs):paste(f,0,j,f'华为原片 · {p:.3f}')
        report[name]={}
        mask=c.scene.roi
        yy,xx=np.mgrid[:m['frame'][1],:m['frame'][0]]
        outside_card=(xx<x)|(xx>=x1)|(yy<y)|(yy>=y1)
        origin_band=np.zeros(mask.shape,bool)
        for ox,oy,start in OBSERVED_SITES[name]:
            origin_band|=((xx-(x+ox*(x1-x)))**2+(yy-(y+oy*(y1-y)))**2)<(span*.30)**2
        origin_band &= outside_card & mask
        bg=np.asarray(Image.open(HERE/'assets'/name/'background.png').convert('RGB'),np.float32)
        for k,key in enumerate(a.variants,1):
            label,volume=CASES[key]
            selected_flow=load_transport(Path(a.candidate_transport)) if key=='candidate' and a.candidate_transport else flow
            r=OriginVolumeRenderer(name,volume=volume,weights=weights,transport_weights=selected_flow,rules=rules,sites=[Site(*s) for s in OBSERVED_SITES[name]],ctx=ctx,quality=2)
            values=[]
            for j,(p,f) in enumerate(refs):
                g=r.render(p);paste(g,k,j,label)
                err=np.abs(cv2.GaussianBlur(f.astype('float32'),(0,0),4)-cv2.GaussianBlur(g.astype('float32'),(0,0),4)).mean(2)
                s=np.frombuffer(r.state.read(),np.float32).reshape(-1,8);age=r.step/240-r.base[:,2];visible=(age>0)&(age<r.base[:,6]);pos=s[:,:2]
                outside=(pos[:,0]<0)|(pos[:,0]>r.cw)|(pos[:,1]<0)|(pos[:,1]>r.ch)
                ref_effect=cv2.GaussianBlur(np.abs(f.astype('float32')-bg).mean(2),(0,0),4)
                effect=cv2.GaussianBlur(np.abs(g.astype('float32')-bg).mean(2),(0,0),4)
                values.append(dict(phase=p,rgb4=float(err[mask].mean()) if mask.any() else None,edge_rgb4=float(err[origin_band].mean()),edge_reference=float(ref_effect[origin_band].mean()),edge_candidate=float(effect[origin_band].mean()),outside_fraction=float(outside[visible].mean()),depth_max=float(s[:,2].max()/r.span)))
            r.close();report[name][key]=values
            print(name,key,round(np.mean([q['rgb4'] for q in values]),3),flush=True)
        sheet.save(out/f'{name}.jpg',quality=94)
        (out/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        print('完成',name,round(time.monotonic()-begin,1),'秒',flush=True)
    ctx.release()

if __name__=='__main__':main()
