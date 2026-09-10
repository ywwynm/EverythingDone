from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'build_gallery.py';s=p.read_text(encoding='utf-8').replace('R31','R32').replace('r30／r31','r31／r32').replace('上一版 r30、本轮 r31','上一版 r31、本轮 r32').replace('粒子消散 · 白底过渡优化','粒子消散 · 卷束与微片质感').replace('自有弹窗新增 r31／r32 并排对比。先看添加附件、调整颜色的粒子化边界，再检查灭霸和科比的局部运动。','对比 r31／r32：改善灭霸、科比的弯曲粒束，并同步复核自有弹窗的白底过渡、微片明暗和任意方向。')
p.write_text(s,encoding='utf-8')
p=ROOT/'inspect_white_boundary.py';s=p.read_text(encoding='utf-8').replace("'r31'","'r32'").replace('r31-ui-','r32-ui-').replace('"r31"','"r32"').replace("'r30'","'r31'").replace('"r30"','"r31"')
p.write_text(s,encoding='utf-8')
