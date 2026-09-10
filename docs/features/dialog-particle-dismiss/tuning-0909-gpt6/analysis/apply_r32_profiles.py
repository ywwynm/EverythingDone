"""记录选定的少量弯曲应变参数，并更新四个自有弹窗共享的流场。"""
import sys,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np
from fit_flow import common_texture
regions={'thanos':[[.72,.65,.42,.45,-.65,.30,8.,.12,.34]],'kobe':[[.81,.57,.34,.54,-.15,.45,6.,.30,.52]]}
for name,region in regions.items():
    p=ROOT/'assets'/name/'flow-profile.json';data=json.loads(p.read_text(encoding='utf-8'));data['focusing_regions']=region
    data['r32_note']='有限宽度弯曲应变场：连续速度收束与现有输运共同积分，始终遵守主方向下限。'
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
np.save(ROOT/'assets/common-flow.npy',common_texture())
