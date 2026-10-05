"""桌面预览：同一 GPU 模型、参考并排、任意触点方向与逐帧拖动。"""
import sys,time,json,math,argparse,traceback,secrets
from pathlib import Path
import numpy as np
from PIL import Image
from PySide6.QtCore import Qt,QTimer
from PySide6.QtGui import QImage,QPixmap,QKeySequence,QShortcut,QFontDatabase,QFont
from PySide6.QtWidgets import QApplication,QWidget,QVBoxLayout,QHBoxLayout,QLabel,QPushButton,QSlider,QComboBox,QDoubleSpinBox,QCheckBox
from renderer import Renderer,HERE
from unified_model import RULES,touch_gap_from_point

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
    def __init__(self,fixed=False,fronts=False,v2=None,shared=False):
        super().__init__();self.setWindowTitle('完事儿 · 粒子消散统一规则');self.resize(1280,1060)
        self.setStyleSheet('QWidget{background:#131a24;color:#e7edf5;font-family:Microsoft YaHei;font-size:14px;} QPushButton,QComboBox,QDoubleSpinBox{padding:7px 13px;background:#243349;border:1px solid #3c516d;border-radius:6px;} QPushButton:hover{background:#304c69;} QSlider{min-height:25px;}')
        self.metas=json.loads((HERE/'assets/scenes.json').read_text(encoding='utf-8'));self.renderer=None;self.playing=False;self.progress=0.;self.last=time.perf_counter();self.elapsed=0.;self.speed=1.
        self.fronts=fronts or v2 is not None or shared;self.v2=v2;self.shared=shared
        if shared:
            from learned_release import load_weights
            from conditional_material import ConditionalRules
            from shared_transport import load_transport
            self.shared_weights=load_weights(HERE/'conditional-release-weights.json')
            self.shared_rules=ConditionalRules(**json.loads((HERE/'conditional-material-rules.json').read_text('utf-8'))['rules'])
            self.transport_weights=load_transport(HERE/'conditional-transport-weights.json')
        if self.fronts:
            added=[json.loads(p.read_text('utf-8')) for p in sorted((HERE/'assets').glob('reference-0926-*/scene.json'))]
            added.append(json.loads((HERE/'assets/spiderman-original/scene.json').read_text('utf-8')))
            self.metas=added+self.metas
            self.setWindowTitle('完事儿 · 共同条件场桌面候选' if shared else '完事儿 · 第二版共同模型' if v2 is not None else '完事儿 · 多起点共同模型桌面候选')
        self.seed=0
        layout=QVBoxLayout(self);head=QHBoxLayout();layout.addLayout(head)
        title=QLabel('共同条件场 · 桌面候选' if shared else '第二版共同模型 · 桌面候选' if v2 is not None else '多起点共同模型 · 桌面候选' if fronts else '粒子消散 · 统一参数验证');title.setStyleSheet('font-size:22px;font-weight:600;');head.addWidget(title);head.addStretch()
        self.scenes=QComboBox();self.scenes.addItems([m['title'] for m in self.metas]);head.addWidget(self.scenes)
        labels=QHBoxLayout();labels.addWidget(QLabel('华为参考 / 静态源素材'));labels.addWidget(QLabel('统一规则 · 点击画面设置触点位置'));layout.addLayout(labels)
        views=QHBoxLayout();self.left=Canvas();self.right=Canvas(self.touch);views.addWidget(self.left);views.addWidget(self.right);layout.addLayout(views,1)
        self.note=QLabel();self.note.setWordWrap(True);self.note.setStyleSheet('color:#aab8ca;font-size:12px;');layout.addWidget(self.note)
        self.position=QSlider(Qt.Horizontal);self.position.setRange(0,1000);layout.addWidget(self.position)
        controls=QHBoxLayout();layout.addLayout(controls)
        self.play=QPushButton('播放');self.play.clicked.connect(self.toggle);controls.addWidget(self.play)
        change=QPushButton('换一种效果');change.clicked.connect(self.new_effect);controls.addWidget(change)
        self.lock=QCheckBox('固定当前效果');self.lock.setChecked(fixed);controls.addWidget(self.lock)
        for text,delta in [('上一帧',-1/120),('下一帧',1/120)]:
            b=QPushButton(text);b.clicked.connect(lambda checked=False,d=delta:self.step(d));controls.addWidget(b)
        self.rate=QComboBox();self.rate.addItems(['1 倍速','0.5 倍速']);self.rate.currentIndexChanged.connect(lambda i:setattr(self,'speed',1. if i==0 else .5));controls.addWidget(self.rate)
        controls.addWidget(QLabel('逻辑时长'));self.duration=QDoubleSpinBox();self.duration.setRange(.5,2.5);self.duration.setSingleStep(.05);self.duration.setSuffix(' 秒');self.duration.setValue(1.0);controls.addWidget(self.duration)
        controls.addStretch();self.status=QLabel();controls.addWidget(self.status)
        direction=QHBoxLayout();layout.addLayout(direction);direction.addWidget(QLabel('方向'))
        self.angle=QSlider(Qt.Horizontal);self.angle.setRange(0,359);direction.addWidget(self.angle,1)
        self.angle_label=QLabel();direction.addWidget(self.angle_label)
        restore=QPushButton('恢复参考输入');restore.clicked.connect(self.restore_reference);direction.addWidget(restore)
        direction.addWidget(QLabel('边缘外距离'))
        self.gap=QDoubleSpinBox();self.gap.setRange(0,10);self.gap.setSingleStep(.1);self.gap.setValue(RULES['touch_gap_default']);self.gap.setSuffix(' × 短边');direction.addWidget(self.gap)
        self.explicit_distance=False
        self.gap.valueChanged.connect(self.change_distance)
        self.position.valueChanged.connect(self.scrub);self.scenes.currentIndexChanged.connect(self.select);self.angle.valueChanged.connect(self.change_direction)
        self.timer=QTimer(self);self.timer.timeout.connect(self.tick);self.timer.start(16)
        self.shortcut=QShortcut(QKeySequence('Space'),self);self.shortcut.activated.connect(self.toggle)
        self.select(0)
    def reference_inputs(self):
        """第二版的参考重放输入（方向、触点远近、起点）；没有记录时返回 None。"""
        if self.shared:
            from v2_inputs import OBSERVED_SITES
            from dissolve_v2 import Site
            sites=OBSERVED_SITES.get(self.meta['name'])
            if sites:return dict(direction=self.meta['direction'],sites=[Site(*s) for s in sites])
            return None
        if self.v2 is None:return None
        from v2_inputs import SITES
        if self.meta['name'] not in SITES:return None
        from dissolve_v2 import reference_inputs
        return reference_inputs(self.meta['name'])
    def select(self,index):
        self.explicit_distance=False
        self.playing=False;self.meta=self.metas[index]
        q=self.reference_inputs()
        self.angle.blockSignals(True);self.angle.setValue(round(q['direction'] if q else self.meta['direction']));self.angle.blockSignals(False)
        self.seed=self.meta['seed'] if self.lock.isChecked() else secrets.randbits(31)
        self.gap.blockSignals(True);self.gap.setValue(RULES['touch_gap_default']);self.gap.blockSignals(False)
        p=HERE/'assets'/self.meta['name']
        self.original=np.array(Image.open(p/'source.png').convert('RGB'))
        self.ref=np.load(p/'reference.npy',mmap_mode='r') if self.meta['reference'] else None
        self.ref_times=np.load(p/'reference-times.npy') if self.ref is not None else None
        self.ref_index=None
        cache=HERE/'analysis/match-cache'/self.meta['name']
        if (self.v2 is not None or self.shared) and self.ref is not None and (cache/'frames.npy').exists():
            # 按独立画面顺序对齐进度；无法据此确认原视频的实机时长。
            self.ref_index=(np.load(cache/'frames.npy',mmap_mode='r'),np.load(cache/'progress.npy'))
        self.note.setText(('候选尚未达到逐像素一致。' if self.fronts else '')+('参考按进度对齐；不代表录屏文件原速。' if self.ref is not None else '左侧为静态源素材；没有同内容华为运动参考。')+'  '+self.meta['background_note'])
        self.rebuild();self.progress=0.;self.elapsed=.32;self.draw()
    def rebuild(self):
        if self.renderer:self.renderer.close()
        cls=Renderer;options={}
        if self.shared:
            from shared_transport import SharedTransportRenderer
            from dissolve_v2 import sample_sites
            cls=SharedTransportRenderer;options.update(weights=self.shared_weights,rules=self.shared_rules,transport_weights=self.transport_weights)
            if (HERE/'surface-transfer-rules.json').is_file():
                from surface_transfer import TransferRenderer,load_transfer
                cls=TransferRenderer;options['transfer']=load_transfer(HERE/'surface-transfer-rules.json')
            if (HERE/'particle-coverage-rules.json').is_file():
                from particle_coverage import CoverageRenderer,load_coverage
                cls=CoverageRenderer;options['coverage']=load_coverage(HERE/'particle-coverage-rules.json')
            if (HERE/'particle-cohort-weights.json').is_file():
                from particle_cohorts import CohortRenderer,load_cohorts
                cls=CohortRenderer;options['cohorts']=load_cohorts(HERE/'particle-cohort-weights.json')
            if (HERE/'surface-coherence-weights.json').is_file():
                from surface_coherence import CoherenceRenderer,load_coherence
                cls=CoherenceRenderer;options['coherence']=load_coherence(HERE/'surface-coherence-weights.json')
            if (HERE/'particle-scatter-weights.json').is_file():
                from scatter_material import ScatterRenderer,load_scatter
                cls=ScatterRenderer;options['scatter']=load_scatter(HERE/'particle-scatter-weights.json')
            q=self.reference_inputs()
            if self.seed==self.meta['seed']:
                options['sites']=q['sites'] if q else None
            else:
                x,y,x1,y1=self.meta['rect'];options['sites']=sample_sites(x1-x,y1-y,self.seed,self.angle.value())
        elif self.v2 is not None:
            from dissolve_v2 import V2Renderer,sample_sites
            cls=V2Renderer;options['rules']=self.v2
            q=self.reference_inputs()
            if self.seed==self.meta['seed'] and q:
                # 固定参考输入：记录的起点与触点远近；方向仍由滑杆决定（初值为记录值）。
                options['sites']=q['sites']
                if not self.explicit_distance and q.get('touch_strength') is not None:options['touch_strength']=q['touch_strength']
            else:
                x,y,x1,y1=self.meta['rect'];options['sites']=sample_sites(x1-x,y1-y,self.seed,self.angle.value())
        elif self.fronts:
            from front_transport import FrontRenderer,ReleaseSite,sample_sites
            cls=FrontRenderer
            if self.seed==self.meta['seed']:
                observed=self.meta.get('release_sites')
                options['sites']=[ReleaseSite(**p) for p in observed] if observed else None
            else:
                x,y,x1,y1=self.meta['rect'];options['sites']=sample_sites(x1-x,y1-y,self.seed)
        if self.v2 is not None and self.explicit_distance:options.pop('touch_strength',None)
        self.renderer=cls(self.meta['name'],direction=self.angle.value(),seed=self.seed,touch_gap=self.gap.value() if self.explicit_distance else None,**options)
        self.angle_label.setText(f'{self.angle.value()}°（0° 右 / 90° 上）')
    def restore_reference(self):
        self.lock.setChecked(True);self.select(self.scenes.currentIndex())
    def change_direction(self,a):
        self.rebuild();self.draw()
    def change_distance(self,a):
        self.explicit_distance=True;self.rebuild();self.draw()
    def touch(self,x,y):
        left,top,right,bottom=self.meta.get('touch_rect',[0,0,*self.meta['frame']])
        if not (left<=x<=right and top<=y<=bottom):return
        x0,y0,x1,y1=self.meta['rect']
        if x0<=x<=x1 and y0<=y<=y1:return
        self.explicit_distance=True
        x0,y0,x1,y1=self.meta['rect'];dx=x-(x0+x1)/2;dy=(y0+y1)/2-y
        if math.hypot(dx,dy)>.001:
            self.angle.blockSignals(True);self.angle.setValue(round(math.degrees(math.atan2(dy,dx)))%360);self.angle.blockSignals(False)
        self.gap.blockSignals(True);self.gap.setValue(touch_gap_from_point(x-x0,y-y0,x1-x0,y1-y0));self.gap.blockSignals(False)
        if not self.lock.isChecked():self.seed=secrets.randbits(31)
        self.rebuild()
        self.elapsed=.32;self.progress=0;self.playing=True;self.play.setText('暂停');self.draw()
    def new_effect(self):
        self.seed=secrets.randbits(31);self.rebuild();self.elapsed=.32;self.progress=0.
        self.playing=True;self.play.setText('暂停');self.last=time.perf_counter();self.draw()
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
        if self.elapsed>duration+.70:
            self.elapsed=0.
            if not self.lock.isChecked():self.seed=secrets.randbits(31);self.rebuild()
        self.progress=float(np.clip((self.elapsed-.32)/duration,0,1));self.draw()
    def draw(self,update_slider=True):
        self.right.display(self.renderer.render(self.progress))
        if self.ref is not None:
            r=self.meta['reference']
            if self.progress>r.get('valid_progress',1):
                # 不把截断录像的最后一帧冻结后冒充后续参考。
                from export_reference_placeholder import ended_reference
                self.left.display(ended_reference(self.meta['frame']))
            elif self.ref_index is not None:
                frames,progress=self.ref_index;self.left.display(np.asarray(frames[int(np.argmin(abs(progress-self.progress)))]))
            else:
                t=r['start']+self.progress*(r['end']-r['start'])
                self.left.display(np.asarray(self.ref[np.argmin(abs(self.ref_times-t))]))
        else:self.left.display(self.original)
        if update_slider:self.position.blockSignals(True);self.position.setValue(round(self.progress*1000));self.position.blockSignals(False)
        self.status.setText(f'进度 {self.progress:.3f} · 种子 {self.seed}')
    def closeEvent(self,event):
        self.renderer.close();super().closeEvent(event)

def main():
    p=argparse.ArgumentParser();p.add_argument('--capture',action='store_true');p.add_argument('--fronts',action='store_true')
    p.add_argument('--v2',action='store_true');p.add_argument('--shared-field',action='store_true',help='共同条件场候选；使用同一份权重和材料规则')
    p.add_argument('--rules',help='第二版规则 JSON；缺省用 dissolve_v2 当前采用的规则');args=p.parse_args()
    if sum([args.v2,args.fronts,args.shared_field])>1:p.error('--v2、--fronts、--shared-field 只能选择一个')
    v2=None
    if args.v2:
        import dataclasses
        from dissolve_v2 import RULES as V2_RULES
        v2=V2_RULES
        if args.rules:
            data=json.loads(Path(args.rules).read_text('utf-8'));v2=dataclasses.replace(V2_RULES,**data.get('best',data).get('rules',data))
    app=QApplication(['viewer']+(['-platform','offscreen'] if args.capture else []))
    font_id=QFontDatabase.addApplicationFont('C:/Windows/Fonts/msyh.ttc')
    families=QFontDatabase.applicationFontFamilies(font_id)
    if families:app.setFont(QFont(families[0],10))
    v=Viewer(fixed=args.capture or args.fronts or args.v2 or args.shared_field,fronts=args.fronts,v2=v2,shared=args.shared_field);v.show()
    if args.capture:
        app.processEvents();v.progress=.48;v.draw();app.processEvents();v.grab().save(str(HERE/'analysis'/('shared-viewer.png' if args.shared_field else 'v2-viewer.png' if args.v2 else 'front-viewer.png' if args.fronts else 'viewer.png')))
        print('桌面预览已离屏渲染验证');v.close();return
    sys.exit(app.exec())
if __name__=='__main__':
    try:main()
    except Exception:
        (HERE/'analysis/viewer-error.log').write_text(traceback.format_exc(),encoding='utf-8');raise
