"""生成本轮发布核验入口，避免复用上一轮产物身份。"""
from pathlib import Path
HERE=Path(__file__).resolve().parent
s=(HERE/'final_front_coherence.py').read_text('utf-8')
s=s.replace('analysis/front-coherence','analysis/frame-difference').replace('device-front-coherence','device-frame-difference')
s=s.replace('(128,34)','(128,36)').replace("len(family['videos'])==34","len(family['videos'])==36").replace('videos=162','videos=164')
s=s.replace('实际渲染确认前沿衔接、角部收束、原纹理交接与拖尾宽度；两端采用共同规则，整帧误差不等于视觉相似度。','逐帧校准共同释放、输运与颗粒对比度；完整动画区域的留出相位误差下降，不能据此声称单粒子像素一致。')
(HERE/'final_frame_calibration.py').write_text(s,'utf-8')
s=(HERE/'check_front_publish.py').read_text('utf-8').replace('device-front-coherence','device-frame-difference')
(HERE/'check_frame_publish.py').write_text(s,'utf-8')
