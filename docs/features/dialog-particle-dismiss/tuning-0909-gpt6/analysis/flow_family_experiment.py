"""在冻结的原运动上验证共同形态范围；不修改正式模型或 Android 资源。"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np
import moderngl
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
from evaluate_targeted_release import frozen
from unified_model import patch_release, random_values, smooth
from export_videos import Reference, focus_bounds

OUT = HERE / 'analysis/flow-family'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 18)


def locality(width, height, direction, seed):
    angle = math.radians(direction)
    ux, uy = abs(math.cos(angle)) / width, abs(math.sin(angle)) / height
    corner = 2 * min(ux, uy) / max(ux + uy, 1e-8)
    # 向边中部的流动允许更多分离起点；向角落仍保留共同释放场的连贯卷边。
    # 连续几何输入，不按参考名称或方向分段选择模型。
    sampled = random_values(1, int(seed) ^ 0x510e527f)[0].astype(float)
    geometry = (1 - corner) ** 1.5 * (.42 + .14 * sampled[0])
    random_locality = .72 * float(smooth((sampled[3] - .30) / .55))
    return geometry + (1 - geometry) * random_locality


def candidate():
    cls, model = frozen()
    # frozen() 的动态模块不注册到 sys.modules，方法的 globals 指向实际模块空间。
    globals_ = cls.__init__.__globals__
    shader = globals_['COMPUTE']
    start = shader.index('    // 距离决定流束延伸')
    end = shader.index('    float random_angle=', start)
    replacement = '''    // 原共同速度、卷动和密度输运保持为主体。
    float reach=1.-exp(-.65/.85);
    float carried=smoothstep(.012,.16,age);
    vec2 q=p-card*.5-wind*span*(.43*time+.16*time*time);
    vec2 flow=curl(q,time)*span*curl_gain;
    float missing=1.-smoothstep(.12,.85,sample0.z);
    vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach);
    target+=flow*mix(.018,.44+.06*reach,missing)*smoothstep(.01,.09,age);
    target+=wind*span*(.12+.50*reach)*carried*wind_gain;
    // 速度变化有上下限，默认距离保持原共同模型的速度。
    float gain=(.88+.30*(1.-exp(-touch_gap/.25)))/(.88+.30*(1.-exp(-.65/.25)));
    target*=gain;
    // 触点只影响材料出发时的局部偏向；不随当前位置吸附，也不缩短收敛时间。
    float edge=min(card.x/(2.*max(abs(wind.x),.000001)),card.y/(2.*max(abs(wind.y),.000001)));
    vec2 destination=card*.5+wind*(edge+span*touch_gap);
    vec2 bearing=normalize(destination-m.src.xy);
    float turn=.13*atan(dot(bearing,vec2(-wind.y,wind.x)),dot(bearing,wind));
    target=mat2(cos(turn),sin(turn),-sin(turn),cos(turn))*target;
'''
    globals_['COMPUTE'] = shader[:start] + replacement + shader[end:]
    base_release = model.release_components

    def release(nx, ny, direction, width=None, height=None, seed=0):
        width = nx if width is None else width
        height = ny if height is None else height
        original, offset = base_release(nx, ny, direction, width, height, seed)
        weight = locality(width, height, direction, seed)
        y, x = np.mgrid[:ny, :nx].astype(float)
        local = patch_release((x + .5) / nx * width, (y + .5) / ny * height,
                              width, height, direction, seed, original)
        released = (original + weight * (local - original)).astype('float32')
        return released, (offset + released - original).astype('float32')

    model.release_components = release
    return cls, model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seeds', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True, parents=True)
    ctx = moderngl.create_standalone_context(require=430)
    current, _ = candidate()
    old, _ = frozen()
    if args.seeds:
        for direction in [90, 128]:
            sheet = Image.new('RGB', (330 * 6, 360 * 3), '#0e141e')
            draw = ImageDraw.Draw(sheet)
            for seed in range(9):
                r = current('ironman', direction=direction, seed=seed, ctx=ctx)
                lo, hi = focus_bounds(r.meta, True)
                for i, t in enumerate([.43, .62]):
                    panel = Image.fromarray(r.render(t)[lo:hi]); panel.thumbnail((320, 320))
                    x = (seed % 3 * 2 + i) * 330; y = seed // 3 * 360
                    sheet.paste(panel, (x, y + 28))
                    draw.text((x + 6, y + 3), f'种子 {seed} · {direction}° · {t}', font=FONT, fill='white')
                r.close()
            sheet.save(OUT / f'seeds-{direction}.jpg', quality=95)
    else:
        for name in ['ironman', 'ironman-up-reference', 'thanos', 'kobe', 'attachment', 'color']:
            r = current(name, ctx=ctx); previous = old(name, ctx=ctx); ref = Reference(r.meta)
            lo, hi = focus_bounds(r.meta, True); w = 330; h = round((hi - lo) * w / r.w)
            rows = 3 if ref.frames is not None else 2
            sheet = Image.new('RGB', (w * 5, (h + 32) * rows), '#0e141e'); draw = ImageDraw.Draw(sheet)
            for col, t in enumerate([.20, .35, .48, .63, .78]):
                panels = [(previous.render(t), '改动前'), (r.render(t), '保留原流动的候选')]
                if ref.frames is not None: panels.insert(0, (ref.at(t), '华为参考'))
                for row, (array, label) in enumerate(panels):
                    draw.text((col * w + 6, row * (h + 32) + 4), f'{label} · {t:.2f}', font=FONT, fill='white')
                    sheet.paste(Image.fromarray(array[lo:hi]).resize((w, h)), (col * w, row * (h + 32) + 32))
            sheet.save(OUT / f'candidate-{name}.jpg', quality=95)
            print(name, '局部起点权重', round(locality(r.cw, r.ch, r.direction, r.meta['seed']), 3), flush=True)
            r.close(); previous.close()
    ctx.release()


if __name__ == '__main__':
    main()
