"""分离真实绘制的表面、颗粒与几何覆盖；不把诊断白片作为动画候选。"""
from pathlib import Path
import argparse, hashlib, json, sys
import cv2, moderngl, numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE/'analysis')]
from check_cloud_evolution import configuration
from dissolve_v2 import Site
from v2_inputs import OBSERVED_SITES
from match_compare import Comparison


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=['ironman-up-reference', 'reference-0926-2', 'ironman', 'kobe'])
    ap.add_argument('--phases', type=float, nargs='+', default=[.23, .34, .46, .58, .72])
    a = ap.parse_args()
    out = HERE/'analysis/match-cache/visible-support'; out.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    cls, kw = configuration(HERE)
    # 动态诊断分支保证原材质 uniform 仍存在；非诊断画面严格走原分支。
    fragment = cls.fragment_source.replace('a<.001 || diagnostic==2', 'a<.001 || diagnostic==2 || diagnostic==4')
    original = 'frag=vec4(color*alpha,alpha);'
    assert fragment.count(original) == 1
    fragment = fragment.replace(original, 'if(diagnostic==4){color=vec3(1.);alpha=1.;}\n    '+original)
    support_cls = type('VisibleSupportRenderer', (cls,), {'fragment_source': fragment})
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
    records = {}
    identity = hashlib.sha256()
    for filename in sorted(HERE.glob('*-weights.json')): identity.update(filename.read_bytes())
    for name in a.scenes:
        comp = Comparison(name); scene = comp.scene
        r = support_cls(name, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[name]], **kw)
        base = cls(name, ctx=ctx, quality=2, sites=[Site(*s) for s in OBSERVED_SITES[name]], **kw)
        bg = r.bg_tex.read(); black = np.zeros((r.h, r.w, 3), np.uint8).tobytes()
        tw = 270; th = round(r.h*tw/r.w); dy = th+26
        sheet = Image.new('RGB', (tw*len(a.phases), dy*5), (14, 20, 30)); draw = ImageDraw.Draw(sheet)
        rows = []
        for col, requested in enumerate(a.phases):
            index, reference = comp.ref.nearest(requested); phase = float(comp.ref.progress[index])
            r.bg_tex.write(bg); complete = r.render(phase)
            assert np.array_equal(complete, base.render(phase)), '诊断关闭时不等于原绘制'
            surface = r.render(phase, 3); cloud = r.render(phase, 2)
            r.bg_tex.write(black); support = r.render(phase, 4)
            possible = cv2.GaussianBlur(support.astype(np.float32).mean(2)/255., (0, 0), 2.)
            obs = scene.observe(reference)
            visible = obs['particle'] & scene.roi
            rows.append(dict(phase=phase, reference_particle_pixels=int(visible.sum()),
                fraction_low_support=float((possible[visible]<.10).mean()) if visible.any() else None,
                scope='参考颗粒分类仍受源纹理、背景和配准影响；白片只表示当前存活且参与绘制材料的几何可达范围'))
            frames = [('华为原片',reference), ('当前完整画面',complete), ('原位表面与背景',surface), ('运动材料与背景',cloud), ('当前材料覆盖上界·诊断',support)]
            for k, (label, frame) in enumerate(frames):
                sheet.paste(Image.fromarray(frame).resize((tw,th), Image.Resampling.LANCZOS), (col*tw,k*dy+26))
                draw.text((col*tw+3,k*dy+3), f'{label} {phase:.3f}', font=font, fill='white')
                Image.fromarray(frame).save(out/f'{name}-{col}-{k}.png')
        r.close(); base.close(); sheet.save(out/f'{name}.jpg', quality=95)
        records[name] = rows
        (out/'report.json').write_text(json.dumps(dict(identity=identity.hexdigest(),scenes=records), ensure_ascii=False, indent=2), 'utf-8')
        print(name, [(round(q['phase'],3), round(q['fraction_low_support'],3) if q['fraction_low_support'] is not None else None) for q in rows], flush=True)
    ctx.release()


if __name__ == '__main__': main()
