"""为共同模型审阅页加入逐帧差异入口。"""
from pathlib import Path
HERE=Path(__file__).resolve().parents[1]
p=HERE/'build_gallery.py';s=p.read_text('utf-8')
s=s.replace("const kinds={'surface-whole':", "const kinds={'pixel-difference':'逐帧像素与结构差异','surface-whole':")
s=s.replace("const kindOrder=['surface-whole','curve-split'", "const kindOrder=['surface-whole','pixel-difference','curve-split'")
s=s.replace("const notes={'surface-whole':", "const notes={'pixel-difference':'依次为华为参考、共同模型、原始 RGB 差异和低频结构差异。亮处误差更大；画面没有配准形变、重排参考时间或遮蔽问题区域。水印与系统时钟变化保留显示，动画区误差另行统计。','surface-whole':")
p.write_text(s,'utf-8',newline='\n')
p=HERE/'analysis/build_rim_review.py';s=p.read_text('utf-8')
s=s.replace('修正前沿衔接与局部流动','逐帧校准释放与粒子流动')
s=s.replace('先看完整画面的前沿衔接、右上角收束及中心纹理，再看弧边与拖尾。左为华为参考，中为本轮调整前，右为本轮效果。','先看完整画面的连续运动，再看逐帧像素差与结构差。三栏对照依次为华为参考、本轮调整前和本轮共同模型；差异视频另有明确标注。')
p.write_text(s,'utf-8',newline='\n')
