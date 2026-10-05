"""分离释放时序与材料运动的误差；观测释放图只作离线诊断，不进入运行模型。"""
from pathlib import Path
import argparse, dataclasses, json, sys, time
import cv2, numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, gaussian_filter, map_coordinates

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
OUT = HERE / 'analysis/match-cache/shared-layers'
PHASES = [.25, .38, .50, .62, .74]
LABELS = {'old': '此前共同模型', 'claude': 'Claude 候选',
          'native': '仅恢复旧释放场', 'observed': '观测释放 · 诊断',
          'coherent': '观测释放 · 减少独立剥离',
          'old-observed': '旧绘制 + 观测释放', 'registered-observed': '释放运动共变换 · 诊断',
          'old-sites': '旧绘制 + 起点释放', 'registered-sites': '起点释放运动共变换',
          'learned': '共享条件释放 · 实验','oracle': '释放与运动观测 · 诊断',
          'global-observed': '共同时间 · 观测释放','global-sites': '共同时间 · 起点释放',
          'conditional-material': '共同条件场 · 材料状态','basis-flow':'共同解析形变 · 实验'}


def observed_field(probe):
    a = probe.huawei().copy()
    good = probe.valid & np.isfinite(a)
    indices = distance_transform_edt(~good, return_distances=False, return_indices=True)
    a = a[tuple(indices)]
    a = gaussian_filter(np.minimum(a, .84), .7) - .025
    def field(nx, ny, width, height, sites, seed, rules, panel, direction, image):
        y, x = np.mgrid[:ny, :nx]
        return np.maximum(map_coordinates(a, [((y+.5)/ny*height)/10-.5,
                                              ((x+.5)/nx*width)/10-.5], order=1, mode='nearest'), .002).astype('float32')
    return field


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman', 'kobe', 'thanos', 'reference-0926-5', 'reference-0926-7'])
    ap.add_argument('--variants', nargs='+', default=['old','claude','native','observed','coherent'])
    ap.add_argument('--label',default='initial')
    ap.add_argument('--weights',default='conditional')
    ap.add_argument('--parameters')
    ap.add_argument('--flow-parameters')
    a = ap.parse_args()
    weight_path=Path(a.weights)
    if not weight_path.is_file():
        weight_path=HERE/f'analysis/match-cache/shared-release/{a.weights}.json'
    import moderngl, dissolve_v2 as V
    from renderer import Renderer
    from unified_model import release_components
    from release_integrity import Probe
    output=OUT/a.label;output.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
    original = V.release_field
    def native(nx, ny, width, height, sites, seed, rules, panel, direction, image):
        return release_components(nx, ny, direction, width, height, seed)[0]
    summary = {}
    for name in a.scenes:
        start = time.monotonic()
        probe = Probe(name); c = probe.comp; meta = c.scene.meta
        phases = [(float(c.ref.progress[i]), np.asarray(f)) for i, f in [c.ref.nearest(t) for t in PHASES]]
        x,y,x1,y1 = meta['rect']; w,h = meta['frame']; pad=round(min(x1-x,y1-y)*.20)
        box = (max(0,x-pad),max(0,y-pad),min(w,x1+pad),min(h,y1+pad))
        tw = 290; th=round((box[3]-box[1])*tw/(box[2]-box[0])); dy=th+30
        sheet=Image.new('RGB',(tw*len(phases),dy*(len(a.variants)+1)),(14,20,30)); draw=ImageDraw.Draw(sheet)
        def paste(arr, row, col, label):
            im=Image.fromarray(arr).crop(box).resize((tw,th),Image.Resampling.LANCZOS)
            sheet.paste(im,(col*tw,row*dy+27));draw.text((col*tw+5,row*dy+3),label,font=font,fill='white')
        for j,(p,f) in enumerate(phases):paste(f,0,j,f'华为原片 · {p:.3f}')
        mask=c.scene.roi; summary[name]={}
        for k,variant in enumerate(a.variants,1):
            V.release_field = native if variant=='native' else observed_field(probe) if variant in ['observed','coherent'] else original
            rules=dataclasses.replace(V.RULES,legacy_peel_var=0.) if variant=='coherent' else V.RULES
            if variant in ['conditional-material','basis-flow','shared-transport']:
                from conditional_material import ConditionalRenderer,ConditionalRules
                from learned_release import load_weights
                from v2_inputs import OBSERVED_SITES
                weights=load_weights(weight_path)
                cfg=ConditionalRules(**json.loads(Path(a.parameters).read_text('utf-8'))['rules']) if a.parameters else ConditionalRules()
                cls=ConditionalRenderer;options={}
                if variant=='basis-flow':
                    from analytic_flow_correction import AnalyticFlowRenderer
                    cls=AnalyticFlowRenderer;options['coefficients']=json.loads(Path(a.flow_parameters).read_text('utf-8'))['coefficients']
                if variant=='shared-transport':
                    from shared_transport import SharedTransportRenderer,load_transport
                    cls=SharedTransportRenderer;options['transport_weights']=load_transport(Path(a.flow_parameters))
                r=cls(name,sites=[V.Site(*s) for s in OBSERVED_SITES[name]],weights=weights,rules=cfg,ctx=ctx,quality=2,**options)
            elif variant=='oracle':
                from transport_oracle import OracleRenderer,observed_motion
                f=observed_field(probe)
                def target(nx,ny,width,height,direction,seed,fg):return f(nx,ny,width,height,None,seed,None,0.,direction,fg)
                r=OracleRenderer(name,ctx=ctx,quality=2,release=target,observed=observed_motion(probe))
            elif variant=='learned':
                from coupled_transport import CoupledRenderer
                from learned_release import load_weights,predict
                from v2_inputs import OBSERVED_SITES
                weights=load_weights(weight_path)
                def target(nx,ny,width,height,direction,seed,fg):
                    return predict(nx,ny,width,height,direction,seed,[V.Site(*s) for s in OBSERVED_SITES[name]],weights)
                r=CoupledRenderer(name,ctx=ctx,quality=2,release=target)
            elif variant in ['old-observed','registered-observed','old-sites','registered-sites','global-observed','global-sites']:
                from coupled_transport import CoupledRenderer,CoupledRules
                q=V.reference_inputs(name)
                f=observed_field(probe) if variant.endswith('observed') else original
                def target(nx,ny,width,height,direction,seed,fg):return f(nx,ny,width,height,q['sites'],seed,V.RULES,0.,direction,fg)
                r=CoupledRenderer(name,ctx=ctx,quality=2,direction=q['direction'],seed=q['seed'],release=target,rules=CoupledRules(warp=variant.startswith('registered'),local_clock=not variant.startswith('global')))
            elif variant=='old':r=Renderer(name,ctx=ctx,quality=2)
            else:r=V.V2Renderer(name,ctx=ctx,quality=2,rules=rules,**V.reference_inputs(name))
            rows=[]
            for j,(p,f) in enumerate(phases):
                g=r.render(p);paste(g,k,j,LABELS.get(variant,'共同输运 · 实验'))
                errors={}
                for sigma in [1.,4.,12.]:
                    fa=cv2.GaussianBlur(f.astype('float32'),(0,0),sigma)
                    ga=cv2.GaussianBlur(g.astype('float32'),(0,0),sigma)
                    errors[f'rgb_{sigma:g}']=float(np.abs(fa-ga).mean(2)[mask].mean()) if mask.any() else None
                rows.append(dict(phase=p,**errors))
            r.close();summary[name][variant]=rows
            values=[d['rgb_4'] for d in rows if d['rgb_4'] is not None]
            print(name,variant,round(np.mean(values),3) if values else '背景真值不完整，仅作可观测区域检查',flush=True)
        V.release_field=original
        sheet.save(output/f'{name}.jpg',quality=94)
        (output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
        print('完成',name,round(time.monotonic()-start,1),'秒',flush=True)
    ctx.release()


if __name__=='__main__':main()
