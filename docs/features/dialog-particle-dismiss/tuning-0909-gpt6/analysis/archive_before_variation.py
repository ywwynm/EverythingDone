"""冻结已认可共同模型的原始帧与资源，后续比较不重新解释旧帧。"""
from pathlib import Path
import json
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from unified_model import SHARED, model_fingerprint
from renderer import HERE

out = HERE / 'archive/transport-before-variation'
out.mkdir(parents=True, exist_ok=True)
identity = {'commit': 'fa8de16a', 'model_hash': model_fingerprint()}
for name in ['ironman', 'thanos', 'kobe', 'language', 'color', 'attachment', 'attachment-image']:
    target = out / (name + '.npy')
    if not target.exists():
        shutil.copy2(HERE / 'cache' / target.name, target)
for name in ['renderer.py', 'unified_model.py', 'export_videos.py']:
    shutil.copy2(HERE / name, out / name)
shutil.copytree(SHARED, out / 'shared', dirs_exist_ok=True)
for name in ['acceptance.json', 'freeze.json']:
    shutil.copy2(HERE / 'analysis/transport-model' / name, out / name)
shutil.copy2(HERE / 'videos/manifest.json', out / 'video-manifest.json')
(out / 'identity.json').write_text(json.dumps(identity, ensure_ascii=False, indent=2), 'utf-8')
print('认可共同模型已冻结', identity)
