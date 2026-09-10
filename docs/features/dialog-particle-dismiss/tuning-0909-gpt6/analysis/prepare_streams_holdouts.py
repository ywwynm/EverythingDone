"""冻结后才创建的独立照片与有色面板；仅定义输入，不修改模型。"""
from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
from PIL import Image,ImageDraw,ImageFont
from skimage import data
from renderer import HERE
from unified_model import model_fingerprint

out=HERE/'analysis/streams-content'
frozen=json.loads((out/'freeze.json').read_text('utf-8'))
assert frozen['model_hash']==model_fingerprint()
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
cases=[]
def save(name,title,fg,rect,direction,description):
    folder=HERE/'assets'/name
    assert not folder.exists(), '留出输入不可覆盖'
    folder.mkdir()
    yy,xx=np.mgrid[:1000,:720];blend=xx/720*.4+yy/1000*.6
    bg=Image.fromarray((np.array([61,52,85])+(np.array([31,88,99])-np.array([61,52,85]))*blend[...,None]).astype('uint8'))
    source=bg.copy();source.paste(fg.convert('RGB'),tuple(rect[:2]),fg.getchannel('A'))
    fg.save(folder/'foreground.png');bg.save(folder/'background.png');source.save(folder/'source.png')
    m=dict(name=name,title=title,frame=[720,1000],rect=rect,radius=0,duration=1.,seed=9201654,direction=direction,
           dim_alpha=0.,reference=None,background_truth=False,background_note=description,provenance=description,
           holdout=True,validation_round='卷曲与内容色',frozen_model_hash=frozen['model_hash'])
    (folder/'scene.json').write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8');cases.append(m)

photo=Image.fromarray(data.coffee()).convert('RGBA').resize((600,400),Image.Resampling.LANCZOS)
save('holdout-coffee','本轮留出 · 咖啡照片',photo,[60,280,660,680],157,'scikit-image 本地 coffee 照片，本轮冻结后首次用于模型；背景为构造渐变。')
panel=Image.new('RGBA',(576,346),(0,0,0,0));d=ImageDraw.Draw(panel)
d.rounded_rectangle((0,0,575,345),28,fill=(123,39,114,255))
d.text((28,28),'有色面板与中性色内容',font=font,fill=(255,237,249,255))
for i,(text,color) in enumerate([('灰色说明文字',(203,196,206,255)),('浅色按钮和图标',(235,237,235,255)),('局部青色强调',(51,219,218,255))]):
    y=100+i*63;d.ellipse((30,y,57,y+27),fill=color);d.text((78,y-1),text,font=font,fill=color)
save('holdout-colored-panel','本轮留出 · 有色面板',panel,[72,330,648,676],286,'冻结后新构造的饱和紫色面板、灰白文字与青色图标，检验面板识别不依赖白色。')
p=HERE/'assets/scenes.json';scenes=json.loads(p.read_text('utf-8'))
assert not {c['name'] for c in cases}&{c['name'] for c in scenes}
p.write_text(json.dumps(scenes+cases,ensure_ascii=False,indent=2),encoding='utf-8')
assert model_fingerprint()==frozen['model_hash']
(out/'holdouts.json').write_text(json.dumps({'model_hash':frozen['model_hash'],'cases':cases},ensure_ascii=False,indent=2),encoding='utf-8')
print('已添加两个冻结后留出输入，模型指纹未改变。')
