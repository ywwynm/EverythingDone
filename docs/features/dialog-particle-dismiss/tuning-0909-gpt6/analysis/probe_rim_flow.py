"""冻结本轮前状态后，隔离速度响应、弧边流动与触点输运。"""
from pathlib import Path
import sys,json,shutil,hashlib
import moderngl,numpy as np
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer
from unified_model import SHARED,model_fingerprint
from export_videos import Reference,load_meta,label,BG
from rim_candidate import frozen_shader
OUT=HERE/'analysis/rim-flow';OUT.mkdir(exist_ok=True)

def archive():
    target=HERE/'archive/before-rim-flow'
    if target.exists():return
    target.mkdir()
    for name in ['renderer.py','unified_model.py','export_videos.py','touch_geometry.py','viewer.py']:
        shutil.copy2(HERE/name,target/name)
    shutil.copytree(SHARED,target/'particle-dismiss')
    for name in ['ironman','ironman-up-reference','thanos','kobe','language','color','attachment','attachment-image']:
        for ext in ['npy','json']:shutil.copy2(HERE/f'cache/{name}.{ext}',target/f'{name}.{ext}')
    for name in ['manifest.json','family-videos.json']:shutil.copy2(HERE/'videos'/name,target/name)
    (target/'identity.json').write_text(json.dumps(dict(model_hash=model_fingerprint())),'utf-8')
    shutil.copy2('C:/Users/ywwynm/AppData/Local/Temp/codex-clipboard-00ba59cc-f25e-4a9a-9028-7223d326c0fa.png',OUT/'user-note.png')

def main():
    archive();ctx=moderngl.create_standalone_context(require=430);original=frozen_shader()
    times=[.4,.48,.55,.63];meta=load_meta('ironman');ref=Reference(meta)
    variants=[('current','本轮前',original),
        ('response','更快响应场速度',original.replace('(.018+.036*m.random.z)','(.004+.006*m.random.z)')),
        ('high-contrast','增加局部速度变化',original.replace('vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach);','vec2 target=sample0.xy*(guide_gain/.9)*(1.08+.12*reach);\n    target+=.30*(sample0.xy-wind*dot(sample0.xy,wind));')),
        ('lower-floor','降低观测区速度下限',original.replace('float deficit=.16-axial;','float deficit=mix(.025,.16,missing)-axial;'))]
    for name,title,shader in variants:
        renderer.COMPUTE=shader;r=renderer.Renderer('ironman',ctx=ctx)
        sheet=Image.new('RGB',(4*420,760),BG);d=ImageDraw.Draw(sheet)
        for col,t in enumerate(times):
            for row,(text,frame) in enumerate([('华为参考',ref.at(t)),(title,r.render(t))]):
                label(d,(col*420+10,row*380+4),f'{text} · {t:.2f}',22)
                tile=Image.fromarray(frame).crop((82,450,418,728)).resize((420,348))
                sheet.paste(tile,(col*420,row*380+32))
        sheet.save(OUT/f'probe-{name}.jpg',quality=95);r.close();print(name,flush=True)
    renderer.COMPUTE=original;ctx.release()

if __name__=='__main__':main()
