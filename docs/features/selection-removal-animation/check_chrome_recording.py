"""在最终显示合成结果中检查顶栏遮挡及半透明阴影下的素材连续性。"""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument('path', type=Path)
p.add_argument('--batch', action='store_true')
p.add_argument('--density', type=float, default=3.5)
a = p.parse_args()
if not a.batch:
    ready = json.loads((a.path / 'ready.json').read_text())
    density = ready['density']
    before = np.asarray(Image.open(a.path / 'before.png').convert('RGB')).astype(float)
    bottom = round(density * 144)
    left = round(ready['x'] + density * 24)
    right = round(ready['x'] + ready['width'] * .55)
    top = round(ready['y'] + density * 4)
    report = {}
    if (a.path / 'held.png').exists():
        held = np.asarray(Image.open(a.path / 'held.png').convert('RGB')).astype(float)
        opaque_error = np.abs(before[top:bottom-2, left:right] - held[top:bottom-2, left:right]).mean()
        shadow_error = np.abs(before[bottom+2:bottom+round(density*4), left:right] -
                              held[bottom+2:bottom+round(density*4), left:right]).mean()
        report.update(opaqueRgbError=float(opaque_error), shadowRgbError=float(shadow_error))
        assert opaque_error < 3, '粒子或快照越过不透明顶栏'
        assert shadow_error < 5, '阴影消失、重复加深或阴影下的卡片被裁掉'
    if (a.path / 'capture.mp4').exists():
        c = cv2.VideoCapture(str(a.path / 'capture.mp4'))
        errors = []
        baseline_errors = []
        while True:
            ok, f = c.read()
            if not ok:
                break
            rgb = cv2.cvtColor(f[top:bottom-2, left:right], cv2.COLOR_BGR2RGB).astype(float)
            error = np.abs(rgb - before[top:bottom-2, left:right]).mean()
            # 输入开始前先测同一静止画面的编码噪声；三星首帧文字附近的误差可超过 5。
            if c.get(cv2.CAP_PROP_POS_MSEC) < 300:
                baseline_errors.append(error)
            else:
                errors.append(error)
        assert len(errors) > 20, '录屏未覆盖动画'
        noise = max(baseline_errors, default=0)
        report.update(videoFrames=len(errors), maxVideoOpaqueRgbError=float(max(errors)), codecBaselineError=float(noise))
        assert max(errors) < max(5, noise + 1), report
    print(json.dumps(report))
else:
    c = cv2.VideoCapture(str(a.path))
    d = a.density
    bottom, shadow = round(d*96), round(d*5)
    ratios = []
    saw_dim = False
    while True:
        ok, bgr = c.read()
        if not ok:
            break
        f = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(float)
        if f[bottom-8, 12:40, 0].mean() < 200:
            saw_dim = True
            continue  # 确认对话框的暗层仍在，尚未开始列表动画。
        if not saw_dim:
            continue
        x1, x2 = round(d*26), round(d*34)  # 左卡片内侧、文字左侧的纯色材料。
        def material(region):
            return ((region[:,:,0] - region[:,:,2] > 45) & (region[:,:,0] > 180)).mean()
        below = material(f[bottom+shadow+3:bottom+shadow+8, x1:x2])
        if below < .2:
            break  # 原卡片已消失，后续补位卡片的圆角不属于裁切回归。
        if below > .85:
            ratios.append(material(f[bottom+2:bottom+shadow-2, x1:x2]) / below)
    assert len(ratios) > 5, '缺少主体完整、阴影下仍应有卡片的合成帧'
    print(json.dumps({'frames': len(ratios), 'minShadowMaterialRatio': min(ratios)}))
    assert min(ratios) > .65, '主体仍完整时，半透明阴影下出现空白带'
