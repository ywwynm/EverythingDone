"""把桌面复刻逐段估计出的参考输入整理成飘散样式库 `shared/particle-dismiss/drift-styles.json`。

来源：`docs/features/dialog-particle-dismiss/tuning-0909-gpt6/native_reference_inputs.json`（格点值、横风、栅格化差异）
与各参考的 `assets/<名字>/scene.json`（卡片矩形）。每条样式：

- angle：风向（度，y 向上，0 = 右、90 = 上）。源码纵风固定 40000（向上），横风为估计值。
- wind：风力大小（工作面单位）= 0.5 × hypot(横风, 40000)，与 `ParticleDriftModel.random` 的口径相同。
- size：参考卡片的快照尺寸（设备像素），格点值按它套到本次卡片上（归一化位置取样 + 可见范围对齐）。
- expansion：参考的扩展画布尺寸（设备像素）。湍流取样按它换算到参考卡片自己的坐标，每颗粒子取到与原片里对应粒子相同的湍流。
- lattice：值噪声格点值，按行（y 向下）。
- fadeSizePower / fadeAlphaPower：寿命末段衰减分别进尺寸与不透明度的幂（缺省各 0.5；旧钢铁侠是 0.5 / 0，只缩小不变淡）。
- overAlphaMul：标准 src-over 时颗粒层 alpha 的倍数（缺省 1.15，参考 2 为 0.9）。只在背景亮、选了 src-over 时生效。

用法：python docs/features/harmony-particle-android-port/export_styles.py
"""
import json
import math
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / 'docs/features/dialog-particle-dismiss/tuning-0909-gpt6'
TARGET = REPO / 'shared/particle-dismiss/drift-styles.json'
WIND_Y = 40000.0
DEFAULT_OVER_ALPHA = 1.15


def main():
    table = json.loads((SOURCE / 'native_reference_inputs.json').read_text('utf-8'))
    styles = []
    for name, e in table.items():
        meta = json.loads((SOURCE / 'assets' / name / 'scene.json').read_text('utf-8'))
        x0, y0, x1, y1 = meta['rect']
        s = e['scale']
        width, height = round((x1 - x0) * s), round((y1 - y0) * s)
        raster = e.get('raster_override') or {}
        style = {
            'name': name,
            'angle': round(math.degrees(math.atan2(WIND_Y, e['wind_x'])), 2),
            'wind': round(0.5 * math.hypot(e['wind_x'], WIND_Y), 1),
            'size': [width, height],
            'expansion': [round(v, 2) for v in e['expansion']],
            'lattice': [[round(v, 5) for v in row] for row in e['lattice']],
            'fadeSizePower': raster.get('fade_size_power', 0.5) if raster.get('size_source') == 'radius_alpha_split' else 0.5,
            'fadeAlphaPower': raster.get('fade_alpha_power', 0.5) if raster.get('size_source') == 'radius_alpha_split' else 0.5,
            'overAlphaMul': raster.get('alpha_mul', DEFAULT_OVER_ALPHA) if raster.get('blend') == 'over' else DEFAULT_OVER_ALPHA,
        }
        styles.append(style)
    styles.sort(key=lambda v: v['angle'])
    out = {
        'version': 1,
        'note': '由 docs/features/harmony-particle-android-port/export_styles.py 生成，勿手改。',
        'styles': styles,
    }
    text = json.dumps(out, ensure_ascii=False, indent=1)
    TARGET.write_bytes((text + '\n').encode('utf-8'))
    for v in styles:
        print('%-22s angle=%6.1f wind=%7.0f size=%s fade=%.1f/%.1f over=%.2f' % (
            v['name'], v['angle'], v['wind'], v['size'], v['fadeSizePower'], v['fadeAlphaPower'], v['overAlphaMul']))


if __name__ == '__main__':
    main()
