from pathlib import Path
import sys,json,hashlib,shutil
import numpy as np
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from fields import field_grid
REPO=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
for name in ['ironman','thanos','kobe','language','color','attachment','attachment-image']:
    shutil.copyfile(HERE/'assets'/name/'foreground.png',HERE/'android-fixtures'/f'{name}.png')
dest=REPO/'app/src/test/resources/particle-dismiss';dest.mkdir(parents=True,exist_ok=True)
for direction in [0,45,90,125,180,225,270,315]:
    values=field_grid({'direction':65},37,29,direction)
    (dest/f'release-{direction}.f32').write_bytes(values.astype('<f4').tobytes())
meta=REPO/'shared/particle-dismiss/model.json'
data=json.loads(meta.read_text('utf-8'))
data['files']={name:hashlib.sha256((meta.parent/name).read_bytes()).hexdigest() for name in data['files']}
meta.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print('固定素材与八方向释放场基准已生成')
