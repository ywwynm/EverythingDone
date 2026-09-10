import sys,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import numpy as np
from fit_flow import common_texture
regions={'thanos':[[.72,.67,.32,.39,-.65,.30,6.,.18,.44]],'kobe':[[.81,.57,.34,.54,-.15,.45,4.,.38,.58]]}
for name,region in regions.items():
    p=ROOT/'assets'/name/'flow-profile.json';data=json.loads(p.read_text(encoding='utf-8'));data['focusing_regions']=region;data['r33_note']='收弱强增密候选；有限宽度应变更晚进入，保留弯曲输运并避免过长尾束。';p.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
np.save(ROOT/'assets/common-flow.npy',common_texture())
p=ROOT/'build_gallery.py';s=p.read_text(encoding='utf-8').replace('R32','R33').replace('r31／r32','r31／r33').replace('本轮 r32','本轮 r33');p.write_text(s,encoding='utf-8')
p=ROOT/'inspect_white_boundary.py';s=p.read_text(encoding='utf-8').replace("'r32'","'r33'").replace('"r32"','"r33"').replace('r32-ui-','r33-ui-');p.write_text(s,encoding='utf-8')
