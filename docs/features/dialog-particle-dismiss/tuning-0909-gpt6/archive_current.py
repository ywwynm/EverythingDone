"""保存当前模型及七场景无损缓存，供后续版本对照；不移动或删除原件。"""
import json,shutil
from pathlib import Path
from export_videos import HERE,VERSION,code_hash

dest=HERE/'archive'/VERSION
dest.mkdir(parents=True,exist_ok=True)
for name in ['renderer.py','fields.py','fit_reference.py','fit_flow.py','export_videos.py','build_gallery.py','compare_metrics.py']:
    target=dest/name
    assert not target.exists() or target.read_bytes()==(HERE/name).read_bytes(),f'归档已有不同内容：{target}'
    shutil.copy2(HERE/name,target)
shutil.copy2(HERE/'videos/manifest.json',dest/'manifest.json')
for scene in sorted((HERE/'assets').iterdir()):
    if not scene.is_dir():continue
    (dest/scene.name).mkdir(exist_ok=True)
    for p in scene.glob('*.json'):shutil.copy2(p,dest/scene.name/p.name)
    frozen=dest/'assets'/scene.name;frozen.mkdir(parents=True,exist_ok=True)
    for p in list(scene.glob('*.json'))+list(scene.glob('*.png')):shutil.copy2(p,frozen/p.name)
    cache=HERE/'cache'/f'{scene.name}.npy'
    stamp=json.loads(cache.with_suffix('.json').read_text())
    assert stamp['hash']==code_hash(),scene.name
    target=dest/cache.name
    if not target.exists():shutil.copy2(cache,target)
    assert target.stat().st_size==cache.stat().st_size
    print('已归档',VERSION,scene.name,flush=True)
shutil.copy2(HERE/'assets/common-flow.npy',dest/'assets/common-flow.npy')
shutil.copy2(HERE/'assets/scenes.json',dest/'assets/scenes.json')
