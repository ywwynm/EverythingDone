"""未采用的流场诊断：分别检查时钟、基础流动、剥离及压力响应。

所有变体使用同一份默认模型和真实 GPU；不能把消融变体当作新模型发布。
"""
from pathlib import Path
import argparse,json,sys
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(HERE),str(HERE/'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from diagnose_motion_continuity import trajectory_reader,metrics
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison
from fit_particle_optics import measure


class ClockProbeRenderer(ScatterRenderer):
    compute_source=ScatterRenderer.compute_source.replace('vec2 curl(vec2 p,float t) {','''
uniform int diagnostic_curl_mode;
vec2 curl(vec2 p,float t) {
    if(diagnostic_curl_mode!=0)t=0.;''',1)
    compute_source=compute_source.replace('void main(){','''
uniform sampler2D diagnostic_clock;
uniform int diagnostic_clock_mode;
uniform float diagnostic_clock_mix;
uniform vec4 diagnostic_gain;
void main(){''',1)
    compute_source=compute_source.replace('float t=clamp(time-m.physical.w,0.,1.);','''
    float clock_offset=m.physical.w;
    if(diagnostic_clock_mode!=0){
        float alternative=diagnostic_clock_mode==1?0.:texture(diagnostic_clock,p/card).r;
        clock_offset=mix(clock_offset,alternative,diagnostic_clock_mix);
    }
    float t=clamp(time-clock_offset,0.,1.);''',1)
    compute_source=compute_source.replace('sample0.xy*(guide_gain/.9)','sample0.xy*diagnostic_gain.x*(guide_gain/.9)',1)
    compute_source=compute_source.replace('flow*mix(.018,','flow*diagnostic_gain.y*mix(.018,',1)
    compute_source=compute_source.replace('target+=curl(p+transport_random*span*.4,time)*span*.075',
        'target+=curl((diagnostic_curl_mode==2?q:p)+transport_random*span*.4,time)*span*.075*diagnostic_gain.y',1)
    compute_source=compute_source.replace('target+=(peel*1.2141309','target+=(diagnostic_gain.z*peel*1.2141309',1)
    compute_source=compute_source.replace('+sample_peel(p))*span;','+diagnostic_gain.w*sample_peel(p))*span;',1)

    def __init__(self,*args,**kw):
        super().__init__(*args,**kw)
        n=self.nx*self.ny
        field=np.zeros(n,np.float32)
        indices=self.base[:,3].astype(np.int64)%n
        field[indices]=self.base[:,7]
        self.clock_texture=self.ctx.texture((self.nx,self.ny),1,field.tobytes(),dtype='f4')
        self.clock_texture.filter=(moderngl.LINEAR,moderngl.LINEAR)
        self.clock_texture.repeat_x=self.clock_texture.repeat_y=False
        self.compute['diagnostic_clock']=12
        self.configure('current')

    def configure(self,name):
        self.compute['diagnostic_clock_mode']=0
        self.compute['diagnostic_curl_mode']=0
        self.compute['diagnostic_clock_mix']=0.
        self.compute['diagnostic_gain']=(1.,1.,1.,1.)
        if name in ['curl-static','curl-convected']:
            self.compute['diagnostic_curl_mode']=1 if name=='curl-static' else 2
        elif name.startswith('global-'):
            self.compute['diagnostic_clock_mode']=1
            self.compute['diagnostic_clock_mix']=float(name.split('-')[-1])
        elif name.startswith('spatial-'):
            self.compute['diagnostic_clock_mode']=2
            self.compute['diagnostic_clock_mix']=float(name.split('-')[-1])
        elif name=='curl-motion':
            self.compute['diagnostic_gain']=(1.,float(self.compute['conditional_motion'].value),1.,1.)
        elif name in ['guide-zero','curl-zero','peel-zero','pressure-zero']:
            values=[1.,1.,1.,1.];values[['guide-zero','curl-zero','peel-zero','pressure-zero'].index(name)]=0.
            self.compute['diagnostic_gain']=tuple(values)
        elif name!='current':raise ValueError(name)
        self.reset()

    def seek(self,time):
        self.clock_texture.use(12)
        super().seek(time)

    def close(self):
        super().close();self.clock_texture.release()


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--scenes',nargs='+',default=['ironman-up-reference','reference-0926-2','ironman'])
    ap.add_argument('--variants',nargs='+',default=['current','global-1','spatial-1','spatial-0.5','guide-zero','curl-zero','peel-zero','pressure-zero'])
    ap.add_argument('--motion',action='store_true');ap.add_argument('--label',default='flow-clock')
    ap.add_argument('--root',type=Path,default=HERE);a=ap.parse_args()
    out=HERE/'analysis/match-cache'/a.label;out.mkdir(parents=True,exist_ok=True)
    cls,kw=configuration(a.root);ctx=moderngl.create_standalone_context(require=430)
    report={};font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',15)
    for name in a.scenes:
        c=Comparison(name);args=dict(ctx=ctx,quality=2,sites=[Site(*v) for v in OBSERVED_SITES[name]],**kw)
        baseline=cls(name,**args);r=ClockProbeRenderer(name,**args)
        report[name]=dict(zero_max_pixel_delta=0,variants={})
        samples=[]
        for p in [.18,.29,.41,.58,.77]:
            i,ref=c.ref.nearest(p);p=float(c.ref.progress[i]);f=baseline.render(p);q=r.render(p)
            report[name]['zero_max_pixel_delta']=max(report[name]['zero_max_pixel_delta'],int(abs(f.astype(np.int16)-q.astype(np.int16)).max()))
            samples.append((p,ref))
        if report[name]['zero_max_pixel_delta']!=0:raise AssertionError('零消融不是原默认画面')
        baseline.close();tw=250;th=round(r.h*tw/r.w)
        for variant in a.variants:
            r.configure(variant);sheet=Image.new('RGB',(tw*5,(th+26)*2),(14,20,30));draw=ImageDraw.Draw(sheet);rows=[]
            for j,(p,ref) in enumerate(samples):
                actual=r.render(p)
                row=dict(phase=p)
                if c.scene.roi.any():
                    row.update({key:float(abs(u-v).mean()) for key,u,v in zip(['rgb_1','rgb_4','rgb_12','grain'],measure(actual,c.scene.roi),measure(ref,c.scene.roi))})
                for k,(title,f) in enumerate([('华为原片',ref),(variant,actual)]):
                    sheet.paste(Image.fromarray(f).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+26)+26))
                    draw.text((j*tw+3,k*(th+26)+2),f'{title} {p:.3f}',font=font,fill='white')
                rows.append(row)
            sheet.save(out/f'{name}-{variant}.jpg',quality=94)
            result=dict(frames=rows)
            if a.motion:
                r.configure(variant);read,close=trajectory_reader(r);phases=np.arange(0,.901,1/120);tr=[]
                for p in phases:r.render(float(p));tr.append(read())
                tr=np.asarray(tr);fg=np.asarray(Image.open(r.directory/'foreground.png').convert('RGBA'))
                sx=np.clip(r.base[:,0].astype(int),0,r.cw-1);sy=np.clip(r.base[:,1].astype(int),0,r.ch-1);mask=fg[sy,sx,3]>.5
                result['motion'],_,_=metrics(tr,phases,np.array(r.wind),r.span,mask)
                close()
            report[name]['variants'][variant]=result
            mean={key:float(np.mean([v[key] for v in rows])) for key in ['rgb_4','grain']} if c.scene.roi.any() else {}
            if a.motion:mean.update({k:result['motion']['displayed'][k] for k in ['reverse_fraction','maximum_synchronized_turn_fraction','lateral_excess_quantiles']})
            print(name,variant,mean,flush=True)
            (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
        r.close()
    ctx.release()


if __name__=='__main__':main()
