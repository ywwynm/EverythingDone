"""模型冻结之后引入新材质与新布局，不根据结果回调参数。"""
from pathlib import Path
import json,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from skimage import data
from PIL import Image,ImageDraw,ImageFont
from renderer import HERE
from unified_model import model_fingerprint

out=HERE/'analysis/transport-model';frozen={'model_hash':model_fingerprint(),'status':'加入新输入之前冻结','training':'观测控制组，另用三人物和附件、颜色检查迁移；新输入不参与调参'}
(out/'freeze.json').write_text(json.dumps(frozen,ensure_ascii=False,indent=2),'utf-8')
cases=[]
def save(name,title,fg,rect,direction,description):
    folder=HERE/'assets'/name
    assert not folder.exists(),'不得覆盖已有留出输入'
    folder.mkdir()
    yy,xx=np.mgrid[:1000,:720];gradient=(xx/720*.6+yy/1000*.4)[...,None]
    bg=Image.fromarray(np.uint8(np.array([37,45,69])+(np.array([54,100,91])-np.array([37,45,69]))*gradient))
    src=bg.copy();src.paste(fg.convert('RGB'),tuple(rect[:2]),fg.getchannel('A'))
    fg.save(folder/'foreground.png');bg.save(folder/'background.png');src.save(folder/'source.png')
    m=dict(name=name,title=title,frame=[720,1000],rect=rect,radius=0,duration=1.,seed=9600910,direction=direction,dim_alpha=0.,reference=None,background_truth=False,background_note=description,holdout=True,validation_round='共同释放与输运',frozen_model_hash=frozen['model_hash'])
    (folder/'scene.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),'utf-8');cases.append(m)

photo=Image.fromarray(data.camera()).convert('RGBA').resize((480,480),Image.Resampling.LANCZOS)
save('holdout-monochrome','本轮留出 · 灰度照片',photo,[120,250,600,730],203,'scikit-image 本地 camera 灰度图；本轮冻结后首次验证。背景为构造渐变。')
panel=Image.new('RGBA',(640,300),(0,0,0,0));draw=ImageDraw.Draw(panel);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
draw.rounded_rectangle((0,0,639,299),28,fill=(252,250,245,255));draw.text((30,25),'移除选中的附件',font=font,fill='#294f64')
draw.text((30,84),'已选中 3 个项目，移除后仍可重新添加。',font=small,fill='#69727c')
for i,color in enumerate(['#e3913a','#428cd2','#599581']):
    draw.rounded_rectangle((34+i*92,136,99+i*92,194),8,fill=color)
draw.line((25,221,615,221),fill='#dfdedb',width=1);draw.text((426,246),'取消',font=small,fill='#888888');draw.text((523,246),'移除',font=small,fill='#d4752e')
save('holdout-compact-dialog','本轮留出 · 横向弹窗',panel,[40,355,680,655],334,'冻结后新构造的浅暖面板、灰字与三色图标；未参与本轮调参。')
p=HERE/'assets/scenes.json';scenes=json.loads(p.read_text('utf-8'));p.write_text(json.dumps(scenes+cases,ensure_ascii=False,indent=2),'utf-8')
assert model_fingerprint()==frozen['model_hash'];(out/'holdouts.json').write_text(json.dumps({'freeze':frozen,'cases':cases},ensure_ascii=False,indent=2),'utf-8');print('已加入两个全新留出输入，模型未变化。')
