"""验证 ParticleRecordingAppearanceProbe 的真实录音准备页输出；原始记事画面只保留在本机。"""
import json
import pathlib
import sys
import numpy as np
from PIL import Image


def compare(folder):
    folder = pathlib.Path(folder)
    report = json.loads((folder / 'report.json').read_text(encoding='utf8'))
    events = report['events']
    snapshots = [e['values'] for e in events if e['event'] == 'recording_probe_snapshot']
    assert snapshots, '录音出现粒子未播放，不能计为通过'
    assert not any(e['event'] == 'appearance_preparation_aborted' for e in events), '出现准备曾失败'
    bounds = json.loads((folder / 'samples.json').read_text(encoding='utf8'))[-1]['clockBounds']
    native = np.array(Image.open(folder / 'screen-4500.png').convert('RGB').crop(bounds), dtype=float)
    source = snapshots[-1]
    x, y = source['originX'], source['originY']
    box = [bounds[0] - x, bounds[1] - y, bounds[2] - x, bounds[3] - y]
    output = {'run': report['id'], 'device': report['metadata']['visibleModel'], 'images': {}}
    # OPPO 的窗口缓冲与最终屏幕有小幅合成差异；保留数值，不宣称逐位相同。
    # 原错误的前景平均通道差约 20，且在稳定态重复捕获仍失败。
    ink = native.min(2) < 240
    assert ink.any(), '缺少可判定的计时数字'
    for filename in [source['name'], 'settled-capture.png']:
        captured = np.array(Image.open(folder / filename).convert('RGB').crop(box), dtype=float)
        delta = np.abs(native - captured)
        result = {'meanChannelError': float(delta.mean()), 'inkMeanChannelError': float(delta[ink].mean()),
                  'maxChannelError': float(delta.max()), 'identical': bool((delta == 0).all())}
        output['images'][filename] = result
        assert result['inkMeanChannelError'] < 3 and result['maxChannelError'] <= 10, result
    return output


if __name__ == '__main__':
    print(json.dumps(compare(sys.argv[1]), ensure_ascii=False, indent=2))
