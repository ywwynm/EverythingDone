from pathlib import Path
import sys,json,shutil,argparse
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np,moderngl
from PIL import Image,ImageDraw,ImageFont
from renderer import Renderer,HERE
from unified_model import materials,model_fingerprint

p=argparse.ArgumentParser();p.add_argument('--report-dir',default='analysis/unified-validation');args=p.parse_args()
out=HERE/args.report_dir;frozen=json.loads((out/'freeze.json').read_text('utf-8'))
assert frozen['model_hash']==model_fingerprint()
ctx=moderngl.create_standalone_context(require=430)
alias=out/'renamed-input';alias.mkdir(exist_ok=True)
for filename in ['foreground.png','background.png','scene.json']:shutil.copyfile(HERE/'assets/ironman'/filename,alias/filename)
meta=json.loads((alias/'scene.json').read_text('utf-8'));meta['name']='unrelated-name';meta['origins']=[[999,999,-999]]
(alias/'scene.json').write_text(json.dumps(meta),encoding='utf-8')
for file in ['profile.json','flow-profile.json']:(alias/file).write_text('这不是有效 JSON；正式模型不得读取照片拟合配置。',encoding='utf-8')
original=Renderer('ironman',ctx=ctx);renamed=Renderer(alias,ctx=ctx)
assert np.array_equal(original.base,renamed.base)
for t in [0,.27,.51,.83,1]:assert np.array_equal(original.render(t),renamed.render(t))
original.close();renamed.close()
cases=json.loads((HERE/'assets/scenes.json').read_text('utf-8'))
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);report=[]
for m in cases:
    fg=np.array(Image.open(HERE/'assets'/m['name']/'foreground.png').convert('RGBA'))
    w=m['rect'][2]-m['rect'][0];h=m['rect'][3]-m['rect'][1]
    a=materials(w,h,fg,m['direction'],m['seed']);b=materials(w,h,fg[:,:,[2,0,1,3]],m['direction'],m['seed'])
    assert np.array_equal(a['base'],b['base']) and np.array_equal(a['pigment'],b['pigment']),m['name']
    if m.get('holdout'):
        r=Renderer(m['name'],ctx=ctx)
        sheet=Image.new('RGB',(300*5,440),(15,21,30));d=ImageDraw.Draw(sheet)
        for i,t in enumerate([0,.20,.40,.65,1.]):
            im=Image.fromarray(r.render(t));im.thumbnail((300,400));sheet.paste(im,(i*300+(300-im.width)//2,32))
            d.text((i*300+10,5),f'{m["title"]} {t:.2f}',font=font,fill='white')
        sheet.save(out/f'{m["name"]}.jpg',quality=94);r.close()
    report.append(dict(scene=m['name'],holdout=m.get('holdout',False),channel_permutation_identical_material=True))
ctx.release()
result=dict(frozen_model_hash=model_fingerprint(),renamed_and_poisoned_profiles_pixel_identical=True,cases=report)
(out/'contract.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('名称／拟合文件独立性、',len(cases),'场景颜色通道置换与冻结散列检查通过')
