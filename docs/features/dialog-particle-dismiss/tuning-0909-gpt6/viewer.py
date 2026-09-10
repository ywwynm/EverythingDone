"""桌面预览：同一 GPU 模型、参考并排、任意触点方向与逐帧拖动。"""
import sys,time,json,math,argparse,traceback
from pathlib import Path
import numpy as np
from PIL import Image
from PySide6.QtCore import Qt,QTimer
from PySide6.QtGui import QImage,QPixmap,QKeySequence,QShortcut,QFontDatabase,QFont
from PySide6.QtWidgets import QApplication,QWidget,QVBoxLayout,QHBoxLayout,QLabel,QPushButton,QSlider,QComboBox,QDoubleSpinBox
from renderer import Renderer,HERE

class Canvas(QLabel):
    def __init__(self,on_click=None):
        super().__init__();self.on_click=on_click;self.setAlignment(Qt.AlignCenter);self.setMinimumSize(200,300);self.array=None;self.setStyleSheet('background:#0d1118;border:1px solid #273244;border-radius:8px;')
    def display(self,a):
        self.array=a;h,w=a.shape[:2]
        img=QImage(a.data,w,h,a.strides[0],QImage.Format_RGB888).copy()
        self.pix=QPixmap.fromImage(img);self.setPixmap(self.pix.scaled(self.size(),Qt.KeepAspectRatio,Qt.SmoothTransformation))
    def resizeEvent(self,event):
        if self.array is not None:self.setPixmap(self.pix.scaled(self.size(),Qt.KeepAspectRatio,Qt.SmoothTransformation))
        super().resizeEvent(event)
    def mousePressEvent(self,event):
        if self.on_click and self.array is not None:
            h,w=self.array.shape[:2];s=min(self.width()/w,self.height()/h)
            x=(event.position().x()-(self.width()-w*s)/2)/s;y=(event.position().y()-(self.height()-h*s)/2)/s
            if 0<=x<w and 0<=y<h:self.on_click(x,y)

class Viewer(QWidget):
    def __init__(self):
        super().__init__();self.setWindowTitle('完事儿 · 粒子消散统一规则');self.resize(1280,1060)
        self.setStyleSheet('QWidget{background:#131a24;color:#e7edf5;font-family:Microsoft YaHei;font-size:14px;} QPushButton,QComboBox,QDoubleSpinBox{padding:7px 13px;background:#243349;border:1px solid #3c516d;border-radius:6px;} QPushButton:hover{background:#304c69;} QSlider{min-height:25px;}')
        self.metas=json.loads((HERE/'assets/scenes.json').read_text(encoding='utf-8'));self.renderer=None;self.playing=False;self.progress=0.;self.last=time.perf_counter();self.elapsed=0.;self.speed=1.
        layout=QVBoxLayout(self);head=QHBoxLayout();layout.addLayout(head)
        title=QLabel('粒子消散 · 统一参数验证');title.setStyleSheet('font-size:22px;font-weight:600;');head.addWidget(title);head.addStretch()
        self.scenes=QComboBox();self.scenes.addItems([m['title'] for m in self.metas]);head.addWidget(self.scenes)
        labels=QHBoxLayout();labels.addWidget(QLabel('华为参考 / 静态源素材'));labels.addWidget(QLabel('统一规则 · 点击画面设置触点方向'));layout.addLayout(labels)
        views=QHBoxLayout();self.left=Canvas();self.right=Canvas(self.touch);views.addWidget(self.left);views.addWidget(self.right);layout.addLayout(views,1)
        self.note=QLabel();self.note.setWordWrap(True);self.note.setStyleSheet('color:#aab8ca;font-size:12px;');layout.addWidget(self.note)
        self.position=QSlider(Qt.Horizontal);self.position.setRange(0,1000);layout.addWidget(self.position)
        controls=QHBoxLayout();layout.addLayout(controls)
        self.play=QPushButton('播放');self.play.clicked.connect(self.toggle);controls.addWidget(self.play)
        for text,delta in [('上一帧',-1/120),('下一帧',1/120)]:
            b=QPushButton(text);b.clicked.connect(lambda checked=False,d=delta:self.step(d));controls.addWidget(b)
        self.rate=QComboBox();self.rate.addItems(['1 倍速','0.5 倍速']);self.rate.currentIndexChanged.connect(lambda i:setattr(self,'speed',1. if i==0 else .5));controls.addWidget(self.rate)
        controls.addWidget(QLabel('逻辑时长'));self.duration=QDoubleSpinBox();self.duration.setRange(.5,2.5);self.duration.setSingleStep(.05);self.duration.setSuffix(' 秒');self.duration.setValue(1.0);controls.addWidget(self.duration)
        controls.addStretch();self.status=QLabel();controls.addWidget(self.status)
        direction=QHBoxLayout();layout.addLayout(direction);direction.addWidget(QLabel('方向'))
        self.angle=QSlider(Qt.Horizontal);self.angle.setRange(0,359);direction.addWidget(self.angle,1)
        self.angle_label=QLabel();direction.addWidget(self.angle_label)
        restore=QPushButton('恢复参考方向');restore.clicked.connect(lambda:self.angle.setValue(self.meta['direction']));direction.addWidget(restore)
        self.position.valueChanged.connect(self.scrub);self.scenes.currentIndexChanged.connect(self.select);self.angle.valueChanged.connect(self.change_direction)
        self.timer=QTimer(self);self.timer.timeout.connect(self.tick);self.timer.start(16)
        self.shortcut=QShortcut(QKeySequence('Space'),self);self.shortcut.activated.connect(self.toggle)
        self.select(0)
    def select(self,index):
        self.playing=False;self.meta=self.metas[index];self.angle.blockSignals(True);self.angle.setValue(self.meta['direction']);self.angle.blockSignals(False)
        p=HERE/'assets'/self.meta['name']
        self.original=np.array(Image.open(p/'source.png').convert('RGB'))
        self.ref=np.load(p/'reference.npy',mmap_mode='r') if self.meta['reference'] else None
        self.ref_times=np.load(p/'reference-times.npy') if self.ref is not None else None
        self.note.setText(('参考按进度对齐；不代表录屏文件原速。' if self.ref is not None else '左侧为静态源素材；没有同内容华为运动参考。')+'  '+self.meta['background_note'])
        self.rebuild();self.progress=0.;self.elapsed=.32;self.draw()
    def rebuild(self):
        if self.renderer:self.renderer.close()
        self.renderer=Renderer(self.meta['name'],direction=self.angle.value())
        self.angle_label.setText(f'{self.angle.value()}°（0° 右 / 90° 上）')
    def change_direction(self,a):
        self.rebuild();self.draw()
    def touch(self,x,y):
        x0,y0,x1,y1=self.meta['rect'];dx=x-(x0+x1)/2;dy=(y0+y1)/2-y
        if math.hypot(dx,dy)>.001:self.angle.setValue(round(math.degrees(math.atan2(dy,dx)))%360)
        self.elapsed=.32;self.progress=0;self.playing=True;self.play.setText('暂停');self.draw()
    def toggle(self):
        self.playing=not self.playing;self.play.setText('暂停' if self.playing else '播放');self.last=time.perf_counter()
    def step(self,delta):
        self.playing=False;self.play.setText('播放');self.progress=float(np.clip(self.progress+delta,0,1));self.elapsed=.32+self.progress*self.duration.value();self.draw()
    def scrub(self,value):
        self.progress=value/1000;self.elapsed=.32+self.progress*self.duration.value();self.draw(update_slider=False)
    def tick(self):
        now=time.perf_counter();dt=min(now-self.last,.08);self.last=now
        if not self.playing:return
        self.elapsed+=dt*self.speed
        duration=self.duration.value()
        if self.elapsed>duration+.70:self.elapsed=0.
        self.progress=float(np.clip((self.elapsed-.32)/duration,0,1));self.draw()
    def draw(self,update_slider=True):
        self.right.display(self.renderer.render(self.progress))
        if self.ref is not None:
            r=self.meta['reference'];t=r['start']+self.progress*(r['end']-r['start']);self.left.display(np.asarray(self.ref[np.argmin(abs(self.ref_times-t))]))
        else:self.left.display(self.original)
        if update_slider:self.position.blockSignals(True);self.position.setValue(round(self.progress*1000));self.position.blockSignals(False)
        self.status.setText(f'进度 {self.progress:.3f}')
    def closeEvent(self,event):
        self.renderer.close();super().closeEvent(event)

def main():
    p=argparse.ArgumentParser();p.add_argument('--capture',action='store_true');args=p.parse_args()
    app=QApplication(['viewer']+(['-platform','offscreen'] if args.capture else []))
    font_id=QFontDatabase.addApplicationFont('C:/Windows/Fonts/msyh.ttc')
    families=QFontDatabase.applicationFontFamilies(font_id)
    if families:app.setFont(QFont(families[0],10))
    v=Viewer();v.show()
    if args.capture:
        app.processEvents();v.progress=.48;v.draw();app.processEvents();v.grab().save(str(HERE/'analysis/viewer.png'))
        print('桌面预览已离屏渲染验证');v.close();return
    sys.exit(app.exec())
if __name__=='__main__':
    try:main()
    except Exception:
        (HERE/'analysis/viewer-error.log').write_text(traceback.format_exc(),encoding='utf-8');raise
