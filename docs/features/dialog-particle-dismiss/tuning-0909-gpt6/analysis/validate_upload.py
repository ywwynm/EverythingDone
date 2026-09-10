from pathlib import Path
import json
import numpy as np
from PIL import Image
root=Path(__file__).resolve().parents[1]
rows=[]
for serial in ['9018f404','R5CW20BLNKL']:
    base=root/'device-r33'/serial
    for path in (base/'fixed').glob('*.png'):
        before=np.asarray(Image.open(base/'fixed-before-upload'/path.name))
        after=np.asarray(Image.open(path))
        delta=np.abs(before.astype(int)-after.astype(int))
        row={'device':serial,'file':path.name,'changed':int(np.count_nonzero(delta)),'max':int(delta.max())}
        rows.append(row)
        # 上传代码的替换必须不改变同设备、同着色器的任何像素。
        assert row['max']==0,row
        if path.stem.endswith('-60'):
            assert not np.any(after[:,:,3]),path
report={'uploadPixelsIdentical':True,'terminalAlphaZero':True,'images':len(rows),'results':rows}
(root/'analysis/android-upload-qa.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='results'}))
