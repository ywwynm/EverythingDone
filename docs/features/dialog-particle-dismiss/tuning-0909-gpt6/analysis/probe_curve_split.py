"""固定释放和粒子材料，逐项隔离导致弧边分支丢失的输运项。"""
from pathlib import Path
import sys, json, shutil, hashlib
import numpy as np, moderngl
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
import renderer, unified_model
from unified_model import SHARED, model_fingerprint
from export_videos import Reference
OUT=HERE/'analysis/curve-split';OUT.mkdir(exist_ok=True,parents=True)


def archive():
    dest=HERE/'archive/before-curve-split'
    if dest.exists():return
    dest.mkdir()
    for name in ['renderer.py','unified_model.py','export_videos.py','touch_geometry.py','export_android.py','viewer.py']:
        shutil.copy2(HERE/name,dest/name)
    shutil.copytree(SHARED,dest/'particle-dismiss')
    for name in ['ironman','ironman-up-reference','thanos','kobe','language','attachment','attachment-image','color']:
        for ext in ['npy','json']:shutil.copy2(HERE/f'cache/{name}.{ext}',dest/f'{name}.{ext}')
    shutil.copy2(HERE/'videos/manifest.json',dest/'manifest.json')
    (dest/'identity.json').write_text(json.dumps(dict(model_hash=model_fingerprint()),indent=2),'utf-8')
    shutil.copy2('C:/Users/ywwynm/AppData/Local/Temp/codex-clipboard-0c90e2e7-db6e-4f74-97d5-1e1791de3c34.png',OUT/'user-note.png')


def variant(shader,mode):
    if mode in ['wind','both','field']:
        shader=shader.replace('*(.12+.50*reach)*carried*wind_gain;','*(.12+.50*reach)*carried*wind_gain*missing;')
    if mode in ['floor','both','field']:
        shader=shader.replace('float deficit=.16-axial;','float deficit=mix(.025,.16,missing)-axial;')
    if mode=='field':shader=shader.replace('*(1.08+.12*reach);',';')
    return shader


def main():
    archive();ctx=moderngl.create_standalone_context(require=430)
    from evaluate_targeted_release import frozen
    baseline,oldmodel=frozen('before-curve-split')
    baseline_globals=baseline.__init__.__globals__
    original=baseline_globals['COMPUTE']
    meta=json.loads((HERE/'assets/ironman/scene.json').read_text('utf-8'));ref=Reference(meta)
    approved=np.load(HERE/'archive/observed-approved/ironman.npy',mmap_mode='r')
    times=[.31,.43,.55,.65]
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
    original_variation=oldmodel.variation
    original_locality=oldmodel.release_locality
    def identity(seed):
        v=original_variation(seed);v[:8]=[1,1,0,0,0,0,0,0];v[12:]=0
        return v
    def coherent(seed):
        v=original_variation(seed)
        sample=float(unified_model.random_values(1,seed^0x510e527f)[0,3])
        strength=.08+.92*float(unified_model.smooth((sample-.30)/.55))
        mirror=-1 if v[0]<0 else 1
        v[0]=mirror*(1+(abs(v[0])-1)*strength);v[1]=1+(v[1]-1)*strength
        v[2:8]*=strength;v[12:]*=strength
        return v
    for mode,title in [('current','调整前'),('both','减弱叠加风与速度下限'),('identity','固定变换'),('coherent','限制连贯卷边变形')]:
        oldmodel.variation=coherent if mode=='coherent' else identity if mode=='identity' else original_variation
        baseline_globals['COMPUTE']=variant(original,mode)
        r=baseline('ironman',ctx=ctx)
        frames=[]
        for t in times:frames.append(r.render(t).copy())
        r.close()
        w=360;lo=180;hi=790;h=(hi-lo)//2
        sheet=Image.new('RGB',(w*4,(h+30)*3),'#111923');d=ImageDraw.Draw(sheet)
        for i,t in enumerate(times):
            for row,(label,array) in enumerate([('华为',ref.at(t)),('认可控制组',np.asarray(approved[round(t*120)])),(title,frames[i])]):
                sheet.paste(Image.fromarray(array[lo:hi]).resize((w,h)),(i*w,row*(h+30)+30))
                d.text((i*w+8,row*(h+30)+4),f'{label} {t:.2f}',font=font,fill='white')
        sheet.save(OUT/f'ablation-{mode}.jpg',quality=95)
        Image.fromarray(frames[2]).save(OUT/f'full-{mode}.png')
        print(mode,flush=True)
    baseline_globals['COMPUTE']=original;oldmodel.variation=original_variation;oldmodel.release_locality=original_locality;ctx.release()


if __name__=='__main__':main()
