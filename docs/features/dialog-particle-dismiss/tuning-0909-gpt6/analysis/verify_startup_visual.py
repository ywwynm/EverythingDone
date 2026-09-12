"""验证启动链加速前后材料、GPU 状态和完整帧逐值一致，不更新基准。"""
import json
from pathlib import Path
import numpy as np
from PIL import Image

root = Path(__file__).resolve().parents[1]
actual = root / 'analysis/startup-latency/final-render/9018f404/generated'
reference = root / 'analysis/startup-latency/reference-render/9018f404/generated'
approved = root / 'analysis/edge-flow-support/device-final/9018f404/generated'
reports = []


def compare(scene, expected, label):
    meta = json.loads((actual / f'{scene}.json').read_text('utf-8'))
    old = json.loads((expected / f'{scene}.json').read_text('utf-8'))
    for key in ['modelHash', 'direction', 'touchGap', 'touchStrength', 'count', 'width', 'height']:
        assert old[key] == meta[key], (scene, label, key)
    row = {'scene': scene, 'comparison': label, 'modelHash': meta['modelHash'],
           'expected': str(expected), 'material': {}, 'state': {}, 'frames': []}
    for suffix in ['materials', 'pigment', 'peel-compression', 'state018', 'state034', 'state040']:
        a = np.fromfile(expected / f'{scene}-{suffix}.f32', dtype='<f4')
        b = np.fromfile(actual / f'{scene}-{suffix}.f32', dtype='<f4')
        assert a.shape == b.shape and np.isfinite(a).all() and np.isfinite(b).all(), (scene, suffix)
        delta = float(np.abs(a-b).max())
        row['state' if suffix.startswith('state') else 'material'][suffix] = delta
        assert delta == 0, (scene, suffix, delta)
    for frame in [0, 10, 15, 18, 20, 34, 40, 48, 60]:
        a = np.asarray(Image.open(expected / f'{scene}-{frame}.png').convert('RGBA')).astype('int16')
        b = np.asarray(Image.open(actual / f'{scene}-{frame}.png').convert('RGBA')).astype('int16')
        assert a.shape == b.shape
        delta = np.abs(a-b)
        row['frames'].append({'frame': frame, 'max': int(delta.max()), 'mean': float(delta.mean()),
                              'changedPixels': int(np.any(delta != 0, axis=2).sum())})
        assert not delta.any(), (scene, frame, row['frames'][-1])
    reports.append(row)


# 认可版本存有这两个同模型场景；颜色弹窗的更早产物模型哈希不同，不用于本轮零差值校验。
for scene in ['ironman', 'attachment']:
    compare(scene, approved, '认可版本的已保存输出')
for scene in ['ironman', 'attachment', 'color']:
    meta = json.loads((reference / f'{scene}.json').read_text('utf-8'))
    assert meta['nativeMaterial'] is False, '必须实际调用保留的 Kotlin 参考实现'
    compare(scene, reference, '同一 APK 的 Kotlin 参考计算与上传路径')

path = root / 'analysis/startup-latency/final-visual-parity.json'
path.write_text(json.dumps(reports, ensure_ascii=False, indent=2), 'utf-8')
print('认可版本两场景、Kotlin 参考路径三场景：材料、GPU 状态及共 45 次完整帧比较均为零差值。')
