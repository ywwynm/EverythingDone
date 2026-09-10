"""更新本轮审阅入口与说明，不混淆控制组、已发布版及共同模型。"""
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'build_gallery.py';s=p.read_text('utf-8')
s=s.replace('粒子消散 · 卷曲流束与内容色','粒子消散 · 共同释放与输运')
s=s.replace('人物照片与自有弹窗共用运动规则：局部卷动、聚成细缕，再逐渐散开。非面板内容拥有更多原色微片。对照列为此前发布的统一模型；本轮新增咖啡照片与有色面板作冻结后的留出验证，其余素材用于开发或回归。','沿用户认可的观测运动效果继续优化，照片与弹窗共用释放和运动规则。可比较华为参考、认可的控制组及本轮效果；另外保留此前发布版对照。新增灰度照片和横向弹窗作为冻结后的留出验证。')
s=s.replace("const kinds={'compare-versions'","const kinds={'compare-control':'认可控制组对照','compare-versions'")
s=s.replace("const kindOrder=['compare-versions'","const kindOrder=['compare-control','compare-versions'")
s=s.replace("const notes={'compare-versions':'按相同进度比较此前模型和统一规则。此前照片使用各自拟合的参数；本轮人物与弹窗共用同一规则。照片另有华为参考列（映射为 1 秒）。'","const notes={'compare-control':'三栏依次为华为参考、用户认可的观测运动控制组、本轮共同模型。用于检查提炼之后是否保留了轮廓与连续运动；中间列是冻结的对照产物。','compare-versions':'按相同进度比较此前发布版与本轮共同模型；两版都没有运行时人物专用配置。照片另有华为参考列（映射为 1 秒）。'")
s=s.replace("v.file==='ironman-compare-versions-0.5x.mp4'","v.file==='ironman-compare-control-0.5x.mp4'")
p.write_text(s,'utf-8',newline='\n');print('审阅页配置已更新。')
