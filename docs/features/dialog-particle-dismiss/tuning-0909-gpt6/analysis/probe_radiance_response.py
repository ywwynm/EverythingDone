"""固定运动与覆盖布局，检验保留源色的乘性反射能否解释灰粒差异。"""
from pathlib import Path
import argparse, json, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from scatter_material import ScatterRenderer
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison
from fit_particle_optics import measure
from probe_grain_contrast import detail


class RadianceRenderer(ScatterRenderer):
    fragment_source = ScatterRenderer.fragment_source.replace(
        'void main(){', 'uniform float radiance_gain,radiance_contrast,radiance_selection;\nvoid main(){', 1)
    fragment_source = fragment_source.replace('frag=vec4(color*alpha,alpha);', '''
    color*=exp(radiance_gain*smoothstep(.015,.08,age));
    // 每片的反射差异使用固定随机编号，不随时间周期闪动；在线性空间中均值近似为 1。
    float sigma=radiance_contrast*smoothstep(.015,.08,age);
    float normal=sqrt(-2.*log(max(random_out.y,.0001)))*cos(random_out.w*6.2831853);
    color*=exp(sigma*normal-.5*sigma*sigma);
    float source_luma=dot(linear(src.rgb),vec3(.2126,.7152,.0722));
    alpha*=pow((.10+source_luma)/1.10,radiance_selection*smoothstep(.015,.08,age));
    frag=vec4(color*alpha,alpha);''', 1)

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.program['radiance_gain'] = 0.
        self.program['radiance_contrast'] = 0.
        self.program['radiance_selection'] = 0.


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman-up-reference', 'reference-0926-2', 'ironman', 'kobe'])
    ap.add_argument('--contrast', action='store_true', help='固定逐片反射差异，检查灰团是否来自亮度分布过窄')
    ap.add_argument('--selection', action='store_true', help='只检验原始材料明度对可见覆盖的共同影响')
    a = ap.parse_args()
    if a.contrast and a.selection:raise ValueError('每次只诊断一个原因')
    out = HERE / 'analysis/match-cache' / ('radiance-selection' if a.selection else 'radiance-contrast' if a.contrast else 'radiance-response')
    out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    _, kw = configuration(HERE)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 15)
    variants = [('当前', 0., 0.,0.), ('源色反射乘 1.4', np.log(1.4), 0.,0.),
                ('源色反射乘 2', np.log(2.), 0.,0.), ('反射乘 1.4 并降低覆盖', np.log(1.4), -.25,0.),
                ('反射乘 2 并降低覆盖', np.log(2.), -.50,0.), ('仅降低覆盖', 0., -.35,0.)]
    if a.contrast:
        variants=[('当前',0.,0.,0.),('固定反射差异 0.6',0.,0.,.6),('固定反射差异 1.0',0.,0.,1.),
                  ('固定反射差异 1.4',0.,0.,1.4),('反射差异 1.0、均值乘 1.15',np.log(1.15),0.,1.)]
    variants=[(*v,0.) for v in variants]
    if a.selection:
        variants=[('当前',0.,0.,0.,0.),('源明度覆盖 0.2',0.,0.,0.,.2),('源明度覆盖 0.5',0.,0.,0.,.5),
                  ('源明度覆盖 0.8',0.,0.,0.,.8),('覆盖 0.5、反射乘 1.2',np.log(1.2),0.,0.,.5)]
    report = {}
    for name in a.scenes:
        c = Comparison(name)
        r = RadianceRenderer(name, ctx=ctx, quality=2, sites=[Site(*v) for v in OBSERVED_SITES[name]], **kw)
        tw = 310; th = round(r.h * tw / r.w)
        sheet = Image.new('RGB', (tw * 3, (th+25) * (len(variants)+1)), (14,20,30))
        d = ImageDraw.Draw(sheet); rows = []
        for j, t in enumerate([.22, .39, .56]):
            idx, reference = c.ref.nearest(t); phase = float(c.ref.progress[idx])
            target = measure(reference, c.scene.roi)
            target_detail = detail(reference, c.scene.roi)
            frames = [('华为原片', reference)]; row = dict(phase=phase)
            for label, gain, density, contrast, selection in variants:
                r.program['radiance_gain'] = float(gain)
                r.program['radiance_contrast'] = float(contrast)
                r.program['radiance_selection'] = float(selection)
                r.program['scatter_delta'] = (density,0.,0.)
                frame = r.render(phase); frames.append((label,frame))
                if c.scene.roi.any():
                    row[label] = {key:float(abs(x-y).mean()) for key,x,y in
                        zip(['rgb_1','rgb_4','rgb_12','grain'], measure(frame,c.scene.roi), target)}
                    row[label]['signed_detail'] = float(abs(detail(frame,c.scene.roi)-target_detail).mean())
            for k,(label,frame) in enumerate(frames):
                sheet.paste(Image.fromarray(frame).resize((tw,th),Image.Resampling.LANCZOS),(j*tw,k*(th+25)+25))
                d.text((j*tw+2,k*(th+25)), f'{label} {phase:.3f}', font=font,fill='white')
                if j == 1: Image.fromarray(frame).save(out/f'{name}-{k}.png')
            rows.append(row)
        r.close(); sheet.save(out/f'{name}.jpg',quality=95)
        report[name] = rows
        print(name,{label:{key:round(float(np.mean([row[label][key] for row in rows])),5)
            for key in ['rgb_4','grain','signed_detail']} for label,_,_,_,_ in variants} if c.scene.roi.any() else '只作目视对照',flush=True)
        (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    ctx.release()


if __name__ == '__main__': main()
