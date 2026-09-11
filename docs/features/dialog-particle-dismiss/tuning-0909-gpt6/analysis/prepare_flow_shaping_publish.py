"""整理本轮发布日志；只在发布前运行一次，不包含上传凭据。"""
from pathlib import Path
from datetime import datetime
import json
HERE=Path(__file__).resolve().parents[1];ROOT=next(p for p in HERE.parents if (p/'gradlew.bat').is_file())
feature=HERE.parent
notes='''## 弹窗消散：卷动形变、长宽比例与触点距离

此前补充流动解决了停滞，但部分边界仍保持同样形状整体移动，行程偏短；长弹窗的斜向前沿还会沿长轴拉出尖峰。本次保留共同运动、微片材质、内容色份额和随机起始规则，继续针对这些问题调整。

- 在补充流动的区域加入随主流迁移的局部卷动与剪切，使粒子群轮廓持续变化；同时增加整体飞行距离。粒子不被原控件、屏幕或观测范围截停。
- 长、宽明显不等的弹窗采用受控的局部尺度和多个平滑重叠的解除区域，减少贯穿对角线的拉伸和尖锐折弯。
- 背景点击保留真实触点的远近：从控件边缘沿消逝方向向外量距，远点形成更长的流束，不在触点停止或反弹。返回键仍固定左上，采用默认中距离。
- 桌面新增颜色弹窗八方向，以及钢铁侠、添加附件、颜色弹窗各自的双方向三距离同屏视频；全部 118 个正式视频按原速、半速导出并校验。

调研参考了旋度噪声、电影流体细节、Houdini 的风速响应和引导力；远近到流束长度的映射属于交互设计，并非声称还原华为内部物理实现。桌面与 Android 共用着色器和参数，ParticleFlowGeometry 统一尺寸与触点距离换算，新增共同流场可信度资源；没有按场景添加专用参数。

复查全部既有 226 组标注输入和 7 组画廊输入的 GPU 状态，没有重复导出标注视频。代表输入扣除平移与旋转后，原先较机械的边缘群体形变明显增加。12 个模型单元测试、17 场景起止与重算一致性、30 组方向检查通过。9018f404 与 R5CW20BLNKL 共 14 组独立建材和 GPU 对照通过，并验证了真实背景近远点击、返回关闭和动画层清理。视觉效果仍以本轮慢放和实机体验验收。
'''
relative=Path('docs/features/dialog-particle-dismiss/debug-updates')/f'update-{datetime.now():%Y%m%d%H%M%S}.md'
path=ROOT/relative;path.parent.mkdir(exist_ok=True);path.write_text(notes,encoding='utf-8')
memory=ROOT/'memory/debug-update-notes.md';previous=memory.read_text('utf-8')
memory.write_text('# Debug update notes\n\n'+notes+'\n'+previous.removeprefix('# Debug update notes').lstrip(),encoding='utf-8')
(HERE/'analysis/flow-shaping/publish-notes.json').write_text(json.dumps({'notes':relative.as_posix()},ensure_ascii=False),encoding='utf-8')
print(relative.as_posix())
