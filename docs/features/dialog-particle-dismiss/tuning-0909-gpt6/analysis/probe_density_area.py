"""只消融密度补偿引入的面积变化，检查它是否造成额外的细粒收缩。

超出的面积换成光学厚度，不改模拟中心、源色、释放或寿命。有限覆盖和
遮挡会使这一换算不精确，因此必须比较真实图像，不能只比较总量。
"""
from pathlib import Path
import argparse,json,sys
import moderngl,numpy as np
from PIL import Image,ImageDraw,ImageFont

HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison
from fit_particle_optics import measure


class AreaResponseRenderer(ScatterRenderer):
    vertex_source=ScatterRenderer.vertex_source.replace('void main(){',
        'uniform float area_response_limit;\nflat out float area_response_ratio;\nvoid main(){',1)
    vertex_source=vertex_source.replace('float projected=scale*','''
    float original_scale=scale;
    float count_extra=min(density_out*coverage_area,max(0.,-log(conditional_keep)))*coverage_count;
    float extra_area=density_out*coverage_area-count_extra;
    float limited_area=area_response_limit>0.?area_response_limit*tanh(extra_area/area_response_limit):extra_area;
    area_response_ratio=exp(extra_area-limited_area);
    scale*=exp(.5*(limited_area-extra_area));
    // 仍用原投影决定连续细分份额，避免同时改变另一个被测机制。
    float projected=original_scale*''',1)
    fragment_source=ScatterRenderer.fragment_source.replace('void main(){',
        'flat in float area_response_ratio;\nvoid main(){',1)
    fragment_source=fragment_source.replace('if(alpha<.001)discard;',
        'alpha=1.-pow(max(1.-alpha,.00001),area_response_ratio);\n    if(alpha<.001)discard;',1)

    def __init__(self,*args,area_limit=0.,**kw):
        super().__init__(*args,**kw)
        self.program['area_response_limit']=float(area_limit)


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman','kobe'])
    a=ap.parse_args();out=HERE/'analysis/match-cache/density-area';out.mkdir(parents=True,exist_ok=True)
    ctx=moderngl.create_standalone_context(require=430);cls,kw=configuration(HERE)
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15);report={}
    variants=[('当前',0.),('限制面积 1.2',1.2),('限制面积 0.8',.8),('限制面积 0.4',.4)]
    for name in a.scenes:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        r=AreaResponseRenderer(name,**args);baseline=cls(name,**args)
        tw=285;th=round(r.h*tw/r.w);sheet=Image.new('RGB',(5*tw,(len(variants)+1)*(th+26)),(14,20,30));draw=ImageDraw.Draw(sheet)
        rows=[];zero_error=0
        for j,p in enumerate([.18,.29,.41,.58,.77]):
            i,reference=c.ref.nearest(p);p=float(c.ref.progress[i]);frames=[('华为原片',reference)];values={}
            target=measure(reference,c.scene.roi) if c.scene.roi.any() else None
            for label,cap in variants:
                r.program['area_response_limit']=cap;frame=r.render(p);frames.append((label,frame))
                if cap==0.:zero_error=max(zero_error,int(abs(frame.astype(np.int16)-baseline.render(p).astype(np.int16)).max()))
                if target is not None:values[label]={key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(frame,c.scene.roi),target)}
            for k,(label,frame) in enumerate(frames):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26))
                draw.text((j*tw+2,k*(th+26)+2),f'{label} {p:.3f}',font=font,fill='white')
            rows.append(dict(phase=p,variants=values))
        r.close();baseline.close();sheet.save(out/f'{name}.jpg',quality=94)
        report[name]=dict(zero_max_pixel_delta=zero_error,frames=rows)
        print(name,'关闭后的最大像素差',zero_error,{label:{key:float(np.mean([row['variants'][label][key] for row in rows])) for key in ['rgb_4','grain']} for label,_ in variants} if c.scene.roi.any() else '未知背景，只作目视',flush=True)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__=='__main__':main()
