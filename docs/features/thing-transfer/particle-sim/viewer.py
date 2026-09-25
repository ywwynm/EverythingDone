"""碰一碰动画模拟器：桌面预览与调参。

素材是真机录下的整段会话（`scenes/<设备>-send|receive`，见 README）：
- 发送从打开分享界面开始：记事抬起成卡片、罩层与面板、等待被碰的扩散圈、碰上后压下投影、卡片化成颗粒流进
  天线（天线处的光）、「已发送」停留、流回拼成卡片、关闭时落回；
- 接收从进入接收状态开始：罩层与屏幕边框粒子、碰上后边框换成来件颜色、列表插入与让位、颗粒从天线与屏幕四边
  流进空位、留出空洞、退出。
外部决定的时刻（打开、碰上、传输各阶段、插入、退出）取录下的值；应用自己控制的时长与缓动取参数
（`sim_timeline.py`），改了时长，后面的时刻跟着移。

打开「对照」时左边是参数文件里的值、右边是当前改动，两边同一种子、同一时刻。右侧是
`shared/tap-transfer/particles.properties` 的全部参数（只显示当前会话用得到的几组）：
- 终点一组与接收的边框占比改了要重算粒子轨迹（几十到三百毫秒）；
- 接收的罩层、流入时长改了要重算舞台（几毫秒）；光尘带按时间求值，改了立即生效；
- 其余只换时间线，立即生效。
算过的轨迹与舞台按参数缓存，来回改不重算。

快捷键：空格 播放/暂停，← → 逐帧（1/60 秒），Home 回到开头（或当前段的开头）。
运行：C:/Users/ywwynm/miniconda3/envs/everythingdone/python.exe -X utf8 viewer.py
"""
from __future__ import annotations

import secrets
import sys
import time
import traceback
from collections import OrderedDict

import moderngl
import numpy as np
from PySide6.QtCore import QTimer, Qt
from PySide6.QtGui import QColor, QFont, QFontDatabase, QImage, QKeySequence, QPainter, QPen, QPixmap, QShortcut
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QDoubleSpinBox, QFrame, QGridLayout, QGroupBox,
                               QHBoxLayout, QLabel, QLineEdit, QMessageBox, QProgressDialog, QPushButton, QScrollArea,
                               QSlider, QVBoxLayout, QWidget)

from export import pair_video, video
from sim_model import HERE, PARAM_SHAPE, PARAMS_FILE, PHYSICS_KEYS, STAGE_KEYS, Params, list_scenes
from sim_session import SessionPlayer, close_flight, make_flight
from sim_timeline import Session

# 参数：标题、每个分量的（最小, 最大, 步长）、小数位；颜色列表写 'colors'
EASE = [(0., 1., .01), (-1., 2., .01), (0., 1., .01), (-1., 2., .01)]
SPECS = {
    # 终点 · 整团滑行（发送的流出与流回、接收的流入共用）
    'sink_release_scale': ('释放时刻整体压缩', [(.05, 1., .01)], 2),
    'sink_arrival': ('被牵引的颗粒抵达的时间窗：最早 / 最晚（粒子时间轴 0–1）', [(.2, 1., .01)] * 2, 2),
    'sink_glide': ('整团滑行：牵引渐入的时间窗起 / 止、接近增益', [(0., 1., .01), (0., 1., .01), (.5, 4., .05)], 2),
    'sink_absorb_share': ('流向天线的颗粒里被吸入的比例（其余在途中消散）', [(0., 1., .01)], 2),
    'sink_free_life': ('途中消散的颗粒：寿命倍数', [(.2, 3., .05)], 2),
    'sink_drift': ('途中消散的颗粒：朝天线漂移（短边 / 秒）', [(0., 4., .05)], 2),
    'sink_min_life': ('最短寿命（秒）', [(.02, .6, .01)], 2),
    'sink_arrival_end': ('途中消散的颗粒最晚消失（粒子时间轴 0–1）', [(.2, 1., .01)], 2),
    # 终点 · 天线处
    'sink_spread_dp': ('落点在天线周围散开的半径（dp）', [(0., 160., 1.)], 1),
    'sink_swirl': ('滑行的缓弧：切向 / 径向', [(-1.5, 1.5, .02)], 2),
    'sink_spiral': ('贴近落点（涡旋核内）的切向 / 径向；0 = 末段不旋转', [(-4., 4., .05)], 2),
    'sink_vortex_dp': ('涡旋核半径（dp）', [(1., 240., 1.)], 1),
    'sink_absorb_dp': ('贴近天线时隐去：内 / 外半径（dp）', [(0., 160., 1.)] * 2, 1),
    'sink_fade_s': ('寿命末端的淡出（秒，弹窗是 0.075）', [(.01, .6, .01)], 2),
    'sink_near_scale': ('贴近天线时颗粒缩到的比例', [(.05, 1., .01)], 2),
    # 终点 · 流向光尘带（接收）
    'sink_frame_jitter': ('流向光尘带的颗粒沿边错开（占短边）', [(0., 1.5, .01)], 2),
    'sink_band_spiral': ('流向光尘带的颗粒：路径的螺线切向比', [(-2., 2., .02)], 2),
    'sink_curl': ('被牵引的颗粒途中的卷流强度', [(0., 2., .02)], 2),
    # 多张卡片同时拼出
    'sink_group_stagger': ('按卡片错开：第一张出生平移 / 最后一张抵达提前（粒子时间轴）', [(0., .4, .01)] * 2, 2),
    'sink_group_swirl': ('按卡片错开：切向倍数沿卡片顺序增加', [(-2., 2., .05)], 2),

    'send_dim_alpha': ('罩层不透明度：浅色 / 深色', [(0., 1., .01)] * 2, 2),
    'send_open_ms': ('罩层与面板打开（毫秒）', [(20, 3000, 10)], 0),
    'send_close_ms': ('罩层与面板关闭（毫秒）', [(20, 3000, 10)], 0),
    'send_lift_ms': ('记事抬起成卡片（毫秒）', [(20, 3000, 10)], 0),
    'send_lift_ease': ('抬起缓动 x1 y1 x2 y2', EASE, 2),
    'send_drop_ms': ('关闭时落回（毫秒）', [(20, 3000, 10)], 0),
    'send_drop_ease': ('落回缓动 x1 y1 x2 y2', EASE, 2),
    'send_lift_fade': ('卡片在抬起进度的前这一段淡入', [(.01, 1., .01)], 2),
    'send_detail_rise_dp': ('详情页：卡片从目标位置下方多远升起（dp）', [(0., 300., 1.)], 1),
    'send_detail_rise_scale': ('详情页：升起时的起始缩放', [(.5, 1.2, .01)], 2),
    'send_elevation_dp': ('抬起后的投影高度（dp）', [(0., 40., .5)], 1),
    'send_shadow_drop_ms': ('碰上后压下投影（毫秒）', [(10, 2000, 10)], 0),
    'send_shadow_restore_ms': ('旧版：流回后恢复投影（毫秒）', [(10, 2000, 10)], 0),
    'send_pulse_rings': ('天线处的扩散圈：圈数', [(0, 8, 1)], 0),
    'send_pulse_period_s': ('扩散圈：一圈的周期（秒）', [(.2, 6., .05)], 2),
    'send_pulse_radius_dp': ('扩散圈半径：起始 + 扩展（dp）', [(0., 120., 1.), (0., 240., 1.)], 1),
    'send_pulse_alpha': ('扩散圈最大不透明度', [(0., 1., .01)], 2),
    'send_pulse_shrink': ('流出时波纹收缩的比例（接收流入时同样）', [(0., .95, .01)], 2),

    'send_outflow_ms': ('流出时长（毫秒）', [(200, 6000, 50)], 0),
    'send_outflow_ease': ('流出缓动 x1 y1 x2 y2', EASE, 2),
    'send_sent_hold_ms': ('「已发送」停留，之后重新抬起（毫秒）', [(0, 5000, 50)], 0),
    'send_reform_ms': ('旧版：流回时长（毫秒）', [(200, 6000, 50)], 0),
    'send_reform_ease': ('旧版：流回缓动 x1 y1 x2 y2', EASE, 2),
    'send_glow_curve': ('天线光亮度：升起 a→b，熄灭 c→d（流出进度）', [(0., 1., .01)] * 4, 2),
    'send_glow_radius_dp': ('天线光半径：基础 + 随亮度增加（dp，接收也用）', [(0., 80., 1.)] * 2, 1),
    'send_glow_alpha': ('天线光不透明度', [(0., 1., .01)], 2),

    'receive_dim_alpha': ('罩层不透明度：浅色 / 深色', [(0., 1., .01)] * 2, 2),
    'receive_dim_fade_s': ('罩层淡入淡出（秒）', [(.02, 3., .01)], 2),
    'receive_hole_close_s': ('收到的卡片处的空洞合上（秒）', [(.02, 3., .05)], 2),

    'receive_border_inset_dp': ('光尘带离屏幕边缘（dp）', [(0., 40., .5)], 1),
    'receive_dust_per_dp': ('每 dp 周长的颗粒数', [(.5, 30., .5)], 1),
    'receive_dust_band_dp': ('带宽（dp）', [(2., 80., 1.)], 1),
    'receive_dust_size_dp': ('颗粒尺寸：最小 / 最大（dp）', [(.2, 4., .05)] * 2, 2),
    'receive_dust_speed_dp': ('沿边流动的平均速度（dp/秒，顺时针）', [(0., 200., 1.)], 0),
    'receive_dust_wander_dp': ('噪声漂移幅度（dp）', [(0., 30., .5)], 1),
    'receive_dust_flip_hz': ('翻转频率：最低 / 最高（Hz）', [(0., 6., .05)] * 2, 2),
    'receive_dust_rest': ('静止时出场的颗粒比例（进度越高越多）', [(0., 1., .01)], 2),
    'receive_dust_spread_dp': ('进度 100% 时比等待时多扩散的深度（dp，之后继续缓慢扩散）', [(0., 120., 1.)], 0),
    'receive_dust_inward_hz': ('颗粒向内扩散一轮的频率：最低 / 最高（Hz）', [(0., 3., .01)] * 2, 2),
    'receive_dust_glow': ('带底下的柔光：晕宽（dp）/ 峰值不透明度', [(0., 40., .5), (0., 1., .01)], 2),
    'receive_dust_pull_dp': ('旧版：开始流入时光尘带边淡去边向内移动（dp）', [(0., 200., 1.)], 0),
    'receive_dust_retreat': ('开始流入时没被接手的光尘向外退去（dp）/ 用时（秒）', [(0., 120., 1.), (.05, 3., .05)], 2),
    'receive_border_fade_s': ('光尘带出现、退场（秒）', [(.02, 3., .05)], 2),
    'receive_border_converge_s': ('开始流入时光尘带淡去（秒）', [(.02, 3., .05)], 2),
    'receive_border_palette': ('调色板（6 位十六进制，逗号分隔，沿屏幕一圈均匀分布）', 'colors', 0),
    'receive_border_palette_cycle_s': ('调色板转一圈（秒）', [(1., 200., 1.)], 0),
    'receive_border_palette_fade_s': ('碰上后换成来件颜色（秒）', [(.02, 5., .05)], 2),

    'receive_appear_s': ('流入时长（秒）', [(.2, 5., .05)], 2),
    'receive_frame_share': ('从光尘带流进卡片的颗粒占比（其余由冒出的颗粒接手）', [(0., 1., .05)], 2),
    'receive_frame_blend_dp': ('来自四周与来自天线的两部分之间的过渡宽度（dp）', [(0., 240., 1.)], 0),
    'receive_handoff': ('被接手的颗粒：牵引渐入（粒子时间）/ 途中卷流倍数 / 卷流取样频率',
                        [(.01, .6, .01), (0., 8., .05), (.2, 12., .05)], 2),

    # 接收 · 进度期间天线处的颗粒（代理卡片的倒放）
    'receive_emerge_pace': ('倒放时间轴：进度系数 / 最低速度（每秒）/ 到齐之后逼近的上限 / 进度的指数',
                            [(0., 1., .01), (.001, .5, .001), (.05, .95, .01), (.1, 3., .05)], 3),
    'receive_emerge_sink': ('从天线处出来的：散开半径 / 淡去外半径（dp）/ 占比（其余沿途浮现）/ 冒出时的淡入（时间轴）',
                            [(0., 80., 1.), (0., 80., 1.), (0., 1., .01), (.005, .3, .005)], 3),
    'receive_crossfade': ('交叉淡化：真实那层淡入（秒）/ 代理那层淡出的起 / 止（秒）', [(.02, 2., .01)] * 3, 2),
    'receive_dust_join': ('光尘接手：交接后倒放的起 / 止、最短飞行（时间轴）', [(0., .6, .01)] * 3, 2),
    'receive_emerge_aspect': ('预计落点高度的误差倍数（1 = 按发送方告知的高宽比，准确）', [(.2, 3., .05)], 2),

    # 旧版：Android 现行实现还在读，模拟器的新动画不用
    'sink_flight_base': ('飞行基础时长（秒）', [(.02, 1.5, .01)], 2),
    'sink_flight_per_span': ('每个短边距离加时（秒）', [(0., 1., .01)], 2),
    'sink_flight_jitter': ('飞行时长抖动（±）', [(0., .9, .01)], 2),
    'sink_homing': ('转向终点区间：起 / 止（飞行进度）', [(0., .99, .01), (.01, 1., .01)], 2),
    'sink_band_dp': ('边框落点带宽（dp）', [(0., 200., 1.)], 1),
    'receive_border_spacing_dp': ('边框粒子沿周长的间距（dp）', [(.3, 20., .1)], 1),
    'receive_border_lane_dp': ('流带宽度（dp）', [(0., 80., .5)], 1),
    'receive_border_speed_dp': ('平均速度（dp/秒，顺时针）', [(0., 400., 5.)], 0),
    'receive_border_wobble_dp': ('横向起伏（dp）', [(0., 30., .1)], 1),
    'receive_border_trail_s': ('拖尾按多少秒的运动长度画', [(.01, 2., .01)], 2),
    'receive_border_radius_dp': ('粒子实心半径：最小 / 最大（dp）', [(.1, 10., .1)] * 2, 1),
    'receive_border_particle_fade_s': ('新粒子淡入（秒）', [(.02, 5., .05)], 2),
    'receive_glow_rings': ('边框底下那圈光：圈数', [(0, 64, 1)], 0),
    'receive_glow_ring_dp': ('那圈光：每圈宽度（dp）', [(.1, 10., .1)], 1),
    'receive_glow_alpha': ('那圈光：最外圈不透明度', [(0., 1., .01)], 2),
}
_send = [k for k in SPECS if k.startswith('send_')]
_outflow = _send.index('send_outflow_ms')
LEGACY = ['sink_flight_base', 'sink_flight_per_span', 'sink_flight_jitter', 'sink_homing', 'sink_band_dp',
          'receive_border_spacing_dp', 'receive_border_lane_dp', 'receive_border_speed_dp', 'receive_border_wobble_dp',
          'receive_border_trail_s', 'receive_border_radius_dp', 'receive_border_particle_fade_s',
          'receive_glow_rings', 'receive_glow_ring_dp', 'receive_glow_alpha',
          'send_glow_curve', 'send_glow_radius_dp', 'send_glow_alpha', 'send_reform_ms', 'send_reform_ease',
          'send_shadow_restore_ms', 'receive_dust_pull_dp']
GROUPS = [
    ('终点 · 整团滑行（发送与接收共用）', 'both', ['sink_release_scale', 'sink_arrival', 'sink_glide',
                                          'sink_absorb_share', 'sink_free_life', 'sink_drift', 'sink_min_life',
                                          'sink_arrival_end']),
    ('终点 · 天线处', 'both', ['sink_spread_dp', 'sink_swirl', 'sink_spiral', 'sink_vortex_dp', 'sink_absorb_dp',
                           'sink_fade_s', 'sink_near_scale']),
    ('终点 · 流向光尘带（接收）', 'receive', ['sink_frame_jitter', 'sink_band_spiral', 'sink_curl']),
    ('终点 · 多张卡片同时拼出（接收）', 'receive', ['sink_group_stagger', 'sink_group_swirl']),
    ('发送 · 打开、等待与关闭（波纹接收时也用）', 'send', [k for k in _send[:_outflow] if k not in LEGACY]),
    ('发送 · 流出与重新抬起', 'send', [k for k in _send[_outflow:] if k not in LEGACY]),
    ('接收 · 罩层', 'receive', ['receive_dim_alpha', 'receive_dim_fade_s', 'receive_hole_close_s']),
    ('接收 · 屏幕四周的光尘带', 'receive', ['receive_border_inset_dp']
     + [k for k in SPECS if k.startswith('receive_dust_') and k not in LEGACY and k != 'receive_dust_join']
     + ['receive_border_fade_s', 'receive_border_converge_s', 'receive_border_palette',
        'receive_border_palette_cycle_s', 'receive_border_palette_fade_s']),
    ('接收 · 进度期间天线处的颗粒（弹窗出现的前半段）', 'receive',
     [k for k in SPECS if k.startswith('receive_emerge_')]),
    ('接收 · 颗粒流入与交接', 'receive', ['receive_appear_s', 'receive_frame_share', 'receive_frame_blend_dp',
                                     'receive_handoff',
                                     'receive_crossfade', 'receive_dust_join']),
    ('旧版参数（Android 现行实现在用，新动画不用）', 'legacy', LEGACY),
]
assert set(SPECS) == set(PARAM_SHAPE), set(SPECS) ^ set(PARAM_SHAPE)
assert sorted(k for _, _, keys in GROUPS for k in keys) == sorted(SPECS)
HEAVY_KEYS = PHYSICS_KEYS | STAGE_KEYS | {'receive_appear_s'}

STYLE = '''
QWidget{background:#141a23;color:#e6ecf4;font-family:"Microsoft YaHei";font-size:13px;}
QGroupBox{border:1px solid #2c3a4e;border-radius:8px;margin-top:14px;padding:8px 8px 6px 8px;}
QGroupBox::title{subcontrol-origin:margin;left:10px;padding:0 4px;color:#9fb3cc;}
QPushButton,QComboBox,QDoubleSpinBox,QLineEdit{padding:5px 9px;background:#223149;border:1px solid #3a4f6b;
    border-radius:6px;}
QPushButton:hover{background:#2d4563;}
QPushButton:checked{background:#3d6a99;}
QDoubleSpinBox[changed="true"],QLineEdit[changed="true"]{border:1px solid #e0a040;}
QLineEdit[invalid="true"]{border:1px solid #e05050;}
QDoubleSpinBox:disabled{color:#56657a;background:#1a2230;border:1px solid #273345;}
QScrollArea{border:none;}
'''


def relevant(key: str, kind: str) -> bool:
    if key.startswith('send_'):
        return kind == 'send' or key == 'send_glow_radius_dp'
    if key.startswith('receive_') or key in ('sink_frame_jitter', 'sink_band_spiral', 'sink_curl'):
        return kind == 'receive'
    return True


def parse_palette(text: str) -> list[int]:
    colors = []
    for part in text.replace(' ', '').split(','):
        part = part.lstrip('#')
        if len(part) != 6:
            raise ValueError(f'「{part}」不是 6 位十六进制')
        colors.append(int(part, 16))
    if not colors:
        raise ValueError('至少一个颜色')
    return colors


class Phone(QLabel):
    """一台手机的画面；可叠加天线、边框终点与快照范围的标注。"""

    def __init__(self, caption: str, on_click=None):
        super().__init__()
        self.on_click = on_click
        self.placement = None  # (ox, oy, scale)：画面在控件里的位置，点击换算用
        self.setAlignment(Qt.AlignCenter)
        self.setMinimumSize(240, 420)
        self.setStyleSheet('background:#0c1017;border:1px solid #243044;border-radius:8px;')
        self.caption = caption
        self.image: np.ndarray | None = None
        self.marks = None

    def show_image(self, image: np.ndarray, marks=None):
        self.image, self.marks = image, marks
        self._paint()

    def _paint(self):
        if self.image is None:
            return
        h, w = self.image.shape[:2]
        qimage = QImage(self.image.data, w, h, self.image.strides[0], QImage.Format_RGB888).copy()
        box_w, box_h = self.width() - 8, self.height() - 34
        s = min(box_w / w, box_h / h)
        pix = QPixmap.fromImage(qimage).scaled(max(1, int(w * s)), max(1, int(h * s)), Qt.KeepAspectRatio,
                                              Qt.SmoothTransformation)
        canvas = QPixmap(self.width(), self.height())
        canvas.fill(QColor('#0c1017'))
        painter = QPainter(canvas)
        painter.setRenderHint(QPainter.Antialiasing)
        ox, oy = (self.width() - pix.width()) // 2, 28 + (box_h - pix.height()) // 2
        self.placement = (ox, oy, pix.width() / w)
        painter.drawPixmap(ox, oy, pix)
        painter.setPen(QColor('#9fb3cc'))
        painter.drawText(10, 20, self.caption)
        if self.marks:
            scale = pix.width() / self.marks['screen'][0]

            def at(x, y):
                return ox + x * scale, oy + y * scale
            if self.marks.get('frame'):
                l, t, r, b = self.marks['frame']
                painter.setPen(QPen(QColor(80, 200, 255, 180), 1, Qt.DashLine))
                x0, y0 = at(l, t)
                x1, y1 = at(r, b)
                painter.drawRect(int(x0), int(y0), int(x1 - x0), int(y1 - y0))
            l, t, r, b = self.marks['bounds']
            painter.setPen(QPen(QColor(255, 200, 80, 170), 1, Qt.DashLine))
            x0, y0 = at(l, t)
            x1, y1 = at(r, b)
            painter.drawRect(int(x0), int(y0), int(x1 - x0), int(y1 - y0))
            sx, sy = at(*self.marks['sink'])
            painter.setPen(QPen(QColor(255, 80, 80), 2))
            painter.drawLine(int(sx - 9), int(sy), int(sx + 9), int(sy))
            painter.drawLine(int(sx), int(sy - 9), int(sx), int(sy + 9))
        painter.end()
        self.setPixmap(canvas)

    def resizeEvent(self, event):
        self._paint()
        super().resizeEvent(event)

    def mousePressEvent(self, event):
        if self.on_click is None or self.placement is None or self.image is None:
            return
        ox, oy, s = self.placement
        h, w = self.image.shape[:2]
        # 画面按出图分辨率显示；换成占屏幕的比例交给调用方
        x, y = (event.position().x() - ox) / s, (event.position().y() - oy) / s
        if 0 <= x < w and 0 <= y < h:
            self.on_click(x / w, y / h)


class Marks(QWidget):
    """时间轴下方的阶段刻度：当前参数下各阶段的起点。"""

    def __init__(self):
        super().__init__()
        self.setFixedHeight(22)
        self.marks: list[tuple[str, float]] = []
        self.duration = 1.
        self.cursor = 0.

    def set(self, marks, duration, cursor):
        self.marks, self.duration, self.cursor = marks, max(duration, 1e-3), cursor
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        margin = 8  # 与滑块两端留白相当
        width = self.width() - 2 * margin
        font = painter.font()
        font.setPointSize(8)
        painter.setFont(font)
        last_right = -1e9
        for name, t in self.marks:
            x = margin + t / self.duration * width
            painter.setPen(QPen(QColor('#5f7fa6'), 1))
            painter.drawLine(int(x), 0, int(x), 7)
            text_w = painter.fontMetrics().horizontalAdvance(name)
            if x + 2 > last_right:  # 挤在一起的名字不重叠
                painter.setPen(QColor('#8fa2ba'))
                painter.drawText(int(x + 2), 19, name)
                last_right = x + 2 + text_w + 6
        painter.end()


class Swatch(QLabel):
    """调色板的色块预览。"""

    def __init__(self):
        super().__init__()
        self.setFixedHeight(14)
        self.colors: list[int] = []

    def set(self, colors):
        self.colors = list(colors)
        self.update()

    def paintEvent(self, event):
        if not self.colors:
            return
        painter = QPainter(self)
        w = self.width() / len(self.colors)
        for k, c in enumerate(self.colors):
            painter.fillRect(int(k * w), 0, int((k + 1) * w) - int(k * w), self.height(), QColor(f'#{c:06X}'))
        painter.end()


class Viewer(QWidget):
    PREVIEW_SCALE = .5
    FLIGHT_CACHE = 4
    STAGE_CACHE = 8
    SEGMENT_LEAD = .25   # 按段循环时，段前后各多放一点
    SEGMENT_TAIL = .35

    def __init__(self):
        super().__init__()
        self.setWindowTitle('完事儿 · 碰一碰动画模拟器')
        self.resize(1560, 1040)
        self.setStyleSheet(STYLE)
        self.ctx = moderngl.create_standalone_context(require=430)
        self.paths = [p for p in list_scenes() if (p / 'session.json').is_file()]
        if not self.paths:
            raise SystemExit('scenes/ 下没有会话素材（session.json），先用 device/capture.py 抓一套')
        self.loaded: dict = {}
        self.file_params = Params.load()
        self.params = self.file_params.copy()
        self.session: Session | None = None
        self.seed = 0
        self.recorded_sinks: dict = {}   # 素材路径 → (天线, 释放起点)：挪过天线之后恢复用
        self.current: SessionPlayer | None = None
        self.baseline: SessionPlayer | None = None
        self.baseline_key = None
        self.flights: OrderedDict = OrderedDict()
        self.stages: OrderedDict = OrderedDict()
        self.work: list[str] = []
        self.t = 0.
        self.playing = False
        self.speed = 1.
        self.last = time.perf_counter()
        self.segment: tuple[float, float] | None = None
        self.inputs: dict[str, list[QDoubleSpinBox]] = {}
        self.groups: list[tuple[QGroupBox, str]] = []
        self._build_ui()
        self.heavy_timer = QTimer(self)
        self.heavy_timer.setSingleShot(True)
        self.heavy_timer.timeout.connect(self.refresh)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(16)
        self.select_scene(0)

    # ------------------------------------------------------------ 界面

    def _build_ui(self):
        root = QHBoxLayout(self)
        left = QVBoxLayout()
        root.addLayout(left, 1)
        head = QHBoxLayout()
        left.addLayout(head)
        self.scene_box = QComboBox()
        for path in self.paths:
            self.scene_box.addItem(path.name)
        self.scene_box.currentIndexChanged.connect(self.select_scene)
        head.addWidget(self.scene_box, 1)
        recolor_button = QPushButton('随机记事颜色')
        recolor_button.setToolTip('把收发的记事换成随机的纯色或渐变（应用的调色板与 8 个渐变方向），'
                                  '每张卡片各自随机')
        recolor_button.clicked.connect(self.random_color)
        head.addWidget(recolor_button)
        original_button = QPushButton('原色')
        original_button.setToolTip('恢复素材录下时的记事颜色')
        original_button.clicked.connect(self.original_color)
        head.addWidget(original_button)
        self.compare = QPushButton('对照参数文件')
        self.compare.setCheckable(True)
        self.compare.toggled.connect(lambda _: self.refresh_layout())
        head.addWidget(self.compare)
        self.marks = QCheckBox('标注天线 / 边框 / 快照范围')
        self.marks.toggled.connect(lambda _: self.draw())
        head.addWidget(self.marks)
        self.full = QCheckBox('全分辨率')
        self.full.toggled.connect(lambda _: self.reset_players())
        head.addWidget(self.full)
        head.addWidget(QLabel('边框推进'))
        self.hz_box = QComboBox()
        self.hz_box.addItems(['120 Hz', '90 Hz', '60 Hz', '144 Hz'])
        self.hz_box.setToolTip('接收舞台（边框粒子）按设备刷新率逐帧推进：拖尾长度与帧间隔有关')
        self.hz_box.currentIndexChanged.connect(lambda _: self.refresh())
        head.addWidget(self.hz_box)

        phones = QHBoxLayout()
        left.addLayout(phones, 1)
        self.phone_base = Phone('参数文件', self.set_sink)
        self.phone_cur = Phone('当前参数', self.set_sink)
        phones.addWidget(self.phone_base, 1)
        phones.addWidget(self.phone_cur, 1)

        self.slider = QSlider(Qt.Horizontal)
        self.slider.setRange(0, 2000)
        self.slider.valueChanged.connect(self.scrub)
        left.addWidget(self.slider)
        self.ticks = Marks()
        left.addWidget(self.ticks)
        transport = QHBoxLayout()
        left.addLayout(transport)
        self.play_button = QPushButton('播放')
        self.play_button.clicked.connect(self.toggle)
        transport.addWidget(self.play_button)
        for text, delta in (('上一帧', -1 / 60), ('下一帧', 1 / 60)):
            b = QPushButton(text)
            b.clicked.connect(lambda _=False, d=delta: self.step(d))
            transport.addWidget(b)
        self.rate = QComboBox()
        self.rate.addItems(['1 倍速', '0.5 倍速', '0.25 倍速', '0.1 倍速'])
        self.rate.currentIndexChanged.connect(lambda i: setattr(self, 'speed', [1., .5, .25, .1][i]))
        transport.addWidget(self.rate)
        transport.addWidget(QLabel('片段'))
        self.segment_box = QComboBox()
        self.segment_box.setMinimumWidth(170)
        self.segment_box.setToolTip('选一段：跳到它的开头，播放时只在这一段里循环')
        self.segment_box.currentIndexChanged.connect(self.select_segment)
        transport.addWidget(self.segment_box)
        self.loop = QCheckBox('循环')
        self.loop.setChecked(True)
        transport.addWidget(self.loop)
        new_seed = QPushButton('换一种效果')
        new_seed.setToolTip('换粒子的随机种子（同一组参数下颗粒的另一种分布）')
        new_seed.clicked.connect(self.new_seed)
        transport.addWidget(new_seed)
        scene_seed = QPushButton('素材里的种子')
        scene_seed.clicked.connect(self.scene_seed)
        transport.addWidget(scene_seed)
        sink_back = QPushButton('素材里的天线位置')
        sink_back.setToolTip('在画面上点一下可以把天线挪到那里')
        sink_back.clicked.connect(self.restore_sink)
        transport.addWidget(sink_back)
        transport.addStretch()
        self.status = QLabel()
        left.addWidget(self.status)
        self.info = QLabel()
        self.info.setStyleSheet('color:#8fa2ba;font-size:12px;')
        self.info.setWordWrap(True)
        left.addWidget(self.info)

        panel = QWidget()
        panel_layout = QVBoxLayout(panel)
        for title, kind, keys in GROUPS:
            group = QGroupBox(title)
            grid = QGridLayout(group)
            row = 0
            for key in keys:
                label, ranges, decimals = SPECS[key]
                name = QLabel(label)
                name.setWordWrap(True)
                cost = ('改动后重算粒子轨迹' if key in PHYSICS_KEYS else
                        '改动后重算接收舞台' if key in HEAVY_KEYS else '只换时间线，立即生效')
                name.setToolTip(f'{key}（{cost}）')
                grid.addWidget(name, row, 0, 1, 4)
                row += 1
                if ranges == 'colors':
                    self.palette_edit = QLineEdit()
                    self.palette_edit.editingFinished.connect(lambda k=key: self.palette_changed(k))
                    grid.addWidget(self.palette_edit, row, 0, 1, 4)
                    row += 1
                    self.swatch = Swatch()
                    grid.addWidget(self.swatch, row, 0, 1, 4)
                    row += 1
                    continue
                boxes = []
                for i, (lo, hi, step) in enumerate(ranges):
                    box = QDoubleSpinBox()
                    box.setRange(lo, hi)
                    box.setSingleStep(step)
                    box.setDecimals(decimals)
                    box.setKeyboardTracking(False)
                    box.valueChanged.connect(lambda _=0., k=key: self.param_changed(k))
                    grid.addWidget(box, row, i)
                    boxes.append(box)
                self.inputs[key] = boxes
                row += 1
            self.groups.append((group, kind))
            panel_layout.addWidget(group)
        self.show_legacy = QCheckBox('显示旧版参数（Android 现行实现在用）')
        self.show_legacy.toggled.connect(lambda _: self.mark_changed())
        panel_layout.addWidget(self.show_legacy)
        buttons = QGridLayout()
        save = QPushButton('写回参数文件')
        save.clicked.connect(self.save)
        reload_ = QPushButton('重新读取参数文件')
        reload_.clicked.connect(self.reload_file)
        recorded = QPushButton('用素材录制时的参数')
        recorded.clicked.connect(self.use_recorded)
        export = QPushButton('导出视频（1 倍 + 0.5 倍）')
        export.setToolTip('选了片段就只导出那一段；打开对照时导出左右并排的对照视频')
        export.clicked.connect(self.export)
        buttons.addWidget(save, 0, 0)
        buttons.addWidget(reload_, 0, 1)
        buttons.addWidget(recorded, 1, 0)
        buttons.addWidget(export, 1, 1)
        panel_layout.addLayout(buttons)
        note = QLabel(f'参数文件：{PARAMS_FILE}\n写回后重新编译 debug 包即在手机上生效。橙框 = 与参数文件不同。'
                      '\n在画面上点一下可以挪动天线位置（发送的卡片不会像真机那样为天线让位，只作流向参考）。')
        note.setWordWrap(True)
        note.setStyleSheet('color:#8fa2ba;font-size:12px;')
        panel_layout.addWidget(note)
        panel_layout.addStretch()
        scroll = QScrollArea()
        scroll.setWidget(panel)
        scroll.setWidgetResizable(True)
        scroll.setFixedWidth(540)
        scroll.setFrameShape(QFrame.NoFrame)
        root.addWidget(scroll)

        QShortcut(QKeySequence('Space'), self, activated=self.toggle)
        QShortcut(QKeySequence('Left'), self, activated=lambda: self.step(-1 / 60))
        QShortcut(QKeySequence('Right'), self, activated=lambda: self.step(1 / 60))
        QShortcut(QKeySequence('Home'), self, activated=lambda: self.seek(self.segment[0] if self.segment else 0.))
        self.fill_inputs()

    def fill_inputs(self):
        for key, boxes in self.inputs.items():
            for box, value in zip(boxes, self.params.values[key]):
                box.blockSignals(True)
                box.setValue(value)
                box.blockSignals(False)
        colors = self.params.values['receive_border_palette']
        self.palette_edit.setText(Params.format(colors, 'receive_border_palette'))
        self.palette_edit.setProperty('invalid', 'false')
        self.swatch.set(colors)
        self.mark_changed()

    def _restyle(self, widget, prop, flag):
        widget.setProperty(prop, 'true' if flag else 'false')
        widget.style().unpolish(widget)
        widget.style().polish(widget)

    def mark_changed(self):
        for key, boxes in self.inputs.items():
            for box, a, b in zip(boxes, self.params.values[key], self.file_params.values[key]):
                self._restyle(box, 'changed', abs(a - b) > 1e-9)
        key = 'receive_border_palette'
        self._restyle(self.palette_edit, 'changed', self.params.values[key] != self.file_params.values[key])
        kind = self.session.kind if self.session else 'send'
        for group, group_kind in self.groups:
            group.setVisible(group_kind in ('both', kind) or (group_kind == 'legacy' and self.show_legacy.isChecked()))
        for key, boxes in self.inputs.items():
            for box in boxes:
                box.setEnabled(relevant(key, kind))

    def refresh_layout(self):
        self.phone_base.setVisible(self.compare.isChecked())
        self.phone_cur.caption = '当前参数' if self.compare.isChecked() else '模拟器'
        self.refresh()

    # ------------------------------------------------------------ 会话与缓存

    @property
    def scale(self) -> float:
        return 1. if self.full.isChecked() else self.PREVIEW_SCALE

    @property
    def hz(self) -> float:
        return float(self.hz_box.currentText().split()[0])

    @property
    def flight_scene(self):
        return self.session.flights[0][1] if self.session and self.session.flights else None

    def select_scene(self, index: int):
        path = self.paths[index]
        try:
            if path not in self.loaded:
                self.loaded[path] = Session(path)
                flights = self.loaded[path].flights
                if flights:
                    meta = flights[0][1].meta
                    self.recorded_sinks[path] = (list(meta['sink']), meta.get('release_origin'))
            session = self.loaded[path]
        except Exception as error:  # noqa: BLE001  素材坏了也要能看到是哪一份
            QMessageBox.warning(self, '素材读取失败', f'{path.name}\n{error}')
            return
        self.release_players()
        self.session = session
        scene = self.flight_scene
        if scene is not None:
            self.seed = int(scene.meta['seed'])
        self.t = 0.
        self.segment = None
        self.mark_changed()
        self.phone_base.setVisible(self.compare.isChecked())
        self.refresh()

    def _plan(self, params: Params):
        plan = SessionPlayer.plan_for(self.session, params)
        scene = self.flight_scene
        if self.session.kind == 'send' and scene is not None:
            # 挪过天线时，扩散圈与光跟着挪（舞台坐标 = 屏幕坐标 − 舞台偏移）
            sx, sy = scene.meta['sink']
            ox, oy = plan.stage_offset
            plan.antenna = (sx - ox, sy - oy)
        return plan

    def _flight_key(self, params: Params, plan):
        return SessionPlayer.flight_key_for(self.session, params, self.seed, plan) + (self.scale,)

    def _flight(self, params: Params, plan, track):
        """粒子飞行（材料、渲染器；接收时连同进度期间冒出的颗粒与交接）。接收的交接要用舞台的调色板，
        所以舞台先算。"""
        scene = self.flight_scene
        if scene is None:
            return None
        key = self._flight_key(params, plan)
        if key in self.flights:
            self.flights.move_to_end(key)
            return self.flights[key]
        started = time.perf_counter()
        flight = make_flight(self.session, params, self.ctx, self.scale, self.seed, plan, track)
        build = flight[0]
        text = f'粒子轨迹 {build.stats["count"]} 片'
        if flight[2] is not None:
            text += f'（冒出 {flight[2].n} 片）'
        self.work.append(f'{text} {(time.perf_counter() - started) * 1000:.0f} ms')
        self.flights[key] = flight
        in_use = {id(p.flight) for p in (self.current, self.baseline) if p is not None}
        while len(self.flights) > self.FLIGHT_CACHE:
            victim = next((k for k, v in self.flights.items() if id(v[1]) not in in_use and k != key), None)
            if victim is None:
                break
            close_flight(self.flights.pop(victim))
        return flight

    def _stage(self, params: Params, plan):
        if self.session.kind != 'receive':
            return None
        key = SessionPlayer.stage_key(self.session, params, plan, self.hz)
        if key in self.stages:
            self.stages.move_to_end(key)
            return self.stages[key]
        started = time.perf_counter()
        track = SessionPlayer.simulate_stage(self.session, params, plan, self.hz)
        self.work.append(f'边框舞台 {(time.perf_counter() - started) * 1000:.0f} ms')
        self.stages[key] = track
        while len(self.stages) > self.STAGE_CACHE:
            self.stages.popitem(last=False)
        return track

    def _player(self, player: SessionPlayer | None, params: Params) -> SessionPlayer:
        plan = self._plan(params)
        track = self._stage(params, plan)
        flight = self._flight(params, plan, track)
        if player is not None and player.s is self.session and player.output_scale == self.scale:
            player.seed = self.seed
            player.stage_hz = self.hz
            player.retune(params, plan=plan, stage_track=track, flight=flight)
            return player
        if player is not None:
            player.close()
        return SessionPlayer(self.session, params, self.ctx, self.scale, self.seed, self.hz, track, flight, plan)

    def refresh(self):
        """按当前参数更新右边（与打开对照时的左边）；轨迹与舞台先查缓存。"""
        if self.session is None:
            return
        problems = self.params.problems()
        if problems:
            self.info.setText('<span style="color:#ff8a7a">参数不合法，手机读参数时会抛异常：'
                              + '；'.join(problems) + '</span>')
            return
        self.work = []
        busy = False
        try:
            if not self._cached(self.params) or (self.compare.isChecked() and not self._cached(self.file_params)):
                QApplication.setOverrideCursor(Qt.WaitCursor)
                busy = True
            self.current = self._player(self.current, self.params.copy())
            if self.compare.isChecked():
                key = (str(self.session.path), self.seed, self.scale, self.hz, tuple(self.flight_scene.meta['sink'])
                       if self.flight_scene else None,
                       tuple((k, tuple(v)) for k, v in sorted(self.file_params.values.items())))
                if self.baseline is None or self.baseline_key != key:
                    self.baseline = self._player(self.baseline, self.file_params.copy())
                    self.baseline_key = key
            elif self.baseline is not None:
                self.baseline.close()
                self.baseline, self.baseline_key = None, None
        except Exception:  # noqa: BLE001  参数组合出问题时保留界面，给出原因
            QMessageBox.warning(self, '重算失败', traceback.format_exc()[-1800:])
            return
        finally:
            if busy:
                QApplication.restoreOverrideCursor()
        text = self.current.describe()
        text += ' · ' + ('；'.join(self.work) if self.work else '轨迹与舞台取自缓存')
        self.info.setText(text)
        self.update_segments()
        self.draw()

    def _cached(self, params: Params) -> bool:
        scene = self.flight_scene
        if scene is not None and self._flight_key(params, self._plan(params)) not in self.flights:
            return False
        if self.session.kind == 'receive':
            return SessionPlayer.stage_key(self.session, params, self._plan(params), self.hz) in self.stages
        return True

    def reset_players(self):
        self.release_players()
        self.refresh()

    def release_players(self):
        for player in (self.current, self.baseline):
            if player is not None:
                player.close()
        self.current = self.baseline = None
        self.baseline_key = None

    def param_changed(self, key: str):
        values = [box.value() for box in self.inputs[key]]
        try:
            self.params.set(key, values)
        except ValueError:
            return
        self.mark_changed()
        if key in HEAVY_KEYS:
            self.heavy_timer.start(220)
        else:
            self.refresh()

    def palette_changed(self, key: str):
        try:
            colors = parse_palette(self.palette_edit.text())
        except ValueError as error:
            self._restyle(self.palette_edit, 'invalid', True)
            self.palette_edit.setToolTip(str(error))
            return
        self._restyle(self.palette_edit, 'invalid', False)
        self.palette_edit.setToolTip('')
        if colors == self.params.values[key]:
            return
        self.params.set(key, colors)
        self.swatch.set(colors)
        self.mark_changed()
        self.heavy_timer.start(10)

    # ------------------------------------------------------------ 片段

    def update_segments(self):
        marks = self.current.plan.milestones() if self.current else []
        previous = self.segment_box.currentText().split('  ')[0] if self.segment_box.count() else '整段'
        self.segment_box.blockSignals(True)
        self.segment_box.clear()
        self.segment_box.addItem('整段')
        for name, t in marks:
            self.segment_box.addItem(f'{name}  {t:.2f}s')
        names = ['整段'] + [n for n, _ in marks]
        index = names.index(previous) if previous in names else 0
        self.segment_box.setCurrentIndex(index)
        self.segment_box.blockSignals(False)
        self._set_segment(index, seek=False)

    def select_segment(self, index: int):
        self._set_segment(index, seek=True)

    def _set_segment(self, index: int, seek: bool):
        marks = self.current.plan.milestones() if self.current else []
        if index <= 0 or index > len(marks):
            self.segment = None
        else:
            t0 = marks[index - 1][1]
            t1 = marks[index][1] if index < len(marks) else self.duration
            self.segment = (max(0., t0 - self.SEGMENT_LEAD), min(self.duration, t1 + self.SEGMENT_TAIL))
            if seek:
                self.seek(self.segment[0])
        self.draw()

    # ------------------------------------------------------------ 播放

    @property
    def duration(self) -> float:
        durations = [p.duration for p in (self.current, self.baseline) if p is not None]
        return max(durations) if durations else 1.

    def toggle(self):
        self.playing = not self.playing
        self.play_button.setText('暂停' if self.playing else '播放')
        self.last = time.perf_counter()

    def step(self, delta: float):
        self.playing = False
        self.play_button.setText('播放')
        self.seek(self.t + delta)

    def seek(self, t: float):
        self.t = float(np.clip(t, 0., self.duration))
        self.draw()

    def scrub(self, value: int):
        self.t = value / 2000 * self.duration
        self.draw(update_slider=False)

    def tick(self):
        now = time.perf_counter()
        dt = min(now - self.last, .1)
        self.last = now
        if not self.playing:
            return
        self.t += dt * self.speed
        lo, hi = self.segment if self.segment else (0., self.duration)
        if self.t > hi:
            if self.loop.isChecked():
                self.t = lo
            else:
                self.t = hi
                self.toggle()
        self.draw()

    def draw(self, update_slider: bool = True):
        if self.current is None:
            return
        marks = None
        scene = self.flight_scene
        if self.marks.isChecked() and scene is not None:
            marks = dict(screen=self.session.screen, bounds=scene.bounds, sink=scene.sink, frame=scene.frame)
        image, label = self.current.render(min(self.t, self.current.duration))
        self.phone_cur.show_image(image, marks)
        if self.baseline is not None and self.compare.isChecked():
            base, base_label = self.baseline.render(min(self.t, self.baseline.duration))
            self.phone_base.show_image(base, marks)
            if base_label != label:
                label = f'文件：{base_label} ｜ 当前：{label}'
        if update_slider:
            self.slider.blockSignals(True)
            self.slider.setValue(round(self.t / self.duration * 2000))
            self.slider.blockSignals(False)
        self.ticks.set(self.current.plan.milestones(), self.duration, self.t)
        self.status.setText(f'{self.t:5.2f} / {self.duration:.2f} s · {label} · 种子 {self.seed}')

    def set_sink(self, fx: float, fy: float):
        """把天线挪到画面上点的那一点；起点随之按「快照上离天线最近的点」重新求。"""
        scene = self.flight_scene
        if scene is None:
            return
        w, h = self.session.screen
        scene.meta['sink'] = [fx * w, fy * h]
        scene.meta['release_origin'] = None
        self.refresh()

    def restore_sink(self):
        scene = self.flight_scene
        key = getattr(self.session, 'base', self.session).path
        if scene is None or key not in self.recorded_sinks:
            return
        sink, origin = self.recorded_sinks[key]
        scene.meta['sink'], scene.meta['release_origin'] = list(sink), origin
        self.refresh()

    def random_color(self):
        """当前素材换一种随机的记事颜色（每张卡片各自随机），从原会话出发，不在上一次的换色上叠加。"""
        from sim_recolor import recolor
        base = getattr(self.session, 'base', self.session)
        if base is None:
            return
        try:
            recolored = recolor(base, secrets.randbits(30))
        except Exception:  # noqa: BLE001
            QMessageBox.warning(self, '换色失败', traceback.format_exc()[-1600:])
            return
        self.release_players()
        self.session = recolored
        self.refresh()
        self.info.setText(recolored.title + ' · ' + self.info.text())

    def original_color(self):
        base = getattr(self.session, 'base', None)
        if base is None:
            return
        self.release_players()
        self.session = base
        self.refresh()

    def new_seed(self):
        self.seed = secrets.randbits(31)
        self.refresh()

    def scene_seed(self):
        if self.flight_scene is not None:
            self.seed = int(self.flight_scene.meta['seed'])
        self.refresh()

    # ------------------------------------------------------------ 参数文件

    def save(self):
        problems = self.params.problems()
        if problems:
            QMessageBox.warning(self, '写回参数文件', '参数不合法，手机读参数时会抛异常，未写回：\n' + '\n'.join(problems))
            return
        changed = [k for k in PARAM_SHAPE if self.params.values[k] != self.file_params.values[k]]
        if not changed:
            QMessageBox.information(self, '写回参数文件', '与参数文件相同，没有要写的。')
            return
        text = '\n'.join(f'{k}: {Params.format(self.file_params.values[k], k)} → {Params.format(self.params.values[k], k)}'
                         for k in changed)
        if QMessageBox.question(self, '写回参数文件', f'写回这些改动？\n\n{text}') != QMessageBox.Yes:
            return
        self.params.save()
        self.file_params = Params.load()
        self.mark_changed()
        self.refresh()

    def reload_file(self):
        self.file_params = Params.load()
        self.params = self.file_params.copy()
        self.fill_inputs()
        self.refresh()

    def use_recorded(self):
        self.params = Params.from_scene(self.session)
        self.fill_inputs()
        self.refresh()

    def export(self):
        if self.current is None:
            return
        stamp = time.strftime('%Y%m%d-%H%M%S')
        lo, hi = self.segment if self.segment else (0., self.duration)
        pair = self.compare.isChecked()
        name = f'{self.session.path.name}-{stamp}' + ('-对照' if pair else '')
        out = HERE / 'out' / name
        # 全分辨率出图（预览是半分辨率）；轨迹与舞台照样走缓存
        players = []
        full = self.full.isChecked()
        try:
            self.full.blockSignals(True)
            self.full.setChecked(True)
            players.append(self._player(None, self.params.copy()))
            if pair:
                players.insert(0, self._player(None, self.file_params.copy()))
        finally:
            self.full.setChecked(full)
            self.full.blockSignals(False)
        written = []
        try:
            for rate in (1., .5):
                path = out.with_name(f'{out.name}-{rate:g}x.mp4')
                total = int(round((hi - lo) / rate * 60))
                dialog = QProgressDialog(f'导出 {path.name}', '中止', 0, total, self)
                dialog.setWindowModality(Qt.WindowModal)
                dialog.setMinimumDuration(0)

                def progress(done, _total, dialog=dialog):
                    dialog.setValue(done)
                    QApplication.processEvents()
                    return not dialog.wasCanceled()
                if pair:
                    pair_video(players[0], players[1], path, rate, width=540, start=lo, end=hi, progress=progress)
                else:
                    video(players[0], path, rate, width=720, start=lo, end=hi, progress=progress)
                dialog.close()
                written.append(path.name)
                if dialog.wasCanceled():
                    break
        finally:
            for player in players:
                player.close()
        QMessageBox.information(self, '导出视频', f'已导出到 {out.parent}：\n' + '\n'.join(written))

    def closeEvent(self, event):
        self.release_players()
        for flight in self.flights.values():
            close_flight(flight)
        self.flights.clear()
        super().closeEvent(event)


def main():
    capture = '--capture' in sys.argv
    app = QApplication(['viewer'] + (['-platform', 'offscreen'] if capture else []))
    font_id = QFontDatabase.addApplicationFont('C:/Windows/Fonts/msyh.ttc')
    families = QFontDatabase.applicationFontFamilies(font_id)
    if families:
        app.setFont(QFont(families[0], 10))
    viewer = Viewer()
    viewer.show()
    if capture:
        # 离屏自检：每段会话打开对照、跳到各阶段截一张窗口图；再改一个时间线参数、一个舞台参数、一个终点参数
        viewer.compare.setChecked(True)
        viewer.marks.setChecked(True)
        out = HERE / 'out' / 'viewer-capture'
        out.mkdir(parents=True, exist_ok=True)
        for index in range(viewer.scene_box.count()):
            viewer.scene_box.setCurrentIndex(index)
            app.processEvents()
            for k in range(1, viewer.segment_box.count()):
                viewer.segment_box.setCurrentIndex(k)
                lo, hi = viewer.segment
                viewer.seek(lo + (hi - lo) * .45)
                app.processEvents()
                viewer.grab().save(str(out / f'{viewer.session.path.name}-{k}.png'))
            viewer.segment_box.setCurrentIndex(0)
            started = time.perf_counter()
            if viewer.session.kind == 'send':
                viewer.inputs['send_outflow_ms'][0].setValue(2400)
            else:
                viewer.inputs['receive_appear_s'][0].setValue(2.0)
                viewer.refresh()
            app.processEvents()
            print(viewer.session.path.name, '改参数', f'{(time.perf_counter() - started) * 1000:.0f} ms', viewer.info.text())
            viewer.reload_file()
            app.processEvents()
        print('窗口截图', out)
        viewer.close()
        return
    sys.exit(app.exec())


if __name__ == '__main__':
    main()
