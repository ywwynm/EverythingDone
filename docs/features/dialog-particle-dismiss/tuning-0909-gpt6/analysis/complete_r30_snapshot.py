"""补齐 r30 静态运行素材，并用原交付散列核对，确保试验可独立重现。"""
from pathlib import Path
import json,types,shutil,hashlib,sys,numpy as np
root=Path(__file__).resolve().parents[1];snap=root/'archive/r30'
sys.path.insert(0,str(root))
for scene in (root/'assets').iterdir():
    if not scene.is_dir():continue
    dest=snap/'assets'/scene.name;dest.mkdir(parents=True,exist_ok=True)
    for p in (snap/scene.name).glob('*.json'):shutil.copy2(p,dest/p.name)
    for p in scene.glob('*.png'):shutil.copy2(p,dest/p.name)
shutil.copy2(root/'assets/scenes.json',snap/'assets/scenes.json')
m=types.ModuleType('r30_flow');m.__file__=str(snap/'fit_flow.py')
exec(compile((snap/'fit_flow.py').read_text(encoding='utf-8'),str(snap/'fit_flow.py'),'exec'),m.__dict__)
np.save(snap/'assets/common-flow.npy',m.common_texture())
paths=[snap/n for n in ['renderer.py','fields.py','fit_flow.py','export_videos.py']]
paths+=sorted((snap/'assets').glob('*/*.json'));paths+=sorted((snap/'assets').glob('*/*.png'));paths+=[snap/'assets/common-flow.npy']
h=hashlib.sha256()
for p in paths:h.update(p.name.encode());h.update(p.read_bytes())
assert h.hexdigest()==json.loads((snap/'manifest.json').read_text(encoding='utf-8'))['code_hash'],h.hexdigest()
print('r30 完整归档与原交付散列一致',h.hexdigest())
