from pathlib import Path
import sys,json,hashlib
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from renderer import COMPUTE,VERTEX,FRAGMENT,FULLVERT
from android_shaders import convert
dest=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())/'shared/particle-dismiss'
for name,source in [('step.comp',COMPUTE),('material.vert',VERTEX),('material.frag',FRAGMENT),('resolve.vert',FULLVERT)]:
    (dest/name).write_text(convert(name,source),encoding='utf-8')
meta=json.loads((dest/'model.json').read_text('utf-8'))
meta['files']={name:hashlib.sha256((dest/name).read_bytes()).hexdigest() for name in meta['files']}
meta['android_optimizations']=['静止材料直接覆盖源像素','非当前绘制层与完全透明材料提前裁剪']
(dest/'model.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
